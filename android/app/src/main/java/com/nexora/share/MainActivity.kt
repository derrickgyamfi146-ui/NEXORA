package com.nexora.share

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.view.Gravity
import android.widget.*
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var peerList: LinearLayout
    private lateinit var receivedList: LinearLayout
    private lateinit var sendButton: Button
    private val selectedUris = ArrayList<Uri>()
    private val discovered = LinkedHashMap<String, String>()
    private var sharing: TransferKeepAliveService? = null
    private var bound = false
    private var activeTab = "Home"
    private var darkMode = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            sharing = (binder as? TransferKeepAliveService.LocalBinder)?.service()
            bound = sharing != null
            sharing?.setListener(serviceListener)
            if (::sendButton.isInitialized) sendButton.isEnabled = sharing != null && selectedUris.isNotEmpty()
        }
        override fun onServiceDisconnected(name: ComponentName?) { sharing = null; bound = false }
    }

    private val serviceListener = object : TransferKeepAliveService.Listener {
        override fun onStatus(message: String) {
            runOnUiThread { updateStatus(message); if (message.startsWith("Received and saved:")) refreshReceivedFiles() }
        }
        override fun onPeerFound(endpointId: String, name: String) {
            runOnUiThread { discovered[endpointId] = name; if (::peerList.isInitialized) rebuildPeerList() }
        }
        override fun onPeerLost(endpointId: String) {
            runOnUiThread { discovered.remove(endpointId); if (::peerList.isInitialized) rebuildPeerList() }
        }
        override fun onConnectionRequest(endpointId: String, name: String, digits: String) {
            runOnUiThread {
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("Connect to $name?")
                    .setMessage("Compare this security code on both phones:\n\n$digits")
                    .setPositiveButton("Accept") { _, _ -> sharing?.accept(endpointId) }
                    .setNegativeButton("Reject") { _, _ -> sharing?.reject(endpointId) }
                    .setCancelable(false).show()
            }
        }
        override fun onConnected(endpointId: String) { runOnUiThread { if (::sendButton.isInitialized) sendButton.isEnabled = selectedUris.isNotEmpty() } }
        override fun onDisconnected(endpointId: String) { runOnUiThread { if (::sendButton.isInitialized) sendButton.isEnabled = false } }
        override fun onTransferProgress(message: String) { runOnUiThread { updateStatus(message) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render(); refreshReceivedFiles() }
    override fun onStart() { super.onStart(); bindService(Intent(this, TransferKeepAliveService::class.java), serviceConnection, BIND_AUTO_CREATE) }
    override fun onStop() {
        if (bound) { sharing?.setListener(null); unbindService(serviceConnection); bound = false }
        super.onStop()
    }

    private fun render() {
        val page = if (darkMode) 0xFF101827.toInt() else 0xFFF3F6FC.toInt()
        val card = if (darkMode) 0xFF1C2940.toInt() else -1
        val ink = if (darkMode) -1 else 0xFF14213D.toInt()
        val muted = if (darkMode) 0xFFB3C0D5.toInt() else 0xFF64748B.toInt()
        val blue = 0xFF4263EB.toInt()
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(page) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(14)) }
        header.addView(TextView(this).apply {
            text = "NEXORA"; textSize = 23f; letterSpacing = .06f
            typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(ink)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply { text = "SHARE WITHOUT LIMITS"; textSize = 9f; letterSpacing = .08f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(muted) })
        root.addView(header)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(14)) }
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(5), dp(7), dp(5), dp(7)); setBackgroundColor(card); elevation = 8f }
        listOf("Home", "Media", "Files", "Received", "Settings").forEach { tab ->
            nav.addView(TextView(this).apply {
                text = tab; textSize = 10f; gravity = Gravity.CENTER
                typeface = if (activeTab == tab) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                setTextColor(if (activeTab == tab) blue else muted); setPadding(dp(2), dp(10), dp(2), dp(10))
                setOnClickListener { activeTab = tab; render() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(nav); setContentView(root)
        when (activeTab) {
            "Home" -> showHome(card, ink, muted, blue)
            "Media" -> showMedia(card, ink, muted, blue)
            "Files" -> showFiles(card, ink, muted, blue)
            "Received" -> showReceived(card, ink, muted)
            "Settings" -> showSettings(card, ink, muted, blue)
        }
    }

    private fun showHome(card: Int, ink: Int, muted: Int, blue: Int) {
        content.addView(TextView(this).apply { text = "Share anything.\nAt high speed."; textSize = 28f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(ink); setPadding(dp(2), dp(8), dp(2), dp(14)) })
        val statusCard = panel(card)
        statusCard.addView(label("TRANSFER STATUS", muted))
        status = TextView(this).apply { text = "Ready. Choose Send or Receive to begin."; textSize = 14f; setTextColor(ink); setPadding(0, dp(8), 0, dp(4)) }
        statusCard.addView(status); content.addView(statusCard, bottom(12))
        content.addView(label("QUICK ACTIONS", muted), bottom(8))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(actionTile("↑", "Send files", blue) { activeTab = "Media"; render() }, LinearLayout.LayoutParams(0, dp(112), 1f).apply { rightMargin = dp(6) })
        actions.addView(actionTile("↓", "Receive files", 0xFF16A085.toInt()) { withNearbyPermissions { sharing?.startReceiving() ?: updateStatus("Starting service. Try again.") } }, LinearLayout.LayoutParams(0, dp(112), 1f).apply { leftMargin = dp(6) })
        content.addView(actions, bottom(12))
        val devices = panel(card)
        devices.addView(label("NEARBY DEVICES", muted))
        devices.addView(TextView(this).apply { text = "Tap Find phones, then choose a device to connect."; textSize = 12f; setTextColor(muted); setPadding(0, dp(6), 0, dp(6)) })
        peerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; devices.addView(peerList)
        devices.addView(button("FIND NEARBY PHONES", blue) {
            withNearbyPermissions { discovered.clear(); rebuildPeerList(); sharing?.startFinding() ?: updateStatus("Starting service. Tap Find again.") }
        })
        content.addView(devices, bottom(12))
        content.addView(button("STOP / DISCONNECT", 0xFF718096.toInt()) { sharing?.stopSharing(); discovered.clear(); rebuildPeerList(); updateStatus("Sharing stopped.") })
    }

    private fun showMedia(card: Int, ink: Int, muted: Int, blue: Int) {
        content.addView(TextView(this).apply { text = "Choose what to share"; textSize = 24f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(ink); setPadding(dp(2), dp(8), dp(2), dp(4)) })
        content.addView(TextView(this).apply { text = "Select a category. You can choose one or many items."; textSize = 12f; setTextColor(muted); setPadding(dp(2), 0, dp(2), dp(14)) })
        val categories = listOf(Triple("Photos", "Images from your phone", "image/*"), Triple("Videos", "Video clips", "video/*"), Triple("Music", "Audio files", "audio/*"), Triple("Documents", "PDF, Word, ZIP and more", "application/*"), Triple("All files", "Browse any file type", "*/*"))
        categories.forEach { item ->
            val tile = panel(card); tile.orientation = LinearLayout.HORIZONTAL; tile.gravity = Gravity.CENTER_VERTICAL
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(TextView(this).apply { text = item.first; textSize = 16f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(ink) })
            info.addView(TextView(this).apply { text = item.second; textSize = 11f; setTextColor(muted); setPadding(0, dp(3), 0, 0) })
            tile.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            tile.addView(TextView(this).apply { text = "›"; textSize = 28f; setTextColor(blue) })
            tile.setOnClickListener { openPicker(item.third) }
            content.addView(tile, bottom(9))
        }
        content.addView(TextView(this).apply { text = "Connect to a nearby phone from Home. Your selection stays ready until you send or choose another batch."; textSize = 11f; setTextColor(muted); setPadding(dp(4), dp(8), dp(4), dp(8)) })
        sendButton = button("CHOOSE FILES TO SEND", blue) { openPicker("*/*") }
        content.addView(sendButton)
    }

    private fun showFiles(card: Int, ink: Int, muted: Int, blue: Int) {
        content.addView(TextView(this).apply { text = "File tools"; textSize = 24f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(ink); setPadding(dp(2), dp(8), dp(2), dp(12)) })
        listOf("Browse all files" to "*/*", "Browse documents" to "application/*", "Browse images" to "image/*", "Browse videos" to "video/*", "Browse audio" to "audio/*").forEach { (title, mime) ->
            content.addView(button(title, blue) { openPicker(mime) }, bottom(9))
        }
        val note = panel(card); note.addView(label("ABOUT FILE ACCESS", muted))
        note.addView(TextView(this).apply { text = "NEXORA uses Android's secure file picker. Your private folders remain protected, and you choose exactly what to share."; textSize = 12f; setTextColor(ink); setPadding(0, dp(8), 0, 0) })
        content.addView(note, bottom(12))
    }

    private fun showReceived(card: Int, ink: Int, muted: Int) {
        content.addView(TextView(this).apply { text = "Received files"; textSize = 24f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(ink); setPadding(dp(2), dp(8), dp(2), dp(12)) })
        val box = panel(card); box.addView(label("SAVED ON THIS PHONE", muted))
        receivedList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; box.addView(receivedList)
        content.addView(box); refreshReceivedFiles()
    }

    private fun showSettings(card: Int, ink: Int, muted: Int, blue: Int) {
        content.addView(TextView(this).apply { text = "Preferences"; textSize = 24f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(ink); setPadding(dp(2), dp(8), dp(2), dp(12)) })
        val appearance = panel(card); appearance.addView(label("APPEARANCE", muted))
        appearance.addView(button(if (darkMode) "Switch to light theme" else "Switch to dark theme", blue) { darkMode = !darkMode; render() })
        content.addView(appearance, bottom(10))
        val prefs = panel(card); prefs.addView(label("TRANSFER & STORAGE", muted))
        listOf("Save location\nDownload/NEXORA", "Connection\nNearby Connections", "Transfer mode\nDirect device-to-device", "Mobile data\nNot required for local transfers", "Privacy\nFiles are selected by you").forEach { value ->
            prefs.addView(TextView(this).apply { text = value; textSize = 12f; setTextColor(ink); setPadding(0, dp(10), 0, dp(4)) })
        }
        content.addView(prefs, bottom(10))
        content.addView(TextView(this).apply { text = "NEXORA • Version 0.1.0\nAdvanced features depend on Android permissions and device support."; textSize = 11f; setTextColor(muted); gravity = Gravity.CENTER; setPadding(dp(8), dp(12), dp(8), dp(12)) })
    }

    private fun actionTile(symbol: String, title: String, color: Int, action: () -> Unit): LinearLayout {
        val tile = panel(if (darkMode) 0xFF1C2940.toInt() else -1); tile.gravity = Gravity.CENTER
        tile.addView(TextView(this).apply { text = symbol; textSize = 26f; gravity = Gravity.CENTER; setTextColor(color) })
        tile.addView(TextView(this).apply { text = title; textSize = 13f; gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(if (darkMode) -1 else 0xFF14213D.toInt()) })
        tile.setOnClickListener { action() }; return tile
    }

    private fun panel(color: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(color, 18); elevation = 2f
    }
    private fun label(value: String, color: Int) = TextView(this).apply { text = value; textSize = 10f; letterSpacing = .08f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(color) }
    private fun button(value: String, color: Int, action: () -> Unit) = Button(this).apply {
        text = value; textSize = 12f; isAllCaps = false; typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(-1); background = rounded(color, 13); setPadding(dp(10), dp(8), dp(10), dp(8)); setOnClickListener { action() }
    }
    private fun rounded(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun bottom(margin: Int) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(margin) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun updateStatus(message: String) { if (::status.isInitialized) status.text = message }
    private fun rebuildPeerList() {
        if (!::peerList.isInitialized) return
        peerList.removeAllViews()
        if (discovered.isEmpty()) peerList.addView(TextView(this).apply { text = "Searching for nearby NEXORA phones…"; textSize = 11f; setTextColor(if (darkMode) 0xFFB3C0D5.toInt() else 0xFF64748B.toInt()); setPadding(0, dp(8), 0, dp(8)) })
        discovered.forEach { (id, name) -> peerList.addView(button("Connect to $name", 0xFF16A085.toInt()) { sharing?.connect(id, name) }, bottom(6)) }
    }

    private fun withNearbyPermissions(action: () -> Unit) {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.NEARBY_WIFI_DEVICES
        if (Build.VERSION.SDK_INT >= 31) { needed += Manifest.permission.BLUETOOTH_SCAN; needed += Manifest.permission.BLUETOOTH_CONNECT; needed += Manifest.permission.BLUETOOTH_ADVERTISE }
        else if (Build.VERSION.SDK_INT >= 29) needed += Manifest.permission.ACCESS_FINE_LOCATION
        else needed += Manifest.permission.ACCESS_COARSE_LOCATION
        val missing = needed.distinct().filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else requestPermissions(missing.toTypedArray(), REQUEST_NEARBY)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NEARBY) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) Toast.makeText(this, "Permissions granted. Tap Send or Receive again.", Toast.LENGTH_LONG).show()
            else updateStatus("Nearby permissions are needed to connect to phones.")
        }
    }

    private fun openPicker(mime: String) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = mime; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { startActivityForResult(intent, REQUEST_FILE) } catch (_: Exception) { updateStatus("No file picker is available on this phone.") }
    }

    @Deprecated("Deprecated Android callback retained for broad device compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FILE && resultCode == RESULT_OK && data != null) {
            val chosen = LinkedHashSet<Uri>()
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) chosen.add(clip.getItemAt(i).uri) }
            data.data?.let { chosen.add(it) }
            selectedUris.clear(); selectedUris.addAll(chosen)
            if (selectedUris.isEmpty()) updateStatus("No files selected.")
            else {
                activeTab = "Home"; render()
                updateStatus("${selectedUris.size} file(s) selected. Connect to a phone, then send.")
                sendButton.text = "SEND ${selectedUris.size} SELECTED FILE(S)"
                sendButton.isEnabled = sharing != null
                sendButton.setOnClickListener {
                    sharing?.sendFiles(ArrayList(selectedUris)); selectedUris.clear()
                    sendButton.isEnabled = false; sendButton.text = "CHOOSE FILES TO SEND"
                }
            }
        }
    }

    private fun refreshReceivedFiles() {
        if (!::receivedList.isInitialized || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        receivedList.removeAllViews()
        try {
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE)
            contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, MediaStore.MediaColumns.RELATIVE_PATH + " = ?", arrayOf("Download/NEXORA/"), MediaStore.MediaColumns.DATE_ADDED + " DESC")?.use { cursor ->
                if (cursor.count == 0) addReceivedEmpty()
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0); val name = cursor.getString(1) ?: "Received file"; val mime = cursor.getString(2) ?: "*/*"
                    val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                    receivedList.addView(button("Open  ·  $name", 0xFF4263EB.toInt()) {
                        try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                        catch (_: Exception) { Toast.makeText(this, "No app found to open this file.", Toast.LENGTH_SHORT).show() }
                    }, bottom(6))
                }
            } ?: addReceivedEmpty()
        } catch (_: Exception) { addReceivedEmpty() }
    }

    private fun addReceivedEmpty() {
        if (!::receivedList.isInitialized) return
        receivedList.addView(TextView(this).apply { text = "Received files will appear here when saved."; textSize = 12f; setTextColor(if (darkMode) 0xFFB3C0D5.toInt() else 0xFF64748B.toInt()); setPadding(0, dp(8), 0, dp(8)) })
    }

    companion object { private const val REQUEST_NEARBY = 2001; private const val REQUEST_FILE = 2002 }
}
