package com.nexora.share

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
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
    private lateinit var receivedList: LinearLayout
    private val selectedUris = ArrayList<Uri>()
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
        override fun onStatus(message: String) { runOnUiThread { updateStatus(message); if (message.startsWith("Received and saved:")) refreshReceivedFiles() } }
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
        refreshReceivedFiles()
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
        val navy = 0xFF101B3D.toInt()
        val blue = 0xFF4263EB.toInt()
        val muted = 0xFF64748B.toInt()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 28, 22, 28)
            setBackgroundColor(0xFFF4F6FC.toInt())
        }

        page.addView(TextView(this).apply {
            text = "N"
            textSize = 25f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(-1)
            background = rounded(blue, 18)
        }, LinearLayout.LayoutParams(58, 58).apply { gravity = Gravity.CENTER_HORIZONTAL })

        page.addView(TextView(this).apply {
            text = "NEXORA"
            textSize = 29f
            letterSpacing = 0.08f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(navy)
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 2)
        }, matchWrap())
        page.addView(TextView(this).apply {
            text = "MOVE FILES. NO CABLES. NO LIMITS."
            textSize = 10f
            letterSpacing = 0.12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 22)
        }, matchWrap())

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 16)
            background = rounded(-1, 18)
            elevation = 2f
        }
        statusCard.addView(TextView(this).apply {
            text = "CONNECTION STATUS"
            textSize = 10f
            letterSpacing = 0.1f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(muted)
        }, matchWrap())
        status = TextView(this).apply {
            text = "Ready to share"
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(navy)
            setPadding(0, 8, 0, 2)
        }
        statusCard.addView(status, matchWrap())
        page.addView(statusCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 18 })

        page.addView(TextView(this).apply {
            text = "WHAT WOULD YOU LIKE TO DO?"
            textSize = 11f
            letterSpacing = 0.08f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(muted)
            setPadding(2, 0, 0, 8)
        }, matchWrap())

        val receiveCard = actionCard(
            "↓", "RECEIVE FILES", "Make this phone visible to the sender",
            "MAKE PHONE VISIBLE", blue
        ) { withNearbyPermissions { startReceiving() } }
        page.addView(receiveCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 })

        val sendCard = actionCard(
            "↑", "SEND FILES", "Find another phone running NEXORA",
            "FIND NEARBY PHONES", navy
        ) { withNearbyPermissions { startFinding() } }
        page.addView(sendCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 18 })

        val devicesCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 12)
            background = rounded(-1, 18)
        }
        devicesCard.addView(TextView(this).apply {
            text = "NEARBY DEVICES"
            textSize = 12f
            letterSpacing = 0.08f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(navy)
        }, matchWrap())
        devicesCard.addView(TextView(this).apply {
            text = "Phones you discover will appear here. Tap a phone to connect."
            textSize = 12f
            setTextColor(muted)
            setPadding(0, 6, 0, 4)
        }, matchWrap())
        peerList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 6, 0, 0)
        }
        devicesCard.addView(peerList, matchWrap())
        page.addView(devicesCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 })

        sendButton = Button(this).apply {
            text = "CHOOSE FILE TO SEND"
            isEnabled = false
            setTextColor(-1)
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = rounded(blue, 14)
            setPadding(12, 12, 12, 12)
            setOnClickListener { openFilePicker() }
        }
        page.addView(sendButton, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 })

        val receivedCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 16, 18, 12); background = rounded(-1, 18) }
        receivedCard.addView(TextView(this).apply { text = "RECEIVED FILES"; textSize = 12f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(navy) }, matchWrap())
        receivedList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 8, 0, 0) }
        receivedCard.addView(receivedList, matchWrap())
        page.addView(receivedCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 })

        val stopButton = Button(this).apply {
            text = "STOP / DISCONNECT"
            textSize = 12f
            setTextColor(navy)
            background = rounded(0xFFE3E9F7.toInt(), 14)
            setOnClickListener {
                sharing?.stopSharing()
                discovered.clear()
                peerList.removeAllViews()
                sendButton.isEnabled = false
            }
        }
        page.addView(stopButton, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 16 })

        page.addView(TextView(this).apply {
            text = "HOW IT WORKS\n1. One phone taps Receive.\n2. The other phone taps Send.\n3. Select the phone, compare the security code, then choose a file."
            textSize = 12f
            setTextColor(muted)
            setLineSpacing(4f, 1f)
            setPadding(4, 4, 4, 8)
        }, matchWrap())

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(page)
        })
    }

    private fun actionCard(
        symbol: String,
        heading: String,
        description: String,
        buttonText: String,
        accent: Int,
        action: () -> Unit
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 16)
            background = rounded(-1, 18)
            elevation = 2f
        }
        card.addView(TextView(this).apply {
            text = symbol
            textSize = 25f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(accent)
        }, matchWrap())
        card.addView(TextView(this).apply {
            text = heading
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFF101B3D.toInt())
            setPadding(0, 4, 0, 2)
        }, matchWrap())
        card.addView(TextView(this).apply {
            text = description
            textSize = 12f
            setTextColor(0xFF64748B.toInt())
            setPadding(0, 0, 0, 12)
        }, matchWrap())
        card.addView(Button(this).apply {
            text = buttonText
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(-1)
            background = rounded(accent, 12)
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, -2))
        return card
    }

    private fun rounded(color: Int, radiusDp: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * resources.displayMetrics.density
        }
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
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_FILE)
    }

    @Deprecated("Deprecated Android callback retained for broad device compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FILE && resultCode == RESULT_OK && data != null) {
            val chosen = LinkedHashSet<Uri>()
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) chosen.add(clip.getItemAt(i).uri) }
            data.data?.let { chosen.add(it) }
            selectedUris.clear()
            selectedUris.addAll(chosen)
            if (selectedUris.isEmpty()) updateStatus("No files selected.")
            else {
                updateStatus(selectedUris.size.toString() + " file(s) selected. Connect, then tap Send.")
                sendButton.text = "SEND " + selectedUris.size + " FILE(S)"
                sendButton.isEnabled = sharing != null
                sendButton.setOnClickListener {
                    sharing?.sendFiles(ArrayList(selectedUris))
                    selectedUris.clear()
                    sendButton.isEnabled = false
                    sendButton.text = "CHOOSE FILES TO SEND"
                }
            }
        }
    }

    private fun refreshReceivedFiles() {
        if (!::receivedList.isInitialized || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        receivedList.removeAllViews()
        try {
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE)
            contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection,
                MediaStore.MediaColumns.RELATIVE_PATH + " = ?", arrayOf("Download/NEXORA/"),
                MediaStore.MediaColumns.DATE_ADDED + " DESC")?.use { cursor ->
                if (cursor.count == 0) addReceivedEmpty()
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1) ?: "Received file"
                    val mime = cursor.getString(2) ?: "*/*"
                    val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                    receivedList.addView(Button(this).apply {
                        text = "Open  ·  " + name
                        isAllCaps = false
                        setOnClickListener {
                            try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                            catch (_: Exception) { Toast.makeText(this@MainActivity, "No app found to open this file.", Toast.LENGTH_SHORT).show() }
                        }
                    }, buttonParams())
                }
            } ?: addReceivedEmpty()
        } catch (_: Exception) { addReceivedEmpty() }
    }

    private fun addReceivedEmpty() {
        receivedList.addView(TextView(this).apply { text = "Received files will appear here when saved."; textSize = 12f; setTextColor(0xFF64748B.toInt()) }, matchWrap())
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
