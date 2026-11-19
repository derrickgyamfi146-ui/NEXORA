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
    }

    inner class LocalBinder : Binder() {
        fun service(): TransferKeepAliveService = this@TransferKeepAliveService
    }

    private val binder = LocalBinder()
    private val client by lazy { Nearby.getConnectionsClient(this) }
    private var listener: Listener? = null
    private var lastStatusMessage = "NEXORA is ready."
    private var connectedEndpoint: String? = null
    private var advertising = false
    private var discovering = false
    private val openFiles = ConcurrentHashMap<Long, android.os.ParcelFileDescriptor>()
    private val outgoingQueue = ArrayDeque<Uri>()
    private var sending = false
    private var currentOutgoingPayloadId: Long? = null
    private var transferStartedAt = 0L
    private var lastProgressAt = 0L
    private val incomingFiles = ConcurrentHashMap<Long, Pair<Uri, String>>()
    private val serviceId = "com.nexora.share"
    private val strategy = Strategy.P2P_POINT_TO_POINT

    fun setListener(value: Listener?) { listener = value; value?.onStatus(lastStatusMessage) }

    @Synchronized
    fun startReceiving() {
        if (advertising) {
            status("Already visible. Keep this screen open while the other phone searches.")
            return
        }
        if (discovering) {
            client.stopDiscovery()
            discovering = false
        }
        promoteToForeground()
        advertising = true
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        client.startAdvertising(deviceName(), serviceId, connectionCallback, options)
            .addOnSuccessListener { status("Your phone is visible. On the other phone, tap Send.") }
            .addOnFailureListener {
                advertising = false
                status("Could not become visible: ${it.localizedMessage}")
            }
    }

    @Synchronized
    fun startFinding() {
        if (discovering) {
            status("Already searching. Wait for the other phone to appear below.")
            return
        }
        if (advertising) {
            client.stopAdvertising()
            advertising = false
        }
        promoteToForeground()
        discovering = true
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        client.startDiscovery(serviceId, discoveryCallback, options)
            .addOnSuccessListener { status("Searching for nearby NEXORA phones…") }
            .addOnFailureListener {
                discovering = false
                status("Could not search: ${it.localizedMessage}")
            }
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
        if (connectedEndpoint == null) { status("Connect to a phone first."); return }
        outgoingQueue.addAll(uris)
        status("${outgoingQueue.size} file(s) queued. Starting transfer…")
        sendNextFile()
    }

    @Synchronized
    private fun sendNextFile() {
        if (sending) return
        val endpoint = connectedEndpoint
        if (endpoint == null) {
            outgoingQueue.clear()
            status("Connection lost. Reconnect before sending again.")
            return
        }
        val uri = outgoingQueue.pollFirst()
        if (uri == null) {
            sending = false
            currentOutgoingPayloadId = null
            status("All selected files have been sent.")
            return
        }
        try {
            val descriptor = contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Could not open this file")
            val name = queryDisplayName(uri) ?: "NEXORA-file"
            val payload = Payload.fromFile(descriptor)
            payload.setFileName(name)
            openFiles[payload.id] = descriptor
            sending = true
            currentOutgoingPayloadId = payload.id
            transferStartedAt = System.currentTimeMillis()
            lastProgressAt = transferStartedAt
            status("Sending $name…")
            client.sendPayload(endpoint, payload).addOnFailureListener {
                openFiles.remove(payload.id)?.close()
                currentOutgoingPayloadId = null
                sending = false
                status("Could not start $name: ${it.localizedMessage}. Trying the next file…")
                sendNextFile()
            }
        } catch (e: Exception) {
            status("Could not send file: ${e.localizedMessage}. Trying the next file…")
            sendNextFile()
        }
    }

    fun stopSharing() {
        client.stopAdvertising()
        client.stopDiscovery()
        advertising = false
        discovering = false
        client.stopAllEndpoints()
        connectedEndpoint = null
        openFiles.values.forEach { runCatching { it.close() } }
        openFiles.clear()
        outgoingQueue.clear()
        sending = false
        currentOutgoingPayloadId = null
        incomingFiles.clear()
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
            listener?.onPeerFound(endpointId, info.endpointName)
            status("Found ${info.endpointName}. Tap its button to connect.")
        }
        override fun onEndpointLost(endpointId: String) {
            listener?.onPeerLost(endpointId)
        }
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            listener?.onConnectionRequest(endpointId, info.endpointName, info.authenticationDigits)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoint = endpointId
                client.stopDiscovery()
                client.stopAdvertising()
                discovering = false
                advertising = false
                listener?.onConnected(endpointId)
                status("Connected securely. Choose a file to send.")
            } else {
                status("Connection was not completed. Try again.")
            }
        }
        override fun onDisconnected(endpointId: String) {
            if (connectedEndpoint == endpointId) connectedEndpoint = null
            listener?.onDisconnected(endpointId)
            status("Device disconnected.")
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
                        listener?.onTransferProgress("Sending… $percent% · ${formatBytes(speed)}/s · ${formatTime(remaining)} left")
                        lastProgressAt = now
                    } else {
                        listener?.onTransferProgress("Receiving… $percent%")
                    }
                }
                PayloadTransferUpdate.Status.SUCCESS -> {
                    val outgoing = openFiles.remove(update.payloadId)
                    outgoing?.close()
                    val incoming = incomingFiles.remove(update.payloadId)
                    if (incoming != null) {
                        saveReceivedFile(incoming.first, incoming.second)
                    } else if (outgoing != null) {
                        currentOutgoingPayloadId = null
                        sending = false
                        status("File sent. ${outgoingQueue.size} file(s) remaining.")
                        sendNextFile()
                    }
                }
                PayloadTransferUpdate.Status.FAILURE -> {
                    openFiles.remove(update.payloadId)?.close()
                    incomingFiles.remove(update.payloadId)
                    if (currentOutgoingPayloadId == update.payloadId) {
                        currentOutgoingPayloadId = null
                        sending = false
                        status("One file failed. Continuing with the queue…")
                        sendNextFile()
                    } else status("Transfer failed. Check both phones and try again.")
                }
                PayloadTransferUpdate.Status.CANCELED -> {
                    openFiles.remove(update.payloadId)?.close()
                    incomingFiles.remove(update.payloadId)
                    if (currentOutgoingPayloadId == update.payloadId) {
                        currentOutgoingPayloadId = null
                        sending = false
                        status("Transfer canceled. Continuing with the queue…")
                        sendNextFile()
                    } else status("Transfer canceled.")
                }
            }
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
                        put(MediaStore.Downloads.MIME_TYPE, contentResolver.getType(sourceUri) ?: "application/octet-stream")
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

    private fun promoteToForeground() {
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
        openFiles.values.forEach { runCatching { it.close() } }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.nexora.share.STOP_TRANSFER_SERVICE"
        private const val CHANNEL_ID = "nexora_transfer_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
