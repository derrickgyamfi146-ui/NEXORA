package com.nexora.share

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var peerList: LinearLayout
    private lateinit var sendButton: Button
    private var sharing: TransferKeepAliveService? = null
    private var bound = false
    private val discovered = LinkedHashMap<String, String>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            sharing = (binder as? TransferKeepAliveService.LocalBinder)?.service()
            bound = sharing != null
            sharing?.setListener(serviceListener)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            sharing = null
            bound = false
            sendButton.isEnabled = false
        }
    }

    private val serviceListener = object : TransferKeepAliveService.Listener {
        override fun onStatus(message: String) { runOnUiThread { updateStatus(message) } }
        override fun onPeerFound(endpointId: String, name: String) {
            runOnUiThread {
                if (!discovered.containsKey(endpointId)) {
                    discovered[endpointId] = name
                    peerList.addView(Button(this@MainActivity).apply {
                        text = "Connect to $name"
                        setOnClickListener { sharing?.connect(endpointId, name) }
                    }, buttonParams())
                }
            }
        }
        override fun onPeerLost(endpointId: String) {
            runOnUiThread {
                discovered.remove(endpointId)
                rebuildPeerList()
            }
        }
        override fun onConnectionRequest(endpointId: String, name: String, digits: String) {
            runOnUiThread {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Connect to $name?")
                    .setMessage("Compare this security code on both phones:\n\n$digits")
                    .setPositiveButton("Accept") { _, _ -> sharing?.accept(endpointId) }
                    .setNegativeButton("Reject") { _, _ -> sharing?.reject(endpointId) }
                    .setCancelable(false)
                    .show()
            }
        }
        override fun onConnected(endpointId: String) {
            runOnUiThread { sendButton.isEnabled = true }
        }
        override fun onDisconnected(endpointId: String) {
            runOnUiThread { sendButton.isEnabled = false }
        }
        override fun onTransferProgress(message: String) {
            runOnUiThread { updateStatus(message) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        updateStatus("Ready. Both phones need NEXORA installed.")
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, TransferKeepAliveService::class.java), serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        if (bound) {
            sharing?.setListener(null)
            unbindService(serviceConnection)
            bound = false
        }
        super.onStop()
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
                sharing?.stopSharing()
                discovered.clear()
                peerList.removeAllViews()
                sendButton.isEnabled = false
            }
        }, buttonParams())
        page.addView(TextView(this).apply {
            text = "Tip: Keep both phones nearby. Compare the security code before accepting a connection."
            textSize = 12f
            setTextColor(0xFF64748B.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }, matchWrap())
        setContentView(ScrollView(this).apply { addView(page) })
    }

    private fun startReceiving() {
        discovered.clear()
        peerList.removeAllViews()
        sharing?.startReceiving() ?: updateStatus("Starting NEXORA service… tap Receive again.")
    }

    private fun startFinding() {
        discovered.clear()
        peerList.removeAllViews()
        sharing?.startFinding() ?: updateStatus("Starting NEXORA service… tap Send again.")
    }

    private fun rebuildPeerList() {
        peerList.removeAllViews()
        discovered.forEach { (id, name) ->
            peerList.addView(Button(this).apply {
                text = "Connect to $name"
                setOnClickListener { sharing?.connect(id, name) }
            }, buttonParams())
        }
    }

    private fun withNearbyPermissions(action: () -> Unit) {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.NEARBY_WIFI_DEVICES
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
            } else updateStatus("Nearby permissions are needed to find and connect to phones.")
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
            val uri: Uri = data?.data ?: return
            sharing?.sendFile(uri) ?: updateStatus("NEXORA is reconnecting. Try selecting the file again.")
        }
    }

    private fun updateStatus(message: String) {
        if (::status.isInitialized) status.text = message
    }
    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
    private fun buttonParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = 12 }

    companion object {
        private const val REQUEST_NEARBY = 2001
        private const val REQUEST_FILE = 2002
    }
}
