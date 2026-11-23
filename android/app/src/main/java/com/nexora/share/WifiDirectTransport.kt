package com.nexora.share

import android.content.ContentValues
import android.content.Context
import android.content.IntentFilter
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import org.json.JSONObject
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class IncomingQueueItem(val id: Int, val name: String, val size: Long, val status: String, val progress: Int = 0)

/** QR-authenticated Wi-Fi Direct file transport. */
class WifiDirectTransport(private val context: Context, private val callbacks: Callbacks) {
    interface Callbacks {
        fun onStatus(message: String)
        fun onQrReady(payload: String)
        fun onConnected(endpointId: String, peerName: String, localIsReceiver: Boolean)
        fun onDisconnected(endpointId: String)
        fun onProgress(message: String)
        fun onReceived(name: String)
        fun onIncomingQueueChanged(items: List<IncomingQueueItem>)
    }
    companion object {
        private const val PORT = 43821
        private const val MAGIC = "NEXORA-WFD-3"
        private const val MAX_FILES = 200
        private const val FRAME_BATCH = 1
        private const val FRAME_ACK = 2
        private const val FRAME_REQUEST_SEND = 3
        private const val FRAME_GRANT_SEND = 4
        private const val FRAME_RELEASE_SEND = 5
    }
    private class GroupPeer(
        val id: String,
        val name: String,
        val socket: Socket,
        val input: DataInputStream,
        val output: DataOutputStream
    ) {
        val outputLock = Any()
        val acknowledgementQueue = LinkedBlockingQueue<Boolean>()
        val sendPermit = java.util.concurrent.Semaphore(0)
        val permitLock = Any()
        @Volatile var localWaitingForTurn = false
        @Volatile var localOwnsTurn = false
        @Volatile var remoteOwnsTurn = false
        @Volatile var remoteRequestPending = false
    }

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel = manager.initialize(context, context.mainLooper, null)
    private val io = Executors.newCachedThreadPool()
    private val active = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    private val sending = AtomicBoolean(false)
    private val serverStarting = AtomicBoolean(false)
    private val incomingPaused = AtomicBoolean(false)
    private val incomingCancelled = AtomicBoolean(false)
    private val outgoingPaused = AtomicBoolean(false)
    private val outgoingCancelled = AtomicBoolean(false)
    @Volatile private var incomingName: String? = null
    @Volatile private var currentIncomingIndex = -1
    private val incomingQueueLock = Any()
    private val incomingQueue = ArrayList<IncomingQueueItem>()
    private val cancelledIncomingItems = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var peerInput: DataInputStream? = null
    @Volatile private var peerOutput: DataOutputStream? = null
    @Volatile private var localIsGroupOwner = false
    private val outputLock = Any()
    private val permitLock = Any()
    private val sendPermit = java.util.concurrent.Semaphore(0)
    private val acknowledgementQueue = LinkedBlockingQueue<Boolean>()
    private val groupPeers = java.util.concurrent.ConcurrentHashMap<String, GroupPeer>()
    @Volatile private var activeGroupSendPeer: GroupPeer? = null
    private val groupIncomingLock = Any()
    @Volatile private var localWaitingForTurn = false
    @Volatile private var localOwnsTurn = false
    @Volatile private var remoteOwnsTurn = false
    @Volatile private var remoteRequestPending = false
    @Volatile private var token: String? = null
    @Volatile private var receiverRegistered = false
    @Volatile private var peerName = "NEXORA device"
    @Volatile private var currentQrPayload: String? = null

