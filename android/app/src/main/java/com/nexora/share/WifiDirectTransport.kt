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
import java.util.concurrent.atomic.AtomicBoolean

/** QR-authenticated Wi-Fi Direct file transport. */
class WifiDirectTransport(private val context: Context, private val callbacks: Callbacks) {
    interface Callbacks {
        fun onStatus(message: String)
        fun onQrReady(payload: String)
        fun onConnected(peerName: String, localIsReceiver: Boolean)
        fun onDisconnected()
        fun onProgress(message: String)
        fun onReceived(name: String)
    }
    companion object {
        private const val PORT = 43821
        private const val MAGIC = "NEXORA-WFD-1"
        private const val MAX_FILES = 200
    }
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel = manager.initialize(context, context.mainLooper, null)
    private val io = Executors.newCachedThreadPool()
    private val active = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    private val sending = AtomicBoolean(false)
    private val serverStarting = AtomicBoolean(false)
    @Volatile private var server: ServerSocket? = null
    @Volatile private var socket: Socket? = null
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
            if (info.isGroupOwner) startServer()
            else {
                val host = info.groupOwnerAddress?.hostAddress
                if (host.isNullOrBlank()) callbacks.onStatus("Wi-Fi Direct connected, but Android did not provide the receiver address.")
                else connectSocket(host)
            }
        } else if (socket != null) {
            runCatching { socket?.close() }
            socket = null
            callbacks.onDisconnected()
            callbacks.onStatus("Wi-Fi Direct connection ended. Scan again to reconnect.")
        }
    }

    private fun isWifiEnabled(): Boolean {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return false
        return runCatching { wifi.isWifiEnabled }.getOrDefault(false)
    }

    fun startReceiving() {
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
        currentQrPayload = null
        token = UUID.randomUUID().toString()
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
            peerName = data.optString("name", "NEXORA device")
            active.set(true)
            register()
            callbacks.onStatus("Joining " + peerName + "'s private Wi-Fi Direct group…")
            if (Build.VERSION.SDK_INT < 29) throw IllegalStateException("QR Wi-Fi Direct joining needs Android 10 or newer.")
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
                callbacks.onStatus("Waiting for the phone scanning your QR code…")
                while (active.get() && !listening.isClosed) {
                    try {
                        val incoming = listening.accept()
                        socket = incoming
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

    private fun connectSocket(host: String) {
        if (socket?.isConnected == true && socket?.isClosed == false) return
        if (!connecting.compareAndSet(false, true)) return
        io.execute {
            try {
                var connected: Socket? = null
                for (attempt in 0 until 15) {
                    if (!active.get()) return@execute
                    try { connected = Socket().apply { connect(InetSocketAddress(host, PORT), 2500) }; break }
                    catch (_: Exception) { Thread.sleep(500) }
                }
                val s = connected ?: throw IOException("The receiving phone did not respond. Scan its QR code again.")
                socket = s
                val output = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                output.writeUTF(MAGIC); output.writeUTF(token ?: ""); output.flush()
                val input = DataInputStream(BufferedInputStream(s.getInputStream()))
                if (!input.readBoolean()) throw IOException("The QR session was rejected or expired.")
                callbacks.onConnected(peerName, false)
                callbacks.onStatus("Connected to " + peerName + ". Select files to send.")
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
            val valid = input.readUTF() == MAGIC && input.readUTF() == token
            output.writeBoolean(valid); output.flush()
            if (!valid) { s.close(); return }
            callbacks.onConnected("NEXORA sender", true)
            s.soTimeout = 0
            while (active.get() && !s.isClosed) {
                val count = try { input.readInt() } catch (_: EOFException) { break }
                require(count in 1..MAX_FILES) { "Invalid file count." }
                repeat(count) {
                    val name = safeName(input.readUTF())
                    val mime = input.readUTF().take(120).ifBlank { "application/octet-stream" }
                    val size = input.readLong()
                    require(size in 0..(20L * 1024 * 1024 * 1024)) { "Invalid file size." }
                    saveIncoming(input, name, mime, size)
                    output.writeBoolean(true)
                    output.flush()
                    callbacks.onReceived(name)
                }
                callbacks.onStatus("All files received successfully.")
            }
        } catch (e: Exception) { if (active.get()) callbacks.onStatus("Receive failed: " + e.localizedMessage) }
        finally {
            runCatching { s.close() }
            if (socket === s) socket = null
            callbacks.onDisconnected()
        }
    }

    private fun saveIncoming(input: DataInputStream, name: String, mime: String, size: Long) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/NEXORA/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create the Downloads file.")
        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                val buffer = ByteArray(256 * 1024)
                var remaining = size
                var done = 0L
                while (remaining > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (n < 0) throw EOFException("Connection ended before the file completed.")
                    out.write(buffer, 0, n); remaining -= n; done += n
                    callbacks.onProgress("Receiving " + name + " · " + (done * 100 / size.coerceAtLeast(1)) + "%")
                }
            } ?: throw IOException("Could not open Downloads output.")
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) { context.contentResolver.delete(uri, null, null); throw e }
    }

    fun sendFiles(uris: List<Uri>) {
        val s = socket ?: run { callbacks.onStatus("Connect by scanning the receiver's QR code first."); return }
        if (!sending.compareAndSet(false, true)) { callbacks.onStatus("A transfer is already running."); return }
        io.execute {
            try {
                val output = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                val inputAck = DataInputStream(BufferedInputStream(s.getInputStream()))
                output.writeInt(uris.size.coerceAtMost(MAX_FILES))
                uris.take(MAX_FILES).forEach { uri ->
                    val name = safeName(queryName(uri))
                    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    val size = querySize(uri)
                    require(size >= 0) { "Could not determine the size of " + name }
                    output.writeUTF(name); output.writeUTF(mime.take(120)); output.writeLong(size)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(256 * 1024)
                        var remaining = size
                        var done = 0L
                        while (remaining > 0) {
                            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (n < 0) throw EOFException("The file changed or ended before all of " + name + " was sent.")
                            output.write(buffer, 0, n)
                            remaining -= n
                            done += n
                            callbacks.onProgress("Sending " + name + " · " + (done * 100 / size.coerceAtLeast(1)) + "%")
                        }
                    } ?: throw IOException("Could not open " + name)
                    output.flush()
                    if (!inputAck.readBoolean()) throw IOException("The receiving phone did not confirm " + name)
                    callbacks.onStatus("Sent: " + name)
                }
                callbacks.onStatus("Transfer complete.")
            } catch (e: Exception) {
                runCatching { s.close() }
                if (socket === s) socket = null
                callbacks.onDisconnected()
                callbacks.onStatus("Send failed: " + e.localizedMessage)
            } finally { sending.set(false) }
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
        currentQrPayload = null
        runCatching { socket?.close() }; socket = null
        runCatching { server?.close() }; server = null
        runCatching { manager.removeGroup(channel, null) }
        if (receiverRegistered) { runCatching { context.unregisterReceiver(receiver) }; receiverRegistered = false }
        callbacks.onDisconnected()
    }
}
