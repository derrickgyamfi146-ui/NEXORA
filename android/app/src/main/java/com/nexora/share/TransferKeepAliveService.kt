package com.nexora.share

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

class TransferKeepAliveService : Service() {
    interface Listener {
        fun onStatus(message: String)
        fun onPeerFound(endpointId: String, name: String)
        fun onPeerLost(endpointId: String)
        fun onConnectionRequest(endpointId: String, name: String, digits: String)
        fun onConnected(endpointId: String)
        fun onDisconnected(endpointId: String)
        fun onTransferProgress(message: String)
        fun onQrReady(payload: String)
    }

    inner class LocalBinder : Binder() {
        fun service(): TransferKeepAliveService = this@TransferKeepAliveService
    }

    private val binder = LocalBinder()
    private val wifiTransport by lazy {
        WifiDirectTransport(this, object : WifiDirectTransport.Callbacks {
            override fun onStatus(message: String) { status(message) }
            override fun onQrReady(payload: String) { listener?.onQrReady(payload) }
            override fun onConnected(peerName: String) {
                synchronized(connectedEndpoints) { connectedEndpoints.add("WIFI_DIRECT") }
                endpointNames["WIFI_DIRECT"] = peerName
                listener?.onConnected("WIFI_DIRECT")
                status("Connected to " + peerName + " over Wi-Fi Direct.")
            }
            override fun onDisconnected() {
                synchronized(connectedEndpoints) { connectedEndpoints.remove("WIFI_DIRECT") }
                endpointNames.remove("WIFI_DIRECT")
                listener?.onDisconnected("WIFI_DIRECT")
            }
            override fun onProgress(message: String) { listener?.onTransferProgress(message) }
            override fun onReceived(name: String) { status("Received and saved: " + name) }
        })
    }
    private var transferWakeLock: android.os.PowerManager.WakeLock? = null
    private val client by lazy { Nearby.getConnectionsClient(this) }
    private var listener: Listener? = null
    private var lastStatusMessage = "NEXORA is ready."
    private val connectedEndpoints = LinkedHashSet<String>()
    private val endpointNames = ConcurrentHashMap<String, String>()
    private val pendingPayloadEndpoints = ConcurrentHashMap<Long, MutableSet<String>>()
    private var advertising = false
    private var discovering = false
    private val openFiles = ConcurrentHashMap<Long, android.os.ParcelFileDescriptor>()
    private val outgoingPayloadNames = ConcurrentHashMap<Long, String>()
    private val outgoingPayloadSuccesses = ConcurrentHashMap.newKeySet<Long>()
    private val outgoingEndpointProgress = ConcurrentHashMap<Long, ConcurrentHashMap<String, Int>>()
    private val outgoingQueue = ArrayDeque<Uri>()
    private val queuedUriKeys = LinkedHashSet<String>()
    private var activeOutgoingUriKey: String? = null
    private var sending = false
    private var outgoingSucceeded = 0
    private var outgoingFailed = 0
    private var currentOutgoingPayloadId: Long? = null
    private var transferStartedAt = 0L
    private var lastProgressAt = 0L
    private val incomingFiles = ConcurrentHashMap<Long, Pair<Uri, String>>()
    private val serviceId = "com.nexora.share"
    private var strategy = Strategy.P2P_STAR

    fun setListener(value: Listener?) { listener = value; value?.onStatus(lastStatusMessage) }

    val isAdvertising: Boolean get() = advertising
    val isConnected: Boolean get() = synchronized(connectedEndpoints) { connectedEndpoints.isNotEmpty() }
    fun connectedEndpointCount(): Int = synchronized(connectedEndpoints) { connectedEndpoints.size }
    fun connectedEndpointIds(): List<String> = synchronized(connectedEndpoints) { connectedEndpoints.toList() }
    fun connectedEndpointName(endpointId: String): String? = endpointNames[endpointId]

    @Synchronized
    fun setHighSpeedMode(enabled: Boolean) {
        val next = if (enabled) Strategy.P2P_POINT_TO_POINT else Strategy.P2P_STAR
        if (strategy == next) return
        val wasAdvertising = advertising
        val wasDiscovering = discovering
        if (wasAdvertising) client.stopAdvertising()
        if (wasDiscovering) client.stopDiscovery()
        advertising = false
        discovering = false
        strategy = next
        status(if (enabled) {
            "High-speed direct mode selected. Both phones must enable it."
        } else {
            "Standard compatibility mode selected."
        })
        if (wasAdvertising) startReceiving()
        else if (wasDiscovering) startFinding()
    }

    @Synchronized
    fun startReceiving() {
        promoteToForeground()
        advertising = true
        wifiTransport.startReceiving()
    }