    private val receiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: android.content.Intent?) {
            if (intent?.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
            @Suppress("DEPRECATION")
            val broadcastInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO) as? WifiP2pInfo
            if (broadcastInfo != null) {
                handleConnectionInfo(broadcastInfo)
            } else {
                // Some Android builds omit the connection-info extra. Ask the P2P manager
                // directly instead of silently leaving the QR session waiting forever.
                runCatching {
                    manager.requestConnectionInfo(channel) { info -> handleConnectionInfo(info) }
                }.onFailure {
                    if (active.get()) callbacks.onStatus("Android could not read Wi-Fi Direct connection details: " +
                        (it.localizedMessage ?: "please retry"))
                }
            }
        }
    }

    private fun handleConnectionInfo(info: WifiP2pInfo?) {
        if (!active.get()) return
        if (info?.groupFormed == true) {
            if (info.isGroupOwner) {
                localIsGroupOwner = true
                startServer()
            }
            else {
                val host = info.groupOwnerAddress?.hostAddress
                if (host.isNullOrBlank()) callbacks.onStatus("Wi-Fi Direct connected, but Android did not provide the receiver address.")
                else connectSocket(host)
            }
        } else if (socket != null) {
            runCatching { socket?.close() }
            socket = null
            callbacks.onDisconnected("WIFI_DIRECT")
            callbacks.onStatus("Wi-Fi Direct connection ended. Scan again to reconnect.")
        }
    }

    private fun isWifiEnabled(): Boolean {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return false
        return runCatching { wifi.isWifiEnabled }.getOrDefault(false)
    }

    fun startReceiving() {
        // The QR group-joining APIs and MediaStore.Downloads path used by NEXORA
        // require Android 10+. Keep the app installable on older phones, but
        // fail clearly before registering receivers or changing session state.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            active.set(false)
            currentQrPayload = null
            callbacks.onStatus("NEXORA QR Wi-Fi Direct sharing requires Android 10 or newer on this phone.")
            return
        }
        if (!isWifiEnabled()) {
            active.set(false)
            currentQrPayload = null
            callbacks.onStatus("Wi-Fi is off. Turn Wi-Fi on, then tap Receive again.")
            return
        }
        // A previous attempt can leave the service active without ever producing a QR.
        // Reuse a valid QR, but fully restart an incomplete session instead of getting stuck.
        if (active.get() && !currentQrPayload.isNullOrBlank()) {
            callbacks.onQrReady(currentQrPayload!!)
            callbacks.onStatus("Wi-Fi Direct is ready. Show the QR code to the sending phone.")
            return
        }
        active.set(true)
        localIsGroupOwner = false
        groupPeers.values.toList().forEach { peer ->
            runCatching { peer.socket.close() }
            callbacks.onDisconnected(peer.id)
        }
        groupPeers.clear()
        currentQrPayload = null
        token = UUID.randomUUID().toString()
        if (socket != null) callbacks.onDisconnected("WIFI_DIRECT")
        runCatching { socket?.close() }
        socket = null
        runCatching { server?.close() }
        server = null
        register()
        callbacks.onStatus("Preparing a fresh private Wi-Fi Direct connection…")

        // Android may retain a group from an earlier failed attempt. Remove it first;
        // createGroup commonly returns BUSY while that stale group still exists.
        try {
            manager.requestGroupInfo(channel) { existing ->
                if (!active.get()) return@requestGroupInfo
                if (existing != null) {
                    manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() { createReceiverGroup() }
                        override fun onFailure(reason: Int) {
                            // Continue after the removal callback; createGroup reports BUSY if needed.
                            createReceiverGroup()
                        }
                    })
                } else createReceiverGroup()
            }
        } catch (e: Exception) {
            active.set(false)
            callbacks.onStatus("Wi-Fi Direct could not inspect the current group: " + (e.localizedMessage ?: "permission or Wi-Fi error"))
        }
    }

    private fun createReceiverGroup() {
        if (!active.get()) return
        callbacks.onStatus("Creating a private Wi-Fi Direct connection…")
        try {
            manager.createGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { requestGroupDetails(0) }
                override fun onFailure(reason: Int) {
                    active.set(false)
                    val detail = when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "This phone does not support Wi-Fi Direct."
                        WifiP2pManager.BUSY -> "Android is busy. Close any active hotspot or Wi-Fi Direct session, wait a moment, and retry."
                        WifiP2pManager.ERROR -> "Android reported a Wi-Fi Direct error. Check Wi-Fi and hotspot settings, then retry."
                        else -> "Android could not create the group. Check Wi-Fi and hotspot settings, then retry."
                    }
                    callbacks.onStatus(detail + " (code " + reason + ").")
                }
            })
        } catch (e: Exception) {
            active.set(false)
            callbacks.onStatus("Wi-Fi Direct could not start: " + (e.localizedMessage ?: "check Wi-Fi and permissions"))
        }
    }

    private fun requestGroupDetails(attempt: Int) {
        manager.requestGroupInfo(channel) { group ->
            if (group == null) {
                if (attempt < 30) io.execute { Thread.sleep(500); requestGroupDetails(attempt + 1) }
                else {
                    active.set(false)
                    callbacks.onStatus("Wi-Fi Direct group details were unavailable. Please retry.")
                }
                return@requestGroupInfo
            }
            val ssid = group.networkName.orEmpty()
            val password = group.passphrase.orEmpty()
            if (ssid.isBlank() || password.isBlank()) {
                active.set(false)
                callbacks.onStatus("Android did not provide Wi-Fi Direct credentials. Please retry.")
                return@requestGroupInfo
            }
            peerName = deviceName()
            val json = JSONObject().put("type", MAGIC).put("ssid", ssid).put("password", password)
                .put("host", "192.168.49.1").put("port", PORT).put("token", token).put("name", peerName).toString()
            val encoded = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            currentQrPayload = "NEXORA-WFD:" + encoded
            callbacks.onQrReady(currentQrPayload!!)
            callbacks.onStatus("Wi-Fi Direct is ready. Show the QR code to the sending phone.")
        }
    }

    fun connectWithQr(qr: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            active.set(false)
            callbacks.onStatus("Joining a NEXORA QR Wi-Fi Direct session requires Android 10 or newer on this phone.")
            return
        }
        try {
            val encoded = qr.removePrefix("NEXORA-WFD:")
            require(encoded != qr && encoded.length < 4096) { "Not a NEXORA Wi-Fi Direct QR code." }
            val data = JSONObject(String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
            require(data.optString("type") == MAGIC) { "This QR code is not supported." }
            val ssid = data.getString("ssid")
            val password = data.getString("password")
            val host = data.getString("host")
            val port = data.getInt("port")
            val session = data.getString("token")
            require(ssid.length in 1..32 && password.length in 8..63 && host == "192.168.49.1" && port == PORT && session.length in 20..64) {
                "The QR connection details are invalid."
            }
            if (!isWifiEnabled()) {
                active.set(false)
                callbacks.onStatus("Wi-Fi is off. Turn Wi-Fi on, then scan the receiver QR code again.")
                return
            }
            token = session
            localIsGroupOwner = false
            peerName = data.optString("name", "NEXORA device")
            active.set(true)
            register()
            callbacks.onStatus("Joining " + peerName + "'s private Wi-Fi Direct group…")
            val config = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(password).build()
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { callbacks.onStatus("Connection requested. Approve Android's Wi-Fi Direct prompt if shown…") }
                override fun onFailure(reason: Int) {
                    active.set(false)
                    callbacks.onStatus("Could not join Wi-Fi Direct group (code " + reason + "). Keep Wi-Fi on and scan again.")
                }
            })
        } catch (e: Exception) { callbacks.onStatus(e.localizedMessage ?: "That QR code could not be read.") }
    }

    @Suppress("DEPRECATION")
    private fun register() {
        if (receiverRegistered) return
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
        receiverRegistered = true
    }

    private fun startServer() {
        if (!serverStarting.compareAndSet(false, true)) return
        io.execute {
            try {
                if (server != null) return@execute
                val listening = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)); soTimeout = 1000 }
                server = listening
                callbacks.onStatus("Waiting for phones to scan your group QR code…")
                while (active.get() && !listening.isClosed) {
                    try {
                        val incoming = listening.accept()
                        handleIncoming(incoming)
                    } catch (_: SocketTimeoutException) { }
                }
            } catch (e: Exception) {
                if (active.get()) {
                    active.set(false)
                    callbacks.onStatus("Wi-Fi Direct receiver could not start: " + (e.localizedMessage ?: "socket error"))
                }
            } finally {
                serverStarting.set(false)
                if (server?.isClosed != false) server = null
            }
        }
    }

    private fun startPeerSession(
        s: Socket,
        input: DataInputStream,
        output: DataOutputStream,
        localReceiver: Boolean
    ) {
        socket = s
        peerInput = input
        peerOutput = output
        localIsGroupOwner = localReceiver
        s.soTimeout = 0
        acknowledgementQueue.clear()
        sendPermit.drainPermits()
        callbacks.onConnected("WIFI_DIRECT", peerName, localReceiver)
        callbacks.onStatus("Connected to " + peerName + ". You can send and receive over this connection.")
        io.execute { peerReadLoop(s, input, output) }
    }

    private fun peerReadLoop(s: Socket, input: DataInputStream, output: DataOutputStream) {
        try {
            while (active.get() && !s.isClosed) {
                when (input.readUnsignedByte()) {
                    FRAME_BATCH -> {
                        remoteOwnsTurn = true
                        val count = input.readInt()
                        require(count in 1..MAX_FILES) { "Invalid file count." }
                        data class IncomingMeta(val name: String, val mime: String, val size: Long)
                        val metadata = ArrayList<IncomingMeta>(count)
                        repeat(count) {
                            val name = safeName(input.readUTF())
                            val mime = input.readUTF().take(120).ifBlank { "application/octet-stream" }
                            val size = input.readLong()
                            require(size in 0..(20L * 1024 * 1024 * 1024)) { "Invalid file size." }
                            metadata.add(IncomingMeta(name, mime, size))
                        }
                        synchronized(incomingQueueLock) {
                            incomingQueue.clear()
                            cancelledIncomingItems.clear()
                            metadata.forEachIndexed { index, item ->
                                incomingQueue.add(IncomingQueueItem(index, item.name, item.size, "Queued", 0))
                            }
                        }
                        publishIncomingQueue()
                        metadata.forEachIndexed { index, item ->
                            currentIncomingIndex = index
                            if (cancelledIncomingItems.contains(index)) {
                                skipIncomingBytes(input, item.size)
                                updateIncomingQueue(index, "Cancelled", 0)
                                synchronized(outputLock) {
                                    output.writeByte(FRAME_ACK)
                                    output.writeBoolean(false)
                                    output.flush()
                                }
                                callbacks.onStatus("Incoming transfer cancelled: " + item.name)
                            } else {
                                updateIncomingQueue(index, "Receiving", 0)
                                callbacks.onProgress("Receiving " + item.name + " · 0%")
                                val saved = saveIncoming(input, item.name, item.mime, item.size, index)
                                updateIncomingQueue(index, if (saved) "Completed" else "Cancelled", if (saved) 100 else incomingProgress(index))
                                synchronized(outputLock) {
                                    output.writeByte(FRAME_ACK)
                                    output.writeBoolean(saved)
                                    output.flush()
                                }
                                if (saved) callbacks.onReceived(item.name)
                                else callbacks.onStatus("Incoming transfer cancelled: " + item.name)
                            }
                        }
                        currentIncomingIndex = -1
                        callbacks.onStatus("All files received successfully.")
                    }
                    FRAME_ACK -> acknowledgementQueue.offer(input.readBoolean())
                    FRAME_REQUEST_SEND -> {
                        val grantNow = synchronized(permitLock) {
                            if (localOwnsTurn || (localWaitingForTurn && localIsGroupOwner)) {
                                remoteRequestPending = true
                                false
                            } else {
                                remoteOwnsTurn = true
                                true
                            }
                        }
                        if (grantNow) writeControl(FRAME_GRANT_SEND)
                    }
                    FRAME_GRANT_SEND -> sendPermit.release()
                    FRAME_RELEASE_SEND -> {
                        val grantQueuedPeer = synchronized(permitLock) {
                            remoteOwnsTurn = false
                            if (remoteRequestPending && !localOwnsTurn && !localWaitingForTurn) {
                                remoteRequestPending = false
                                remoteOwnsTurn = true
                                true
                            } else false
                        }
                        if (grantQueuedPeer) writeControl(FRAME_GRANT_SEND)
                    }
                    else -> throw IOException("The connected phone sent an unknown NEXORA transfer frame.")
                }
            }
        } catch (e: Exception) {
            if (active.get() && !s.isClosed) callbacks.onStatus("Connection ended: " + (e.localizedMessage ?: "network closed"))
        } finally {
            runCatching { s.close() }
            if (socket === s) {
                socket = null
                peerInput = null
                peerOutput = null
                localWaitingForTurn = false
                localOwnsTurn = false
                remoteOwnsTurn = false
                remoteRequestPending = false
                acknowledgementQueue.offer(false)
                sendPermit.release()
                callbacks.onDisconnected("WIFI_DIRECT")
            }
        }
    }

    private fun writeControl(frame: Int) {
        val output = peerOutput ?: throw IOException("The NEXORA connection is not ready.")
        synchronized(outputLock) {
            output.writeByte(frame)
            output.flush()
        }
    }

    private fun requestSendTurn() {
        sendPermit.drainPermits()
        synchronized(permitLock) { localWaitingForTurn = true }
        writeControl(FRAME_REQUEST_SEND)
        if (!sendPermit.tryAcquire(120, TimeUnit.SECONDS)) {
            synchronized(permitLock) { localWaitingForTurn = false }
            throw IOException("The other phone is busy. Please try sending again.")
        }
        synchronized(permitLock) {
            localWaitingForTurn = false
            localOwnsTurn = true
        }
    }

    private fun releaseSendTurn() {
        runCatching { writeControl(FRAME_RELEASE_SEND) }
        val grantQueuedPeer = synchronized(permitLock) {
            localOwnsTurn = false
            if (remoteRequestPending && !localWaitingForTurn) {
                remoteRequestPending = false
                remoteOwnsTurn = true
                true
            } else false
        }
        if (grantQueuedPeer) runCatching { writeControl(FRAME_GRANT_SEND) }
    }

    private fun connectSocket(host: String) {
        if (socket?.isConnected == true && socket?.isClosed == false) return
        if (!connecting.compareAndSet(false, true)) return
        io.execute {
            try {
                var connected: Socket? = null
                for (attempt in 0 until 15) {
                    if (!active.get()) return@execute
                    try {
                        connected = Socket().apply {
                            tcpNoDelay = true
                            keepAlive = true
                            connect(InetSocketAddress(host, PORT), 2000)
                        }
                        break
                    } catch (_: Exception) { Thread.sleep(300) }
                }
                val s = connected ?: throw IOException("The receiving phone did not respond. Scan its QR code again.")
                val output = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                output.writeUTF(MAGIC)
                output.writeUTF(token ?: "")
                output.writeUTF(deviceName())
                output.flush()
                val input = DataInputStream(BufferedInputStream(s.getInputStream()))
                if (!input.readBoolean()) throw IOException("The QR session was rejected or expired.")
                startPeerSession(s, input, output, false)
            } catch (e: Exception) {
                runCatching { socket?.close() }
                socket = null
                if (active.get()) callbacks.onStatus("Wi-Fi Direct connection failed: " + e.localizedMessage)
            } finally { connecting.set(false) }
        }
    }

    private fun handleIncoming(s: Socket) {
        try {
            s.soTimeout = 15000
            val input = DataInputStream(BufferedInputStream(s.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            val remoteMagic = input.readUTF()
            val remoteToken = input.readUTF()
            val remoteName = input.readUTF().trim().take(24).ifBlank { "NEXORA device" }
            val valid = remoteMagic == MAGIC && remoteToken == token
            output.writeBoolean(valid)
            output.flush()
            if (!valid) { s.close(); return }
            startGroupPeerSession(s, input, output, remoteName)
        } catch (e: Exception) {
            runCatching { s.close() }
            if (active.get()) callbacks.onStatus("Receive connection failed: " + (e.localizedMessage ?: "network error"))
        }
    }

    private fun startGroupPeerSession(s: Socket, input: DataInputStream, output: DataOutputStream, remoteName: String) {
        val id = UUID.randomUUID().toString()
        val peer = GroupPeer(id, remoteName, s, input, output)
        s.soTimeout = 0
        groupPeers[id] = peer
        callbacks.onConnected(id, peer.name, true)
        callbacks.onStatus("Connected to " + peer.name + ". Group Share is ready for this phone.")
        io.execute { groupPeerReadLoop(peer) }
    }

    private fun groupPeerReadLoop(peer: GroupPeer) {
        val s = peer.socket
        try {
            while (active.get() && !s.isClosed) {
                when (peer.input.readUnsignedByte()) {
                    FRAME_BATCH -> synchronized(groupIncomingLock) {
                        peer.remoteOwnsTurn = true
                        val count = peer.input.readInt()
                        require(count in 1..MAX_FILES) { "Invalid file count." }
                        data class IncomingMeta(val name: String, val mime: String, val size: Long)
                        val metadata = ArrayList<IncomingMeta>(count)
                        repeat(count) {
                            val name = safeName(peer.input.readUTF())
                            val mime = peer.input.readUTF().take(120).ifBlank { "application/octet-stream" }
                            val size = peer.input.readLong()
                            require(size in 0..(20L * 1024 * 1024 * 1024)) { "Invalid file size." }
                            metadata.add(IncomingMeta(name, mime, size))
                        }
                        synchronized(incomingQueueLock) {
                            incomingQueue.clear()
                            cancelledIncomingItems.clear()
                            metadata.forEachIndexed { index, item ->
                                incomingQueue.add(IncomingQueueItem(index, item.name, item.size, "Queued", 0))
                            }
                        }
                        publishIncomingQueue()
                        metadata.forEachIndexed { index, item ->
                            currentIncomingIndex = index
                            if (cancelledIncomingItems.contains(index)) {
                                skipIncomingBytes(peer.input, item.size)
                                updateIncomingQueue(index, "Cancelled", 0)
                                synchronized(peer.outputLock) {
                                    peer.output.writeByte(FRAME_ACK)
                                    peer.output.writeBoolean(false)
                                    peer.output.flush()
                                }
                            } else {
                                updateIncomingQueue(index, "Receiving", 0)
                                callbacks.onProgress("Receiving " + item.name + " · 0%")
                                val saved = saveIncoming(peer.input, item.name, item.mime, item.size, index)
                                updateIncomingQueue(index, if (saved) "Completed" else "Cancelled", if (saved) 100 else incomingProgress(index))
                                synchronized(peer.outputLock) {
                                    peer.output.writeByte(FRAME_ACK)
                                    peer.output.writeBoolean(saved)
                                    peer.output.flush()
                                }
                                if (saved) callbacks.onReceived(item.name)
                                else callbacks.onStatus("Incoming transfer cancelled: " + item.name)
                            }
                        }
                        currentIncomingIndex = -1
                        callbacks.onStatus("Incoming batch finished from " + peer.name + ".")
                    }
                    FRAME_ACK -> peer.acknowledgementQueue.offer(peer.input.readBoolean())
                    FRAME_REQUEST_SEND -> {
                        val grant = synchronized(peer.permitLock) {
                            if (peer.localOwnsTurn || peer.localWaitingForTurn) {
                                peer.remoteRequestPending = true
                                false
                            } else {
                                peer.remoteOwnsTurn = true
                                true
                            }
                        }
                        if (grant) writeGroupControl(peer, FRAME_GRANT_SEND)
                    }
                    FRAME_GRANT_SEND -> peer.sendPermit.release()
                    FRAME_RELEASE_SEND -> {
                        var grantRemote = false
                        var grantLocal = false
                        synchronized(peer.permitLock) {
                            peer.remoteOwnsTurn = false
                            if (peer.remoteRequestPending && !peer.localOwnsTurn && !peer.localWaitingForTurn) {
                                peer.remoteRequestPending = false
                                peer.remoteOwnsTurn = true
                                grantRemote = true
                            } else if (peer.localWaitingForTurn && !peer.localOwnsTurn) {
                                grantLocal = true
                            }
                        }
                        if (grantRemote) writeGroupControl(peer, FRAME_GRANT_SEND)
                        if (grantLocal) peer.sendPermit.release()
                    }
                    else -> throw IOException("The connected phone sent an unknown NEXORA transfer frame.")
                }
            }
        } catch (e: Exception) {
            if (active.get() && !s.isClosed) callbacks.onStatus("Group connection ended: " + (e.localizedMessage ?: "network closed"))
        } finally {
            runCatching { s.close() }
            groupPeers.remove(peer.id)
            peer.acknowledgementQueue.offer(false)
            peer.sendPermit.release()
            callbacks.onDisconnected(peer.id)
            callbacks.onStatus(peer.name + " disconnected from the group.")
        }
    }

    private fun writeGroupControl(peer: GroupPeer, frame: Int) {
        synchronized(peer.outputLock) {
            peer.output.writeByte(frame)
            peer.output.flush()
        }
    }

    private fun requestGroupSendTurn(peer: GroupPeer) {
        peer.sendPermit.drainPermits()
        synchronized(peer.permitLock) { peer.localWaitingForTurn = true }
        writeGroupControl(peer, FRAME_REQUEST_SEND)
        if (!peer.sendPermit.tryAcquire(120, TimeUnit.SECONDS)) {
            synchronized(peer.permitLock) { peer.localWaitingForTurn = false }
            throw IOException("The other phone is busy. Please try again.")
        }
        synchronized(peer.permitLock) {
            peer.localWaitingForTurn = false
            peer.localOwnsTurn = true
        }
    }

    private fun releaseGroupSendTurn(peer: GroupPeer) {
        runCatching { writeGroupControl(peer, FRAME_RELEASE_SEND) }
        var grantRemote = false
        synchronized(peer.permitLock) {
            peer.localOwnsTurn = false
            if (peer.remoteRequestPending && !peer.localWaitingForTurn) {
                peer.remoteRequestPending = false
                peer.remoteOwnsTurn = true
                grantRemote = true
            }
        }
        if (grantRemote) runCatching { writeGroupControl(peer, FRAME_GRANT_SEND) }
    }

    fun sendFilesToGroup(uris: List<Uri>, excludedPeerIds: Set<String> = emptySet()) {
        if (!localIsGroupOwner) {
            sendFiles(uris)
            return
        }
        if (uris.isEmpty()) { callbacks.onStatus("Choose at least one item to send."); return }
        val recipients = groupPeers.values.filter { it.id !in excludedPeerIds }.sortedBy { it.name }
        if (recipients.isEmpty()) {
            callbacks.onStatus("No group recipients are selected or connected.")
            return
        }
        if (!sending.compareAndSet(false, true)) {
            callbacks.onStatus("A transfer is already running.")
            return
        }
        outgoingCancelled.set(false)
        callbacks.onProgress("Preparing group transfer…")
        io.execute {
            try {
                val completedNames = LinkedHashSet<String>()
                recipients.forEach { peer ->
                    if (outgoingCancelled.get()) return@forEach
                    activeGroupSendPeer = peer
                    var ownsTurn = false
                    try {
                        requestGroupSendTurn(peer)
                        ownsTurn = true
                        val metadata = uris.take(MAX_FILES).map { uri ->
                            val name = safeName(queryName(uri))
                            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                            val size = querySize(uri)
                            require(size >= 0) { "Could not determine the size of " + name }
                            Triple(uri, name, Pair(mime.take(120), size))
                        }
                        synchronized(peer.outputLock) {
                            peer.output.writeByte(FRAME_BATCH)
                            peer.output.writeInt(metadata.size)
                            metadata.forEach { item ->
                                peer.output.writeUTF(item.second)
                                peer.output.writeUTF(item.third.first)
                                peer.output.writeLong(item.third.second)
                            }
                            peer.output.flush()
                        }
                        metadata.forEach { item ->
                            val uri = item.first
                            val name = item.second
                            val size = item.third.second
                            synchronized(peer.outputLock) {
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    val buffer = ByteArray(256 * 1024)
                                    var remaining = size
                                    var done = 0L
                                    while (remaining > 0) {
                                        waitIfOutgoingPaused()
                                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                        if (n < 0) throw EOFException("The file changed before " + name + " was sent.")
                                        peer.output.write(buffer, 0, n)
                                        remaining -= n
                                        done += n
                                        callbacks.onProgress("Sending to " + peer.name + ": " + name + " · " + (done * 100 / size.coerceAtLeast(1)) + "%")
                                    }
                                } ?: throw IOException("Could not open " + name)
                                peer.output.flush()
                            }
                            val confirmed = peer.acknowledgementQueue.poll(10, TimeUnit.MINUTES)
                                ?: throw IOException(peer.name + " did not acknowledge " + name)
                            if (confirmed) completedNames.add(name)
                            else callbacks.onStatus(peer.name + " cancelled " + name)
                        }
                        callbacks.onStatus("Sent selected files to " + peer.name + ".")
                    } catch (e: Exception) {
                        callbacks.onStatus("Could not send to " + peer.name + ": " + (e.localizedMessage ?: "network error"))
                    } finally {
                        if (ownsTurn) releaseGroupSendTurn(peer)
                        activeGroupSendPeer = null
                    }
                }
                completedNames.forEach { callbacks.onStatus("Sent: " + it) }
                callbacks.onStatus("Group transfer finished.")
            } finally {
                outgoingPaused.set(false)
                outgoingCancelled.set(false)
                sending.set(false)
            }
        }
    }

    private fun saveIncoming(input: DataInputStream, name: String, mime: String, size: Long, queueId: Int): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/NEXORA/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create the Downloads file.")
        incomingName = name
        incomingPaused.set(false)
        incomingCancelled.set(cancelledIncomingItems.contains(queueId))
        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                val buffer = ByteArray(256 * 1024)
                var remaining = size
                var done = 0L
                while (remaining > 0) {
                    while (incomingPaused.get() && active.get() && !incomingCancelled.get()) Thread.sleep(100)
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (n < 0) throw EOFException("Connection ended before the file completed.")
                    if (!incomingCancelled.get()) out.write(buffer, 0, n)
                    remaining -= n
                    done += n
                    val percent = (done * 100 / size.coerceAtLeast(1)).toInt()
                    updateIncomingQueue(queueId, "Receiving", percent)
                    callbacks.onProgress("Receiving " + name + " · " + percent + "%")
                }
            } ?: throw IOException("Could not open Downloads output.")
            if (incomingCancelled.get()) {
                context.contentResolver.delete(uri, null, null)
                return false
            }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return true
        } catch (e: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw e
        } finally {
            incomingPaused.set(false)
            incomingCancelled.set(false)
            incomingName = null
        }
    }

    private fun skipIncomingBytes(input: DataInputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped
            else {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (n < 0) throw EOFException("Connection ended while skipping a cancelled item.")
                remaining -= n
            }
        }
    }

    private fun publishIncomingQueue() {
        val snapshot = synchronized(incomingQueueLock) { incomingQueue.toList() }
        callbacks.onIncomingQueueChanged(snapshot)
    }

    private fun incomingProgress(id: Int): Int =
        synchronized(incomingQueueLock) { incomingQueue.firstOrNull { it.id == id }?.progress ?: 0 }

    private fun updateIncomingQueue(id: Int, status: String, progress: Int) {
        synchronized(incomingQueueLock) {
            val index = incomingQueue.indexOfFirst { it.id == id }
            if (index >= 0) incomingQueue[index] = incomingQueue[index].copy(status = status, progress = progress)
        }
        publishIncomingQueue()
    }

    fun cancelIncomingItem(id: Int) {
        val valid = synchronized(incomingQueueLock) { incomingQueue.any { it.id == id && it.status !in setOf("Completed", "Cancelled") } }
        if (!valid) return
        cancelledIncomingItems.add(id)
        if (id == currentIncomingIndex) cancelIncoming()
        else updateIncomingQueue(id, "Cancelled", 0)
    }

    fun incomingQueueSnapshot(): List<IncomingQueueItem> =
        synchronized(incomingQueueLock) { incomingQueue.toList() }

    fun pauseIncoming() {
        if (incomingName != null) {
            incomingPaused.set(true)
            callbacks.onProgress("Paused: " + incomingName)
        }
    }

    fun resumeIncoming() {
        if (incomingName != null) {
            incomingPaused.set(false)
            callbacks.onProgress("Receiving " + incomingName + " · Resuming")
        }
    }

    fun cancelIncoming() {
        if (incomingName != null) {
            incomingCancelled.set(true)
            incomingPaused.set(false)
        }
    }

    fun isIncomingPaused(): Boolean = incomingPaused.get()
    fun hasIncomingTransfer(): Boolean = incomingName != null

    fun pauseOutgoing() {
        if (sending.get()) {
            outgoingPaused.set(true)
            callbacks.onProgress("Paused: Sending")
        }
    }

    fun resumeOutgoing() {
        outgoingPaused.set(false)
        if (sending.get()) callbacks.onProgress("Sending · Resuming")
    }

    fun isOutgoingPaused(): Boolean = outgoingPaused.get()

    fun cancelOutgoing() {
        if (!sending.get()) return
        outgoingCancelled.set(true)
        outgoingPaused.set(false)
        if (localIsGroupOwner) {
            runCatching { activeGroupSendPeer?.socket?.close() }
        } else {
            runCatching { socket?.close() }
        }
        callbacks.onStatus("Outgoing transfer cancelled. Reconnect if the peer connection ended.")
    }

    private fun waitIfOutgoingPaused() {
        while (outgoingPaused.get() && active.get() && !outgoingCancelled.get()) Thread.sleep(100)
        if (outgoingCancelled.get()) throw IOException("Outgoing transfer cancelled.")
        if (!active.get()) throw IOException("The Wi-Fi Direct session ended.")
    }

    fun sendFiles(uris: List<Uri>) {
        if (localIsGroupOwner) { sendFilesToGroup(uris); return }
        val s = socket
        if (s == null || s.isClosed || peerOutput == null) {
            callbacks.onStatus("Connect to another phone before sending files.")
            return
        }
        if (uris.isEmpty()) {
            callbacks.onStatus("Choose at least one item to send.")
            return
        }
        if (!sending.compareAndSet(false, true)) {
            callbacks.onStatus("A transfer is already running.")
            return
        }
        outgoingCancelled.set(false)
        callbacks.onProgress("Preparing transfer…")
        io.execute {
            var ownsTurn = false
            try {
                requestSendTurn()
                ownsTurn = true
                val output = peerOutput ?: throw IOException("The NEXORA connection ended.")
                val outgoing = uris.take(MAX_FILES)
                data class OutgoingMeta(val uri: Uri, val name: String, val mime: String, val size: Long)
                    val metadata = outgoing.map { uri ->
                        val name = safeName(queryName(uri))
                        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                        val size = querySize(uri)
                        require(size >= 0) { "Could not determine the size of " + name }
                        OutgoingMeta(uri, name, mime.take(120), size)
                    }
                    synchronized(outputLock) {
                        output.writeByte(FRAME_BATCH)
                        output.writeInt(metadata.size)
                        metadata.forEach { item ->
                            output.writeUTF(item.name)
                            output.writeUTF(item.mime)
                            output.writeLong(item.size)
                        }
                        output.flush()
                    }
                    metadata.forEach { item ->
                        val uri = item.uri
                        val name = item.name
                        val size = item.size
                        synchronized(outputLock) {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                            val buffer = ByteArray(256 * 1024)
                            var remaining = size
                            var done = 0L
                            while (remaining > 0) {
                                waitIfOutgoingPaused()
                                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (n < 0) throw EOFException("The file changed or ended before all of " + name + " was sent.")
                                output.write(buffer, 0, n)
                                remaining -= n
                                done += n
                                callbacks.onProgress("Sending " + name + " · " + (done * 100 / size.coerceAtLeast(1)) + "%")
                            }
                        } ?: throw IOException("Could not open " + name)
                        output.flush()
                    }
                    val confirmed = acknowledgementQueue.poll(10, TimeUnit.MINUTES)
                        ?: throw IOException("The receiving phone did not respond to " + name)
                    if (confirmed) callbacks.onStatus("Sent: " + name)
                    else callbacks.onStatus("Receiver cancelled: " + name)
                }
                callbacks.onStatus("Transfer complete.")
            } catch (e: Exception) {
                val busy = e.localizedMessage?.contains("busy", ignoreCase = true) == true
                if (!busy) {
                    // Closing the socket lets the single peer reader own cleanup
                    // and emit exactly one disconnect callback.
                    runCatching { s.close() }
                }
                callbacks.onStatus("Send failed: " + (e.localizedMessage ?: "network error"))
            } finally {
                if (ownsTurn) releaseSendTurn()
                outgoingPaused.set(false)
                outgoingCancelled.set(false)
                sending.set(false)
            }
        }
    }

    private fun querySize(uri: Uri): Long {
        val descriptorSize = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L }.getOrDefault(-1L)
        if (descriptorSize >= 0) return descriptorSize
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (index >= 0 && !it.isNull(index)) return it.getLong(index)
            }
        }
        return -1L
    }

    private fun queryName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0) ?: "NEXORA-file"
        }
        return uri.lastPathSegment ?: "NEXORA-file"
    }
    private fun safeName(value: String): String {
        val name = value.substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' && it != '\u007f' }.take(240)
        require(name.isNotBlank() && name != "." && name != "..") { "Invalid file name." }
        return name
    }
    private fun deviceName(): String =
        context.getSharedPreferences("nexora_preferences", Context.MODE_PRIVATE).getString("device_name", null)
            ?.trim()?.takeIf { it.isNotEmpty() }?.take(24) ?: (Build.MODEL ?: "Android device").take(24)

    fun stop() {
        active.set(false)
        localIsGroupOwner = false
        currentQrPayload = null

        // Close every group-member socket as well as the single-peer socket.
        // Group-owner sessions do not use the single-peer 'socket' field, so
        // closing only that field left group reader threads blocked in read().
        val peers = groupPeers.values.toList()
        peers.forEach { peer ->
            peer.acknowledgementQueue.offer(false)
            peer.sendPermit.release()
            runCatching { peer.socket.close() }
        }
        groupPeers.clear()
        activeGroupSendPeer = null

        val activePeer = socket
        runCatching { activePeer?.close() }
        if (activePeer == null && peers.isEmpty()) callbacks.onDisconnected("WIFI_DIRECT")
        runCatching { server?.close() }
        server = null
        runCatching { manager.removeGroup(channel, null) }
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
    }
}
