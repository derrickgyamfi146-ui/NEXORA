package com.nexora.share

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.android.gms.tasks.Task
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class MainActivity : Activity() {
    private val client by lazy { Nearby.getConnectionsClient(this) }
    private lateinit var status: TextView
    private lateinit var peerList: LinearLayout
    private lateinit var sendButton: Button
    private var connectedEndpoint: String? = null
    private val discovered = LinkedHashMap<String, String>()
    private val openFiles = ConcurrentHashMap<Long, android.os.ParcelFileDescriptor>()
    private val incomingFiles = ConcurrentHashMap<Long, Pair<Uri, String>>()
    private val serviceId = "com.nexora.share"
    private val strategy = Strategy.P2P_POINT_TO_POINT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        updateStatus("Ready. Both phones need NEXORA installed.")
    }

    private fun buildScreen() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            setBackgroundColor(0xFFF5F7FB.toInt())
        }

        page.addView(TextView(this).apply {
            text = "NEXORA"
            textSize = 30f
            setTextColor(0xFF172554.toInt())
            gravity = Gravity.CENTER
        }, matchWrap())

        page.addView(TextView(this).apply {
            text = "SHARE WITHOUT LIMITS"
            textSize = 12f
            letterSpacing = 0.16f
            setTextColor(0xFF64748B.toInt())
            gravity = Gravity.CENTER
        }, matchWrap())

        status = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF0F172A.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 20)
        }
        page.addView(status, matchWrap())

        page.addView(Button(this).apply {
            text = "Receive files · Make my phone visible"
            setOnClickListener { withNearbyPermissions { startReceiving() } }
        }, buttonParams())

        page.addView(Button(this).apply {
            text = "Send files · Find nearby phones"
            setOnClickListener { withNearbyPermissions { startFinding() } }
        }, buttonParams())

        peerList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 8)
        }
        page.addView(peerList, matchWrap())

        sendButton = Button(this).apply {
            text = "Choose a file to send"
            isEnabled = false
            setOnClickListener { openFilePicker() }
        }
        page.addView(sendButton, buttonParams())

        page.addView(Button(this).apply {
            text = "Stop searching / disconnect"
            setOnClickListener {
                stopTransferKeepAlive()
                client.stopAdvertising()
                client.stopDiscovery()
                client.stopAllEndpoints()
                connectedEndpoint = null
                sendButton.isEnabled = false
                peerList.removeAllViews()
                updateStatus("Stopped. You can start again.")
            }
        }, buttonParams())

        page.addView(TextView(this).apply {
            text = "Tip: Keep both phones nearby. Compare the security code before accepting a connection."
            textSize = 12f
            setTextColor(0xFF64748B.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }, matchWrap())

        val scroll = ScrollView(this).apply { addView(page) }
        setContentView(scroll)
    }

    private fun withNearbyPermissions(action: () -> Unit) {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            needed += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (Build.VERSION.SDK_INT >= 31) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_CONNECT
            needed += Manifest.permission.BLUETOOTH_ADVERTISE
        } else if (Build.VERSION.SDK_INT >= 29) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        } else {
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }

        val missing = needed.distinct().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) action()
        else requestPermissions(missing.toTypedArray(), REQUEST_NEARBY)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NEARBY) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Permissions granted. Tap Send or Receive again.", Toast.LENGTH_LONG).show()
            } else {
                updateStatus("Nearby permissions are needed to find and connect to phones.")
            }
        }
    }

    private fun startReceiving() {
        startTransferKeepAlive()
        discovered.clear()
        peerList.removeAllViews()
        updateStatus("Making this phone visible to nearby NEXORA devices…")
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        client.startAdvertising(deviceName(), serviceId, connectionCallback, options)
            .addOnSuccessListener { updateStatus("Ready to receive. Ask the other phone to tap Send.") }
            .addOnFailureListener { updateStatus("Could not become visible: ${it.localizedMessage}") }
    }

    private fun startFinding() {
        startTransferKeepAlive()
        discovered.clear()
        peerList.removeAllViews()
        updateStatus("Searching for nearby NEXORA phones…")
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        client.startDiscovery(serviceId, discoveryCallback, options)
            .addOnSuccessListener { updateStatus("Searching… Select the phone when it appears.") }
            .addOnFailureListener { updateStatus("Could not search: ${it.localizedMessage}") }
    }

    private fun deviceName(): String = Build.MODEL?.take(24) ?: "Android device"

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            runOnUiThread {
                if (!discovered.containsKey(endpointId)) {
                    discovered[endpointId] = info.endpointName
                    val peerButton = Button(this@MainActivity).apply {
                        text = "Connect to ${info.endpointName}"
                        setOnClickListener { requestConnection(endpointId, info.endpointName) }
                    }
                    peerList.addView(peerButton, buttonParams())
                    updateStatus("Found ${info.endpointName}. Tap its button to connect.")
                }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            runOnUiThread {
                discovered.remove(endpointId)
                peerList.removeAllViews()
                discovered.forEach { (id, name) ->
                    peerList.addView(Button(this@MainActivity).apply {
                        text = "Connect to $name"
                        setOnClickListener { requestConnection(id, name) }
                    }, buttonParams())
                }
            }
        }
    }

    private fun requestConnection(endpointId: String, name: String) {
        updateStatus("Requesting connection to $name…")
        client.requestConnection(deviceName(), endpointId, connectionCallback)
            .addOnFailureListener { updateStatus("Connection request failed: ${it.localizedMessage}") }
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            runOnUiThread {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Connect to ${info.endpointName}?")
                    .setMessage("Compare this security code on both phones:\n\n${info.authenticationDigits}")
                    .setPositiveButton("Accept") { _, _ ->
                        client.acceptConnection(endpointId, payloadCallback)
                            .addOnFailureListener { updateStatus("Could not accept connection.") }
                    }
                    .setNegativeButton("Reject") { _, _ ->
                        client.rejectConnection(endpointId)
                        updateStatus("Connection rejected.")
                    }
                    .setCancelable(false)
                    .show()
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            runOnUiThread {
                if (result.status.isSuccess) {
                    connectedEndpoint = endpointId
                    sendButton.isEnabled = true
                    client.stopDiscovery()
                    client.stopAdvertising()
                    updateStatus("Connected securely. Choose a file to send.")
                } else {
                    updateStatus("Connection was not completed. Try again.")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            runOnUiThread {
                if (connectedEndpoint == endpointId) {
                    connectedEndpoint = null
                    sendButton.isEnabled = false
                }
                updateStatus("Device disconnected.")
            }
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_FILE)
    }

    @Deprecated("Deprecated Android callback retained for broad device compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FILE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            sendSelectedFile(uri)
        }
    }

    private fun sendSelectedFile(uri: Uri) {
        val endpoint = connectedEndpoint ?: run {
            updateStatus("Connect to a phone first.")
            return
        }
        try {
            val descriptor = contentResolver.openFileDescriptor(uri, "r")
            if (descriptor == null) {
                updateStatus("Could not open this file.")
                return
            }
            val name = queryDisplayName(uri) ?: "NEXORA-file"
            val payload = Payload.fromFile(descriptor)
            payload.setFileName(name)
            openFiles[payload.id] = descriptor
            updateStatus("Sending $name…")
            client.sendPayload(endpoint, payload)
                .addOnFailureListener {
                    openFiles.remove(payload.id)?.close()
                    updateStatus("Send failed: ${it.localizedMessage}")
                }
        } catch (e: Exception) {
            updateStatus("Could not send file: ${e.localizedMessage}")
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        return uri.lastPathSegment
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.FILE) {
                val filePayload = payload.asFile() ?: return
                val fileUri = filePayload.asUri() ?: return
                val receivedName = queryDisplayName(fileUri) ?: "NEXORA-received-file"
                incomingFiles[payload.id] = Pair(fileUri, receivedName)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            runOnUiThread {
                when (update.status) {
                    PayloadTransferUpdate.Status.IN_PROGRESS -> {
                        val total = update.totalBytes
                        val done = update.bytesTransferred
                        val percent = if (total > 0) (done * 100 / total).toInt() else 0
                        updateStatus("Transferring… $percent%")
                    }
                    PayloadTransferUpdate.Status.SUCCESS -> {
                        openFiles.remove(update.payloadId)?.close()
                        val incoming = incomingFiles.remove(update.payloadId)
                        if (incoming != null) {
                            saveReceivedFile(incoming.first, incoming.second)
                        } else {
                            updateStatus("File sent successfully.")
                        }
                    }
                    PayloadTransferUpdate.Status.FAILURE -> {
                        openFiles.remove(update.payloadId)?.close()
                        incomingFiles.remove(update.payloadId)
                        updateStatus("Transfer failed. Check both phones and try again.")
                    }
                    PayloadTransferUpdate.Status.CANCELED -> {
                        openFiles.remove(update.payloadId)?.close()
                        incomingFiles.remove(update.payloadId)
                        updateStatus("Transfer canceled.")
                    }
                }
            }
        }
    }

    private fun saveReceivedFile(sourceUri: Uri, suggestedName: String) {
        Thread {
            try {
                val safeName = suggestedName.substringAfterLast('/').ifBlank { "NEXORA-" + System.currentTimeMillis() }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                        put(MediaStore.Downloads.MIME_TYPE, contentResolver.getType(sourceUri) ?: "application/octet-stream")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val destination = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IOException("Could not create the Downloads file")
                    contentResolver.openInputStream(sourceUri).use { input ->
                        if (input == null) throw IOException("Could not read received file")
                        contentResolver.openOutputStream(destination).use { output ->
                            if (output == null) throw IOException("Could not write to Downloads")
                            input.copyTo(output, 1024 * 1024)
                        }
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    contentResolver.update(destination, values, null, null)
                } else {
                    val saved = java.io.File(filesDir, safeName)
                    contentResolver.openInputStream(sourceUri).use { input ->
                        if (input == null) throw IOException("Could not read received file")
                        saved.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
                    }
                }
                contentResolver.delete(sourceUri, null, null)
                runOnUiThread {
                    updateStatus("Received and saved: " + safeName)
                    Toast.makeText(this, "Saved to Downloads: " + safeName, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread { updateStatus("Received, but saving failed: " + e.localizedMessage) }
            }
        }.start()
    }

    private fun startTransferKeepAlive() {
        val intent = Intent(this, TransferKeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun stopTransferKeepAlive() {
        startService(Intent(this, TransferKeepAliveService::class.java).apply {
            action = TransferKeepAliveService.ACTION_STOP
        })
    }

    private fun updateStatus(message: String) {
        if (::status.isInitialized) status.text = message
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun buttonParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = 12 }

    companion object {
        private const val REQUEST_NEARBY = 2001
        private const val REQUEST_FILE = 2002
    }
}