    @Synchronized
    fun startFinding() {
        status("Scan the receiving phone's NEXORA QR code to connect over Wi-Fi Direct.")
    }

    fun connectWithQr(payload: String) {
        promoteToForeground()
        wifiTransport.connectWithQr(payload)
    }

    fun connect(endpointId: String, name: String) {
        status("Requesting connection to $name…")
        client.requestConnection(deviceName(), endpointId, connectionCallback)
            .addOnFailureListener { status("Connection request failed: ${it.localizedMessage}") }
    }

    fun accept(endpointId: String) {
        client.acceptConnection(endpointId, payloadCallback)
            .addOnFailureListener { status("Could not accept connection: ${it.localizedMessage}") }
    }

    fun reject(endpointId: String) {
        client.rejectConnection(endpointId)
        status("Connection rejected.")
    }

    @Synchronized
    fun sendFiles(uris: List<Uri>) {
        if (uris.isEmpty()) { status("Choose at least one file."); return }
        wifiTransport.sendFiles(uris)
    }

    @Synchronized
    private fun sendNextFile() {
        if (sending) return
        val endpoints = synchronized(connectedEndpoints) { connectedEndpoints.toList() }
        if (endpoints.isEmpty()) {
            sending = false
            status("Files are queued. Connect to a nearby phone to start sending.")
            return
        }
        while (true) {
            val uri = outgoingQueue.pollFirst()
            if (uri == null) {
                sending = false
                currentOutgoingPayloadId = null
                status("Transfer queue finished: $outgoingSucceeded sent, $outgoingFailed not sent.")
                return
            }
            activeOutgoingUriKey = uri.toString()
            var descriptor: android.os.ParcelFileDescriptor? = null
            try {
                val openedDescriptor = contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IOException("Could not open this file")
                descriptor = openedDescriptor
                val name = queryDisplayName(uri) ?: "NEXORA-file"
                val payload = Payload.fromFile(openedDescriptor)
                payload.setFileName(name)
                openFiles[payload.id] = descriptor
                descriptor = null // Ownership moves to openFiles until the transfer finishes.
                outgoingPayloadNames[payload.id] = name
                sending = true
                currentOutgoingPayloadId = payload.id
                transferStartedAt = System.currentTimeMillis()
                lastProgressAt = transferStartedAt
                status("Sending $name…")
                pendingPayloadEndpoints[payload.id] = java.util.Collections.synchronizedSet(endpoints.toMutableSet())
                outgoingPayloadSuccesses.remove(payload.id)
                outgoingEndpointProgress[payload.id] = ConcurrentHashMap<String, Int>().apply { endpoints.forEach { put(it, 0) } }
                client.sendPayload(endpoints, payload).addOnFailureListener {
                    pendingPayloadEndpoints.remove(payload.id)
                    outgoingPayloadNames.remove(payload.id)
                    outgoingPayloadSuccesses.remove(payload.id)
                    outgoingEndpointProgress.remove(payload.id)
                    openFiles.remove(payload.id)?.close()
                    currentOutgoingPayloadId = null
                    activeOutgoingUriKey?.let { queuedUriKeys.remove(it) }
                    activeOutgoingUriKey = null
                    sending = false
                    outgoingFailed++
                    status("Could not start $name: ${it.localizedMessage}. Trying the next file…")
                    sendNextFile()
                }
                return
            } catch (e: Exception) {
                runCatching { descriptor?.close() }
                activeOutgoingUriKey?.let { queuedUriKeys.remove(it) }
                activeOutgoingUriKey = null
                outgoingFailed++
                status("Could not send this file: ${e.localizedMessage}. Skipping to the next file…")
            }
        }
    }

    fun stopSharing() {
        wifiTransport.stop()
        client.stopAdvertising()
        client.stopDiscovery()
        advertising = false
        discovering = false
        client.stopAllEndpoints()
        synchronized(connectedEndpoints) { connectedEndpoints.clear() }
        pendingPayloadEndpoints.clear()
        openFiles.values.forEach { runCatching { it.close() } }
        openFiles.clear()
        outgoingPayloadNames.clear()
        outgoingPayloadSuccesses.clear()
        outgoingEndpointProgress.clear()
        endpointNames.clear()
        outgoingQueue.clear()
        queuedUriKeys.clear()
        activeOutgoingUriKey = null
        sending = false
        currentOutgoingPayloadId = null
        incomingFiles.clear()
        releaseTransferWakeLock()
        stopForegroundCompat()
        stopSelf()
        status("Stopped. You can start again.")
    }

