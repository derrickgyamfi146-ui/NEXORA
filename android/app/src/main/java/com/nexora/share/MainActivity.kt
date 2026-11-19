package com.nexora.share

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
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
    private val connectedPeers = LinkedHashSet<String>()
    private var pendingQrDeviceName: String? = null
    private var pendingNearbyAction: (() -> Unit)? = null
    private var sharing: TransferKeepAliveService? = null
    private var bound = false
    private var activeTab = "Media"
    private var latestStatus = "Ready. Choose Send or Receive to begin."
    private var darkMode = false
    private var mediaCategory: String? = null
    private lateinit var mediaList: LinearLayout
    private val mediaRows = ArrayList<Pair<Uri, String>>()
    private var mediaSearch = ""
    private var mediaSort = "Newest"
    private val mediaErrors = HashMap<String, String>()
    private val thumbnailCache = object : android.util.LruCache<String, android.graphics.Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val thumbnailExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            sharing = (binder as? TransferKeepAliveService.LocalBinder)?.service()
            bound = sharing != null
            sharing?.setListener(serviceListener)
            if (::sendButton.isInitialized) sendButton.isEnabled = true
        }
        override fun onServiceDisconnected(name: ComponentName?) { sharing = null; bound = false }
    }

    private val serviceListener = object : TransferKeepAliveService.Listener {
        override fun onStatus(message: String) {
            runOnUiThread {
                if (message.startsWith("Sent: ")) rememberSentFileName(message.removePrefix("Sent: ").trim())
                updateStatus(message)
                if (message.startsWith("Received and saved:")) refreshReceivedFiles()
            }
        }
        override fun onPeerFound(endpointId: String, name: String) {
            runOnUiThread {
                discovered[endpointId] = name
                if (::peerList.isInitialized) rebuildPeerList()
                val wanted = pendingQrDeviceName
                if (wanted != null && name.equals(wanted, ignoreCase = true)) {
                    pendingQrDeviceName = null
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Connect to $name?")
                        .setMessage("This phone matches the NEXORA QR code. Continue with a secure connection?")
                        .setPositiveButton("Connect") { _, _ -> sharing?.connect(endpointId, name) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
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
        override fun onConnected(endpointId: String) {
            runOnUiThread {
                connectedPeers.add(endpointId)
                if (::peerList.isInitialized) rebuildPeerList()
                if (::sendButton.isInitialized) sendButton.isEnabled = true
            }
        }
        override fun onDisconnected(endpointId: String) {
            runOnUiThread {
                connectedPeers.remove(endpointId)
                if (::peerList.isInitialized) rebuildPeerList()
                if (::sendButton.isInitialized) sendButton.isEnabled = true
            }
        }
        override fun onTransferProgress(message: String) { runOnUiThread { updateStatus(message) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkMode = getSharedPreferences("nexora_preferences", MODE_PRIVATE).getBoolean("dark_mode", true)
        val savedTree = getSharedPreferences("nexora_file_manager", MODE_PRIVATE).getString("tree_uri", null)
        if (!savedTree.isNullOrBlank()) {
            runCatching {
                selectedTreeUri = Uri.parse(savedTree)
                val rootDocumentId = android.provider.DocumentsContract.getTreeDocumentId(selectedTreeUri!!)
                currentTreeDocumentId = getSharedPreferences("nexora_file_manager", MODE_PRIVATE).getString("current_document_id", rootDocumentId)
            }.onFailure {
                selectedTreeUri = null
                currentTreeDocumentId = null
            }
        }
        preloadLibraries()
        showLaunchSplash()
    }

    private fun showLaunchSplash() {
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), 0)
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF071526.toInt(), 0xFF102B4B.toInt(), 0xFF10204A.toInt())
            )
        }
        val glow = TextView(this).apply {
            text = "✦"
            textSize = 72f
            gravity = Gravity.CENTER
            setTextColor(0xFF72F1FF.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x3328D7FF)
                setStroke(dp(1), 0x5572F1FF)
            }
            alpha = 0f
            scaleX = .72f
            scaleY = .72f
            elevation = dp(8).toFloat()
        }
        screen.addView(glow, LinearLayout.LayoutParams(dp(230), dp(230)))
        val letters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val word = "NEXORA"
        val letterViews = word.mapIndexed { index, char ->
            TextView(this).apply {
                text = char.toString()
                gravity = Gravity.CENTER
                textSize = 39f
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                setTextColor(if (index == 2) 0xFF72F1FF.toInt() else -1)
                alpha = 0f
                translationY = -dp(72).toFloat()
                elevation = dp(12).toFloat()
            }.also { letters.addView(it, LinearLayout.LayoutParams(-2, dp(62)).apply { leftMargin = dp(1); rightMargin = dp(1) }) }
        }
        screen.addView(letters, LinearLayout.LayoutParams(-1, dp(74)))
        val subtitle = TextView(this).apply {
            text = "SHARE WITHOUT LIMITS"
            textSize = 10f
            letterSpacing = .24f
            gravity = Gravity.CENTER
            setTextColor(0xFFB9D8EA.toInt())
            alpha = 0f
            setPadding(0, dp(14), 0, 0)
        }
        screen.addView(subtitle)
        setContentView(screen)
        val set = android.animation.AnimatorSet()
        val animations = ArrayList<android.animation.Animator>()
        letterViews.forEachIndexed { index, view ->
            animations.add(android.animation.ObjectAnimator.ofFloat(view, "translationY", -dp(72).toFloat(), 0f).apply {
                duration = 520
                startDelay = index * 105L
                interpolator = android.view.animation.BounceInterpolator()
            })
            animations.add(android.animation.ObjectAnimator.ofFloat(view, "alpha", 0f, 1f).apply {
                duration = 260
                startDelay = index * 105L
            })
        }
        animations.add(android.animation.ObjectAnimator.ofFloat(glow, "alpha", 0f, 1f, .35f, 1f, .55f).apply { duration = 1550; startDelay = 220 })
        animations.add(android.animation.ObjectAnimator.ofFloat(glow, "scaleX", .72f, 1f, .9f, 1f).apply { duration = 1550; startDelay = 220; interpolator = android.view.animation.OvershootInterpolator() })
        animations.add(android.animation.ObjectAnimator.ofFloat(glow, "scaleY", .72f, 1f, .9f, 1f).apply { duration = 1550; startDelay = 220; interpolator = android.view.animation.OvershootInterpolator() })
        animations.add(android.animation.ObjectAnimator.ofFloat(subtitle, "alpha", 0f, 1f).apply { duration = 420; startDelay = 850 })
        set.playTogether(animations)
        set.start()
        android.os.Handler(mainLooper).postDelayed({
            val flash = View(this).apply { setBackgroundColor(0xFFE9FCFF.toInt()); alpha = 0f }
            screen.addView(flash, LinearLayout.LayoutParams(-1, -1))
            flash.animate().alpha(1f).setDuration(110).withEndAction {
                render()
                refreshReceivedFiles()
                root.alpha = 0f
                root.animate().alpha(1f).setDuration(260).start()
            }.start()
        }, 2200)
    }
    override fun onDestroy() {
        thumbnailExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onStart() { super.onStart(); bindService(Intent(this, TransferKeepAliveService::class.java), serviceConnection, BIND_AUTO_CREATE) }
    override fun onStop() {
        if (bound) { sharing?.setListener(null); unbindService(serviceConnection); bound = false }
        super.onStop()
    }

    private fun render() {
        receiveModeLabel = null
        transferStatusView = null
        transferProgressBar = null
        transferStepViews.clear()
        val page = if (darkMode) 0xFF071526.toInt() else 0xFFF3F7FC.toInt()
        val card = if (darkMode) 0xFF142A40.toInt() else -1
        val ink = if (darkMode) -1 else 0xFF10243B.toInt()
        val muted = if (darkMode) 0xFFB1C6D8.toInt() else 0xFF63788C.toInt()
        val blue = 0xFF176BDB.toInt()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                if (darkMode) intArrayOf(0xFF071526.toInt(), 0xFF102B42.toInt()) else intArrayOf(0xFFF6F9FF.toInt(), 0xFFEAF3FC.toInt())
            )
        }
        val libraryMode = activeTab in setOf("Home", "Media", "Files", "Sent", "Received", "Contacts")
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(0xFF008F68.toInt())
        }
        val menuButton = TextView(this).apply {
            text = "☰"; textSize = 23f; gravity = Gravity.CENTER; setTextColor(-1)
            setOnClickListener { showXenderMenu() }
        }
        header.addView(menuButton, LinearLayout.LayoutParams(dp(42), dp(42)))
        header.addView(TextView(this).apply {
            text = "NEXORA"; textSize = 21f; letterSpacing = .04f
            typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(-1)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "●"; textSize = 14f; setTextColor(0xFFB7F5D9.toInt())
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(28), dp(38)))
        root.addView(header)
        if (libraryMode) {
            val tabs = android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(if (darkMode) 0xFF10243A.toInt() else 0xFFE7F3EF.toInt())
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(8), dp(5), dp(8), dp(5))
            }
            listOf("History", "Apps", "Photos", "Videos", "Music", "Files", "Contacts").forEach { tab ->
                val selected = when (tab) {
                    "History" -> activeTab == "Sent" || activeTab == "Received"
                    "Apps" -> activeTab == "Media" && mediaCategory == "Apps"
                    "Photos" -> activeTab == "Media" && mediaCategory == "Photos"
                    "Videos" -> activeTab == "Media" && mediaCategory == "Videos"
                    "Music" -> activeTab == "Media" && mediaCategory == "Music"
                    "Files" -> activeTab == "Files"
                    else -> activeTab == "Contacts"
                }
                val chip = TextView(this).apply {
                    text = tab; textSize = 12f; gravity = Gravity.CENTER
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(if (selected) -1 else if (darkMode) 0xFFB8C9D8.toInt() else 0xFF356052.toInt())
                    setPadding(dp(13), dp(10), dp(13), dp(10))
                    if (selected) background = rounded(0xFF00A878.toInt(), 18)
                }
                chip.setOnClickListener {
                    animatePress(chip)
                    when (tab) {
                        "History" -> { activeTab = "Sent"; render() }
                        "Apps" -> { mediaCategory = "Apps"; activeTab = "Media"; render() }
                        "Photos" -> openMediaLibrary("Photos")
                        "Videos" -> openMediaLibrary("Videos")
                        "Music" -> openMediaLibrary("Music")
                        "Files" -> { activeTab = "Files"; render() }
                        "Contacts" -> { activeTab = "Contacts"; render() }
                    }
                }
                row.addView(chip, LinearLayout.LayoutParams(-2, dp(38)).apply { rightMargin = dp(5) })
            }
            tabs.addView(row)
            root.addView(tabs)
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(7), dp(13), dp(18))
        }
        val contentFrame = android.widget.FrameLayout(this)
        contentFrame.addView(ScrollView(this).apply {
            isFillViewport = true; clipToPadding = false; addView(content)
        }, android.widget.FrameLayout.LayoutParams(-1, -1))
        if (libraryMode) {
            val sendFab = TextView(this).apply {
                text = "➤  SEND"; textSize = 12f; gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(-1)
                background = rounded(0xFF009D70.toInt(), 24); elevation = dp(10).toFloat()
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { animatePress(this); activeTab = "Send"; render() }
            }
            val receiveFab = TextView(this).apply {
                text = "⇩  RECEIVE"; textSize = 12f; gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(-1)
                background = rounded(0xFF009D70.toInt(), 24); elevation = dp(10).toFloat()
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener {
                    animatePress(this); activeTab = "Receive"; render()
                    withNearbyPermissions {
                        ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, TransferKeepAliveService::class.java))
                        sharing?.startReceiving() ?: updateStatus("Starting receive mode. Try again in a moment.")
                    }
                }
            }
            contentFrame.addView(sendFab, android.widget.FrameLayout.LayoutParams(dp(132), dp(43), Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(16); bottomMargin = dp(14)
            })
            contentFrame.addView(receiveFab, android.widget.FrameLayout.LayoutParams(dp(132), dp(43), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(16); bottomMargin = dp(14)
            })
            content.setPadding(dp(13), dp(7), dp(13), dp(70))
        }
        root.addView(contentFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        if (libraryMode) {
            val nav = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                setPadding(dp(5), dp(5), dp(5), dp(5))
                setBackgroundColor(if (darkMode) 0xFF071526.toInt() else -1)
            }
            listOf("Music", "Videos", "Connect", "Photos", "Social").forEach { item ->
                val selected = when (item) {
                    "Music" -> activeTab == "Media" && mediaCategory == "Music"
                    "Videos" -> activeTab == "Media" && mediaCategory == "Videos"
                    "Photos" -> activeTab == "Media" && mediaCategory == "Photos"
                    "Connect" -> activeTab == "Home" || activeTab == "Transfers"
                    else -> activeTab == "Settings"
                }
                nav.addView(TextView(this).apply {
                    text = when (item) { "Music" -> "♫\\nMusic"; "Videos" -> "▣\\nVideos"; "Connect" -> "↔"; "Photos" -> "▧\\nPhotos"; else -> "☻\\nSocial" }
                    textSize = if (item == "Connect") 23f else 10f
                    gravity = Gravity.CENTER
                    typeface = if (selected) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                    setTextColor(if (selected) 0xFF00A878.toInt() else muted)
                    setPadding(dp(2), dp(5), dp(2), dp(5))
                    setOnClickListener {
                        animatePress(this)
                        when (item) {
                            "Music" -> openMediaLibrary("Music")
                            "Videos" -> openMediaLibrary("Videos")
                            "Photos" -> openMediaLibrary("Photos")
                            "Connect" -> { activeTab = "Home"; render() }
                            else -> { activeTab = "Settings"; render() }
                        }
                    }
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            root.addView(nav, LinearLayout.LayoutParams(-1, -2))
        }
                setContentView(root)
        when (activeTab) {
            "Home" -> showHome(card, ink, muted, blue)
            "Media" -> showMedia(card, ink, muted, blue)
            "Files" -> showFiles(card, ink, muted, blue)
            "Sent" -> showSent(card, ink, muted)
            "Received" -> showReceived(card, ink, muted)
            "Transfers" -> showTransfers(card, ink, muted)
            "Send" -> showSend(card, ink, muted, blue)
            "Receive" -> showReceive(card, ink, muted)
            "Settings" -> showSettings(card, ink, muted, blue)
            "Contacts" -> showContacts(card, ink, muted)
        }
        content.alpha = 0f
        content.translationY = dp(10).toFloat()
        content.animate().alpha(1f).translationY(0f).setDuration(230).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    private fun showHome(card: Int, ink: Int, muted: Int, blue: Int) {
        content.addView(TextView(this).apply {
            text = "Move files.\nFeel the speed."
            textSize = 29f; typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            setTextColor(ink); setPadding(dp(2), dp(7), dp(2), dp(5))
        })
        content.addView(TextView(this).apply {
            text = "A simpler way to share with people nearby."
            textSize = 12f; setTextColor(muted); setPadding(dp(3), 0, 0, dp(15))
        })
        val connection = panel(card).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF092B4A.toInt(), 0xFF124D73.toInt(), 0xFF123A5D.toInt())
            ).apply { cornerRadius = dp(25).toFloat(); setStroke(dp(1), 0x6648DFFF) }
            elevation = dp(8).toFloat()
        }
        connection.addView(TextView(this).apply {
            text = "NEARBY CONNECTION"; textSize = 10f; letterSpacing = .12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(0xFF9DDDF2.toInt())
        })
        val radar = RadarView()
        connection.addView(radar, LinearLayout.LayoutParams(-1, dp(178)))
        connection.addView(TextView(this).apply {
            text = if (discovered.isEmpty()) "Ready to discover nearby phones" else discovered.size.toString() + " device(s) nearby"
            textSize = 13f; gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(-1); setPadding(0, dp(1), 0, dp(12))
        })
        connection.addView(button("⌁   FIND NEARBY PHONES", 0xFF1689D5.toInt()) {
            withNearbyPermissions {
                ContextCompat.startForegroundService(this, Intent(this, TransferKeepAliveService::class.java))
                discovered.clear(); rebuildPeerList()
                sharing?.startFinding() ?: updateStatus("Starting service. Tap Find again.")
                radar.startPulse()
            }
        })
        content.addView(connection, bottom(14))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(actionTile("↑", "SEND FILES", 0xFF1689D5.toInt()) { activeTab = "Media"; render() },
            LinearLayout.LayoutParams(0, dp(112), 1f).apply { rightMargin = dp(6) })
        actions.addView(actionTile("↓", "RECEIVE", 0xFF18A887.toInt()) {
            withNearbyPermissions { ContextCompat.startForegroundService(this, Intent(this, TransferKeepAliveService::class.java)); sharing?.startReceiving() ?: updateStatus("Starting service. Try again.") }
        }, LinearLayout.LayoutParams(0, dp(112), 1f).apply { leftMargin = dp(6) })
        content.addView(actions, bottom(13))
        val qrActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        qrActions.addView(button("SCAN QR TO CONNECT", 0xFF7357E8.toInt()) { scanQrCode() },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(5) })
        qrActions.addView(button("SHOW MY QR", 0xFFE69A35.toInt()) { showQrCode() },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(5) })
        content.addView(qrActions, bottom(13))
        val quickAccess = panel(card)
        quickAccess.addView(label("QUICK ACCESS", muted))
        val quickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(Triple("Apps", "▦", 0xFF7357E8.toInt()), Triple("Images", "▧", 0xFF168FE5.toInt()), Triple("Videos", "▷", 0xFFE05C8A.toInt()), Triple("Music", "♫", 0xFFE69A35.toInt()), Triple("Files", "▤", 0xFF16A58A.toInt())).forEach { item ->
            val shortcut = TextView(this).apply {
                text = item.second + "\n" + item.first; gravity = Gravity.CENTER
                textSize = 10f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(item.third); setPadding(dp(5), dp(9), dp(5), dp(9))
                background = rounded(if (darkMode) 0xFF20364E.toInt() else 0xFFF0F5FB.toInt(), 13)
                setOnClickListener {
                    mediaCategory = if (item.first == "Images") "Photos" else item.first
                    if (item.first == "Files") { activeTab = "Files"; render() }
                    else if (item.first == "Images" || item.first == "Videos" || item.first == "Music") openMediaLibrary(mediaCategory!!)
                    else { activeTab = "Media"; render() }
                }
            }
            quickRow.addView(shortcut, LinearLayout.LayoutParams(0, dp(58), 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
        }
        quickAccess.addView(quickRow)
        content.addView(quickAccess, bottom(13))
        val devices = panel(card)
        devices.addView(label("DEVICES AROUND YOU", muted))
        devices.addView(TextView(this).apply {
            text = "Nearby phones appear here when discovery finds them."
            textSize = 11f; setTextColor(muted); setPadding(0, dp(5), 0, dp(8))
        })
        peerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        devices.addView(peerList); rebuildPeerList()
        content.addView(devices, bottom(12))
        val statusCard = panel(card)
        statusCard.addView(label("TRANSFER ACTIVITY", muted))
        status = TextView(this).apply { text = latestStatus; textSize = 12f; setTextColor(ink); setPadding(0, dp(7), 0, dp(2)) }
        statusCard.addView(status)
        content.addView(statusCard, bottom(12))
        sendButton = button(if (selectedUris.isEmpty()) "CONTINUE TO FILES" else "SEND " + selectedUris.size + " SELECTED FILE(S)", blue) {
            if (selectedUris.isEmpty()) { activeTab = "Media"; render() }
            else if (sharing == null) updateStatus("NEXORA is preparing the sharing service. Try again in a moment.")
            else {
                sharing?.sendFiles(ArrayList(selectedUris))
                selectedUris.clear()
                sendButton.text = "CONTINUE TO FILES"
                updateStatus("Files added to the transfer queue. Find and connect to a phone to begin.")
            }
        }
        content.addView(sendButton, bottom(8))
        content.addView(button("STOP SHARING", 0xFF62778A.toInt()) { sharing?.stopSharing(); discovered.clear(); connectedPeers.clear(); rebuildPeerList(); updateStatus("Sharing stopped.") })
    }

    private fun showReceive(card: Int, ink: Int, muted: Int) {
        content.addView(TextView(this).apply {
            text = "Receive files"; textSize = 26f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ink); setPadding(dp(2), dp(8), dp(2), dp(4))
        })
        content.addView(TextView(this).apply {
            text = "Make this phone visible so nearby NEXORA users can send to you."
            textSize = 12f; setTextColor(muted); setPadding(dp(2), 0, dp(2), dp(14))
        })
        val ready = panel(card)
        ready.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF103C35.toInt(), 0xFF15594A.toInt(), 0xFF183C45.toInt())
        ).apply { cornerRadius = dp(23).toFloat(); setStroke(dp(1), 0x6656E5B6) }
        receiveModeLabel = TextView(this).apply {
            text = if (sharing?.isAdvertising == true) "●  RECEIVING MODE ACTIVE" else "○  RECEIVE MODE READY"
            textSize = 10f; letterSpacing = .12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(0xFF83F0C9.toInt())
        }
        ready.addView(receiveModeLabel)
        ready.addView(TextView(this).apply {
            text = if (connectedPeers.isEmpty()) "Waiting for another phone to connect" else connectedPeers.size.toString() + " phone(s) connected"
            textSize = 18f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(-1); setPadding(0, dp(14), 0, dp(8))
        })
        ready.addView(TextView(this).apply {
            text = latestStatus; textSize = 12f; setTextColor(0xFFD4EDE5.toInt()); setPadding(0, 0, 0, dp(13))
        })
        ready.addView(button("↓   START RECEIVING", 0xFF16A58A.toInt()) {
            withNearbyPermissions {
                ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, TransferKeepAliveService::class.java))
                sharing?.startReceiving() ?: updateStatus("NEXORA is starting. Tap Start receiving again in a moment.")
            }
        })
        content.addView(ready, bottom(13))
        val qr = panel(card)
        qr.addView(label("CONNECT WITH QR", 0xFF7357E8.toInt()))
        qr.addView(TextView(this).apply {
            text = "Show your code to the sender, or scan their code to find their phone."
            textSize = 12f; setTextColor(muted); setPadding(0, dp(7), 0, dp(10))
        })
        val qrRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        qrRow.addView(button("MY QR CODE", 0xFFE69A35.toInt()) { showQrCode() },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(5) })
        qrRow.addView(button("SCAN QR", 0xFF7357E8.toInt()) { scanQrCode() },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(5) })
        qr.addView(qrRow)
        content.addView(qr, bottom(13))
        val devices = panel(card)
        devices.addView(label("CONNECTED PHONES", 0xFF168FE5.toInt()))
        peerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        devices.addView(peerList)
        rebuildPeerList()
        content.addView(devices, bottom(13))
        content.addView(button("OPEN TRANSFER CENTRE", 0xFF7357E8.toInt()) { activeTab = "Transfers"; render() }, bottom(8))
        content.addView(button("STOP RECEIVING", 0xFF64748B.toInt()) {
            sharing?.stopSharing()
            discovered.clear(); connectedPeers.clear()
            updateStatus("Receiving stopped.")
            render()
        })
    }
    private fun showSend(card: Int, ink: Int, muted: Int, blue: Int) {
        content.addView(TextView(this).apply {
            text = "Send files"; textSize = 25f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ink); setPadding(dp(2), dp(8), dp(2), dp(5))
        })
        content.addView(TextView(this).apply {
            text = "Choose first, then connect. NEXORA sends when a phone accepts."
            textSize = 12f; setTextColor(muted); setPadding(dp(2), 0, dp(2), dp(12))
        })
        val selection = panel(card)
        selection.addView(label("YOUR SELECTION", 0xFF7357E8.toInt()))
        selection.addView(TextView(this).apply {
            text = if (selectedUris.isEmpty()) "No files selected yet." else selectedUris.size.toString() + " file(s) ready to send"
            textSize = 16f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ink); setPadding(0, dp(8), 0, dp(8))
        })
        selectedUris.take(8).forEach { uri ->
            val name = runCatching {
                contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: (uri.lastPathSegment ?: "Selected file")
            selection.addView(TextView(this).apply {
                text = "•  " + name; textSize = 12f; setTextColor(muted)
                maxLines = 2; setPadding(0, dp(3), 0, dp(3))
            })
        }
        if (selectedUris.size > 8) selection.addView(TextView(this).apply {
            text = "and " + (selectedUris.size - 8) + " more…"; textSize = 11f; setTextColor(muted)
        })
        selection.addView(button("＋  CHOOSE MORE FILES", 0xFF7357E8.toInt()) { openPicker("*/*") }, bottom(7))
        selection.addView(button("CLEAR SELECTION", 0xFF64748B.toInt()) { selectedUris.clear(); render() })
        content.addView(selection, bottom(12))
        val connect = panel(card)
        connect.addView(label("CONNECT TO A PHONE", 0xFF168FE5.toInt()))
        connect.addView(TextView(this).apply {
            text = if (connectedPeers.isEmpty()) "No connected phones yet. Find nearby devices or scan a QR code." else connectedPeers.size.toString() + " phone(s) connected and ready."
            textSize = 13f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ink); setPadding(0, dp(8), 0, dp(10))
        })
        connect.addView(button("⌁  FIND NEARBY PHONES", 0xFF168FE5.toInt()) {
            withNearbyPermissions {
                ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, TransferKeepAliveService::class.java))
                sharing?.startFinding() ?: updateStatus("NEXORA is starting. Tap Find nearby again in a moment.")
                updateStatus("Searching for nearby phones…")
            }
        }, bottom(7))
        val qrRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        qrRow.addView(button("SCAN QR", 0xFF7357E8.toInt()) { scanQrCode() },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(5) })
        qrRow.addView(button("MY QR CODE", 0xFFE69A35.toInt()) { showQrCode() },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(5) })
        connect.addView(qrRow)
        peerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        connect.addView(peerList, bottom(8))
        rebuildPeerList()
        content.addView(connect, bottom(12))
        sendButton = button(if (selectedUris.isEmpty()) "CHOOSE FILES FIRST" else "SEND " + selectedUris.size + " FILE(S)", 0xFF16A58A.toInt()) {
            if (selectedUris.isEmpty()) openPicker("*/*")
            else if (sharing == null) updateStatus("NEXORA is preparing the sharing service. Try again in a moment.")
            else {
                sharing?.sendFiles(ArrayList(selectedUris))
                selectedUris.clear()
                updateStatus("Files queued. Connect to a phone and NEXORA will send automatically.")
                render()
            }
        }
        content.addView(sendButton, bottom(10))
        val live = panel(card)
        live.addView(label("LIVE TRANSFER", 0xFF16A58A.toInt()))
        live.addView(TextView(this).apply {
            text = latestStatus; textSize = 13f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ink); setPadding(0, dp(8), 0, dp(4))
        })
        live.addView(button("OPEN TRANSFER CENTRE", 0xFF7357E8.toInt()) { activeTab = "Transfers"; render() })
        content.addView(live)
    }

    private fun showQrCode() {
        val deviceName = (getSharedPreferences("nexora_preferences", MODE_PRIVATE)
            .getString("device_name", Build.MODEL) ?: "Android device").trim().take(24)
        val payload = "NEXORA|$deviceName"
        try {
            val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
                payload, com.google.zxing.BarcodeFormat.QR_CODE, 560, 560
            )
            val bitmap = android.graphics.Bitmap.createBitmap(560, 560, android.graphics.Bitmap.Config.ARGB_8888)
            for (x in 0 until 560) for (y in 0 until 560) {
                bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(12), dp(18), dp(12))
                background = rounded(if (darkMode) 0xFF142A40.toInt() else -1, 18)
            }
            val image = ImageView(this).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                setBackgroundColor(android.graphics.Color.WHITE)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            body.addView(image, LinearLayout.LayoutParams(dp(260), dp(260)))
            body.addView(TextView(this).apply {
                text = "Let another phone scan this code. Keep NEXORA open and choose Receive."
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(if (darkMode) -1 else 0xFF14213D.toInt())
                setPadding(0, dp(12), 0, 0)
            })
            android.app.AlertDialog.Builder(this)
                .setTitle("Your NEXORA QR code")
                .setView(body)
                .setPositiveButton("Start receiving") { _, _ ->
                    withNearbyPermissions {
                        ContextCompat.startForegroundService(this, Intent(this, TransferKeepAliveService::class.java))
                        sharing?.startReceiving() ?: updateStatus("Starting the sharing service. Tap Receive again in a moment.")
                    }
                }
                .setNegativeButton("Close", null)
                .show()
        } catch (e: Exception) {
            updateStatus("Could not create QR code: " + (e.localizedMessage ?: "unknown error"))
        }
    }

    private fun scanQrCode() {
        try {
            com.google.zxing.integration.android.IntentIntegrator(this)
                .setDesiredBarcodeFormats(com.google.zxing.integration.android.IntentIntegrator.QR_CODE)
                .setPrompt("Scan the other phone's NEXORA QR code")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .initiateScan()
        } catch (e: Exception) {
            updateStatus("QR scanner is unavailable: " + (e.localizedMessage ?: "unknown error"))
        }
    }

    private fun showMedia(card: Int, ink: Int, muted: Int, blue: Int) {
        if (mediaCategory == null) mediaCategory = "Apps"
        renderMediaLibrary(mediaCategory!!, ink, muted, blue)
    }

    private fun openMediaLibrary(kind: String) {
        mediaCategory = kind
        mediaSearch = ""
        mediaDisplayLimit = 20
        val permissions = when (kind) {
            "Photos" -> if (Build.VERSION.SDK_INT >= 34) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            "Videos" -> if (Build.VERSION.SDK_INT >= 34) arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            else -> if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_AUDIO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (hasMediaPermission(kind)) render() else {
            val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            requestPermissions(missing.toTypedArray(), REQUEST_MEDIA)
        }
    }

    private fun renderMediaLibrary(kind: String, ink: Int, muted: Int, blue: Int) {
        if (kind == "Apps") { renderAppsLibrary(ink, muted, blue); return }
        if (kind == "Files") {
            content.addView(TextView(this).apply { text = "Browse any folder or file on your phone."; textSize = 13f; setTextColor(muted); setPadding(0, dp(12), 0, dp(10)) })
            content.addView(button("BROWSE FILES", 0xFF16A58A.toInt()) { openPicker("*/*") })
            return
        }
        val displayKind = if (kind == "Images") "Photos" else kind
        content.addView(TextView(this).apply { text = displayKind; textSize = 23f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(ink); setPadding(0, dp(6), 0, dp(8)) })
        val search = EditText(this).apply {
            hint = "Search " + kind.lowercase(); setSingleLine(true); textSize = 14f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackground(rounded(if (darkMode) 0xFF263650.toInt() else -1, 12))
            setTextColor(ink); setHintTextColor(muted); setText(mediaSearch)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { mediaSearch = s?.toString() ?: ""; renderMediaRows(kind, ink, muted, blue) }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        content.addView(search, bottom(8))
        content.addView(button("Sort: " + mediaSort, blue) {
            mediaSort = if (mediaSort == "Newest") "Name A-Z" else "Newest"
            render()
        }, bottom(8))
        mediaList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(mediaList)
        renderMediaRows(kind, ink, muted, blue)
        content.addView(button("CONTINUE TO SEND  ·  " + selectedUris.size + " SELECTED", blue) { activeTab = "Send"; render() }, bottom(10))
    }

    private val mediaCache = HashMap<String, ArrayList<Pair<Uri, String>>>()
    private val mediaLoading = HashSet<String>()
    // Render media in small batches to avoid creating hundreds of views on the UI thread.
    private var mediaDisplayLimit = 20

    private fun renderMediaRows(kind: String, ink: Int, muted: Int, blue: Int) {
        if (!::mediaList.isInitialized) return
        mediaList.removeAllViews()
        val cached = mediaCache[kind]
        if (cached == null) {
            val error = mediaErrors[kind]
            if (error != null) {
                mediaList.addView(TextView(this).apply { text = "Could not load " + kind.lowercase() + ": " + error; textSize = 12f; setTextColor(muted); setPadding(0, dp(12), 0, dp(8)) })
                mediaList.addView(button("TRY AGAIN", blue) { mediaErrors.remove(kind); mediaCache.remove(kind); renderMediaRows(kind, ink, muted, blue) })
                return
            }
            if (mediaLoading.add(kind)) {
                mediaList.addView(TextView(this).apply { text = "Loading your " + kind.lowercase() + "…"; textSize = 13f; setTextColor(muted); setPadding(0, dp(16), 0, dp(16)) })
                Thread {
                    val loaded = ArrayList<Pair<Uri, String>>()
                    val collection = when (kind) {
                        "Photos" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        "Videos" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        else -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    }
                    try {
                        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE)
                        contentResolver.query(collection, projection, null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC LIMIT 1500")?.use { cursor ->
                            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                            while (cursor.moveToNext()) {
                                val id = cursor.getLong(idCol)
                                val name = cursor.getString(nameCol) ?: "Media file"
                                val size = cursor.getLong(sizeCol)
                                loaded.add(Pair(android.content.ContentUris.withAppendedId(collection, id), name + " · " + readableSize(size)))
                            }
                        }
                    } catch (e: Exception) {
                        mediaErrors[kind] = e.localizedMessage ?: "Android could not read this media library."
                    }
                    runOnUiThread {
                        mediaLoading.remove(kind)
                        if (mediaErrors.containsKey(kind)) mediaCache.remove(kind) else mediaCache[kind] = loaded
                        if (mediaCategory == kind && ::mediaList.isInitialized) renderMediaRows(kind, ink, muted, blue)
                    }
                }.start()
            }
            return
        }
        mediaRows.clear(); mediaRows.addAll(cached)
        val matching = cached.filter { it.second.contains(mediaSearch, ignoreCase = true) }
        val filtered = if (mediaSort == "Name A-Z") matching.sortedBy { it.second.lowercase() } else matching
        if (filtered.isEmpty()) mediaList.addView(TextView(this).apply { text = if (cached.isEmpty()) "No " + kind.lowercase() + " found." else "No matching files."; textSize = 12f; setTextColor(muted); setPadding(0, dp(12), 0, dp(12)) })
        filtered.take(mediaDisplayLimit).forEach { item ->
            val isSelected = selectedUris.contains(item.first)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(5), dp(8), dp(5)); background = rounded(if (isSelected) 0xFFE4F5EF.toInt() else if (darkMode) 0xFF1C2940.toInt() else -1, 12) }
            val preview = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(if (darkMode) 0xFF263650.toInt() else 0xFFE8EDF5.toInt()) }
            row.addView(preview, LinearLayout.LayoutParams(dp(66), dp(66)))
            if (kind == "Videos") preview.setOnClickListener { previewVideo(item.first, item.second) }
            row.addView(TextView(this).apply { text = item.second; textSize = 12f; setTextColor(if (darkMode) -1 else 0xFF14213D.toInt()); setPadding(dp(10), 0, dp(8), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply { text = if (isSelected) "✓" else "+"; textSize = 22f; setTextColor(if (isSelected) 0xFF16A085.toInt() else blue) })
            row.setOnClickListener { if (selectedUris.contains(item.first)) selectedUris.remove(item.first) else selectedUris.add(item.first); renderMediaRows(kind, ink, muted, blue) }
            mediaList.addView(row, bottom(6))
            if (kind == "Photos" || kind == "Videos") {
                preview.tag = item.first.toString()
                val cachedThumb = thumbnailCache.get(item.first.toString())
                if (cachedThumb != null) preview.setImageBitmap(cachedThumb)
                else thumbnailExecutor.execute {
                    val bitmap = try {
                        if (Build.VERSION.SDK_INT >= 29) {
                            contentResolver.loadThumbnail(item.first, android.util.Size(144, 144), null)
                        } else {
                            val mediaId = android.content.ContentUris.parseId(item.first)
                            if (kind == "Photos") android.provider.MediaStore.Images.Thumbnails.getThumbnail(contentResolver, mediaId, android.provider.MediaStore.Images.Thumbnails.MINI_KIND, null)
                            else android.provider.MediaStore.Video.Thumbnails.getThumbnail(contentResolver, mediaId, android.provider.MediaStore.Video.Thumbnails.MINI_KIND, null)
                        }
                    } catch (_: Exception) { null }
                    if (bitmap != null) {
                        thumbnailCache.put(item.first.toString(), bitmap)
                        runOnUiThread { if (preview.parent != null && preview.tag == item.first.toString()) preview.setImageBitmap(bitmap) }
                    }
                }
            } else { preview.setImageResource(android.R.drawable.ic_media_play); preview.scaleType = ImageView.ScaleType.CENTER }
        }
        if (filtered.size > mediaDisplayLimit) {
            mediaList.addView(button("LOAD MORE  ·  ${filtered.size - mediaDisplayLimit} LEFT", blue) {
                mediaDisplayLimit = (mediaDisplayLimit + 20).coerceAtMost(filtered.size)
                renderMediaRows(kind, ink, muted, blue)
            }, bottom(8))
        }
    }

    private fun preloadLibraries() {
        if (!appsLoaded && !appsLoading) {
            appsLoading = true
            Thread {
                val result = try {
                    packageManager.getInstalledApplications(0).onEach { app -> appLabels[app.packageName] = packageManager.getApplicationLabel(app).toString() }.sortedBy { appLabels[it.packageName]?.lowercase().orEmpty() }
                } catch (_: Exception) { emptyList() }
                runOnUiThread {
                    installedApps.clear()
                    installedApps.addAll(result)
                    appsLoaded = true
                    appsLoading = false
                    if (activeTab == "Media" && mediaCategory == "Apps") render()
                }
            }.start()
        }
    }

    private fun previewVideo(uri: Uri, title: String) {
        val video = android.widget.VideoView(this)
        val dialog = android.app.Dialog(this)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)); background = rounded(0xFF12243A.toInt(), 20) }
        panel.addView(TextView(this).apply { text = title; textSize = 13f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(-1); setPadding(0, 0, 0, dp(8)) })
        panel.addView(video, LinearLayout.LayoutParams(-1, dp(230)))
        panel.addView(button("CLOSE PREVIEW", 0xFFE05C8A.toInt()) { video.stopPlayback(); dialog.dismiss() })
        dialog.setContentView(panel); dialog.show(); dialog.window?.setBackgroundDrawableResource(android.R.color.transparent); dialog.window?.setLayout(-1, -2)
        try { video.setVideoURI(uri); video.setOnPreparedListener { video.start() }; video.setOnErrorListener { _, _, _ -> Toast.makeText(this, "This video cannot be previewed.", Toast.LENGTH_SHORT).show(); true } }
        catch (_: Exception) { Toast.makeText(this, "Could not open this video.", Toast.LENGTH_SHORT).show() }
    }

    private var appsLoaded = false
    private var appsLoading = false
    private val installedApps = ArrayList<android.content.pm.ApplicationInfo>()
    private val appLabels = HashMap<String, String>()
    private var systemAppsExpanded = false
    private var appDisplayLimit = 20

    private fun renderAppsLibrary(ink: Int, muted: Int, blue: Int) {
        content.addView(TextView(this).apply { text = "Apps"; textSize = 23f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(ink); setPadding(0, dp(6), 0, dp(8)) })
        val search = EditText(this).apply { hint = "Search apps"; setSingleLine(true); textSize = 14f; setPadding(dp(12), dp(8), dp(12), dp(8)); setBackground(rounded(if (darkMode) 0xFF263650.toInt() else -1, 12)); setTextColor(ink); setHintTextColor(muted) }
        content.addView(search, bottom(8))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)
        fun populate(query: String) {
            list.removeAllViews()
            if (!appsLoaded) {
                list.addView(TextView(this).apply { text = "Loading installed apps…"; textSize = 13f; setTextColor(muted); setPadding(0, dp(14), 0, dp(14)) })
                if (!appsLoading) {
                    appsLoading = true
                    Thread {
                        val result = try { packageManager.getInstalledApplications(0).sortedBy { packageManager.getApplicationLabel(it).toString().lowercase() } } catch (_: Exception) { emptyList() }
                        runOnUiThread {
                            installedApps.clear()
                            installedApps.addAll(result)
                            appsLoaded = true
                            appsLoading = false
                            if (mediaCategory == "Apps") populate(search.text.toString())
                        }
                    }.start()
                }
                return
            }
            val matches = installedApps.filter { (appLabels[it.packageName] ?: it.packageName).contains(query, true) || it.packageName.contains(query, true) }
            val userApps = matches.filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
            val systemApps = matches.filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 }
            if (matches.isEmpty()) {
                list.addView(TextView(this).apply { text = "No matching apps found."; textSize = 12f; setTextColor(muted); setPadding(0, dp(12), 0, dp(12)) })
                return
            }
            fun addAppSection(title: String, apps: List<android.content.pm.ApplicationInfo>, accent: Int, collapsible: Boolean = false) {
                if (apps.isEmpty()) return
                var expanded = !collapsible || systemAppsExpanded
                val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val heading = TextView(this).apply {
                    text = title + "  ·  " + apps.size + if (collapsible) "   ⌄" else ""
                    textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(accent); setPadding(dp(3), dp(13), 0, dp(6))
                    if (collapsible) setOnClickListener {
                        systemAppsExpanded = !systemAppsExpanded
                        populate(query)
                    }
                }
                section.addView(heading)
                val grid = android.widget.GridLayout(this).apply { columnCount = 4; useDefaultMargins = false }
                if (expanded) apps.forEachIndexed { tileIndex, app ->
                    val appTile = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                        setPadding(dp(6), dp(12), dp(6), dp(10))
                        background = rounded(if (darkMode) 0xFF142A40.toInt() else -1, 17)
                        elevation = dp(2).toFloat()
                        isClickable = true; isFocusable = true
                    }
                    val iconView = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setImageResource(android.R.drawable.sym_def_app_icon)
                        tag = app.packageName
                    }
                    appTile.addView(iconView, LinearLayout.LayoutParams(dp(48), dp(48)))
                    thumbnailExecutor.execute {
                        val drawable = try { packageManager.getApplicationIcon(app) } catch (_: Exception) { null }
                        if (drawable != null) runOnUiThread { if (iconView.tag == app.packageName && iconView.parent != null) iconView.setImageDrawable(drawable) }
                    }
                    val appName = appLabels[app.packageName] ?: app.packageName
                    appTile.addView(TextView(this).apply {
                        text = appName; textSize = 11f; maxLines = 2; gravity = Gravity.CENTER
                        typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(ink); setPadding(0, dp(7), 0, dp(3))
                    }, LinearLayout.LayoutParams(-1, dp(36)))
                    appTile.addView(TextView(this).apply {
                        text = "SHARE"; textSize = 9f; letterSpacing = .08f; gravity = Gravity.CENTER
                        typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(0xFF1684D9.toInt())
                    })
                    appTile.setOnClickListener { animatePress(appTile); selectAppApk(app) }
                    val params = android.widget.GridLayout.LayoutParams().apply {
                        width = 0; height = dp(125)
                        columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                        setMargins(dp(4), dp(4), dp(4), dp(7))
                    }
                    grid.addView(appTile, params)
                    appTile.alpha = 0f
                    appTile.translationY = dp(8).toFloat()
                    appTile.animate().alpha(1f).translationY(0f)
                        .setStartDelay((tileIndex.toLong() * 14L).coerceAtMost(196L))
                        .setDuration(190L).start()
                }
                section.addView(grid)
                if (!expanded) grid.visibility = View.GONE
                list.addView(section)
            }
            val popularPackages = listOf(
                "com.whatsapp", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
                "com.instagram.android", "com.facebook.katana", "com.google.android.youtube",
                "com.android.chrome", "org.telegram.messenger", "com.snapchat.android",
                "com.spotify.music", "com.google.android.gm", "com.google.android.apps.maps"
            )
            val popularApps = popularPackages.mapNotNull { pkg -> userApps.firstOrNull { it.packageName == pkg } }.distinctBy { it.packageName }
            val featuredApps = (popularApps + userApps.filterNot { app -> popularApps.any { it.packageName == app.packageName } }).take(6)
            addAppSection(if (popularApps.isNotEmpty()) "POPULAR APPS" else "QUICK ACCESS", featuredApps, 0xFFE69A35.toInt())
            addAppSection("LOCAL APPS", userApps.take(appDisplayLimit), 0xFF7357E8.toInt())
            if (userApps.size > appDisplayLimit) {
                list.addView(button("LOAD MORE APPS", blue) {
                    appDisplayLimit = (appDisplayLimit + 20).coerceAtMost(userApps.size)
                    populate(search.text.toString())
                }, bottom(8))
            }
            addAppSection("SYSTEM APPS", systemApps, 0xFF168FE5.toInt(), collapsible = query.isBlank())
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { populate(s?.toString() ?: "") }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        populate("")
    }

    private fun selectAppApk(app: android.content.pm.ApplicationInfo) {
        try {
            val folder = java.io.File(cacheDir, "shared_apks").apply { mkdirs() }
            val batch = System.currentTimeMillis().toString()
            val prefix = "NEXORA_APP_" + app.packageName + "_" + batch
            val sources = ArrayList<java.io.File>()
            sources.add(java.io.File(app.sourceDir))
            app.splitSourceDirs?.forEach { sources.add(java.io.File(it)) }
            sources.forEachIndexed { index, source ->
                val suffix = if (index == 0) "_BASE.apk" else "_SPLIT_" + (index - 1) + ".apk"
                val apk = java.io.File(folder, prefix + suffix)
                source.copyTo(apk, overwrite = true)
                val uri = androidx.core.content.FileProvider.getUriForFile(this, packageName + ".fileprovider", apk)
                if (!selectedUris.contains(uri)) selectedUris.add(uri)
            }
            val label = packageManager.getApplicationLabel(app).toString()
            val parts = sources.size
            activeTab = "Send"
            mediaCategory = "Apps"
            render()
            updateStatus(label + ": " + parts + " APK part(s) selected. Choose a nearby phone, then tap Send.")
        } catch (e: Exception) {
            Toast.makeText(this, "Could not prepare this app: " + e.localizedMessage, Toast.LENGTH_LONG).show()
        }
    }

    private fun installReceivedApkGroup(prefix: String) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            val settings = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + packageName))
            startActivity(settings)
            Toast.makeText(this, "Allow NEXORA to install apps, then return and tap Install again.", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            var sessionId = -1
            try {
                val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
                val apkUris = ArrayList<Pair<Uri, String>>()
                contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection,
                    MediaStore.MediaColumns.RELATIVE_PATH + " = ?",
                    arrayOf("Download/NEXORA/"), MediaStore.MediaColumns.DISPLAY_NAME + " ASC")?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val name = cursor.getString(1) ?: continue
                        if (name.startsWith(prefix) && name.endsWith(".apk", true)) {
                            apkUris.add(Pair(android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), name))
                        }
                    }
                }
                if (apkUris.isEmpty()) throw java.io.IOException("The APK files could not be found.")
                val params = android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                sessionId = packageManager.packageInstaller.createSession(params)
                packageManager.packageInstaller.openSession(sessionId).use { session ->
                    apkUris.forEach { item ->
                        val input = contentResolver.openInputStream(item.first) ?: throw java.io.IOException("Could not read " + item.second)
                        input.use { stream ->
                            session.openWrite(item.second, 0, -1).use { output ->
                                stream.copyTo(output, 1024 * 1024)
                                session.fsync(output)
                            }
                        }
                    }
                    val callback = Intent(this, InstallStatusReceiver::class.java).setAction("com.nexora.share.INSTALL_STATUS")
                    val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pending = PendingIntent.getBroadcast(this, sessionId, callback, pendingFlags)
                    session.commit(pending.intentSender)
                }
                runOnUiThread { Toast.makeText(this, "Install request sent to Android.", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                if (sessionId >= 0) runCatching { packageManager.packageInstaller.abandonSession(sessionId) }
                runOnUiThread { Toast.makeText(this, "Could not install APK: " + e.localizedMessage, Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun hasMediaPermission(kind: String): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            val permission = if (kind == "Photos") Manifest.permission.READ_MEDIA_IMAGES else if (kind == "Videos") Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_AUDIO
            if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) return true
            if (kind == "Photos" || kind == "Videos") return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
            return false
        }
        val permission = when (kind) {
            "Photos" -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            "Videos" -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
            else -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun readableSize(bytes: Long): String {
        if (bytes < 1024) return bytes.toString() + " B"
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        if (bytes < 1024L * 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        return String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    private var selectedTreeUri: Uri? = null
    private var currentTreeDocumentId: String? = null
    private val treeDocumentStack = ArrayList<String>()
    private var sortTreeBySize = false
    private var treeCategoryFilter: String? = null
    private var folderSearch = ""

    private fun showFiles(card: Int, ink: Int, muted: Int, blue: Int) {
        content.addView(TextView(this).apply { text = "File manager"; textSize = 25f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(ink); setPadding(dp(2), dp(8), dp(2), dp(5)) })
        content.addView(TextView(this).apply { text = "Find documents, downloads, APKs and large files."; textSize = 12f; setTextColor(muted); setPadding(dp(2), 0, dp(2), dp(13)) })
        val storage = panel(card)
        storage.addView(label("PHONE STORAGE", 0xFF16A58A.toInt()))
        try {
            val stat = android.os.StatFs(android.os.Environment.getDataDirectory().absolutePath)
            val total = stat.totalBytes; val free = stat.availableBytes; val used = (total - free).coerceAtLeast(0L)
            storage.addView(TextView(this).apply { text = readableSize(used) + " used  ·  " + readableSize(free) + " available"; textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(ink); setPadding(0, dp(8), 0, dp(5)) })
            val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = if (total > 0) ((used * 100L) / total).toInt() else 0
                progressTintList = android.content.res.ColorStateList.valueOf(0xFF16A58A.toInt())
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(if (darkMode) 0xFF29445B.toInt() else 0xFFE2EAF2.toInt())
            }
            storage.addView(bar, LinearLayout.LayoutParams(-1, dp(8)))
            val cacheBytes = runCatching { cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
            storage.addView(TextView(this).apply { text = "NEXORA temporary cache: " + readableSize(cacheBytes); textSize = 11f; setTextColor(muted); setPadding(0, dp(7), 0, 0) })
        } catch (_: Exception) { storage.addView(TextView(this).apply { text = "Storage information is unavailable."; setTextColor(muted); textSize = 12f }) }
        content.addView(storage, bottom(12))
        val removableVolumes = runCatching {
            getExternalFilesDirs(null).filterNotNull().filter { android.os.Environment.isExternalStorageRemovable(it) }
                .distinctBy { it.absolutePath.substringBefore("/Android/") }
        }.getOrDefault(emptyList())
        val sdPanel = panel(card)
        sdPanel.addView(label("SD CARD STORAGE", 0xFF7357E8.toInt()))
        if (removableVolumes.isEmpty()) {
            sdPanel.addView(TextView(this).apply {
                text = "No removable SD card detected."
                textSize = 12f
                setTextColor(muted)
                setPadding(0, dp(7), 0, dp(2))
            })
        } else {
            removableVolumes.forEachIndexed { index, volume ->
                runCatching {
                    val stat = android.os.StatFs(volume.absolutePath)
                    val total = stat.totalBytes
                    val free = stat.availableBytes
                    val used = (total - free).coerceAtLeast(0L)
                    sdPanel.addView(TextView(this).apply {
                        text = "SD card " + (index + 1) + " · " + readableSize(used) + " used · " + readableSize(free) + " free"
                        textSize = 12f
                        setTextColor(ink)
                        setPadding(0, dp(7), 0, dp(5))
                    })
                }
            }
            sdPanel.addView(button("BROWSE SD CARD FOLDER", 0xFF7357E8.toInt()) {
                updateStatus("Choose the SD card in Android's folder picker, then grant NEXORA access.")
                openFolderTree()
            }, bottom(5))
        }
        content.addView(sdPanel, bottom(12))
        val categoryPanel = panel(card)
        categoryPanel.addView(label("BROWSE BY CATEGORY", muted))
        val categoryGrid = android.widget.GridLayout(this).apply { columnCount = 2 }
        listOf(
            Triple("Documents", "documents", 0xFF168FE5.toInt()),
            Triple("E-books", "ebooks", 0xFF7357E8.toInt()),
            Triple("APKs", "apks", 0xFFE69A35.toInt()),
            Triple("Archives", "archives", 0xFF16A58A.toInt()),
            Triple("Big Files", "big", 0xFFE05C8A.toInt())
        ).forEach { item ->
            val tile = TextView(this).apply {
                text = "▤  " + item.first
                textSize = 13f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(item.third); gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(15), dp(8), dp(15))
                background = rounded(if (darkMode) 0xFF20364E.toInt() else 0xFFF1F6FB.toInt(), 13)
                setOnClickListener {
                    treeCategoryFilter = item.second
                    folderSearch = ""
                    if (item.second == "big") sortTreeBySize = true
                    if (selectedTreeUri != null) render() else openFolderTree()
                }
            }
            categoryGrid.addView(tile, android.widget.GridLayout.LayoutParams().apply {
                width = 0; height = dp(58)
                columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        categoryPanel.addView(categoryGrid)
        content.addView(categoryPanel, bottom(10))
        content.addView(button("OPEN PHONE FOLDERS", 0xFF16A58A.toInt()) { openFolderTree() }, bottom(8))
        listOf("Browse all files" to "*/*", "Browse documents" to "application/*", "Browse images" to "image/*", "Browse videos" to "video/*", "Browse audio" to "audio/*").forEach { (title, mime) ->
            val tint = when (mime) { "image/*" -> 0xFF168FE5.toInt(); "video/*" -> 0xFFE05C8A.toInt(); "audio/*" -> 0xFFE69A35.toInt(); else -> blue }
            content.addView(button(title, tint) { openPicker(mime) }, bottom(7))
        }
        selectedTreeUri?.let { uri ->
            val rootId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            if (currentTreeDocumentId == null) currentTreeDocumentId = rootId
            if (currentTreeDocumentId != null) renderTreeContents(uri, currentTreeDocumentId!!, ink, muted)
        }
    }

    private fun openFolderTree() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { startActivityForResult(intent, REQUEST_TREE) } catch (_: Exception) { updateStatus("This phone does not provide folder browsing.") }
    }

    private fun renderTreeContents(treeUri: Uri, documentId: String, ink: Int, muted: Int) {
        val section = panel(if (darkMode) 0xFF142A40.toInt() else -1)
        section.addView(label("CURRENT FOLDER", 0xFF16A58A.toInt()))
        treeCategoryFilter?.let { filter ->
            val categoryName = when (filter) { "documents" -> "DOCUMENTS"; "ebooks" -> "E-BOOKS"; "apks" -> "APKs"; "archives" -> "ARCHIVES"; "big" -> "BIG FILES (50 MB+)"; else -> filter.uppercase() }
            section.addView(TextView(this).apply { text = "FILTER: $categoryName"; textSize = 10f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(0xFFE69A35.toInt()); setPadding(0, dp(5), 0, dp(5)) })
            section.addView(button("CLEAR CATEGORY FILTER", 0xFF64748B.toInt()) { treeCategoryFilter = null; sortTreeBySize = false; render() }, bottom(6))
        }
        section.addView(TextView(this).apply { text = documentId.substringAfterLast(':').ifBlank { "Phone folder" }; textSize = 12f; setTextColor(ink); setPadding(0, dp(6), 0, dp(8)) })
        if (treeDocumentStack.isNotEmpty()) section.addView(button("← PARENT FOLDER", 0xFF64748B.toInt()) {
            currentTreeDocumentId = treeDocumentStack.removeAt(treeDocumentStack.lastIndex)
            getSharedPreferences("nexora_file_manager", MODE_PRIVATE).edit().putString("current_document_id", currentTreeDocumentId).apply()
            render()
        }, bottom(6))
        section.addView(button(if (sortTreeBySize) "SORT: NAME A-Z" else "SORT: LARGEST FIRST", 0xFF7357E8.toInt()) {
            sortTreeBySize = !sortTreeBySize
            render()
        }, bottom(7))
        val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val searchInput = EditText(this).apply {
            hint = "Search this folder"
            setSingleLine(true)
            textSize = 12f
            setTextColor(ink)
            setHintTextColor(muted)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = rounded(if (darkMode) 0xFF20364E.toInt() else 0xFFF1F6FB.toInt(), 11)
            setText(folderSearch)
        }
        searchRow.addView(searchInput, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(6) })
        searchRow.addView(button("SEARCH", 0xFF168FE5.toInt()) {
            folderSearch = searchInput.text?.toString()?.trim().orEmpty()
            render()
        })
        section.addView(searchRow, bottom(8))
        if (folderSearch.isNotBlank()) section.addView(button("CLEAR SEARCH", 0xFF64748B.toInt()) {
            folderSearch = ""
            render()
        }, bottom(6))
        val children = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        section.addView(children)
        val childUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME, android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE, android.provider.DocumentsContract.Document.COLUMN_SIZE)
        var displayedCount = 0
        try {
            contentResolver.query(childUri, projection, null, null,
                if (sortTreeBySize) android.provider.DocumentsContract.Document.COLUMN_SIZE + " DESC" else android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME + " ASC")?.use { cursor ->
                if (cursor.count == 0) children.addView(TextView(this).apply { text = "This folder is empty."; textSize = 12f; setTextColor(muted); setPadding(0, dp(10), 0, dp(10)) })
                while (cursor.moveToNext()) {
                    val idIndex = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIndex = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIndex = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeIndex = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_SIZE)
                    if (idIndex < 0 || nameIndex < 0 || mimeIndex < 0) continue
                    val childId = cursor.getString(idIndex) ?: continue
                    val name = cursor.getString(nameIndex) ?: "Unnamed item"
                    if (folderSearch.isNotBlank() && !name.contains(folderSearch, ignoreCase = true)) continue
                    val mime = cursor.getString(mimeIndex) ?: "*/*"
                    val sizeBytes = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                    val folder = mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR
                    val lowerName = name.lowercase()
                    val categoryMatch = when (treeCategoryFilter) {
                        "documents" -> mime.contains("pdf") || mime.contains("word") || mime.contains("excel") || mime.contains("powerpoint") || mime.startsWith("text/") || listOf(".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt").any { lowerName.endsWith(it) }
                        "ebooks" -> mime.contains("epub") || mime.contains("mobipocket") || listOf(".epub", ".mobi", ".azw", ".azw3", ".pdf").any { lowerName.endsWith(it) }
                        "apks" -> mime.contains("android.package-archive") || lowerName.endsWith(".apk")
                        "archives" -> mime.contains("zip") || mime.contains("rar") || mime.contains("7z") || mime.contains("tar") || mime.contains("gzip") || listOf(".zip", ".rar", ".7z", ".tar", ".gz").any { lowerName.endsWith(it) }
                        "big" -> sizeBytes >= 50L * 1024L * 1024L
                        else -> true
                    }
                    if (!folder && !categoryMatch) continue
                    displayedCount++
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) readableSize(sizeBytes) else "Folder"
                    val fileIcon = when {
                        folder -> "📁"
                        mime.startsWith("image/") -> "🖼️"
                        mime.startsWith("video/") -> "🎬"
                        mime.startsWith("audio/") -> "🎵"
                        mime.contains("pdf") || name.endsWith(".pdf", true) -> "📕"
                        name.endsWith(".apk", true) -> "📦"
                        mime.contains("zip") || name.endsWith(".rar", true) || name.endsWith(".7z", true) -> "🗜️"
                        mime.contains("text") || name.endsWith(".doc", true) || name.endsWith(".docx", true) -> "📝"
                        else -> "📄"
                    }
                    val itemUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(10), dp(8), dp(10)); background = rounded(if (darkMode) 0xFF20364E.toInt() else 0xFFF1F6FB.toInt(), 13) }
                    row.addView(TextView(this).apply { text = fileIcon + "  " + name + "\n" + size; textSize = 12f; setTextColor(ink); maxLines = 2 }, LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(button(if (folder) "OPEN" else "ADD", if (folder) 0xFF16A58A.toInt() else 0xFF168FE5.toInt()) {
                        if (folder) {
                            treeDocumentStack.add(currentTreeDocumentId ?: documentId)
                            currentTreeDocumentId = childId
                            getSharedPreferences("nexora_file_manager", MODE_PRIVATE).edit().putString("current_document_id", childId).apply()
                            render()
                        }
                        else { if (!selectedUris.contains(itemUri)) selectedUris.add(itemUri); updateStatus("${selectedUris.size} file(s) selected."); Toast.makeText(this, "Added: $name", Toast.LENGTH_SHORT).show() }
                    })
                    children.addView(row, bottom(5))
                }
            } ?: children.addView(TextView(this).apply { text = "Could not read this folder."; textSize = 12f; setTextColor(muted) })
            if (displayedCount == 0 && (folderSearch.isNotBlank() || treeCategoryFilter != null)) children.addView(TextView(this).apply {
                text = "No files match the current search or category in this folder."
                textSize = 12f
                setTextColor(muted)
                setPadding(dp(4), dp(12), dp(4), dp(12))
            })
        } catch (e: Exception) { children.addView(TextView(this).apply { text = "Folder could not be opened: " + (e.localizedMessage ?: "unknown error"); textSize = 12f; setTextColor(muted) }) }
        section.addView(button("SEND ${selectedUris.size} SELECTED FILE(S)", 0xFF1689D5.toInt()) {
            if (selectedUris.isEmpty()) Toast.makeText(this, "Select files first.", Toast.LENGTH_SHORT).show()
            else { activeTab = "Send"; render(); updateStatus("${selectedUris.size} file(s) selected. Choose a nearby phone, then tap Send.") }
        }, bottom(5))
        content.addView(section, bottom(12))
    }

    private fun showTransfers(card: Int, ink: Int, muted: Int) {
        content.addView(TextView(this).apply { text = "Transfer centre"; textSize = 25f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(ink); setPadding(dp(2), dp(8), dp(2), dp(12)) })
        val live = panel(card)
        live.addView(label("LIVE TRANSFER STATUS", 0xFF16A58A.toInt()))
        val statusLine = TextView(this).apply { text = latestStatus; textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(ink); setPadding(0, dp(8), 0, dp(6)) }
        transferStatusView = statusLine
        live.addView(statusLine)
        val stepRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("SELECT", "FIND", "CONNECT", "SEND", "DONE").forEachIndexed { index, labelText ->
            val step = TextView(this).apply {
                text = labelText
                textSize = 8f
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(dp(3), dp(8), dp(3), dp(8))
                val tint = intArrayOf(0xFF7357E8.toInt(), 0xFF168FE5.toInt(), 0xFFE69A35.toInt(), 0xFFE05C8A.toInt(), 0xFF16A58A.toInt())[index]
                background = rounded(if (index < transferStage) tint else if (darkMode) 0xFF29445B.toInt() else 0xFFE8EEF5.toInt(), 10)
                setTextColor(if (index < transferStage) -1 else muted)
            }
            transferStepViews.add(step)
            stepRow.addView(step, LinearLayout.LayoutParams(0, dp(34), 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
        }
        live.addView(stepRow, bottom(9))
        val initialPercent = Regex("(\\d{1,3})%").find(latestStatus)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val progressLine = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF16A58A.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(if (darkMode) 0xFF29445B.toInt() else 0xFFE2EAF2.toInt())
            isIndeterminate = initialPercent == null && (latestStatus.contains("sending", true) || latestStatus.contains("receiving", true))
            progress = initialPercent ?: if (transferStage >= 5) 100 else 0
        }
        transferProgressBar = progressLine
        live.addView(progressLine, LinearLayout.LayoutParams(-1, dp(8)))
        live.addView(button("BACK TO SHARING", 0xFF1689D5.toInt()) { activeTab = "Home"; render() })
        content.addView(live, bottom(12))
        val devices = panel(card)
        devices.addView(label("CONNECTED PHONES", 0xFF168FE5.toInt()))
        if (connectedPeers.isEmpty()) {
            devices.addView(TextView(this).apply {
                text = "No phones connected. Open Send or Receive to find a nearby device."
                textSize = 12f
                setTextColor(muted)
                setPadding(0, dp(8), 0, dp(4))
            })
        } else {
            devices.addView(TextView(this).apply {
                text = connectedPeers.size.toString() + " phone(s) connected to this transfer session."
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(ink)
                setPadding(0, dp(8), 0, dp(7))
            })
            connectedPeers.forEach { endpointId ->
                val peerName = discovered[endpointId] ?: "Nearby phone"
                devices.addView(TextView(this).apply {
                    text = "●  " + peerName + "  ·  CONNECTED"
                    textSize = 11f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(0xFF16A58A.toInt())
                    setPadding(dp(9), dp(8), dp(9), dp(8))
                    background = rounded(if (darkMode) 0xFF173C3B.toInt() else 0xFFE7F7F1.toInt(), 10)
                }, bottom(5))
            }
        }
        content.addView(devices, bottom(12))
        val history = panel(card)
        history.addView(label("RECENTLY SENT", 0xFF7357E8.toInt()))
        val entries = getSharedPreferences("nexora_transfer_history", MODE_PRIVATE).getString("sent_items", "")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        if (entries.isEmpty()) history.addView(TextView(this).apply { text = "Your sent files will appear here."; textSize = 12f; setTextColor(muted); setPadding(0, dp(8), 0, dp(5)) })
        else addGroupedHistory(history, entries.take(30), 0xFF16A58A.toInt(), ink, muted)
        content.addView(history, bottom(12))
        content.addView(button("OPEN RECEIVED FILES", 0xFFE05C8A.toInt()) { activeTab = "Received"; render() })
    }

    private fun addGroupedHistory(target: LinearLayout, entries: List<String>, accent: Int, ink: Int, muted: Int) {
        entries.groupBy { it.substringBefore(" · ").substringBefore(",").ifBlank { "Earlier" } }
            .forEach { (date, datedEntries) ->
                target.addView(TextView(this).apply {
                    text = date
                    textSize = 12f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(if (darkMode) 0xFF8FE0C3.toInt() else 0xFF16856A.toInt())
                    setPadding(dp(3), dp(12), dp(3), dp(5))
                })
                datedEntries.forEach { entry ->
                    target.addView(historyCard(entry, accent, ink), bottom(6))
                }
            }
    }

    private fun historyCard(entry: String, accent: Int, ink: Int): LinearLayout {
        val timestamp = entry.substringBefore(" · ", "")
        val filename = entry.substringAfter(" · ", entry)
        val extension = filename.substringAfterLast('.', "").lowercase()
        val glyph = when (extension) {
            "jpg", "jpeg", "png", "webp", "gif" -> "▧"
            "mp4", "mkv", "mov", "avi" -> "▷"
            "mp3", "wav", "m4a", "aac" -> "♫"
            "apk" -> "▦"
            "pdf", "doc", "docx", "txt" -> "▤"
            else -> "↗"
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = rounded(if (darkMode) 0xFF20364E.toInt() else 0xFFF1F6FB.toInt(), 14)
        }
        row.addView(TextView(this).apply {
            text = glyph
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(accent)
            background = rounded(if (darkMode) 0xFF29445B.toInt() else -1, 12)
            setPadding(dp(9), dp(6), dp(9), dp(6))
        })
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        details.addView(TextView(this).apply {
            text = filename
            textSize = 12f
            maxLines = 2
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ink)
        })
        details.addView(TextView(this).apply {
            text = timestamp
            textSize = 10f
            setTextColor(if (darkMode) 0xFFB1C6D8.toInt() else 0xFF63788C.toInt())
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun showSent(card: Int, ink: Int, muted: Int) {
        content.addView(TextView(this).apply { text = "History"; textSize = 24f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(ink); setPadding(dp(2), dp(8), dp(2), dp(8)) })
        val historyTabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        historyTabs.addView(button("SENT", if (activeTab == "Sent") 0xFF7357E8.toInt() else 0xFF334155.toInt()) { activeTab = "Sent"; render() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(5) })
        historyTabs.addView(button("RECEIVED", if (activeTab == "Received") 0xFF16A58A.toInt() else 0xFF334155.toInt()) { activeTab = "Received"; render() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(5) })
        content.addView(historyTabs, bottom(10))
        val box = panel(card)
        box.addView(label("SUCCESSFULLY SENT", muted))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list)
        val entries = getSharedPreferences("nexora_transfer_history", MODE_PRIVATE).getString("sent_items", "")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        if (entries.isEmpty()) list.addView(TextView(this).apply { text = "Files you send will appear here."; textSize = 12f; setTextColor(muted); setPadding(0, dp(8), 0, dp(8)) })
        else addGroupedHistory(list, entries.take(100), 0xFF7357E8.toInt(), ink, muted)
        content.addView(box)
    }

    private fun rememberSentFileName(name: String) {
        if (name.isBlank()) return
        val prefs = getSharedPreferences("nexora_transfer_history", MODE_PRIVATE)
        val old = prefs.getString("sent_items", "") ?: ""
        val stamp = java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val entry = stamp + " · " + name
        prefs.edit().putString("sent_items", (listOf(entry) + old.split("\n").filter { it.isNotBlank() }).take(100).joinToString("\n")).apply()
    }

    private fun rememberSentFiles(uris: List<Uri>) {
        val prefs = getSharedPreferences("nexora_transfer_history", MODE_PRIVATE)
        val old = prefs.getString("sent_items", "") ?: ""
        val stamp = java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val names = uris.map { uri ->
            var name = uri.lastPathSegment ?: "Selected file"
            try { contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name } } catch (_: Exception) {}
            stamp + " · " + name
        }
        prefs.edit().putString("sent_items", (names + old.split("\n").filter { it.isNotBlank() }).take(100).joinToString("\n")).apply()
    }
    private fun showReceived(card: Int, ink: Int, muted: Int) {
        content.addView(TextView(this).apply { text = "History"; textSize = 24f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(ink); setPadding(dp(2), dp(8), dp(2), dp(8)) })
        val historyTabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        historyTabs.addView(button("SENT", if (activeTab == "Sent") 0xFF7357E8.toInt() else 0xFF334155.toInt()) { activeTab = "Sent"; render() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(5) })
        historyTabs.addView(button("RECEIVED", if (activeTab == "Received") 0xFF16A58A.toInt() else 0xFF334155.toInt()) { activeTab = "Received"; render() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(5) })
        content.addView(historyTabs, bottom(10))
        val box = panel(card); box.addView(label("SAVED ON THIS PHONE", muted))
        receivedList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; box.addView(receivedList)
        content.addView(box); refreshReceivedFiles()
    }


    private fun showXenderMenu() {
        val items = arrayOf("Language", "Settings", "Rate Us", "Help & Feedback", "About", "Status & Updates")
        android.app.AlertDialog.Builder(this)
            .setTitle("NEXORA  ·  " + (Build.MODEL ?: "This phone"))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> android.app.AlertDialog.Builder(this).setTitle("Language")
                        .setItems(arrayOf("English")) { _, _ -> Toast.makeText(this, "English is selected.", Toast.LENGTH_SHORT).show() }.show()
                    1 -> { activeTab = "Settings"; render() }
                    2 -> Toast.makeText(this, "Thank you for supporting NEXORA.", Toast.LENGTH_SHORT).show()
                    3 -> android.app.AlertDialog.Builder(this).setTitle("Help & Feedback")
                        .setMessage("For transfer problems, keep both phones nearby, enable Wi-Fi and Bluetooth, and allow nearby-device permissions.")
                        .setPositiveButton("OK", null).show()
                    4 -> android.app.AlertDialog.Builder(this).setTitle("About NEXORA")
                        .setMessage("NEXORA is a local file-sharing app for Android. Transfers use nearby device connections.")
                        .setPositiveButton("OK", null).show()
                    5 -> Toast.makeText(this, "You are using the current installed NEXORA build.", Toast.LENGTH_SHORT).show()
                }
            }.show()
    }

    private fun showContacts(card: Int, ink: Int, muted: Int) {
        content.addView(TextView(this).apply {
            text = "Contacts"; textSize = 23f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ink); setPadding(0, dp(8), 0, dp(8))
        })
        content.addView(TextView(this).apply {
            text = "Choose a contact to prepare a shareable contact card."
            textSize = 12f; setTextColor(muted); setPadding(0, 0, 0, dp(12))
        })
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            content.addView(button("ALLOW CONTACT ACCESS", 0xFF009D70.toInt()) {
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 303)
            })
            return
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)
        var count = 0
        try {
            val projection = arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, null, null,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberCol = cursor.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                val seen = HashSet<String>()
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Unnamed contact"
                    val number = cursor.getString(numberCol) ?: ""
                    if (!seen.add("$id:$number")) continue
                    count++
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(13), dp(11), dp(13), dp(11))
                        background = rounded(if (darkMode) 0xFF142A40.toInt() else -1, 12)
                        isClickable = true
                        setOnClickListener {
                            try {
                                val folder = java.io.File(cacheDir, "shared_contacts")
                                folder.mkdirs()
                                val file = java.io.File(folder, "Contact-$id.vcf")
                                fun vcfEscape(value: String) = value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")
                                file.writeText("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:${vcfEscape(name)}\r\nTEL;TYPE=CELL:${vcfEscape(number)}\r\nEND:VCARD\r\n")
                                val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
                                if (!selectedUris.contains(uri)) selectedUris.add(uri)
                                Toast.makeText(this@MainActivity, "Contact card added to Send.", Toast.LENGTH_SHORT).show()
                                activeTab = "Send"
                                render()
                            } catch (e: Exception) {
                                updateStatus("Could not prepare this contact: " + (e.localizedMessage ?: "unknown error"))
                            }
                        }
                    }
                    row.addView(TextView(this).apply {
                        text = name; textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(ink)
                    })
                    row.addView(TextView(this).apply {
                        text = number; textSize = 12f; setTextColor(muted); setPadding(0, dp(3), 0, 0)
                    })
                    list.addView(row, bottom(6))
                }
            }
        } catch (e: Exception) {
            list.addView(TextView(this).apply { text = "Could not read contacts: " + (e.localizedMessage ?: "unknown error"); setTextColor(muted) })
        }
        if (count == 0) list.addView(TextView(this).apply { text = "No phone contacts found."; textSize = 12f; setTextColor(muted); setPadding(0, dp(12), 0, dp(12)) })
    }

    private fun showSettings(card: Int, ink: Int, muted: Int, blue: Int) {
        content.addView(TextView(this).apply { text = "Preferences"; textSize = 24f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(ink); setPadding(dp(2), dp(8), dp(2), dp(12)) })
        val appearance = panel(card); appearance.addView(label("APPEARANCE", muted))
        appearance.addView(button(if (darkMode) "Switch to light theme" else "Switch to dark theme", blue) { darkMode = !darkMode; getSharedPreferences("nexora_preferences", MODE_PRIVATE).edit().putBoolean("dark_mode", darkMode).apply(); render() })
        content.addView(appearance, bottom(10))
        val prefs = panel(card); prefs.addView(label("TRANSFER & STORAGE", muted))
        listOf("Save location\nDownload/NEXORA", "Connection\nNearby Connections", "Transfer mode\nDirect device-to-device", "Mobile data\nNot required for local transfers", "Privacy\nFiles are selected by you").forEach { value ->
            prefs.addView(TextView(this).apply { text = value; textSize = 12f; setTextColor(ink); setPadding(0, dp(10), 0, dp(4)) })
        }
        content.addView(prefs, bottom(10))
        val currentName = getSharedPreferences("nexora_preferences", MODE_PRIVATE).getString("device_name", Build.MODEL) ?: "Android device"
        content.addView(button("Device name: " + currentName + "  ·  Change", blue) {
            val field = EditText(this@MainActivity).apply { setText(currentName); setSelection(text.length); hint = "Phone name" }
            android.app.AlertDialog.Builder(this@MainActivity).setTitle("Set NEXORA device name").setView(field)
                .setPositiveButton("Save") { _, _ ->
                    val value = field.text.toString().trim()
                    if (value.isNotBlank()) {
                        getSharedPreferences("nexora_preferences", MODE_PRIVATE).edit().putString("device_name", value.take(24)).apply()
                        Toast.makeText(this@MainActivity, "Device name saved. Restart discovery to use it.", Toast.LENGTH_LONG).show()
                        render()
                    }
                }.setNegativeButton("Cancel", null).show()
        }, bottom(10))
        val versionName = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "Unknown" } catch (_: Exception) { "Unknown" }
        content.addView(TextView(this).apply { text = "NEXORA • Version " + versionName + "\nAdvanced features depend on Android permissions and device support."; textSize = 11f; setTextColor(muted); gravity = Gravity.CENTER; setPadding(dp(8), dp(12), dp(8), dp(12)) })
    }

    private fun animatePress(view: View) {
        view.animate().cancel()
        view.scaleX = 1f; view.scaleY = 1f
        view.animate().scaleX(.97f).scaleY(.97f).setDuration(75).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(135).setInterpolator(android.view.animation.OvershootInterpolator()).start()
        }.start()
    }

    private inner class RadarView : View(this@MainActivity) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private var phase = 0f
        private var animator: android.animation.ValueAnimator? = null
        init { startPulse() }
        fun startPulse() {
            if (animator?.isRunning == true) return
            animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2300; repeatCount = android.animation.ValueAnimator.INFINITE
                addUpdateListener { phase = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f; val cy = height / 2f
            val max = dp(68).toFloat()
            for (i in 0..2) {
                val p = (phase + i / 3f) % 1f
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = dp(1).toFloat()
                paint.color = android.graphics.Color.argb(((1f - p) * 105).toInt(), 91, 224, 255)
                canvas.drawCircle(cx, cy, max * (.25f + .75f * p), paint)
            }
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = 0xFF35D8F4.toInt()
            canvas.drawCircle(cx, cy, dp(7).toFloat(), paint)
            paint.color = 0x5535D8F4
            canvas.drawCircle(cx, cy, dp(17).toFloat(), paint)
            paint.color = -1
            paint.textSize = dp(10).toFloat()
            paint.textAlign = android.graphics.Paint.Align.CENTER
            canvas.drawText("NEXORA", cx, cy + dp(92), paint)
        }
    }

    private fun actionTile(symbol: String, title: String, color: Int, action: () -> Unit): LinearLayout {
        val tile = panel(if (darkMode) 0xFF142A40.toInt() else -1)
        tile.gravity = Gravity.CENTER
        tile.background = rounded(if (darkMode) 0xFF142A40.toInt() else -1, 20)
        tile.addView(TextView(this).apply {
            text = symbol; textSize = 29f; gravity = Gravity.CENTER; setTextColor(color)
            background = rounded(if (darkMode) 0xFF203D56.toInt() else 0xFFEAF4FC.toInt(), 16)
            setPadding(dp(10), dp(2), dp(10), dp(2))
        })
        tile.addView(TextView(this).apply {
            text = title; textSize = 11f; gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(if (darkMode) -1 else 0xFF142B43.toInt()); setPadding(0, dp(9), 0, 0)
        })
        tile.setOnClickListener { animatePress(tile); action() }
        return tile
    }

    private fun panel(color: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
        background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            if (darkMode) intArrayOf(0xFF19334A.toInt(), 0xFF10243A.toInt()) else intArrayOf(0xFFFFFFFF.toInt(), 0xFFF8FBFF.toInt())
        ).apply { cornerRadius = dp(21).toFloat(); setStroke(dp(1), if (darkMode) 0x3348BFE5 else 0xFFE3EDF5.toInt()) }
        elevation = dp(3).toFloat()
    }
    private fun label(value: String, color: Int) = TextView(this).apply { text = value; textSize = 10f; letterSpacing = .1f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(color) }
    private fun button(value: String, color: Int, action: () -> Unit) = Button(this).apply {
        text = value; textSize = 12f; isAllCaps = false; typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(-1); background = rounded(color, 15); setPadding(dp(10), dp(9), dp(10), dp(9))
        stateListAnimator = null
        setOnClickListener { animatePress(this); action() }
    }
    private fun rounded(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun bottom(margin: Int) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(margin) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private var receiveModeLabel: TextView? = null
    private var transferStatusView: TextView? = null
    private var transferProgressBar: android.widget.ProgressBar? = null
    private var transferStage = 0
    private val transferStepViews = ArrayList<TextView>()
    private fun updateStatus(message: String) {
        latestStatus = message
        if (::status.isInitialized) status.text = message
        transferStatusView?.text = message
        receiveModeLabel?.text = if (sharing?.isAdvertising == true) "●  RECEIVING MODE ACTIVE" else "○  RECEIVE MODE READY"
        val lower = message.lowercase()
        transferStage = when {
            lower.contains("queue finished") || lower.contains("all selected files") || lower.startsWith("sent:") || lower.contains("file sent") || lower.contains("received and saved") -> 5
            lower.contains("sending") || lower.contains("receiving") -> 4
            lower.contains("connected") || lower.contains("requesting connection") -> 3
            lower.contains("searching") || lower.contains("found ") || lower.contains("visible") -> 2
            lower.contains("queued") || lower.contains("selected") -> 1
            else -> transferStage
        }
        val colors = intArrayOf(0xFF7357E8.toInt(), 0xFF168FE5.toInt(), 0xFFE69A35.toInt(), 0xFFE05C8A.toInt(), 0xFF16A58A.toInt())
        transferStepViews.forEachIndexed { index, view ->
            view.background = rounded(if (index < transferStage) colors[index] else if (darkMode) 0xFF29445B.toInt() else 0xFFE8EEF5.toInt(), 10)
            view.setTextColor(if (index < transferStage) -1 else if (darkMode) 0xFFB1C6D8.toInt() else 0xFF63788C.toInt())
        }
        val percent = Regex("(\\d{1,3})%").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
        transferProgressBar?.let { bar ->
            when {
                percent != null -> {
                    bar.isIndeterminate = false
                    android.animation.ObjectAnimator.ofInt(bar, "progress", bar.progress, percent.coerceIn(0, 100)).apply { duration = 240; start() }
                }
                lower.contains("sending") || lower.contains("receiving") -> bar.isIndeterminate = true
                transferStage >= 5 -> { bar.isIndeterminate = false; bar.progress = 100 }
                lower.contains("searching") || lower.contains("queued") -> { bar.isIndeterminate = false; bar.progress = 0 }
            }
        }
    }
    private fun rebuildPeerList() {
        if (!::peerList.isInitialized) return
        peerList.removeAllViews()
        if (discovered.isEmpty() && connectedPeers.isEmpty()) {
            peerList.addView(TextView(this).apply { text = "No nearby phones yet. Tap Find nearby phones."; textSize = 11f; setTextColor(if (darkMode) 0xFFB3C0D5.toInt() else 0xFF64748B.toInt()); setPadding(0, dp(8), 0, dp(8)) })
        }
        discovered.forEach { (id, name) ->
            if (connectedPeers.contains(id)) {
                peerList.addView(TextView(this).apply {
                    text = "✓  CONNECTED  ·  " + name
                    textSize = 12f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(0xFF16A58A.toInt())
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    background = rounded(if (darkMode) 0xFF173C3B.toInt() else 0xFFE7F7F1.toInt(), 12)
                }, bottom(6))
            } else {
                peerList.addView(button("Connect to $name", 0xFF16A085.toInt()) { sharing?.connect(id, name) }, bottom(6))
            }
        }
        if (connectedPeers.isNotEmpty()) {
            peerList.addView(TextView(this).apply {
                text = connectedPeers.size.toString() + " phone(s) connected · group sharing ready"
                textSize = 11f
                setTextColor(if (darkMode) 0xFF8FE4C5.toInt() else 0xFF168A70.toInt())
                setPadding(dp(4), dp(5), dp(4), dp(3))
            })
        }
    }

    private fun withNearbyPermissions(action: () -> Unit) {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.NEARBY_WIFI_DEVICES
        if (Build.VERSION.SDK_INT >= 31) { needed += Manifest.permission.BLUETOOTH_SCAN; needed += Manifest.permission.BLUETOOTH_CONNECT; needed += Manifest.permission.BLUETOOTH_ADVERTISE }
        else if (Build.VERSION.SDK_INT >= 29) needed += Manifest.permission.ACCESS_FINE_LOCATION
        else needed += Manifest.permission.ACCESS_COARSE_LOCATION
        val missing = needed.distinct().filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else {
            pendingNearbyAction = action
            requestPermissions(missing.toTypedArray(), REQUEST_NEARBY)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MEDIA) {
            val kind = mediaCategory
            if (kind != null && hasMediaPermission(kind)) { mediaErrors.remove(kind); mediaCache.remove(kind); render() }
            else updateStatus("Allow photo or media access so NEXORA can show your files here.")
            return
        }
        if (requestCode == 303) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) render()
            else updateStatus("Contact access was not granted.")
            return
        }
        if (requestCode == REQUEST_NEARBY) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                val resume = pendingNearbyAction
                pendingNearbyAction = null
                if (resume != null) resume()
                else Toast.makeText(this, "Permissions granted.", Toast.LENGTH_SHORT).show()
            } else {
                pendingNearbyAction = null
                updateStatus("Nearby permissions are needed to connect to phones.")
            }
        }
    }

    private fun openPickerTypes(types: Array<String>) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, types)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { startActivityForResult(intent, REQUEST_FILE) } catch (_: Exception) {
            updateStatus("No file picker is available on this phone.")
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
        if (requestCode == com.google.zxing.integration.android.IntentIntegrator.REQUEST_CODE) {
            val scan = com.google.zxing.integration.android.IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
            val contents = scan?.contents
            if (!contents.isNullOrBlank()) {
                val parts = contents.split("|", limit = 2)
                if (parts.size == 2 && parts[0] == "NEXORA" && parts[1].isNotBlank()) {
                    pendingQrDeviceName = parts[1]
                    withNearbyPermissions {
                        ContextCompat.startForegroundService(this, Intent(this, TransferKeepAliveService::class.java))
                        discovered.clear()
                        sharing?.startFinding() ?: updateStatus("Starting discovery. Scan the QR again in a moment.")
                        updateStatus("QR accepted. Looking for " + parts[1] + " nearby…")
                    }
                } else Toast.makeText(this, "That is not a NEXORA connection code.", Toast.LENGTH_LONG).show()
            }
        } else if (requestCode == REQUEST_TREE && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            try {
                val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                contentResolver.takePersistableUriPermission(uri, flags)
                selectedTreeUri = uri
                getSharedPreferences("nexora_file_manager", MODE_PRIVATE).edit().putString("tree_uri", uri.toString()).apply()
                currentTreeDocumentId = android.provider.DocumentsContract.getTreeDocumentId(uri)
                treeDocumentStack.clear()
                activeTab = "Files"
                render()
            } catch (e: Exception) { updateStatus("Folder access was not saved: " + (e.localizedMessage ?: "unknown error")) }
        } else if (requestCode == REQUEST_FILE && resultCode == RESULT_OK && data != null) {
            val chosen = LinkedHashSet<Uri>()
            val grantFlags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            data.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) {
                    val uri = clip.getItemAt(i).uri
                    chosen.add(uri)
                    runCatching { contentResolver.takePersistableUriPermission(uri, grantFlags) }
                }
            }
            data.data?.let { uri ->
                chosen.add(uri)
                runCatching { contentResolver.takePersistableUriPermission(uri, grantFlags) }
            }
            selectedUris.clear(); selectedUris.addAll(chosen)
            if (selectedUris.isEmpty()) updateStatus("No files selected.")
            else {
                activeTab = "Send"; render()
                updateStatus("${selectedUris.size} file(s) selected. Find a nearby phone or connect by QR.")
            }
        }
    }

    private fun refreshReceivedFiles() {
        if (!::receivedList.isInitialized || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        receivedList.removeAllViews()
        try {
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED)
            contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, MediaStore.MediaColumns.RELATIVE_PATH + " = ?", arrayOf("Download/NEXORA/"), MediaStore.MediaColumns.DATE_ADDED + " DESC")?.use { cursor ->
                if (cursor.count == 0) addReceivedEmpty()
                var previousDay = ""
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0); val name = cursor.getString(1) ?: "Received file"
                    val addedAt = cursor.getLong(4)
                    val day = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(addedAt * 1000L))
                    if (day != previousDay) {
                        previousDay = day
                        receivedList.addView(TextView(this).apply { text = day; textSize = 12f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(if (darkMode) 0xFF8FE0C3.toInt() else 0xFF16856A.toInt()); setPadding(dp(3), dp(12), dp(3), dp(6)) })
                    }
                    val mime = cursor.getString(2) ?: "*/*"; val size = readableSize(cursor.getLong(3))
                    val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        background = rounded(if (darkMode) 0xFF20364E.toInt() else 0xFFF1F6FB.toInt(), 14)
                    }
                    row.addView(TextView(this).apply { text = name + "\n" + size; textSize = 12f; setTextColor(if (darkMode) -1 else 0xFF14213D.toInt()) })
                    val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    val isAppBundleBase = name.startsWith("NEXORA_APP_") && name.endsWith("_BASE.apk", true)
                    val isAppBundleSplit = name.startsWith("NEXORA_APP_") && name.contains("_SPLIT_") && name.endsWith(".apk", true)
                    val openLabel = if (isAppBundleBase) "Install app" else if (isAppBundleSplit) "App part" else "Open"
                    actions.addView(button(openLabel, 0xFF4263EB.toInt()) {
                        try {
                            val isApk = mime == "application/vnd.android.package-archive" || name.endsWith(".apk", ignoreCase = true)
                            if (isAppBundleBase) {
                                installReceivedApkGroup(name.removeSuffix("_BASE.apk") + "_")
                            } else if (isAppBundleSplit) {
                                Toast.makeText(this@MainActivity, "This is one part of an app. Tap Install app on its BASE file.", Toast.LENGTH_LONG).show()
                            } else if (isApk) {
                                if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + packageName)))
                                    Toast.makeText(this@MainActivity, "Allow NEXORA to install apps, then tap Open again.", Toast.LENGTH_LONG).show()
                                } else {
                                    val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
                                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    startActivity(intent)
                                }
                            } else {
                                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                startActivity(intent)
                            }
                        } catch (_: Exception) { Toast.makeText(this@MainActivity, "Android could not open this file.", Toast.LENGTH_LONG).show() }
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(4) })
                    actions.addView(button("Rename", 0xFF64748B.toInt()) {
                        val field = EditText(this@MainActivity).apply { setText(name); setSelection(text.length) }
                        android.app.AlertDialog.Builder(this@MainActivity).setTitle("Rename file").setView(field)
                            .setPositiveButton("Save") { _, _ ->
                                val newName = field.text.toString().trim()
                                if (newName.isNotBlank()) {
                                    val values = android.content.ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
                                    contentResolver.update(uri, values, null, null)
                                    refreshReceivedFiles()
                                }
                            }.setNegativeButton("Cancel", null).show()
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
                    actions.addView(button("Delete", 0xFFB42318.toInt()) {
                        android.app.AlertDialog.Builder(this@MainActivity).setTitle("Delete this file?")
                            .setMessage(name).setPositiveButton("Delete") { _, _ -> contentResolver.delete(uri, null, null); refreshReceivedFiles() }
                            .setNegativeButton("Cancel", null).show()
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })
                    row.addView(actions)
                    receivedList.addView(row, bottom(6))
                }
            } ?: addReceivedEmpty()
        } catch (_: Exception) { addReceivedEmpty() }
    }

    private fun addReceivedEmpty() {
        if (!::receivedList.isInitialized) return
        receivedList.addView(TextView(this).apply { text = "Received files will appear here when saved."; textSize = 12f; setTextColor(if (darkMode) 0xFFB3C0D5.toInt() else 0xFF64748B.toInt()); setPadding(0, dp(8), 0, dp(8)) })
    }

    companion object { private const val REQUEST_NEARBY = 2001; private const val REQUEST_FILE = 2002; private const val REQUEST_MEDIA = 2003; private const val REQUEST_TREE = 2004 }
}
