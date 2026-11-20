package com.nexora.share

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class TransferKeepAliveService : Service() {
    interface Listener {
        fun onStatus(message: String)
        fun onPeerFound(endpointId: String, name: String)
        fun onPeerLost(endpointId: String)
        fun onConnectionRequest(endpointId: String, name: String, digits: String)
        fun onConnected(endpointId: String, name: String, isReceiver: Boolean)
        fun onDisconnected(endpointId: String)
        fun onTransferProgress(message: String)
        fun onQrReady(payload: String)
        fun onIncomingQueueChanged(items: List<IncomingQueueItem>)
    }

    inner class LocalBinder : Binder() {
        fun service(): TransferKeepAliveService = this@TransferKeepAliveService
    }

    private val binder = LocalBinder()
    private var listener: Listener? = null
    private var lastStatusMessage = "NEXORA is ready."
    @Volatile private var receiving = false
    private val connectedPeers = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var transferWakeLock: android.os.PowerManager.WakeLock? = null

    private val transportDelegate = lazy {
        WifiDirectTransport(this, object : WifiDirectTransport.Callbacks {
            override fun onStatus(message: String) {
                if (message.startsWith("Wi-Fi is off") ||
                    message.startsWith("Could not create Wi-Fi Direct group") ||
                    message.startsWith("Android is busy") ||
                    message.startsWith("Android reported a Wi-Fi Direct error") ||
                    message.startsWith("This phone does not support Wi-Fi Direct") ||
                    message.startsWith("Wi-Fi Direct group details were unavailable") ||
                    message.startsWith("Android did not provide Wi-Fi Direct credentials") ||
                    message.startsWith("Wi-Fi Direct receiver could not start")) receiving = false
                status(message)
            }
            override fun onQrReady(payload: String) { listener?.onQrReady(payload) }
            override fun onConnected(endpointId: String, peer: String, localIsReceiver: Boolean) {
                connectedPeers[endpointId] = peer
                listener?.onConnected(endpointId, peer, localIsReceiver)
                status("Connected to " + peer + " over Wi-Fi Direct.")
            }
            override fun onDisconnected(endpointId: String) {
                connectedPeers.remove(endpointId)
                listener?.onDisconnected(endpointId)
            }
            override fun onProgress(message: String) {
                lastStatusMessage = message
                listener?.onTransferProgress(message)
            }
            override fun onReceived(name: String) { status("Received and saved: " + name) }
            override fun onIncomingQueueChanged(items: List<IncomingQueueItem>) {
                listener?.onIncomingQueueChanged(items)
            }
        })
    }
    private val transport by transportDelegate

    fun setListener(value: Listener?) {
        listener = value
        value?.onStatus(lastStatusMessage)
    }

    val isAdvertising: Boolean get() = receiving
    val isConnected: Boolean get() = connectedPeers.isNotEmpty()
    fun connectedEndpointCount(): Int = connectedPeers.size
    fun connectedEndpointIds(): List<String> = connectedPeers.keys.toList()
    fun connectedEndpointName(endpointId: String): String? = connectedPeers[endpointId]

    fun setHighSpeedMode(enabled: Boolean) {
        status("Wi-Fi Direct is the active connection mode.")
    }

    fun startReceiving() {
        promoteToForeground()
        receiving = true
        transport.startReceiving()
    }

    fun startFinding() {
        status("Scan the receiving phone's NEXORA QR code to connect over Wi-Fi Direct.")
    }

    fun connectWithQr(payload: String) {
        promoteToForeground()
        transport.connectWithQr(payload)
    }

    fun connect(endpointId: String, name: String) {
        status("Use the NEXORA QR scanner to connect to " + name + ".")
    }

    fun accept(endpointId: String) {
        status("Wi-Fi Direct connections are approved through Android.")
    }

    fun reject(endpointId: String) {
        status("Connection was not accepted.")
    }

    fun sendFiles(uris: List<Uri>) {
        if (uris.isEmpty()) { status("Choose at least one file."); return }
        if (!isConnected) { status("Scan the receiver's QR code before sending files."); return }
        transport.sendFiles(uris)
    }

    fun sendFilesToGroup(uris: List<Uri>, excludedPeerIds: Set<String> = emptySet()) {
        if (uris.isEmpty()) { status("Choose at least one item."); return }
        if (!isConnected) { status("Connect at least one phone before broadcasting."); return }
        transport.sendFilesToGroup(uris, excludedPeerIds)
    }

    fun pauseIncoming() { if (transportDelegate.isInitialized()) transport.pauseIncoming() }
    fun resumeIncoming() { if (transportDelegate.isInitialized()) transport.resumeIncoming() }
    fun cancelIncoming() { if (transportDelegate.isInitialized()) transport.cancelIncoming() }
    fun cancelIncomingItem(id: Int) { if (transportDelegate.isInitialized()) transport.cancelIncomingItem(id) }
    fun incomingQueueSnapshot(): List<IncomingQueueItem> =
        if (transportDelegate.isInitialized()) transport.incomingQueueSnapshot() else emptyList()
    fun isIncomingPaused(): Boolean = transportDelegate.isInitialized() && transport.isIncomingPaused()
    fun hasIncomingTransfer(): Boolean = transportDelegate.isInitialized() && transport.hasIncomingTransfer()

    fun stopSharing() {
        if (transportDelegate.isInitialized()) transport.stop()
        receiving = false
        connectedPeers.clear()
        releaseTransferWakeLock()
        stopForegroundCompat()
        stopSelf()
        status("Stopped. You can start again.")
    }

    private fun status(message: String) {
        lastStatusMessage = message
        listener?.onStatus(message)
    }

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

    private fun promoteToForeground() {
        acquireTransferWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "NEXORA transfers", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("NEXORA is active")
            .setContentText("Wi-Fi Direct file sharing is ready.")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else stopForeground(true)
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
        if (transportDelegate.isInitialized()) transport.stop()
        releaseTransferWakeLock()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.nexora.share.STOP_TRANSFER_SERVICE"
        private const val CHANNEL_ID = "nexora_transfer_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ENDPOINT_ID = "WIFI_DIRECT"
    }
}