    private fun deviceName(): String {
        val configured = getSharedPreferences("nexora_preferences", MODE_PRIVATE).getString("device_name", null)?.trim()
        return configured?.takeIf { it.isNotEmpty() }?.take(24) ?: (Build.MODEL ?: "Android device").take(24)
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            endpointNames[endpointId] = info.endpointName
            listener?.onPeerFound(endpointId, info.endpointName)
            status("Found ${info.endpointName}. Tap its button to connect.")
        }
        override fun onEndpointLost(endpointId: String) {
            listener?.onPeerLost(endpointId)
        }
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            endpointNames[endpointId] = info.endpointName
            listener?.onConnectionRequest(endpointId, info.endpointName, info.authenticationDigits)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                synchronized(connectedEndpoints) { connectedEndpoints.add(endpointId) }
                listener?.onConnected(endpointId)
                status("Connected to ${connectedEndpoints.size} phone(s). You can add more phones or send files.")
                if (outgoingQueue.isNotEmpty()) sendNextFile()
            } else {
                status("Connection was not completed. Try again.")
            }
        }
        override fun onDisconnected(endpointId: String) {
            synchronized(connectedEndpoints) { connectedEndpoints.remove(endpointId) }
            val activeId = currentOutgoingPayloadId
            if (activeId != null) {
                val pending = pendingPayloadEndpoints[activeId]
                if (pending != null) synchronized(pending) {
                    outgoingEndpointProgress[activeId]?.set(endpointId, -1)
                    pending.remove(endpointId)
                    if (pending.isEmpty()) {
                        pendingPayloadEndpoints.remove(activeId)
                        finalizeOutgoingPayload(activeId, "A receiving phone disconnected.")
                    }
                }
            }
            listener?.onDisconnected(endpointId)
            status("A phone disconnected. ${connectedEndpoints.size} phone(s) remain connected.")
            releaseWakeLockIfIdle()
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.FILE) {
                val filePayload = payload.asFile() ?: return
                val fileUri = filePayload.asUri() ?: return
                incomingFiles[payload.id] = Pair(fileUri, queryDisplayName(fileUri) ?: "NEXORA-received-file")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    val total = update.totalBytes
                    val done = update.bytesTransferred
                    val percent = if (total > 0) (done * 100 / total).toInt() else 0
                    if (openFiles.containsKey(update.payloadId)) {
                        val now = System.currentTimeMillis()
                        val elapsedSeconds = ((now - transferStartedAt).coerceAtLeast(1)) / 1000.0
                        val speed = if (elapsedSeconds > 0) (done / elapsedSeconds).toLong() else 0L
                        val remaining = if (speed > 0 && total > done) (total - done) / speed else 0L
                        outgoingEndpointProgress[update.payloadId]?.set(endpointId, percent)
                        val peerProgress = outgoingEndpointProgress[update.payloadId]?.entries
                            ?.sortedBy { endpointNames[it.key] ?: it.key }
                            ?.joinToString(" · ") { entry ->
                                val peer = endpointNames[entry.key] ?: "Phone"
                                peer + " " + if (entry.value < 0) "failed" else if (entry.value >= 100) "done" else entry.value.toString() + "%"
                            }.orEmpty()
                        listener?.onTransferProgress("Sending · $peerProgress · ${formatBytes(speed)}/s · ${formatTime(remaining)} left")
                        lastProgressAt = now
                    } else {
                        listener?.onTransferProgress("Receiving… $percent%")
                    }
                }
                PayloadTransferUpdate.Status.SUCCESS -> {
                    val pending = pendingPayloadEndpoints[update.payloadId]
                    if (pending != null) {
                        synchronized(pending) {
                            outgoingPayloadSuccesses.add(update.payloadId)
                            outgoingEndpointProgress[update.payloadId]?.set(endpointId, 100)
                            pending.remove(endpointId)
                            if (pending.isEmpty()) {
                                pendingPayloadEndpoints.remove(update.payloadId)
                                finalizeOutgoingPayload(update.payloadId, "Transfer finished.")
                            }
                        }
                    } else {
                        val incoming = incomingFiles.remove(update.payloadId)
                        if (incoming != null) saveReceivedFile(incoming.first, incoming.second)
                    }
                }
                PayloadTransferUpdate.Status.FAILURE -> {
                    val pending = pendingPayloadEndpoints[update.payloadId]
                    if (pending != null) {
                        synchronized(pending) {
                            outgoingEndpointProgress[update.payloadId]?.set(endpointId, -1)
                            pending.remove(endpointId)
                            status("One phone could not receive the file.")
                            if (pending.isEmpty()) {
                                pendingPayloadEndpoints.remove(update.payloadId)
                                finalizeOutgoingPayload(update.payloadId, "Transfer failed.")
                            }
                        }
                    } else {
                        incomingFiles.remove(update.payloadId)
                        status("Transfer failed. Check both phones and try again.")
                    }
                }
                PayloadTransferUpdate.Status.CANCELED -> {
                    // Keep the shared file descriptor open until every recipient has finished.
                    incomingFiles.remove(update.payloadId)
                    val pending = pendingPayloadEndpoints[update.payloadId]
                    if (pending != null) {
                        synchronized(pending) {
                            outgoingEndpointProgress[update.payloadId]?.set(endpointId, -1)
                            pending.remove(endpointId)
                            if (pending.isEmpty()) {
                                pendingPayloadEndpoints.remove(update.payloadId)
                                finalizeOutgoingPayload(update.payloadId, "Transfer canceled.")
                            }
                        }
                    } else status("Transfer canceled.")
                }
            }
        }
    }

    private fun finalizeOutgoingPayload(payloadId: Long, fallbackMessage: String) {
        val sentName = outgoingPayloadNames.remove(payloadId) ?: "File"
        val hadSuccess = outgoingPayloadSuccesses.remove(payloadId)
        outgoingEndpointProgress.remove(payloadId)
        openFiles.remove(payloadId)?.let { runCatching { it.close() } }
        if (currentOutgoingPayloadId == payloadId) {
            currentOutgoingPayloadId = null
            activeOutgoingUriKey?.let { queuedUriKeys.remove(it) }
            activeOutgoingUriKey = null
            sending = false
            if (hadSuccess) {
                outgoingSucceeded++
                status("Sent: $sentName")
            } else {
                outgoingFailed++
                status("$fallbackMessage Continuing with the queue…")
            }
            sendNextFile()
        }
    }

    private fun saveReceivedFile(sourceUri: Uri, suggestedName: String) {
        Thread {
            var destination: Uri? = null
            try {
                val safeName = suggestedName.substringAfterLast('/').ifBlank { "NEXORA-${System.currentTimeMillis()}" }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                        put(MediaStore.Downloads.MIME_TYPE, if (safeName.endsWith(".apk", true)) "application/vnd.android.package-archive" else (contentResolver.getType(sourceUri) ?: "application/octet-stream"))
                        put(MediaStore.Downloads.IS_PENDING, 1)
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download/NEXORA/")
                    }
                    destination = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IOException("Could not create the Downloads file")
                    contentResolver.openInputStream(sourceUri).use { input ->
                        if (input == null) throw IOException("Could not read received file")
                        contentResolver.openOutputStream(destination!!).use { output ->
                            if (output == null) throw IOException("Could not write to Downloads")
                            input.copyTo(output, 1024 * 1024)
                        }
                    }
                    val ready = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                    contentResolver.update(destination!!, ready, null, null)
                } else {
                    val saved = File(getExternalFilesDir(null) ?: filesDir, safeName)
                    contentResolver.openInputStream(sourceUri).use { input ->
                        if (input == null) throw IOException("Could not read received file")
                        saved.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
                    }
                }
                contentResolver.delete(sourceUri, null, null)
                status("Received and saved: $safeName")
            } catch (e: Exception) {
                destination?.let { runCatching { contentResolver.delete(it, null, null) } }
                status("Received, but saving failed: ${e.localizedMessage}")
            }
        }.start()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
    }

    private fun formatTime(seconds: Long): String {
        if (seconds <= 0) return "estimating"
        val minutes = seconds / 60
        val remainder = seconds % 60
        return if (minutes > 0) "${minutes}m ${remainder}s" else "${remainder}s"
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment
    }

    private fun status(message: String) { lastStatusMessage = message; listener?.onStatus(message) }

    private fun acquireTransferWakeLock() {
        val lock = transferWakeLock ?: (getSystemService(POWER_SERVICE) as android.os.PowerManager)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "$packageName:NEXORA-transfer").also {
                it.setReferenceCounted(false)
                transferWakeLock = it
            }
        if (!lock.isHeld) lock.acquire()
    }

    private fun releaseTransferWakeLock() {
        transferWakeLock?.let { if (it.isHeld) it.release() }
    }

    @Synchronized
    private fun releaseWakeLockIfIdle() {
        val hasConnections = synchronized(connectedEndpoints) { connectedEndpoints.isNotEmpty() }
        if (!advertising && !discovering && !sending && outgoingQueue.isEmpty() && !hasConnections) {
            releaseTransferWakeLock()
        }
    }

    private fun promoteToForeground() {
        acquireTransferWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "NEXORA transfers", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("NEXORA is active")
            .setContentText("Nearby sharing is running. Transfers can continue in the background.")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSharing()
            return START_NOT_STICKY
        }
        promoteToForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        releaseTransferWakeLock()
        openFiles.values.forEach { runCatching { it.close() } }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.nexora.share.STOP_TRANSFER_SERVICE"
        private const val CHANNEL_ID = "nexora_transfer_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
