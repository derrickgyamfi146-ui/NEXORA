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
    private var incomingStatusView: TextView? = null
    private var incomingQueueView: LinearLayout? = null
    private var incomingQueueItems: List<IncomingQueueItem> = emptyList()
    private var latestIncomingProgress = "No active incoming transfers."
    private lateinit var sendButton: Button
    private val selectedUris = ArrayList<Uri>()
    private val discovered = LinkedHashMap<String, String>()
    private val connectedPeers = LinkedHashSet<String>()
    private var pendingQrDeviceName: String? = null
    private var receivingModeArmed = false
    private var pendingAutoConnectEndpointId: String? = null
    private var pendingWifiDirectAction: (() -> Unit)? = null
    private var sharing: TransferKeepAliveService? = null
    private var bound = false
    private var binding = false
    private var activeTab = "Media"
    private var latestStatus = "Ready. Choose Send or Receive to begin."
    private var darkMode = false
    private var mediaCategory: String? = null
    private var pendingSelectedSend = false
    private var pendingConnectionSendUris: List<Uri>? = null
    private var pendingServiceAction: ((TransferKeepAliveService) -> Unit)? = null
    private var appSelectionButton: Button? = null
    private var selectionSendButton: TextView? = null
    private var pendingCameraScan = false
    private var connectionArrivalAnimationRunning = false
    private var connectionSessionAnimationShown = false
    private var preparingApps = false
    private var launchSplashVisible = true
    private val appIconCache = java.util.concurrent.ConcurrentHashMap<String, android.graphics.drawable.Drawable>()
    private lateinit var mediaList: LinearLayout
    private var mediaContinueButton: Button? = null
    private val mediaRows = ArrayList<Triple<Uri, String, Long>>()
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
            binding = false
            bound = sharing != null
            sharing?.setListener(serviceListener)
            incomingQueueItems = sharing?.incomingQueueSnapshot().orEmpty()
            connectedPeers.clear()
            connectedPeers.addAll(sharing?.connectedEndpointIds().orEmpty())
            if (::sendButton.isInitialized) sendButton.isEnabled = true
            if (::root.isInitialized) render()
            val pending = pendingServiceAction
            pendingServiceAction = null
            if (pending != null && sharing != null) pending(sharing!!)
        }
        override fun onServiceDisconnected(name: ComponentName?) { sharing = null; bound = false; binding = false }
    }

    private val serviceListener = object : TransferKeepAliveService.Listener {
        override fun onStatus(message: String) {
            runOnUiThread {
                if (message.startsWith("Sent: ")) rememberSentFileName(message.removePrefix("Sent: ").trim())
                if (message.startsWith("Receiving ") || message.startsWith("Paused:")) {
                    latestIncomingProgress = message
                    incomingStatusView?.text = message
                }
                updateStatus(message)
                showWifiDirectFailureHelp(message)
                if (message.startsWith("Transfer queue finished:")) {
                    java.io.File(cacheDir, "shared_apks").listFiles()?.forEach { it.delete() }
                }
                if (message.startsWith("Received and saved:")) {
                    rememberReceivedFileName(message.removePrefix("Received and saved:").trim())
                    latestIncomingProgress = "No active incoming transfers."
                    incomingStatusView?.text = latestIncomingProgress
                    refreshReceivedFiles()
                }
                if (message.startsWith("Incoming transfer cancelled:")) {
                    latestIncomingProgress = "Transfer cancelled. The connection is still available."
                    incomingStatusView?.text = latestIncomingProgress
                }
                if (message == "All files received successfully.") {
                    // Connection animation belongs to the connection event only.
                    // A completed file batch must never replay the connection rocket.
                    refreshReceivedFiles()
                }
            }
        }
        override fun onPeerFound(endpointId: String, name: String) {
            runOnUiThread {
                discovered[endpointId] = name
                if (::peerList.isInitialized) rebuildPeerList()
                val wanted = pendingQrDeviceName
                if (wanted != null && name.trim().equals(wanted.trim(), ignoreCase = true)) {
                    pendingQrDeviceName = null
                    pendingAutoConnectEndpointId = endpointId
                    updateStatus("QR matched $name. Requesting a secure connection…")
                    sharing?.connect(endpointId, name)
                } else if (pendingSelectedSend && connectedPeers.isEmpty() && pendingAutoConnectEndpointId == null) {
                    pendingAutoConnectEndpointId = endpointId
                    updateStatus("Found $name. Requesting connection…")
                    sharing?.connect(endpointId, name)
                }
            }
        }
        override fun onPeerLost(endpointId: String) {
            runOnUiThread { discovered.remove(endpointId); if (::peerList.isInitialized) rebuildPeerList() }
        }
        override fun onConnectionRequest(endpointId: String, name: String, digits: String) {
            runOnUiThread {
                // Both sides have already deliberately entered Send or Receive mode.
                // Accept this paired request on both phones so neither waits behind a hidden dialog.
                if (pendingSelectedSend || receivingModeArmed || pendingQrDeviceName != null || pendingAutoConnectEndpointId != null) {
                    updateStatus("Confirming secure connection with $name…")
                    sharing?.accept(endpointId)
                } else {
                    android.app.AlertDialog.Builder(this@MainActivity).setTitle("Connect to $name?")
                        .setMessage("Compare this security code on both phones:\n\n$digits")
                        .setPositiveButton("Accept") { _, _ -> sharing?.accept(endpointId) }
                        .setNegativeButton("Reject") { _, _ -> sharing?.reject(endpointId) }
                        .setCancelable(false).show()
                }
            }
        }
        override fun onConnected(endpointId: String, name: String, isReceiver: Boolean) {
            runOnUiThread {
                discovered[endpointId] = name
                connectedPeers.add(endpointId)
                pendingAutoConnectEndpointId = null
                receivingModeArmed = false
                if (::peerList.isInitialized) rebuildPeerList()
                if (::sendButton.isInitialized) sendButton.isEnabled = true
                if (pendingSelectedSend && selectedUris.isNotEmpty() && pendingConnectionSendUris == null) {
                    pendingSelectedSend = false
                    pendingConnectionSendUris = ArrayList(LinkedHashSet(selectedUris))
                    selectedUris.clear()
                    selectedAppPackages.clear()
                    updateSelectionAction()
                }
                if (!connectionSessionAnimationShown && !connectionArrivalAnimationRunning) {
                    connectionSessionAnimationShown = true
                    connectionArrivalAnimationRunning = true
                    updateStatus("Connected successfully.")
                    val finishArrival = {
                        connectionArrivalAnimationRunning = false
                        activeTab = "Media"
                        mediaCategory = "Apps"
                        render()
                        val outgoing = pendingConnectionSendUris
                        pendingConnectionSendUris = null
                        if (!outgoing.isNullOrEmpty() && sharing?.isConnected == true) {
                            rememberOutgoingUris(outgoing)
                            sendFilesWithRecipientSelection(outgoing)
                            updateStatus("Connected. Sending your selected files…")
                            if (::root.isInitialized) render()
                        }
                    }
                    if (isReceiver) {
                        // The receiver alone gets the upright connection rocket.
                        showRocketConnectAnimation { finishArrival() }
                    } else {
                        // Keep both phones on the connection screen for the same
                        // animation window, without showing a second rocket.
                        android.os.Handler(mainLooper).postDelayed({ finishArrival() }, 2450L)
                    }
                }
            }
        }
        override fun onDisconnected(endpointId: String) {
            runOnUiThread {
                connectedPeers.remove(endpointId)
                if (sharing?.connectedEndpointIds()?.isEmpty() != false && connectedPeers.isEmpty()) {
                    connectionSessionAnimationShown = false
                    pendingAutoConnectEndpointId = null
                    pendingSelectedSend = false
                    pendingConnectionSendUris = null
                    selectedUris.clear()
                    selectedAppPackages.clear()
                    updateSelectionAction()
                }
                if (::peerList.isInitialized) rebuildPeerList()
                if (::sendButton.isInitialized) sendButton.isEnabled = true
                if (::root.isInitialized) render()
            }
        }
        override fun onTransferProgress(message: String) {
            runOnUiThread {
                updateStatus(message)
                if (message.startsWith("Receiving ") || message.startsWith("Paused:")) {
                    latestIncomingProgress = message
                    incomingStatusView?.text = message
                }
            }
        }
        override fun onQrReady(payload: String) { runOnUiThread { showQrCode(payload) } }
        override fun onIncomingQueueChanged(items: List<IncomingQueueItem>) {
            runOnUiThread {
                incomingQueueItems = items
                renderIncomingQueue()
            }
        }
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
                intArrayOf(0xFF07142E.toInt(), 0xFF0B4FB3.toInt(), 0xFF172B48.toInt(), 0xFF7A1F36.toInt())
            )
        }

        val logoStage = android.widget.FrameLayout(this)
        val outerRing = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x00102030)
                setStroke(dp(2), 0xFFFFD21F.toInt())
            }
            alpha = 0f
            scaleX = .72f
            scaleY = .72f
        }
        logoStage.addView(outerRing, android.widget.FrameLayout.LayoutParams(dp(218), dp(218), Gravity.CENTER))

        val orbit = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFFFFD21F.toInt())
                setStroke(dp(2), 0xFFFFF0B0.toInt())
            }
            elevation = dp(14).toFloat()
        }
        val orbitTrack = android.widget.FrameLayout(this)
        orbitTrack.addView(orbit, android.widget.FrameLayout.LayoutParams(dp(13), dp(13), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(4)
        })
        logoStage.addView(orbitTrack, android.widget.FrameLayout.LayoutParams(dp(230), dp(230), Gravity.CENTER))

        val glow = TextView(this).apply {
            text = "N"
            textSize = 72f
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(0xFFFFD66B.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x443B67B5)
                setStroke(dp(2), 0xAAFFD66B.toInt())
            }
            alpha = 0f
            scaleX = .55f
            scaleY = .55f
            elevation = dp(9).toFloat()
        }
        logoStage.addView(glow, android.widget.FrameLayout.LayoutParams(dp(150), dp(150), Gravity.CENTER))

        val sparkleViews = listOf(
            Triple("✦", Gravity.TOP or Gravity.START, 0xFFFFD66B.toInt()),
            Triple("·", Gravity.BOTTOM or Gravity.END, 0xFFE52B3A.toInt()),
            Triple("✧", Gravity.TOP or Gravity.END, 0xFFFFFFFF.toInt()),
            Triple("·", Gravity.BOTTOM or Gravity.START, 0xFFFFD66B.toInt())
        ).mapIndexed { index, item ->
            TextView(this).apply {
                text = item.first
                textSize = if (index == 0 || index == 2) 22f else 28f
                setTextColor(item.third)
                alpha = 0f
                scaleX = .3f
                scaleY = .3f
                logoStage.addView(this, android.widget.FrameLayout.LayoutParams(dp(34), dp(34), item.second).apply {
                    leftMargin = if (index == 0 || index == 3) dp(22) else 0
                    rightMargin = if (index == 1 || index == 2) dp(22) else 0
                    topMargin = if (index == 0 || index == 2) dp(25) else 0
                    bottomMargin = if (index == 1 || index == 3) dp(25) else 0
                })
            }
        }
        screen.addView(logoStage, LinearLayout.LayoutParams(dp(250), dp(250)).apply {
            bottomMargin = dp(8)
        })

        val letters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val letterViews = "NEXORA".mapIndexed { index, char ->
            TextView(this).apply {
                text = char.toString()
                gravity = Gravity.CENTER
                textSize = 38f
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                setTextColor(if (index == 2) 0xFFFFD66B.toInt() else -1)
                alpha = 0f
                translationY = -dp(35).toFloat()
                scaleX = .82f
                scaleY = .82f
            }.also { letters.addView(it, LinearLayout.LayoutParams(-2, dp(58)).apply {
                leftMargin = dp(1)
                rightMargin = dp(1)
            }) }
        }
        screen.addView(letters, LinearLayout.LayoutParams(-1, dp(64)))

        val subtitle = TextView(this).apply {
            text = "SHARE WITHOUT LIMITS"
            textSize = 10f
            letterSpacing = .28f
            gravity = Gravity.CENTER
            setTextColor(0xFFC6E8E8.toInt())
            alpha = 0f
            setPadding(0, dp(8), 0, dp(13))
        }
        screen.addView(subtitle)

        val featureLine = TextView(this).apply {
            text = "FAST  •  SIMPLE  •  DIRECT"
            textSize = 9f
            letterSpacing = .16f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFD66B.toInt())
            alpha = 0f
            background = rounded(0x33FFD21F, 18)
            setPadding(dp(17), dp(9), dp(17), dp(9))
        }
        screen.addView(featureLine)

        val footer = TextView(this).apply {
            text = "GETTING EVERYTHING READY…"
            textSize = 9f
            letterSpacing = .12f
            gravity = Gravity.CENTER
            setTextColor(0x88FFFFFF.toInt())
            alpha = 0f
        }
        screen.addView(footer, LinearLayout.LayoutParams(-1, dp(48)).apply {
            topMargin = dp(34)
        })

        setContentView(screen)
        val animators = ArrayList<android.animation.Animator>()
        animators.add(android.animation.ObjectAnimator.ofFloat(outerRing, "alpha", 0f, .85f).apply { duration = 550; startDelay = 100 })
        animators.add(android.animation.ObjectAnimator.ofFloat(outerRing, "scaleX", .72f, 1.08f, 1f).apply { duration = 1050; startDelay = 100; interpolator = android.view.animation.OvershootInterpolator() })
        animators.add(android.animation.ObjectAnimator.ofFloat(outerRing, "scaleY", .72f, 1.08f, 1f).apply { duration = 1050; startDelay = 100; interpolator = android.view.animation.OvershootInterpolator() })
        animators.add(android.animation.ObjectAnimator.ofFloat(orbitTrack, "rotation", 0f, 360f).apply { duration = 2100; interpolator = android.view.animation.LinearInterpolator() })
        animators.add(android.animation.ObjectAnimator.ofFloat(glow, "alpha", 0f, 1f, .78f, 1f).apply { duration = 1250; startDelay = 120 })
        animators.add(android.animation.ObjectAnimator.ofFloat(glow, "scaleX", .55f, 1.08f, 1f).apply { duration = 1050; startDelay = 120; interpolator = android.view.animation.OvershootInterpolator() })
        animators.add(android.animation.ObjectAnimator.ofFloat(glow, "scaleY", .55f, 1.08f, 1f).apply { duration = 1050; startDelay = 120; interpolator = android.view.animation.OvershootInterpolator() })
        letterViews.forEachIndexed { index, view ->
            animators.add(android.animation.ObjectAnimator.ofFloat(view, "translationY", -dp(35).toFloat(), 0f).apply {
                duration = 430
                startDelay = 600L + index * 65L
                interpolator = android.view.animation.DecelerateInterpolator()
            })
            animators.add(android.animation.ObjectAnimator.ofFloat(view, "alpha", 0f, 1f).apply {
                duration = 300
                startDelay = 600L + index * 65L
            })
            animators.add(android.animation.ObjectAnimator.ofFloat(view, "scaleX", .82f, 1f).apply { duration = 430; startDelay = 600L + index * 65L; interpolator = android.view.animation.OvershootInterpolator() })
            animators.add(android.animation.ObjectAnimator.ofFloat(view, "scaleY", .82f, 1f).apply { duration = 430; startDelay = 600L + index * 65L; interpolator = android.view.animation.OvershootInterpolator() })
        }
        sparkleViews.forEachIndexed { index, view ->
            animators.add(android.animation.ObjectAnimator.ofFloat(view, "alpha", 0f, 1f, .55f).apply { duration = 900; startDelay = 350L + index * 150L })
            animators.add(android.animation.ObjectAnimator.ofFloat(view, "scaleX", .3f, 1.15f, .85f).apply { duration = 900; startDelay = 350L + index * 150L })
            animators.add(android.animation.ObjectAnimator.ofFloat(view, "scaleY", .3f, 1.15f, .85f).apply { duration = 900; startDelay = 350L + index * 150L })
        }
        animators.add(android.animation.ObjectAnimator.ofFloat(subtitle, "alpha", 0f, 1f).apply { duration = 500; startDelay = 1050 })
        animators.add(android.animation.ObjectAnimator.ofFloat(featureLine, "alpha", 0f, 1f).apply { duration = 500; startDelay = 1350 })
        animators.add(android.animation.ObjectAnimator.ofFloat(footer, "alpha", 0f, .7f).apply { duration = 450; startDelay = 1550 })
        android.animation.AnimatorSet().apply {
            playTogether(animators)
            start()
        }

        android.os.Handler(mainLooper).postDelayed({
            if (!isFinishing && !isDestroyed) {
                launchSplashVisible = false
                render()
                refreshReceivedFiles()
                root.alpha = 0f
                root.scaleX = .985f
                root.scaleY = .985f
                root.translationY = dp(10).toFloat()
                root.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(320)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        }, 5000L)
    }

    private fun showExitConfirmation() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Exit NEXORA?")
            .setMessage("Do you really want to leave NEXORA?")
            .setNegativeButton("Stay", null)
            .setPositiveButton("Continue") { _, _ ->
                android.app.AlertDialog.Builder(this)
                    .setTitle("Are you sure?")
                    .setMessage("Close the app now?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Exit") { _, _ -> finish() }
                    .show()
            }
            .show()
    }

    override fun onBackPressed() {
        if (sharing?.isConnected == true || connectedPeers.isNotEmpty()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Disconnect and leave?")
                .setMessage("Your Wi-Fi Direct transfer will end if you leave NEXORA.")
                .setNegativeButton("Stay connected", null)
                .setPositiveButton("Disconnect and leave") { _, _ ->
                    sharing?.stopSharing()
                    connectedPeers.clear()
                    finish()
                }.show()
            return
        }
        when {
            activeTab == "Settings" || activeTab == "Transfers" || activeTab == "Receive" || activeTab == "Files" || activeTab == "Sent" || activeTab == "Received" || activeTab == "History" || activeTab == "Contacts" -> {
                activeTab = "Media"; mediaCategory = "Apps"; render()
            }
            activeTab == "Media" && mediaCategory != "Apps" -> { mediaCategory = "Apps"; render() }
            activeTab == "Media" -> showExitConfirmation()
            else -> showExitConfirmation()
        }
    }

    override fun onDestroy() {
        if (bound || binding) {
            sharing?.setListener(null)
            runCatching { unbindService(serviceConnection) }
        }
        sharing = null
        bound = false
        binding = false
        thumbnailExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        bindSharingServiceIfNeeded()
    }

    private fun bindSharingServiceIfNeeded() {
        if (bound || binding) return
        binding = bindService(Intent(this, TransferKeepAliveService::class.java), serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        // Keep the service binding across the QR scanner and other temporary
        // activities. Rebinding here rebuilt the whole screen and caused flicker.
        super.onStop()
    }

    private fun render() {
        receiveModeLabel = null
        mediaContinueButton = null
        transferStatusView = null
        transferProgressBar = null
        incomingStatusView = null
        transferStepViews.clear()
        val page = if (darkMode) 0xFF071526.toInt() else 0xFFF3F7FC.toInt()
        val card = if (darkMode) 0xFF142A40.toInt() else -1
        val ink = if (darkMode) -1 else 0xFF10243B.toInt()
        val muted = if (darkMode) 0xFFB1C6D8.toInt() else 0xFF63788C.toInt()
        val blue = 0xFF16BFA4.toInt()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                if (darkMode) intArrayOf(0xFF07142E.toInt(), 0xFF102C58.toInt(), 0xFF3D1B32.toInt()) else intArrayOf(0xFFFFF4C2.toInt(), 0xFFFFE7E5.toInt(), 0xFFE6F0FF.toInt())
            )
        }
        val libraryMode = activeTab in setOf("Media", "Files", "Sent", "Received", "Contacts")
        val connectedNow = connectedPeers.isNotEmpty() || sharing?.isConnected == true
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF0B4FB3.toInt(), 0xFF173A78.toInt(), 0xFF9F2436.toInt())).apply { setStroke(dp(1), 0xFFFFD21F.toInt()) }
        }
        val menuButton = TextView(this).apply {
            text = "☰"; textSize = 23f; gravity = Gravity.CENTER; setTextColor(-1)
            contentDescription = "Open menu"
            setOnClickListener { animatePress(this); showXenderMenu() }
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
                setBackgroundColor(if (darkMode) 0xFF0B1B38.toInt() else 0xFFFFD21F.toInt())
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(8), dp(5), dp(8), dp(5))
            }
            listOf("History", "Sent", "Received", "Apps", "Photos", "Videos", "Music", "Files", "Contacts").forEach { tab ->
                val selected = when (tab) {
                    "History" -> activeTab == "History"
                    "Sent" -> activeTab == "Sent"
                    "Received" -> activeTab == "Received"
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
                    if (selected) background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF0B4FB3.toInt(), 0xFFE52B3A.toInt())).apply { cornerRadius = dp(18).toFloat(); setStroke(dp(1), 0xFFFFD21F.toInt()) }
                }
                chip.setOnClickListener {
                    animatePress(chip)
                    when (tab) {
                        "History" -> { activeTab = "History"; render() }
                        "Sent" -> { activeTab = "Sent"; render() }
                        "Received" -> { activeTab = "Received"; render() }
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
            val quickActions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                alpha = 0f
                visibility = View.GONE
                setPadding(dp(10), dp(7), dp(10), dp(7))
                background = rounded(0xEE0B1B38.toInt(), 25)
                elevation = dp(14).toFloat()
            }
            val sendFab = TextView(this).apply {
                text = "➤  SEND"; textSize = 12f; gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(-1)
                background = rounded(0xFF168FE5.toInt(), 22); elevation = dp(5).toFloat()
                setPadding(dp(18), 0, dp(18), 0)
                isHapticFeedbackEnabled = true
                setOnClickListener { animatePress(this); hideQuickActions(); scanQrCode() }
            }
            val receiveFab = TextView(this).apply {
                text = "⇩  RECEIVE"; textSize = 12f; gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(-1)
                background = rounded(0xFFE52B3A.toInt(), 22); elevation = dp(5).toFloat()
                setPadding(dp(18), 0, dp(18), 0)
                isHapticFeedbackEnabled = true
                setOnClickListener { animatePress(this); hideQuickActions(); openSendQr() }
            }
            quickActions.addView(sendFab, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(6) })
            quickActions.addView(receiveFab, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(6) })
            contentFrame.addView(quickActions, android.widget.FrameLayout.LayoutParams(dp(292), dp(60), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(10)
            })
            quickActionPanel = quickActions
            val selectionSend = TextView(this).apply {
                text = "➤  SEND"; textSize = 14f; gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(-1)
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(0xFF0B4FB3.toInt(), 0xFFE52B3A.toInt(), 0xFFFFD21F.toInt())
                ).apply { cornerRadius = dp(24).toFloat(); setStroke(dp(1), 0x88FFFFFF.toInt()) }
                elevation = dp(16).toFloat()
                setPadding(dp(32), 0, dp(32), 0)
                visibility = View.GONE
                setOnClickListener {
                    animatePress(this)
                    if (activeTab == "Media" && mediaCategory == "Apps") {
                        val chosen = installedApps.filter { selectedAppPackages.contains(it.packageName) }
                        selectAppApks(chosen)
                    } else beginSendSelected()
                }
            }
            selectionSendButton = selectionSend
            contentFrame.addView(selectionSend, android.widget.FrameLayout.LayoutParams(-1, dp(54), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                leftMargin = dp(24); rightMargin = dp(24); bottomMargin = dp(12)
            })
            content.setPadding(dp(13), dp(7), dp(13), dp(84))
        }
        root.addView(contentFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        var floatingCentre: TextView? = null
        if (libraryMode || connectedNow) {
            val centre = TextView(this).apply {
                text = if (connectedNow) "⇅" else "↔"
                textSize = if (connectedNow) 27f else 25f
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(-1)
                background = if (connectedNow) android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xFF0B4FB3.toInt(), 0xFFE52B3A.toInt(), 0xFFFFD21F.toInt())
                ).apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setStroke(dp(2), 0xAAFFFFFF.toInt()) }
                else android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0xFF0B4FB3.toInt())
                    setStroke(dp(1), 0xAAFFFFFF.toInt())
                }
                elevation = dp(12).toFloat()
                alpha = 0f
                scaleX = .68f
                scaleY = .68f
                if (connectedNow) {
                    android.animation.ObjectAnimator.ofFloat(this, "translationY", -dp(3).toFloat(), dp(3).toFloat()).apply {
                        duration = 900
                        repeatCount = android.animation.ValueAnimator.INFINITE
                        repeatMode = android.animation.ValueAnimator.REVERSE
                        start()
                    }
                }
                isHapticFeedbackEnabled = true
                contentDescription = if (connectedNow) "Open transfer centre" else "Show Send and Receive actions"
                setOnClickListener {
                    animatePress(this)
                    if (connectedNow) {
                        activeTab = "Transfers"
                        render()
                    } else if (quickActionPanel?.visibility == View.VISIBLE) hideQuickActions() else showQuickActions()
                }
                var downX = 0f
                var downY = 0f
                var startX = 0f
                var startY = 0f
                var dragged = false
                setOnTouchListener { view, event ->
                    val parent = view.parent as? View
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            downX = event.rawX; downY = event.rawY
                            startX = view.x; startY = view.y
                            dragged = false
                            true
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - downX
                            val dy = event.rawY - downY
                            if (connectedNow && (kotlin.math.abs(dx) > dp(5) || kotlin.math.abs(dy) > dp(5)) && parent != null) {
                                dragged = true
                                view.x = (startX + dx).coerceIn(0f, (parent.width - view.width).coerceAtLeast(0).toFloat())
                                view.y = (startY + dy).coerceIn(0f, (parent.height - view.height).coerceAtLeast(0).toFloat())
                            }
                            true
                        }
                        android.view.MotionEvent.ACTION_UP -> {
                            if (!dragged) view.performClick()
                            true
                        }
                        android.view.MotionEvent.ACTION_CANCEL -> true
                        else -> true
                    }
                }
            }
            floatingCentre = centre
        }
        setContentView(root)
        floatingCentre?.let { fab ->
            val appContent = findViewById<android.widget.FrameLayout>(android.R.id.content)
            appContent.addView(fab, android.widget.FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(16)
                bottomMargin = dp(88)
            })
            fab.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(320).setInterpolator(android.view.animation.OvershootInterpolator()).start()
        }
        when (activeTab) {
            "Media" -> showMedia(card, ink, muted, blue)
            "Files" -> showFiles(card, ink, muted, blue)
            "Sent" -> showSent(card, ink, muted)
            "Received" -> showReceived(card, ink, muted)
            "History" -> showHistory(card, ink, muted)
            "Transfers" -> showTransfers(card, ink, muted)
            "Receive" -> showReceive(card, ink, muted)
            "Settings" -> showSettings(card, ink, muted, blue)
            "Contacts" -> showContacts(card, ink, muted)
        }
        header.alpha = 0f
        header.translationY = -dp(9).toFloat()
        header.animate().alpha(1f).translationY(0f).setDuration(260)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        content.alpha = 1f
        for (index in 0 until content.childCount) {
            val child = content.getChildAt(index)
            child.alpha = 0f
            child.translationY = dp(14).toFloat()
            child.animate().alpha(1f).translationY(0f)
                .setStartDelay(index * 42L)
                .setDuration(280)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    private fun showHome(card: Int, ink: Int, muted: Int, blue: Int) {
        content.addView(TextView(this).apply {
            text = "Move files.\\nFeel the speed."
            textSize = 29f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            setTextColor(ink)
            setPadding(dp(2), dp(13), dp(2), dp(5))
        })
        content.addView(TextView(this).apply {
            text = "Share directly between phones using Wi-Fi Direct."
            textSize = 12f
            setTextColor(muted)
            setPadding(dp(3), 0, 0, dp(17))
        })

        val connection = panel(card).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF0B4FB3.toInt(), 0xFF173A78.toInt(), 0xFF8A243A.toInt())
            ).apply { cornerRadius = dp(24).toFloat(); setStroke(dp(1), 0xFFFFD21F.toInt()) }
            elevation = dp(8).toFloat()
        }
        connection.addView(TextView(this).apply {
            text = "NEXORA CONNECTION"
            textSize = 10f
            letterSpacing = .14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFD66B.toInt())
        })
        connection.addView(TextView(this).apply {
            text = if (connectedPeers.isEmpty()) "Ready to connect" else connectedPeers.size.toString() + " phone(s) connected"
            textSize = 21f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(-1)
            setPadding(0, dp(12), 0, dp(5))
        })
        connection.addView(TextView(this).apply {
            text = latestStatus
            textSize = 12f
            setTextColor(0xFFD5E9E8.toInt())
            setPadding(0, 0, 0, dp(4))
        })
        content.addView(connection, bottom(15))

        val quickAccess = panel(card)
        quickAccess.addView(label("QUICK ACCESS", 0xFFE6B94D.toInt()))
        val quickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            Triple("Apps", "▦", 0xFFFFD21F.toInt()),
            Triple("Photos", "▧", 0xFF0B4FB3.toInt()),
            Triple("Videos", "▷", 0xFFE52B3A.toInt()),
            Triple("Music", "♫", 0xFFFFD21F.toInt()),
            Triple("Files", "▤", 0xFF0B4FB3.toInt())
        ).forEach { item ->
            val shortcut = TextView(this).apply {
                text = item.second + "\\n" + item.first
                gravity = Gravity.CENTER
                textSize = 10f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(item.third)
                setPadding(dp(4), dp(10), dp(4), dp(10))
                background = rounded(if (darkMode) 0xFF20364E.toInt() else 0xFFF0F5FB.toInt(), 13)
                isHapticFeedbackEnabled = true
                setOnClickListener {
                    animatePress(this)
                    mediaCategory = item.first
                    if (item.first == "Files") { activeTab = "Files"; render() }
                    else if (item.first == "Photos" || item.first == "Videos" || item.first == "Music") openMediaLibrary(item.first)
                    else { activeTab = "Media"; render() }
                }
            }
            quickRow.addView(shortcut, LinearLayout.LayoutParams(0, dp(64), 1f).apply {
                leftMargin = dp(2); rightMargin = dp(2)
            })
        }
        quickAccess.addView(quickRow)
        content.addView(quickAccess, bottom(14))

        val statusCard = panel(card)
        statusCard.addView(label("TRANSFER STATUS", 0xFFFFC45D.toInt()))
        status = TextView(this).apply {
            text = latestStatus
            textSize = 12f
            setTextColor(ink)
            setPadding(0, dp(8), 0, dp(2))
        }
        statusCard.addView(status)
        content.addView(statusCard, bottom(14))
    }

    private fun showReceive(card: Int, ink: Int, muted: Int) {
        content.addView(TextView(this).apply {
            text = "Receive files"
            textSize = 28f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            setTextColor(ink)
            setPadding(dp(2), dp(12), dp(2), dp(4))
        })
        content.addView(TextView(this).apply {
            text = "Share one QR code so several nearby phones can join your Wi-Fi Direct group."
            textSize = 12f
            setTextColor(muted)
            setPadding(dp(3), 0, 0, dp(15))
        })

        val ready = panel(card).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF0B4FB3.toInt(), 0xFF173A78.toInt(), 0xFF8A243A.toInt())
            ).apply { cornerRadius = dp(24).toFloat(); setStroke(dp(1), 0xFFFFD21F.toInt()) }
            elevation = dp(6).toFloat()
        }
        ready.addView(label("NEXORA · RECEIVE MODE", 0xFFFFD66B.toInt()))
        receiveModeLabel = TextView(this).apply {
            text = if (sharing?.isAdvertising == true) "●  RECEIVING MODE ACTIVE" else "○  READY WHEN YOU ARE"
            textSize = 10f
            letterSpacing = .12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFF83F0C9.toInt())
            setPadding(0, dp(11), 0, dp(5))
        }
        ready.addView(receiveModeLabel)
        ready.addView(TextView(this).apply {
            text = if (connectedPeers.isEmpty()) "Your phone is ready to connect" else "Connected to " + connectedPeers.size + " phone(s)"
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(-1)
            setPadding(0, dp(5), 0, dp(5))
        })
        ready.addView(TextView(this).apply {
            text = latestStatus
            textSize = 12f
            setTextColor(0xFFD4EDE5.toInt())
            setPadding(0, 0, 0, dp(13))
        })
        ready.addView(button("START RECEIVING · SHOW MY QR", 0xFFE52B3A.toInt()) { openSendQr() })
        content.addView(ready, bottom(14))

        val steps = panel(card)
        steps.addView(label("HOW TO CONNECT", 0xFF0B4FB3.toInt()))
        listOf(
            Triple("01", "Start receiving", "NEXORA creates a private Wi-Fi Direct session."),
            Triple("02", "Show your QR code", "Other phones can scan the same code to join your group."),
            Triple("03", "Receive your files", "Received files are saved in Download/NEXORA.")
        ).forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            row.addView(TextView(this).apply {
                text = item.first
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(if (item.first == "02") 0xFFE52B3A.toInt() else 0xFF0B4FB3.toInt())
                background = rounded(if (darkMode) 0xFF20364E.toInt() else 0xFFEAF1FA.toInt(), 11)
            }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(11) })
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            details.addView(TextView(this@MainActivity).apply {
                text = item.second
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(ink)
            })
            details.addView(TextView(this@MainActivity).apply {
                text = item.third
                textSize = 11f
                setTextColor(muted)
                setPadding(0, dp(3), 0, 0)
            })
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            steps.addView(row)
        }
        content.addView(steps, bottom(14))

        val connect = panel(card)
        connect.addView(label("ALREADY HAVE A QR CODE?", 0xFFE52B3A.toInt()))
        connect.addView(TextView(this).apply {
            text = "Scan a NEXORA group QR code to join its receiving session."
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(7), 0, dp(11))
        })
        connect.addView(button("SCAN A QR CODE", 0xFF0B4FB3.toInt()) { scanQrCode() })
        content.addView(connect, bottom(13))
        content.addView(button("OPEN SENT & RECEIVED", 0xFFE69A35.toInt()) {
            activeTab = "Transfers"
            render()
        }, bottom(8))
        content.addView(button("STOP RECEIVING", 0xFF64748B.toInt()) {
            sharing?.stopSharing()
            discovered.clear()
            connectedPeers.clear()
            updateStatus("Receiving stopped.")
            render()
        })
    }

    private fun beginSendSelected() {
        if (selectedUris.isEmpty()) { Toast.makeText(this, "Select something to send first.", Toast.LENGTH_SHORT).show(); return }
        val outgoing = ArrayList(LinkedHashSet(selectedUris))
        rememberOutgoingUris(outgoing)
        if ((sharing?.isConnected == true || connectedPeers.isNotEmpty()) && sharing != null) {
            sendFilesWithRecipientSelection(outgoing)
            selectedUris.clear()
            selectedAppPackages.clear()
            pendingSelectedSend = false
            updateSelectionAction()
            updateStatus("Sending your selected items…")
            activeTab = "Media"
            mediaCategory = "Apps"
            render()
        } else {
            pendingSelectedSend = true
            pendingAutoConnectEndpointId = null
            withWifiDirectPermissions {
                runWithSharingService { service ->
                        activeTab = "Transfers"
                    render()
                    updateStatus("Scan the receiving phone QR code to connect over Wi-Fi Direct.")
                    scanQrCode()
                }
            }
        }
    }

    private fun sendFilesWithRecipientSelection(uris: List<Uri>) {
        val service = sharing ?: return
        val peers = service.connectedEndpointIds()
        if (peers.size <= 1) {
            service.sendFiles(uris)
            return
        }
        val names = peers.map { service.connectedEndpointName(it) ?: "Connected phone" }.toTypedArray()
        val excluded = LinkedHashSet<String>()
        val checked = BooleanArray(peers.size)
        android.app.AlertDialog.Builder(this)
            .setTitle("Group Share · Choose recipients")
            .setMessage("Select phones to exclude. All unchecked phones will receive the files.")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                if (isChecked) excluded.add(peers[which]) else excluded.remove(peers[which])
            }
            .setPositiveButton("SEND TO SELECTED") { _, _ ->
                if (excluded.size == peers.size) {
                    Toast.makeText(this, "Choose at least one recipient.", Toast.LENGTH_SHORT).show()
                } else {
                    service.sendFilesToGroup(uris, excluded)
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun runWithSharingService(action: (TransferKeepAliveService) -> Unit) {
        val current = sharing
        if (current != null) { action(current); return }
        pendingServiceAction = action
        try {
            ContextCompat.startForegroundService(this, Intent(this, TransferKeepAliveService::class.java))
            bindSharingServiceIfNeeded()
            updateStatus("Preparing Wi-Fi Direct…")
        } catch (e: Exception) {
            pendingServiceAction = null
            updateStatus("Could not start the sharing service: " + (e.localizedMessage ?: "unknown error"))
        }
    }

    private fun openSendQr() {
        withWifiDirectReadiness {
            withWifiDirectPermissions {
                runWithSharingService { service ->
                    receivingModeArmed = true
                    updateStatus("Preparing your Wi-Fi Direct QR code…")
                    service.startReceiving()
                }
            }
        }
    }

    private fun withWifiDirectReadiness(action: () -> Unit) {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val wifiEnabled = runCatching { wifiManager?.isWifiEnabled == true }.getOrDefault(false)
        if (!wifiEnabled) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Turn on Wi-Fi")
                .setMessage("NEXORA needs Wi-Fi enabled to create or join a Wi-Fi Direct connection.")
                .setPositiveButton("Turn on Wi-Fi") { _, _ ->
                    try {
                        if (Build.VERSION.SDK_INT >= 29) startActivity(Intent(android.provider.Settings.Panel.ACTION_WIFI))
                        else startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                    } catch (_: Exception) {
                        startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            updateStatus("Wi-Fi is off. Turn it on, then try again.")
            return
        }
        // Do not guess that hotspot is the problem before Android attempts Wi-Fi Direct.
        action()
    }

    private fun showWifiDirectFailureHelp(message: String) {
        val lower = message.lowercase()
        val likelyRadioConflict = lower.contains("android is busy") ||
            lower.contains("could not create wi-fi direct group") ||
            lower.contains("android reported a wi-fi direct error") ||
            lower.contains("could not join wi-fi direct group") ||
            lower.contains("connection failed") ||
            lower.contains("did not respond") ||
            lower.contains("wi-fi direct receiver could not start")
        if (!likelyRadioConflict || isFinishing || isDestroyed) return
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val wifiOn = runCatching { wifiManager?.isWifiEnabled == true }.getOrDefault(false)
        if (!wifiOn) return
        android.app.AlertDialog.Builder(this)
            .setTitle("Wi-Fi Direct could not connect")
            .setMessage("Android could not create the connection. Check whether mobile hotspot is on and turn it off if necessary, then try again. NEXORA cannot switch hotspot off automatically.")
            .setPositiveButton("Check hotspot settings") { _, _ ->
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))
                } catch (_: Exception) {
                    startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun showQrCode(payload: String) {
        val returnTab = activeTab
        val dialog = android.app.Dialog(this)
        val deviceName = (getSharedPreferences("nexora_preferences", MODE_PRIVATE)
            .getString("device_name", null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: (Build.MODEL ?: "Android device").take(24)).take(24)
        try {
            val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
                payload, com.google.zxing.BarcodeFormat.QR_CODE, 560, 560
            )
            val bitmap = android.graphics.Bitmap.createBitmap(560, 560, android.graphics.Bitmap.Config.ARGB_8888)
            for (x in 0 until 560) for (y in 0 until 560) {
                bitmap.setPixel(x, y, if (matrix[x, y]) 0xFF0B4FB3.toInt() else android.graphics.Color.WHITE)
            }

            val page = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(22), dp(18), dp(22), dp(22))
                setBackgroundColor(0xFF101214.toInt())
            }
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(TextView(this).apply {
                text = "‹"
                textSize = 34f
                setTextColor(-1)
                gravity = Gravity.CENTER
                setOnClickListener { dialog.dismiss(); activeTab = returnTab; render() }
            }, LinearLayout.LayoutParams(dp(42), dp(48)))
            top.addView(TextView(this).apply {
                text = "Connect"
                textSize = 19f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(-1)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(TextView(this).apply {
                text = "NEXORA"
                textSize = 11f
                letterSpacing = .08f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(0xFFFFD21F.toInt())
            })
            page.addView(top, LinearLayout.LayoutParams(-1, dp(52)))

            val instruction = TextView(this).apply {
                text = "Other phones can scan this same code to join your private Wi-Fi Direct group"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(0xFFD5D9D8.toInt())
                setPadding(dp(8), dp(24), dp(8), dp(18))
            }
            page.addView(instruction)

            val qrCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(13), dp(13), dp(13), dp(13))
                background = rounded(android.graphics.Color.WHITE, 10)
            }
            qrCard.addView(ImageView(this).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(258), dp(258)))
            qrCard.addView(TextView(this).apply {
                text = "●  DIRECT • " + deviceName
                textSize = 10f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(0xFF008F68.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(9), 0, dp(2))
            })
            page.addView(qrCard, LinearLayout.LayoutParams(-2, -2))

            page.addView(TextView(this).apply {
                text = "Keep NEXORA open while the other phone scans and joins your group."
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(0xFFB7C1C0.toInt())
                setPadding(dp(12), dp(18), dp(12), dp(10))
            })
            val spacer = View(this)
            page.addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))
            page.addView(button("CLOSE", 0xFF4C46A8.toInt()) {
                dialog.dismiss()
                activeTab = returnTab
                render()
            }, LinearLayout.LayoutParams(-1, dp(44)))
            dialog.setContentView(page)
            dialog.setCancelable(true)
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.show()
            dialog.window?.setLayout(-1, -1)
        } catch (e: Exception) {
            updateStatus("Could not create QR code: " + (e.localizedMessage ?: "unknown error"))
        }
    }

    private fun showRocketConnectAnimation(onLaunch: () -> Unit) {
        showRocketFlight(received = false, onComplete = onLaunch)
    }

    private fun showReceivedRocketAnimation(onComplete: () -> Unit) {
        showRocketFlight(received = true, onComplete = onComplete)
    }

    // Shared, full-screen launch sequence used after a Wi-Fi Direct connection
    // or a confirmed successful receive.
    private fun showRocketFlight(received: Boolean, onComplete: () -> Unit) {
        val dialog = android.app.Dialog(this)
        val stage = android.widget.FrameLayout(this).apply {
            setBackground(android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF07142E.toInt(), 0xFF0B4FB3.toInt(), 0xFF7A1F36.toInt())
            ))
        }
        val halo = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x3327DDB0)
                setStroke(dp(2), 0xFFFFD21F.toInt())
            }
            alpha = 0f
            scaleX = .65f
            scaleY = .65f
        }
        stage.addView(halo, android.widget.FrameLayout.LayoutParams(dp(190), dp(190), Gravity.CENTER))
        val flame = TextView(this).apply {
            text = "🔥"
            textSize = 27f
            gravity = Gravity.CENTER
            alpha = 0f
            translationY = dp(42).toFloat()
        }
        stage.addView(flame, android.widget.FrameLayout.LayoutParams(dp(70), dp(70), Gravity.CENTER))
        val rocket = TextView(this).apply {
            text = "🚀"
            textSize = 66f
            gravity = Gravity.CENTER
            rotation = -45f
            alpha = 0f
            scaleX = .45f
            scaleY = .45f
        }
        stage.addView(rocket, android.widget.FrameLayout.LayoutParams(dp(120), dp(120), Gravity.CENTER))
        val caption = TextView(this).apply {
            text = if (received) "FILE RECEIVED" else "CONNECTED SUCCESSFULLY"
            textSize = 14f
            letterSpacing = .13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (received) 0xFFFFD21F.toInt() else 0xFFFFD66B.toInt())
            alpha = 0f
        }
        stage.addView(caption, android.widget.FrameLayout.LayoutParams(-1, dp(54), Gravity.CENTER).apply {
            topMargin = dp(155)
            leftMargin = dp(18)
            rightMargin = dp(18)
        })
        val subcaption = TextView(this).apply {
            text = if (received) "Your files are ready" else "Your phones are now connected"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFFD5E9E8.toInt())
            alpha = 0f
        }
        stage.addView(subcaption, android.widget.FrameLayout.LayoutParams(-1, dp(38), Gravity.CENTER).apply {
            topMargin = dp(205)
        })
        dialog.setContentView(stage)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(-1, -1)

        halo.animate().alpha(1f).scaleX(1.12f).scaleY(1.12f)
            .setDuration(520).setInterpolator(android.view.animation.OvershootInterpolator()).start()
        halo.animate().alpha(.45f).scaleX(.88f).scaleY(.88f)
            .setStartDelay(520).setDuration(520).start()
        flame.animate().alpha(1f).translationY(dp(60).toFloat())
            .setDuration(320).setInterpolator(android.view.animation.AccelerateInterpolator()).start()
        rocket.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(360).setInterpolator(android.view.animation.OvershootInterpolator()).start()
        caption.animate().alpha(1f).setStartDelay(180).setDuration(300).start()
        subcaption.animate().alpha(1f).setStartDelay(360).setDuration(300).start()
        rocket.animate().translationY(-dp(190).toFloat())
            .setStartDelay(420).setDuration(920)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                flame.animate().alpha(0f).setDuration(120).start()
                if (received) {
                    caption.text = "RECEIVED"
                    subcaption.text = "Returning to NEXORA…"
                } else {
                    caption.text = "CONNECTED"
                    subcaption.text = "Returning to NEXORA…"
                }
                android.os.Handler(mainLooper).postDelayed({
                    if (!isFinishing && !isDestroyed) {
                        dialog.dismiss()
                        onComplete()
                    }
                }, if (received) 900L else 650L)
            }.start()
    }

    private fun scanQrCode() {
        withWifiDirectReadiness {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                pendingCameraScan = true
                requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
                return@withWifiDirectReadiness
            }
            launchQrScanner()
        }
    }

    private fun launchQrScanner() {
        try {
            Toast.makeText(this, "Opening NEXORA scanner…", Toast.LENGTH_SHORT).show()
            com.google.zxing.integration.android.IntentIntegrator(this)
                .setDesiredBarcodeFormats(com.google.zxing.integration.android.IntentIntegrator.QR_CODE)
                .setPrompt("Align the NEXORA QR code inside the square")
                .setBeepEnabled(false)
                .setCaptureActivity(PortraitQrCaptureActivity::class.java)
                .setOrientationLocked(true)
                .initiateScan()
        } catch (e: Exception) {
            updateStatus("QR scanner could not open: " + (e.localizedMessage ?: "unknown error"))
            Toast.makeText(this, "Scanner could not open. Check camera permission.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showMedia(card: Int, ink: Int, muted: Int, blue: Int) {
        if (mediaCategory == null) mediaCategory = "Apps"
        renderMediaLibrary(mediaCategory!!, ink, muted, blue)
    }

    private fun openMediaLibrary(kind: String) {
        activeTab = "Media"
        mediaCategory = kind
        mediaSearch = ""
        mediaDisplayLimit = 20
        val permissions = when (kind) {
            "Photos" -> if (Build.VERSION.SDK_INT >= 34) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            "Videos" -> if (Build.VERSION.SDK_INT >= 34) arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            else -> if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_AUDIO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val fullPermission = when (kind) {
            "Photos" -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            "Videos" -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
            else -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val hasFullPermission = ContextCompat.checkSelfPermission(this, fullPermission) == PackageManager.PERMISSION_GRANTED
        if (hasFullPermission) render() else {
            // Ask for the full media permission instead of accepting Android 14's
            // selected-media-only grant as if it covered the entire Videos library.
            val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            requestPermissions(missing.toTypedArray(), REQUEST_MEDIA)
        }
    }

    private fun renderMediaLibrary(kind: String, ink: Int, muted: Int, blue: Int) {
        if (kind == "Apps") { renderAppsLibrary(ink, muted, blue); return }
        if (kind == "Files") {
            content.addView(TextView(this).apply { text = "Browse any folder or file on your phone."; textSize = 13f; setTextColor(muted); setPadding(0, dp(12), 0, dp(10)) })
            val browse = panel(if (darkMode) 0xFF142A40.toInt() else -1)
            browse.addView(label("FILE LIBRARY", 0xFFFFD21F.toInt()))
            browse.addView(TextView(this).apply { text = "Choose files from your folders, downloads or shared storage."; textSize = 12f; setTextColor(muted); setPadding(0, dp(8), 0, dp(10)) })
            browse.addView(button("BROWSE FILES", 0xFF0B4FB3.toInt()) { openPicker("*/*") })
            content.addView(browse, bottom(10))
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
        mediaContinueButton = button("SEND SELECTED  ·  " + selectedUris.size, 0xFF0B9B75.toInt()) {
            if (selectedUris.isEmpty()) Toast.makeText(this, "Select at least one file first.", Toast.LENGTH_SHORT).show()
            else beginSendSelected()
        }
        mediaContinueButton?.isEnabled = selectedUris.isNotEmpty()
        content.addView(mediaContinueButton, bottom(10))
    }

    private val mediaCache = HashMap<String, ArrayList<Triple<Uri, String, Long>>>()
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
                    val loaded = ArrayList<Triple<Uri, String, Long>>()
                    val collection = when (kind) {
                        "Photos" -> if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        "Videos" -> if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        "Music" -> if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        else -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    }
                    try {
                        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED)
                        val cursor = contentResolver.query(collection, projection, null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC")
                            ?: throw java.io.IOException("Android did not return a media list. Check media access and try again.")
                        cursor.use {
                            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                            var loadedCount = 0
                            while (cursor.moveToNext() && loadedCount < 1500) {
                                loadedCount++
                                val id = cursor.getLong(idCol)
                                val name = cursor.getString(nameCol) ?: "Media file"
                                val size = cursor.getLong(sizeCol)
                                loaded.add(Triple(android.content.ContentUris.withAppendedId(collection, id), name + " · " + readableSize(size), cursor.getLong(dateCol)))
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
        val filtered = if (mediaSort == "Name A-Z") matching.sortedWith(compareByDescending<Triple<Uri, String, Long>> { it.third }.thenBy { it.second.lowercase() }) else matching
        if (filtered.isEmpty()) {
            val partialVideoAccess = kind == "Videos" && Build.VERSION.SDK_INT >= 34 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
            mediaList.addView(TextView(this).apply {
                text = if (cached.isEmpty() && partialVideoAccess) {
                    "Android has given NEXORA access only to videos you selected. No videos are currently included. Allow full video access in Android settings to show your entire video library."
                } else if (cached.isEmpty()) "No " + kind.lowercase() + " found." else "No matching files."
                textSize = 12f; setTextColor(muted); setPadding(0, dp(12), 0, dp(12))
            })
            if (partialVideoAccess) mediaList.addView(button("OPEN VIDEO PERMISSIONS", blue) {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + packageName)))
                } catch (_: Exception) {
                    Toast.makeText(this, "Open Android Settings, then Apps → NEXORA → Permissions → Photos and videos.", Toast.LENGTH_LONG).show()
                }
            }, bottom(8))
        }
        val useGrid = kind == "Photos" || kind == "Videos"
        var mediaGrid: android.widget.GridLayout? = null
        var currentMediaDay = ""
        filtered.take(mediaDisplayLimit).forEach { item ->
            if (useGrid || kind == "Music") {
                val day = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(item.third * 1000L))
                if (day != currentMediaDay) {
                    currentMediaDay = day
                    mediaList.addView(TextView(this).apply {
                        text = day; textSize = 12f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(if (darkMode) 0xFF8FE0C3.toInt() else 0xFF16856A.toInt())
                        setPadding(dp(3), dp(12), dp(3), dp(6))
                    })
                    if (useGrid) {
                        mediaGrid = android.widget.GridLayout(this).apply { columnCount = 3; useDefaultMargins = false }
                        mediaList.addView(mediaGrid)
                    }
                }
            }
            val isSelected = selectedUris.contains(item.first)
            val row = LinearLayout(this).apply {
                orientation = if (useGrid) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(7), dp(6), dp(7))
                background = rounded(if (isSelected) { if (darkMode) 0xFF174A3D.toInt() else 0xFFE4F5EF.toInt() } else if (darkMode) 0xFF1C2940.toInt() else -1, 12)
            }
            val preview = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(if (darkMode) 0xFF263650.toInt() else 0xFFE8EDF5.toInt()) }
            row.addView(preview, LinearLayout.LayoutParams(if (useGrid) dp(88) else dp(66), if (useGrid) dp(88) else dp(66)))
            if (kind == "Videos") preview.setOnClickListener { previewVideo(item.first, item.second) }
            row.addView(TextView(this).apply {
                text = item.second; textSize = if (useGrid) 9f else 12f; maxLines = if (useGrid) 2 else 3
                gravity = if (useGrid) Gravity.CENTER else Gravity.START
                setTextColor(if (darkMode) -1 else 0xFF14213D.toInt())
                setPadding(if (useGrid) dp(2) else dp(10), dp(3), dp(2), 0)
            }, if (useGrid) LinearLayout.LayoutParams(-1, dp(38)) else LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply { text = if (isSelected) "✓" else "+"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(if (isSelected) 0xFF16A085.toInt() else blue) })
            row.setOnClickListener {
                if (selectedUris.contains(item.first)) selectedUris.remove(item.first) else selectedUris.add(item.first)
                mediaContinueButton?.text = "SEND SELECTED  ·  " + selectedUris.size
                mediaContinueButton?.isEnabled = selectedUris.isNotEmpty()
                updateSelectionAction()
                renderMediaRows(kind, ink, muted, blue)
            }
            if (useGrid) {
                val params = android.widget.GridLayout.LayoutParams().apply {
                    width = 0; height = dp(148)
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                    setMargins(dp(3), dp(3), dp(3), dp(5))
                }
                mediaGrid?.addView(row, params)
            } else mediaList.addView(row, bottom(6))
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
                    if (!launchSplashVisible && activeTab == "Media" && mediaCategory == "Apps") render()
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
    private val selectedAppPackages = LinkedHashSet<String>()
    private val appLabels = HashMap<String, String>()
    private var systemAppsExpanded = false
    private var appDisplayLimit = 20

    private fun renderAppsLibrary(ink: Int, muted: Int, blue: Int) {
        content.addView(TextView(this).apply { text = "Apps"; textSize = 23f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(ink); setPadding(0, dp(6), 0, dp(8)) })
        val search = EditText(this).apply { hint = "Search apps"; setSingleLine(true); textSize = 14f; setPadding(dp(12), dp(8), dp(12), dp(8)); setBackground(rounded(if (darkMode) 0xFF263650.toInt() else -1, 12)); setTextColor(ink); setHintTextColor(muted) }
        content.addView(search, bottom(8))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        appSelectionButton = null
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
                        background = rounded(if (selectedAppPackages.contains(app.packageName)) { if (darkMode) 0xFF174A3D.toInt() else 0xFFE4F5EF.toInt() } else if (darkMode) 0xFF142A40.toInt() else -1, 17)
                        elevation = dp(2).toFloat()
                        isClickable = true; isFocusable = true
                    }
                    val iconView = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        tag = app.packageName
                        background = rounded(if (darkMode) 0xFF263B50.toInt() else 0xFFE8EEF6.toInt(), 12)
                        val cached = appIconCache[app.packageName]
                        if (cached != null) setImageDrawable(cached)
                    }
                    appTile.addView(iconView, LinearLayout.LayoutParams(dp(48), dp(48)))
                    if (appIconCache[app.packageName] == null) thumbnailExecutor.execute {
                        val drawable = try { packageManager.getApplicationIcon(app) } catch (_: Exception) { null }
                        if (drawable != null) {
                            appIconCache[app.packageName] = drawable
                            runOnUiThread {
                                if (iconView.tag == app.packageName && iconView.parent != null) {
                                    iconView.setImageDrawable(drawable)
                                    iconView.background = null
                                }
                            }
                        }
                    }
                    val appName = appLabels[app.packageName] ?: app.packageName
                    val appSize = runCatching {
                        val base = java.io.File(app.sourceDir).length()
                        val splits = app.splitSourceDirs?.sumOf { java.io.File(it).length() } ?: 0L
                        readableSize(base + splits)
                    }.getOrDefault("Size unavailable")
                    appTile.addView(TextView(this).apply {
                        text = appName; textSize = 11f; maxLines = 2; gravity = Gravity.CENTER
                        typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(ink); setPadding(0, dp(5), 0, dp(1))
                    }, LinearLayout.LayoutParams(-1, dp(32)))
                    appTile.addView(TextView(this).apply {
                        text = appSize; textSize = 9f; gravity = Gravity.CENTER
                        setTextColor(muted); setPadding(0, 0, 0, dp(2))
                    })
                    val selectionLabel = TextView(this).apply {
                        text = if (selectedAppPackages.contains(app.packageName)) "✓ SELECTED" else "＋ SELECT"
                        textSize = 9f; letterSpacing = .04f; gravity = Gravity.CENTER
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(if (selectedAppPackages.contains(app.packageName)) 0xFF16A085.toInt() else 0xFF1684D9.toInt())
                    }
                    appTile.addView(selectionLabel)
                    appTile.setOnClickListener {
                        if (preparingApps) {
                            Toast.makeText(this@MainActivity, "Please wait until app preparation finishes.", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        animatePress(appTile)
                        val nowSelected = if (selectedAppPackages.contains(app.packageName)) {
                            selectedAppPackages.remove(app.packageName); false
                        } else {
                            selectedAppPackages.add(app.packageName); true
                        }
                        selectionLabel.text = if (nowSelected) "✓ SELECTED" else "＋ SELECT"
                        selectionLabel.setTextColor(if (nowSelected) 0xFF16A085.toInt() else 0xFF1684D9.toInt())
                        appTile.background = rounded(
                            if (nowSelected) { if (darkMode) 0xFF174A3D.toInt() else 0xFFE4F5EF.toInt() }
                            else if (darkMode) 0xFF142A40.toInt() else -1, 17
                        )
                        updateSelectionAction()
                    }
                    val params = android.widget.GridLayout.LayoutParams().apply {
                        width = 0; height = dp(137)
                        columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                        setMargins(dp(4), dp(4), dp(4), dp(7))
                    }
                    grid.addView(appTile, params)
                    // Keep the grid stable while selecting apps; only the pressed tile animates.
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
            val localApps = userApps.filterNot { app -> featuredApps.any { it.packageName == app.packageName } }
            addAppSection("LOCAL APPS", localApps.take(appDisplayLimit), 0xFF7357E8.toInt())
            if (localApps.size > appDisplayLimit) {
                list.addView(button("LOAD MORE APPS", blue) {
                    appDisplayLimit = (appDisplayLimit + 20).coerceAtMost(localApps.size)
                    populate(search.text.toString())
                }, bottom(8))
            }
            addAppSection("SYSTEM APPS", systemApps, 0xFF168FE5.toInt(), collapsible = query.isBlank())
            updateSelectionAction()
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { populate(s?.toString() ?: "") }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        populate("")
    }

    private fun selectAppApk(app: android.content.pm.ApplicationInfo) = selectAppApks(listOf(app))

    private fun selectAppApks(apps: List<android.content.pm.ApplicationInfo>) {
        if (apps.isEmpty() || preparingApps) return
        preparingApps = true
        selectionSendButton?.isEnabled = false
        updateStatus("Preparing " + apps.size + " app(s)…")
        Thread {
            val preparedUris = LinkedHashSet<Uri>()
            val labels = ArrayList<String>()
            var totalParts = 0
            var failure: String? = null
            try {
                val folder = java.io.File(cacheDir, "shared_apks").apply {
                    if (exists()) listFiles()?.forEach { it.delete() }
                    mkdirs()
                }
                val batch = System.currentTimeMillis().toString()
                apps.distinctBy { it.packageName }.forEach { app ->
                    val prefix = "NEXORA_APP_" + app.packageName + "_" + batch
                    val sources = ArrayList<java.io.File>()
                    sources.add(java.io.File(app.sourceDir))
                    app.splitSourceDirs?.forEach { sources.add(java.io.File(it)) }
                    sources.forEachIndexed { index, source ->
                        val suffix = if (index == 0) "_BASE.apk" else "_SPLIT_" + (index - 1) + ".apk"
                        val apk = java.io.File(folder, prefix + suffix)
                        source.copyTo(apk, overwrite = true)
                        preparedUris.add(androidx.core.content.FileProvider.getUriForFile(this, packageName + ".fileprovider", apk))
                        totalParts++
                    }
                    labels.add(packageManager.getApplicationLabel(app).toString())
                }
            } catch (e: Exception) {
                failure = e.localizedMessage ?: "unknown error"
            }
            runOnUiThread {
                preparingApps = false
                selectedUris.clear()
                selectedUris.addAll(preparedUris)
                selectedAppPackages.clear()
                updateSelectionAction()
                if (failure != null || selectedUris.isEmpty()) {
                    selectionSendButton?.isEnabled = true
                    Toast.makeText(this, "Could not prepare selected apps: " + (failure ?: "no app files found"), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                activeTab = "Transfers"
                mediaCategory = "Apps"
                render()
                updateStatus(labels.size.toString() + " app(s) prepared (" + totalParts + " APK part(s)). Searching for a receiving phone…")
                beginSendSelected()
            }
        }.start()
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
            // Android 14's selected-photos/videos permission may grant access to only
            // a subset of media. It must not be treated as full library access: for
            // example, a user may have selected photos but no videos at all.
            if (kind == "Photos" || kind == "Videos") {
                return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
            }
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
        storage.addView(label("PHONE STORAGE", 0xFFFFD21F.toInt()))
        try {
            val stat = android.os.StatFs(android.os.Environment.getDataDirectory().absolutePath)
            val total = stat.totalBytes; val free = stat.availableBytes; val used = (total - free).coerceAtLeast(0L)
            storage.addView(TextView(this).apply { text = readableSize(used) + " used  ·  " + readableSize(free) + " available"; textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(ink); setPadding(0, dp(8), 0, dp(5)) })
            val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = if (total > 0) ((used * 100L) / total).toInt() else 0
                progressTintList = android.content.res.ColorStateList.valueOf(0xFF0B4FB3.toInt())
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
        sdPanel.addView(label("SD CARD STORAGE", 0xFFE52B3A.toInt()))
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
            sdPanel.addView(button("BROWSE SD CARD FOLDER", 0xFF0B4FB3.toInt()) {
                updateStatus("Choose the SD card in Android's folder picker, then grant NEXORA access.")
                openFolderTree()
            }, bottom(5))
        }
        content.addView(sdPanel, bottom(12))
        val categoryPanel = panel(card)
        categoryPanel.addView(label("BROWSE BY CATEGORY", 0xFFFFD21F.toInt()))
        val categoryGrid = android.widget.GridLayout(this).apply { columnCount = 2 }
        listOf(
            Triple("Documents", "documents", 0xFF0B4FB3.toInt()),
            Triple("E-books", "ebooks", 0xFFFFD21F.toInt()),
            Triple("APKs", "apks", 0xFFE52B3A.toInt()),
            Triple("Archives", "archives", 0xFF0B4FB3.toInt()),
            Triple("Big Files", "big", 0xFFE52B3A.toInt())
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
        content.addView(button("OPEN PHONE FOLDERS", 0xFF0B4FB3.toInt()) { openFolderTree() }, bottom(8))
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
                        else {
                            if (selectedUris.contains(itemUri)) selectedUris.remove(itemUri) else selectedUris.add(itemUri)
                            updateSelectionAction()
                            updateStatus("${selectedUris.size} file(s) selected.")
                            Toast.makeText(this, if (selectedUris.contains(itemUri)) "Added: $name" else "Removed: $name", Toast.LENGTH_SHORT).show()
                        }
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
        content.addView(section, bottom(12))
    }

    private fun showTransfers(card: Int, ink: Int, muted: Int) {
        val connected = sharing?.isConnected == true || connectedPeers.isNotEmpty()
        content.addView(TextView(this).apply {
            text = "Transfer centre"
            textSize = 27f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            setTextColor(ink)
            setPadding(dp(2), dp(12), dp(2), dp(3))
        })
        content.addView(TextView(this).apply {
            text = "Manage your connection and keep track of file sharing."
            textSize = 12f
            setTextColor(muted)
            setPadding(dp(3), 0, 0, dp(15))
        })

        val live = panel(card).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF0B4FB3.toInt(), 0xFF173A78.toInt(), 0xFF8A243A.toInt())
            ).apply { cornerRadius = dp(23).toFloat(); setStroke(dp(1), 0xFFFFD21F.toInt()) }
            elevation = dp(5).toFloat()
        }
        live.addView(label("LIVE SESSION", 0xFFFFD66B.toInt()))
        val statusLine = TextView(this).apply {
            text = latestStatus
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(-1)
            setPadding(0, dp(10), 0, dp(8))
        }
        transferStatusView = statusLine
        live.addView(statusLine)
        val stepRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("SELECT", "CONNECT", "SEND", "DONE").forEachIndexed { index, stepName ->
            val step = TextView(this).apply {
                text = stepName
                textSize = 8f
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(dp(3), dp(9), dp(3), dp(9))
                val tint = intArrayOf(0xFFFFD21F.toInt(), 0xFF168FE5.toInt(), 0xFFE52B3A.toInt(), 0xFF16A58A.toInt())[index]
                background = rounded(if (index < transferStage) tint else 0x553D5A7A, 9)
                setTextColor(if (index < transferStage) if (index == 0) 0xFF10243B.toInt() else -1 else 0xFFD5E2EF.toInt())
            }
            transferStepViews.add(step)
            stepRow.addView(step, LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                leftMargin = dp(2); rightMargin = dp(2)
            })
        }
        live.addView(stepRow, bottom(10))
        val initialPercent = Regex("(\\d{1,3})%").find(latestStatus)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val progressLine = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(0xFFFFD21F.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x665D7897)
            isIndeterminate = initialPercent == null && (latestStatus.contains("sending", true) || latestStatus.contains("receiving", true))
            progress = initialPercent ?: if (transferStage >= 4) 100 else 0
        }
        transferProgressBar = progressLine
        live.addView(progressLine, LinearLayout.LayoutParams(-1, dp(7)))
        content.addView(live, bottom(14))

        val devices = panel(card)
        devices.addView(label("CONNECTED DEVICES", 0xFF168FE5.toInt()))
        if (!connected) {
            devices.addView(TextView(this).apply {
                text = "No phone is connected yet. Connect first, then choose files to send. You can keep the same session for repeated transfers."
                textSize = 12f
                setTextColor(muted)
                setPadding(0, dp(9), 0, dp(5))
            })
            devices.addView(button("SCAN A QR CODE", 0xFF0B4FB3.toInt()) { scanQrCode() }, bottom(4))
            devices.addView(button("SHOW MY QR CODE", 0xFFE52B3A.toInt()) { openSendQr() })
        } else {
            devices.addView(TextView(this).apply {
                text = "Your NEXORA session is active."
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(ink)
                setPadding(0, dp(8), 0, dp(7))
            })
            val names = if (connectedPeers.isNotEmpty()) connectedPeers.map { discovered[it] ?: "Wi-Fi Direct phone" }
                else listOf("Connected phone")
            names.forEach { peer ->
                devices.addView(TextView(this).apply {
                    text = "●  " + peer + "  ·  CONNECTED"
                    textSize = 11f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(0xFF168A68.toInt())
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    background = rounded(if (darkMode) 0xFF173C3B.toInt() else 0xFFE7F7F1.toInt(), 11)
                }, bottom(6))
            }
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(button("SEND FILES", 0xFF0B4FB3.toInt()) {
                activeTab = "Media"; mediaCategory = "Apps"; render()
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(5) })
            actions.addView(button("RECEIVED", 0xFFE52B3A.toInt()) {
                activeTab = "Received"; render()
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(5) })
            devices.addView(actions, bottom(3))
        }
        content.addView(devices, bottom(14))

        if (connected) {
            content.addView(button("DISCONNECT", 0xFFE52B3A.toInt()) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Disconnect from this session?")
                    .setMessage("The active NEXORA connection will end. You can connect again later.")
                    .setNegativeButton("Keep connected", null)
                    .setPositiveButton("Disconnect") { _, _ ->
                        sharing?.stopSharing()
                        connectedPeers.clear()
                        activeTab = "Media"
                        mediaCategory = "Apps"
                        render()
                    }
                    .show()
            }, bottom(14))
        }

        val history = panel(card)
        history.addView(label("RECENTLY SENT", 0xFFE52B3A.toInt()))
        val entries = getSharedPreferences("nexora_transfer_history", MODE_PRIVATE)
            .getString("sent_items", "")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        if (entries.isEmpty()) {
            history.addView(TextView(this).apply {
                text = "Files you send will appear here."
                textSize = 12f
                setTextColor(muted)
                setPadding(0, dp(8), 0, dp(5))
            })
        } else addGroupedHistory(history, entries.take(20), 0xFF0B4FB3.toInt(), ink, muted)
        content.addView(history, bottom(12))
        content.addView(button("VIEW FULL TRANSFER HISTORY", 0xFF0B4FB3.toInt()) {
            activeTab = "History"; render()
        })
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
        val parts = entry.split(" · ", limit = 3)
        val timestamp = parts.getOrNull(0).orEmpty()
        val filename = parts.getOrNull(1) ?: entry
        val fileUri = parts.getOrNull(2)?.let { runCatching { Uri.parse(it) }.getOrNull() }
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
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackground(rounded(if (darkMode) 0xFF29445B.toInt() else 0xFFE7EEF7.toInt(), 12))
            setPadding(dp(5), dp(5), dp(5), dp(5))
            contentDescription = filename
        }
        preview.setImageResource(android.R.drawable.ic_menu_gallery)
        row.addView(preview, LinearLayout.LayoutParams(dp(52), dp(52)))
        if (fileUri != null) {
            thumbnailExecutor.execute {
                var drawable: android.graphics.drawable.Drawable? = null
                var bitmap: android.graphics.Bitmap? = null
                try {
                    if (filename.endsWith(".apk", true)) {
                        val folder = java.io.File(cacheDir, "history_icons").apply { mkdirs() }
                        val temp = java.io.File(folder, filename.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                        contentResolver.openInputStream(fileUri)?.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
                        val info = packageManager.getPackageArchiveInfo(temp.absolutePath, 0)
                        val appInfo = info?.applicationInfo
                        if (appInfo != null) {
                            appInfo.sourceDir = temp.absolutePath
                            appInfo.publicSourceDir = temp.absolutePath
                            drawable = packageManager.getApplicationIcon(appInfo)
                        }
                    } else if (Build.VERSION.SDK_INT >= 29) {
                        bitmap = contentResolver.loadThumbnail(fileUri, android.util.Size(96, 96), null)
                    }
                } catch (_: Exception) {}
                runOnUiThread {
                    if (preview.parent != null) {
                        if (drawable != null) preview.setImageDrawable(drawable)
                        else if (bitmap != null) preview.setImageBitmap(bitmap)
                        else preview.setImageResource(if (filename.endsWith(".apk", true)) android.R.drawable.sym_def_app_icon else android.R.drawable.ic_menu_gallery)
                    }
                }
            }
        }
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
        val entries = getSharedPreferences("nexora_transfer_history", MODE_PRIVATE)
            .getString("sent_items", "")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        content.addView(TextView(this).apply {
            text = "Sent files"
            textSize = 27f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            setTextColor(ink)
            setPadding(dp(2), dp(12), dp(2), dp(3))
        })
        content.addView(TextView(this).apply {
            text = "Files you've shared from this phone."
            textSize = 12f
            setTextColor(muted)
            setPadding(dp(3), 0, 0, dp(15))
        })
        val summary = panel(card)
        summary.addView(label("TOTAL ITEMS RECORDED", 0xFF0B4FB3.toInt()))
        summary.addView(TextView(this).apply {
            text = entries.size.toString()
            textSize = 26f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ink)
            setPadding(0, dp(5), 0, 0)
        })
        content.addView(summary, bottom(13))

        val box = panel(card)
        box.addView(label("SENT FROM THIS PHONE · NEWEST FIRST", 0xFF0B4FB3.toInt()))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list)
        if (entries.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Files you send will appear here."
                textSize = 12f
                setTextColor(muted)
                setPadding(0, dp(10), 0, dp(10))
            })
        } else addGroupedHistory(list, entries.take(100), 0xFF0B4FB3.toInt(), ink, muted)
        content.addView(box)
    }

    private fun rememberOutgoingUris(uris: List<Uri>) {
        val prefs = getSharedPreferences("nexora_transfer_history", MODE_PRIVATE)
        val edit = prefs.edit()
        uris.forEach { uri ->
            val name = runCatching {
                contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) cur.getString(0) else null
                }
            }.getOrNull() ?: (uri.lastPathSegment ?: "Shared file")
            edit.putString("sent_uri_" + name.hashCode(), uri.toString())
        }
        edit.apply()
    }

    private fun rememberSentFileName(name: String) {
        if (name.isBlank()) return
        val prefs = getSharedPreferences("nexora_transfer_history", MODE_PRIVATE)
        val old = prefs.getString("sent_items", "") ?: ""
        val stamp = java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val uri = prefs.getString("sent_uri_" + name.hashCode(), null)
        val entry = stamp + " · " + name + (uri?.let { " · " + it } ?: "")
        prefs.edit().putString("sent_items", (listOf(entry) + old.split("\n").filter { it.isNotBlank() }).take(100).joinToString("\n")).apply()
        appendHistoryEvent("SENT", name)
    }

    private fun rememberReceivedFileName(name: String) {
        if (name.isNotBlank()) appendHistoryEvent("RECEIVED", name)
    }

    private fun appendHistoryEvent(direction: String, name: String) {
        val prefs = getSharedPreferences("nexora_transfer_history", MODE_PRIVATE)
        val old = prefs.getString("history_events", "") ?: ""
        val safeName = name.replace("|", "_").replace("\n", " ").take(240)
        val event = System.currentTimeMillis().toString() + "|" + direction + "|" + safeName
        prefs.edit().putString("history_events", (listOf(event) + old.split("\n").filter { it.isNotBlank() }).take(200).joinToString("\n")).apply()
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
        names.forEach { entry ->
            val transferredName = entry.substringAfter(" · ", entry).substringBefore(" · ")
            if (transferredName.isNotBlank()) appendHistoryEvent("SENT", transferredName)
        }
    }

    /**
     * Imports records created by older NEXORA builds into the combined History view.
     * It runs once, keeps existing events, and reads the app's own Downloads/NEXORA folder.
     */
    private fun migrateLegacyTransferHistory() {
        val prefs = getSharedPreferences("nexora_transfer_history", MODE_PRIVATE)
        if (prefs.getBoolean("legacy_history_migrated_v1", false)) return

        val events = (prefs.getString("history_events", "") ?: "")
            .split("\n").filter { it.isNotBlank() }.toMutableList()

        fun alreadyRecorded(timestamp: Long, direction: String, name: String): Boolean {
            return events.any { line ->
                val parts = line.split("|", limit = 3)
                if (parts.size != 3 || parts[1] != direction || parts[2] != name) false
                else {
                    val existingTime = parts[0].toLongOrNull() ?: return@any false
                    kotlin.math.abs(existingTime - timestamp) < 120_000L
                }
            }
        }

        fun addLegacyEvent(timestamp: Long, direction: String, rawName: String) {
            val name = rawName.replace("|", "_").replace("\n", " ").take(240)
            if (name.isBlank() || alreadyRecorded(timestamp, direction, name)) return
            events.add(timestamp.toString() + "|" + direction + "|" + name)
        }

        val sentStamp = java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault()).apply {
            isLenient = false
        }
        val now = java.util.Calendar.getInstance()
        (prefs.getString("sent_items", "") ?: "").split("\n").filter { it.isNotBlank() }.forEach { entry ->
            val stampText = entry.substringBefore(" · ")
            val name = entry.substringAfter(" · ", "").substringBefore(" · ")
            val parsed = runCatching { sentStamp.parse(stampText)?.time }.getOrNull()
            val timestamp = parsed?.let { parsedTime ->
                val candidate = java.util.Calendar.getInstance().apply {
                    timeInMillis = parsedTime
                    set(java.util.Calendar.YEAR, now.get(java.util.Calendar.YEAR))
                }
                // Legacy labels omitted the year. If the resulting date is in the
                // future, it belongs to the previous year (e.g. a December record in January).
                if (candidate.timeInMillis > System.currentTimeMillis() + 86_400_000L) {
                    candidate.add(java.util.Calendar.YEAR, -1)
                }
                candidate.timeInMillis
            } ?: System.currentTimeMillis()
            addLegacyEvent(timestamp, "SENT", name)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val projection = arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DATE_ADDED
                )
                contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    MediaStore.MediaColumns.RELATIVE_PATH + " = ?",
                    arrayOf("Download/NEXORA/"),
                    MediaStore.MediaColumns.DATE_ADDED + " DESC"
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0) ?: "Received file"
                        val addedAt = cursor.getLong(1)
                        addLegacyEvent(addedAt * 1000L, "RECEIVED", name)
                    }
                }
            }
        }

        prefs.edit()
            .putString("history_events", events.sortedByDescending { it.substringBefore("|").toLongOrNull() ?: 0L }.take(200).joinToString("\n"))
            .putBoolean("legacy_history_migrated_v1", true)
            .apply()
    }

    private fun showReceived(card: Int, ink: Int, muted: Int) {
        content.addView(TextView(this).apply {
            text = "Received files"
            textSize = 27f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            setTextColor(ink)
            setPadding(dp(2), dp(12), dp(2), dp(3))
        })
        content.addView(TextView(this).apply {
            text = "Files saved by NEXORA are kept in Download/NEXORA."
            textSize = 12f
            setTextColor(muted)
            setPadding(dp(3), 0, 0, dp(15))
        })

        val hasActiveIncoming = sharing?.hasIncomingTransfer() == true
        val incoming = panel(card).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF0B4FB3.toInt(), 0xFF173A78.toInt(), 0xFF8A243A.toInt())
            ).apply { cornerRadius = dp(22).toFloat(); setStroke(dp(1), 0xFFFFD21F.toInt()) }
        }
        incoming.addView(label("INCOMING TRANSFER", 0xFFFFD66B.toInt()))
        incomingStatusView = TextView(this).apply {
            text = latestIncomingProgress
            textSize = 13f
            setTextColor(-1)
            setPadding(0, dp(10), 0, dp(10))
        }
        incoming.addView(incomingStatusView)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pause = button("PAUSE", 0xFF0B4FB3.toInt()) {
            sharing?.pauseIncoming()
            incomingStatusView?.text = "Pausing incoming transfer…"
        }
        pause.isEnabled = hasActiveIncoming && sharing?.isIncomingPaused() != true
        val resume = button("RESUME", 0xFFE69A35.toInt()) {
            sharing?.resumeIncoming()
            incomingStatusView?.text = "Resuming incoming transfer…"
        }
        resume.isEnabled = hasActiveIncoming && sharing?.isIncomingPaused() == true
        val cancel = button("CANCEL", 0xFFE52B3A.toInt()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Cancel this incoming item?")
                .setMessage("Only the current item will be cancelled. The NEXORA connection will remain open.")
                .setNegativeButton("Keep transfer", null)
                .setPositiveButton("Cancel item") { _, _ ->
                    sharing?.cancelIncoming()
                    incomingStatusView?.text = "Cancelling this item…"
                }
                .show()
        }
        cancel.isEnabled = hasActiveIncoming
        controls.addView(pause, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(4) })
        controls.addView(resume, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
        controls.addView(cancel, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(4) })
        incoming.addView(controls)
        content.addView(incoming, bottom(13))

        val queuePanel = panel(card)
        queuePanel.addView(label("TRANSFER QUEUE", 0xFFFFD21F.toInt()))
        incomingQueueView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        queuePanel.addView(incomingQueueView)
        content.addView(queuePanel, bottom(12))
        renderIncomingQueue()

        val box = panel(card)
        box.addView(label("SAVED ON THIS PHONE", 0xFF0B4FB3.toInt()))
        receivedList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(receivedList)
        content.addView(box)
        refreshReceivedFiles()
    }

    private fun renderIncomingQueue() {
        val target = incomingQueueView ?: return
        target.removeAllViews()
        if (incomingQueueItems.isEmpty()) {
            target.addView(TextView(this).apply {
                text = "Files in a batch will appear here, with individual progress and cancel controls."
                textSize = 12f
                setTextColor(if (darkMode) 0xFFCAD5E2.toInt() else 0xFF64748B.toInt())
                setPadding(0, dp(8), 0, dp(4))
            })
            return
        }
        incomingQueueItems.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, dp(9))
            }
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            details.addView(TextView(this).apply {
                text = item.name
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(if (darkMode) -1 else 0xFF172B45.toInt())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            details.addView(TextView(this).apply {
                text = item.status + "  ·  " + readableSize(item.size) +
                    if (item.status == "Receiving") "  ·  " + item.progress + "%" else ""
                textSize = 10f
                setTextColor(if (darkMode) 0xFFB8C7D8.toInt() else 0xFF64748B.toInt())
                setPadding(0, dp(3), 0, 0)
            })
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            if (item.status == "Receiving") {
                val paused = sharing?.isIncomingPaused() == true
                val toggle = button(if (paused) "RESUME" else "PAUSE", 0xFF0B4FB3.toInt()) {
                    if (sharing?.isIncomingPaused() == true) sharing?.resumeIncoming()
                    else sharing?.pauseIncoming()
                }
                toggle.textSize = 9f
                row.addView(toggle, LinearLayout.LayoutParams(dp(78), dp(38)).apply { rightMargin = dp(4) })
            }
            val cancel = button("CANCEL", 0xFFE52B3A.toInt()) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Cancel " + item.name + "?")
                    .setMessage("This item will be skipped or cancelled. Other queued files can continue.")
                    .setNegativeButton("Keep", null)
                    .setPositiveButton("Cancel item") { _, _ -> sharing?.cancelIncomingItem(item.id) }
                    .show()
            }
            cancel.textSize = 9f
            cancel.isEnabled = item.status == "Queued" || item.status == "Receiving"
            row.addView(cancel, LinearLayout.LayoutParams(dp(78), dp(38)))
            target.addView(row)
            target.addView(View(this).apply {
                setBackgroundColor(if (darkMode) 0xFF30445B.toInt() else 0xFFE5EAF0.toInt())
            }, LinearLayout.LayoutParams(-1, dp(1)))
        }
    }

    private fun showHistory(card: Int, ink: Int, muted: Int) {
        migrateLegacyTransferHistory()
        val events = getSharedPreferences("nexora_transfer_history", MODE_PRIVATE)
            .getString("history_events", "")?.split("\n")?.mapNotNull { line ->
                val parts = line.split("|", limit = 3)
                if (parts.size == 3) Triple(parts[0].toLongOrNull() ?: 0L, parts[1], parts[2]) else null
            }?.sortedByDescending { it.first } ?: emptyList()
        val sentCount = events.count { it.second == "SENT" }
        val receivedCount = events.count { it.second == "RECEIVED" }

        content.addView(TextView(this).apply {
            text = "Transfer history"
            textSize = 27f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            setTextColor(ink)
            setPadding(dp(2), dp(12), dp(2), dp(3))
        })
        content.addView(TextView(this).apply {
            text = "A timeline of files shared from and to this phone."
            textSize = 12f
            setTextColor(muted)
            setPadding(dp(3), 0, 0, dp(14))
        })

        val summary = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            Triple("SENT", sentCount, 0xFF0B4FB3.toInt()),
            Triple("RECEIVED", receivedCount, 0xFFE52B3A.toInt())
        ).forEach { item ->
            val tile = panel(card).apply {
                addView(label(item.first, item.third))
                addView(TextView(this@MainActivity).apply {
                    text = item.second.toString()
                    textSize = 25f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(ink)
                    setPadding(0, dp(5), 0, 0)
                })
            }
            summary.addView(tile, LinearLayout.LayoutParams(0, dp(82), 1f).apply {
                if (item.first == "SENT") rightMargin = dp(5) else leftMargin = dp(5)
            })
        }
        content.addView(summary, bottom(13))

        val box = panel(card)
        box.addView(label("ALL TRANSFERS · NEWEST FIRST", 0xFF0B4FB3.toInt()))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list)
        if (events.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Your sent and received transfers will appear here."
                textSize = 12f
                setTextColor(muted)
                setPadding(0, dp(12), 0, dp(12))
            })
        } else {
            events.take(200).forEach { event ->
                val stamp = java.text.SimpleDateFormat("dd MMM yyyy · HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(event.first))
                val sent = event.second == "SENT"
                val accent = if (sent) 0xFF0B4FB3.toInt() else 0xFFE52B3A.toInt()
                val entry = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(11), dp(10), dp(11), dp(10))
                    background = rounded(if (darkMode) 0xFF20364E.toInt() else 0xFFF1F6FB.toInt(), 12)
                }
                entry.addView(TextView(this).apply {
                    text = if (sent) "↑" else "↓"
                    textSize = 21f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(-1)
                    background = rounded(accent, 10)
                }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(10) })
                val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                details.addView(TextView(this@MainActivity).apply {
                    text = event.third
                    textSize = 12f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(ink)
                    maxLines = 2
                })
                details.addView(TextView(this@MainActivity).apply {
                    text = (if (sent) "SENT" else "RECEIVED") + "  ·  " + stamp
                    textSize = 10f
                    setTextColor(muted)
                    setPadding(0, dp(3), 0, 0)
                })
                entry.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
                list.addView(entry, bottom(6))
            }
        }
        content.addView(box)
    }


    private fun showXenderMenu() {
        val dialog = android.app.Dialog(this)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF102C40.toInt(), 0xFF0C4B48.toInt(), 0xFF4B3A17.toInt())
            ).apply { cornerRadius = dp(26).toFloat(); setStroke(dp(1), 0x88DDBA55.toInt()) }
        }
        sheet.addView(TextView(this).apply {
            text = "NEXORA"
            textSize = 21f
            letterSpacing = .08f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFD66B.toInt())
        })
        sheet.addView(TextView(this).apply {
            text = Build.MODEL ?: "This phone"
            textSize = 12f
            setTextColor(0xFFB9D4DE.toInt())
            setPadding(0, dp(3), 0, dp(14))
        })
        val options = listOf(
            "⚙" to "Settings",
            "?" to "Help & Feedback",
            "ⓘ" to "About NEXORA",
            "↻" to "Status & Updates",
            "文" to "Language",
            "★" to "Rate NEXORA"
        )
        options.forEach { (symbol, title) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(12), dp(13), dp(12))
                background = rounded(0x332A6170, 15)
                isHapticFeedbackEnabled = true
                setOnClickListener {
                    animatePress(this)
                    dialog.dismiss()
                    when (title) {
                        "Settings" -> { activeTab = "Settings"; render() }
                        "Help & Feedback" -> android.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle(title)
                            .setMessage("Keep both phones close, turn on Wi-Fi, and allow the Wi-Fi Direct permission when Android asks.")
                            .setPositiveButton("Got it", null).show()
                        "About NEXORA" -> android.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle(title)
                            .setMessage("NEXORA is a local file-sharing app. Transfers use a direct Wi-Fi connection between phones.")
                            .setPositiveButton("Close", null).show()
                        "Status & Updates" -> Toast.makeText(this@MainActivity, "You are using the current installed NEXORA build.", Toast.LENGTH_SHORT).show()
                        "Language" -> Toast.makeText(this@MainActivity, "English is selected.", Toast.LENGTH_SHORT).show()
                        else -> Toast.makeText(this@MainActivity, "Thank you for supporting NEXORA.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            row.addView(TextView(this).apply {
                text = symbol
                textSize = 19f
                gravity = Gravity.CENTER
                setTextColor(0xFFFFD66B.toInt())
            }, LinearLayout.LayoutParams(dp(38), dp(30)))
            row.addView(TextView(this).apply {
                text = title
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(-1)
                setPadding(dp(10), 0, 0, 0)
            })
            sheet.addView(row, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(7) })
        }
        val goHome = button("⌂  HOME", 0xFFB38A2E.toInt()) {
            dialog.dismiss(); activeTab = "Media"; mediaCategory = "Apps"; render()
        }
        sheet.addView(goHome, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(5) })
        val close = button("CLOSE", 0xFF168A83.toInt()) { dialog.dismiss() }
        sheet.addView(close, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(5) })
        dialog.setContentView(sheet)
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels - dp(34)), -2)
        sheet.alpha = 0f
        sheet.translationY = dp(28).toFloat()
        sheet.animate().alpha(1f).translationY(0f).setDuration(230)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
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
            content.addView(button("ALLOW CONTACT ACCESS", 0xFF0B4FB3.toInt()) {
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
                                activeTab = "Media"
                                mediaCategory = "Apps"
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
        content.addView(TextView(this).apply {
            text = "Settings"
            textSize = 27f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            setTextColor(ink)
            setPadding(dp(2), dp(12), dp(2), dp(3))
        })
        content.addView(TextView(this).apply {
            text = "Make NEXORA feel right on your phone."
            textSize = 12f
            setTextColor(muted)
            setPadding(dp(3), 0, 0, dp(15))
        })

        val appearance = panel(card).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF0B4FB3.toInt(), 0xFF173A78.toInt(), 0xFF8A243A.toInt())
            ).apply { cornerRadius = dp(22).toFloat(); setStroke(dp(1), 0xFFFFD21F.toInt()) }
        }
        appearance.addView(label("APPEARANCE", 0xFFFFD66B.toInt()))
        appearance.addView(TextView(this).apply {
            text = if (darkMode) "Dark theme is on" else "Light theme is on"
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(-1)
            setPadding(0, dp(7), 0, dp(9))
        })
        appearance.addView(button(if (darkMode) "SWITCH TO LIGHT THEME" else "SWITCH TO DARK THEME", 0xFF0B4FB3.toInt()) {
            darkMode = !darkMode
            getSharedPreferences("nexora_preferences", MODE_PRIVATE).edit().putBoolean("dark_mode", darkMode).apply()
            render()
        })
        content.addView(appearance, bottom(13))

        val currentName = getSharedPreferences("nexora_preferences", MODE_PRIVATE)
            .getString("device_name", Build.MODEL) ?: "Android device"
        val device = panel(card)
        device.addView(label("YOUR DEVICE", 0xFF0B4FB3.toInt()))
        device.addView(TextView(this).apply {
            text = currentName
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ink)
            setPadding(0, dp(7), 0, dp(3))
        })
        device.addView(TextView(this).apply {
            text = "This name is shown to phones that connect with you."
            textSize = 11f
            setTextColor(muted)
            setPadding(0, 0, 0, dp(10))
        })
        device.addView(button("CHANGE DEVICE NAME", 0xFFE52B3A.toInt()) {
            val field = EditText(this@MainActivity).apply {
                setText(currentName)
                setSelection(text.length)
                hint = "Phone name"
            }
            android.app.AlertDialog.Builder(this@MainActivity).setTitle("Set NEXORA device name").setView(field)
                .setPositiveButton("Save") { _, _ ->
                    val value = field.text.toString().trim()
                    if (value.isNotBlank()) {
                        getSharedPreferences("nexora_preferences", MODE_PRIVATE).edit()
                            .putString("device_name", value.take(24)).apply()
                        Toast.makeText(this@MainActivity, "Device name saved. Restart discovery to use it.", Toast.LENGTH_LONG).show()
                        render()
                    }
                }.setNegativeButton("Cancel", null).show()
        })
        content.addView(device, bottom(13))

        val transfer = panel(card)
        transfer.addView(label("TRANSFER & PRIVACY", 0xFF0B4FB3.toInt()))
        listOf(
            "Save folder" to "Download/NEXORA",
            "Connection" to "Wi-Fi Direct",
            "Transfer type" to "Direct phone-to-phone",
            "Internet" to "Not required for local transfers",
            "Privacy" to "You choose which files to share"
        ).forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(9), 0, dp(8))
            }
            row.addView(TextView(this).apply {
                text = item.first
                textSize = 10f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(muted)
            })
            row.addView(TextView(this).apply {
                text = item.second
                textSize = 13f
                setTextColor(ink)
                setPadding(0, dp(2), 0, 0)
            })
            transfer.addView(row)
        }
        content.addView(transfer, bottom(13))

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "Unknown"
        } catch (_: Exception) { "Unknown" }
        content.addView(TextView(this).apply {
            text = "NEXORA  ·  Version " + versionName + "\nBuilt for simple, private file sharing."
            textSize = 11f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(18))
        })
    }

    private fun animatePress(view: View) {
        view.isHapticFeedbackEnabled = true
        view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        if (Build.VERSION.SDK_INT >= 23 && view.foreground !is android.graphics.drawable.RippleDrawable) {
            view.foreground = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x557FEFD5),
                null,
                null
            )
        }
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.animate().scaleX(.94f).scaleY(.94f).setDuration(65).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(155)
                .setInterpolator(android.view.animation.OvershootInterpolator()).start()
        }.start()
    }

    private var quickActionPanel: LinearLayout? = null
    private val quickActionHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var quickActionHide: Runnable? = null

    private fun currentSelectionCount(): Int =
        if (activeTab == "Media" && mediaCategory == "Apps") selectedAppPackages.size else selectedUris.size

    private fun updateSelectionAction() {
        val count = currentSelectionCount()
        val button = selectionSendButton ?: return
        button.visibility = if (count > 0 && activeTab in setOf("Media", "Files")) View.VISIBLE else View.GONE
        button.isEnabled = count > 0 && !preparingApps
        if (count > 0) quickActionPanel?.visibility = View.GONE
    }

    private fun showQuickActions() {
        if (currentSelectionCount() > 0) { updateSelectionAction(); return }
        val panel = quickActionPanel ?: return
        quickActionHide?.let { quickActionHandler.removeCallbacks(it) }
        panel.visibility = View.VISIBLE
        panel.alpha = 0f
        panel.translationY = dp(18).toFloat()
        panel.scaleX = .94f
        panel.scaleY = .94f
        panel.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(220).setInterpolator(android.view.animation.OvershootInterpolator()).start()
        quickActionHide = Runnable { hideQuickActions() }
        quickActionHandler.postDelayed(quickActionHide!!, 10_000L)
    }

    private fun hideQuickActions() {
        val panel = quickActionPanel ?: return
        quickActionHide?.let { quickActionHandler.removeCallbacks(it) }
        quickActionHide = null
        if (panel.visibility != View.VISIBLE) return
        panel.animate().alpha(0f).translationY(dp(15).toFloat()).scaleX(.94f).scaleY(.94f)
            .setDuration(170).withEndAction { panel.visibility = View.GONE }.start()
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
                paint.color = android.graphics.Color.argb(((1f - p) * 90).toInt(), 73, 231, 202)
                canvas.drawCircle(cx, cy, max * (.25f + .75f * p), paint)
            }
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = dp(2).toFloat()
            paint.strokeCap = android.graphics.Paint.Cap.ROUND
            paint.color = 0xFF61EBD1.toInt()
            val mastTop = cy - dp(39)
            canvas.drawLine(cx, mastTop, cx, cy + dp(28), paint)
            canvas.drawLine(cx - dp(15), cy + dp(28), cx + dp(15), cy + dp(28), paint)
            canvas.drawArc(cx - dp(20).toFloat(), mastTop - dp(20), cx + dp(20).toFloat(), mastTop + dp(20), 215f, 110f, false, paint)
            canvas.drawArc(cx - dp(32).toFloat(), mastTop - dp(32), cx + dp(32).toFloat(), mastTop + dp(32), 215f, 110f, false, paint)
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = 0xFFB9FFF0.toInt()
            canvas.drawCircle(cx, mastTop, dp(4).toFloat(), paint)
            paint.color = 0xFF35D8C0.toInt()
            canvas.drawCircle(cx, cy + dp(28), dp(4).toFloat(), paint)
            paint.color = 0xFFDAFFF8.toInt()
            paint.textSize = dp(10).toFloat()
            paint.textAlign = android.graphics.Paint.Align.CENTER
            canvas.drawText("NEXORA  •  WI-FI DIRECT", cx, cy + dp(58), paint)
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
            if (darkMode) intArrayOf(0xFF14284A.toInt(), 0xFF192A45.toInt(), 0xFF3B2435.toInt()) else intArrayOf(0xFFFFFEF5.toInt(), 0xFFFFF3D2.toInt(), 0xFFEAF1FF.toInt())
        ).apply { cornerRadius = dp(21).toFloat(); setStroke(dp(1), if (darkMode) 0x665CCCB4 else 0xFFE7D8A2.toInt()) }
        elevation = dp(3).toFloat()
    }
    private fun label(value: String, color: Int) = TextView(this).apply { text = value; textSize = 10f; letterSpacing = .1f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(color) }
    private fun button(value: String, color: Int, action: () -> Unit) = Button(this).apply {
        text = value; textSize = 12f; isAllCaps = false; typeface = android.graphics.Typeface.DEFAULT_BOLD
        val brand = when {
            android.graphics.Color.red(color) > 180 && android.graphics.Color.green(color) > 100 && android.graphics.Color.blue(color) < 130 -> 0xFFFFD21F.toInt()
            android.graphics.Color.red(color) > 150 && android.graphics.Color.green(color) < 150 -> 0xFFE52B3A.toInt()
            else -> 0xFF0B4FB3.toInt()
        }
        setTextColor(if (brand == 0xFFFFD21F.toInt()) 0xFF10234B.toInt() else -1)
        background = rounded(brand, 15)
        setPadding(dp(10), dp(9), dp(10), dp(9))
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
            peerList.addView(TextView(this).apply { text = "No phone connected yet. Scan a NEXORA Wi-Fi Direct QR code to connect."; textSize = 11f; setTextColor(if (darkMode) 0xFFB3C0D5.toInt() else 0xFF64748B.toInt()); setPadding(0, dp(8), 0, dp(8)) })
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
                text = connectedPeers.size.toString() + " phone(s) connected · ready to send"
                textSize = 11f
                setTextColor(if (darkMode) 0xFF8FE4C5.toInt() else 0xFF168A70.toInt())
                setPadding(dp(4), dp(5), dp(4), dp(3))
            })
        }
    }

    private fun withWifiDirectPermissions(action: () -> Unit) {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.NEARBY_WIFI_DEVICES
        else if (Build.VERSION.SDK_INT >= 29) needed += Manifest.permission.ACCESS_FINE_LOCATION
        else needed += Manifest.permission.ACCESS_COARSE_LOCATION
        val missing = needed.distinct().filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else {
            pendingWifiDirectAction = action
            requestPermissions(missing.toTypedArray(), REQUEST_WIFI_DIRECT)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            val resume = pendingCameraScan
            pendingCameraScan = false
            if (granted && resume) launchQrScanner()
            else updateStatus("Camera permission is required to scan a QR code.")
            return
        }
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
        if (requestCode == REQUEST_WIFI_DIRECT) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                val resume = pendingWifiDirectAction
                pendingWifiDirectAction = null
                if (resume != null) resume()
                else Toast.makeText(this, "Permissions granted.", Toast.LENGTH_SHORT).show()
            } else {
                pendingWifiDirectAction = null
                updateStatus("Wi-Fi Direct permission is needed to connect to phones.")
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
                if (contents.startsWith("NEXORA-WFD:")) {
                    pendingQrDeviceName = null
                    withWifiDirectPermissions {
                        runWithSharingService { service ->
                            updateStatus("QR recognised. Checking the Wi-Fi Direct connection…")
                            service.connectWithQr(contents)
                        }
                    }
                } else Toast.makeText(this, "That is not a NEXORA Wi-Fi Direct QR code.", Toast.LENGTH_LONG).show()
            } else {
                pendingQrDeviceName = null
                pendingSelectedSend = false
                selectedUris.clear()
                selectedAppPackages.clear()
                java.io.File(cacheDir, "shared_apks").listFiles()?.forEach { it.delete() }
                updateSelectionAction()
                updateStatus("Connection cancelled. Your pending selection was cleared.")
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
                activeTab = "Media"
                mediaCategory = "Apps"
                render()
                updateStatus("${selectedUris.size} file(s) selected. Tap SEND SELECTED to find a receiving phone.")
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
                    val fileHeading = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val filePreview = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackground(rounded(if (darkMode) 0xFF29445B.toInt() else 0xFFE7EEF7.toInt(), 11))
                        setImageResource(if (name.endsWith(".apk", true)) android.R.drawable.sym_def_app_icon else android.R.drawable.ic_menu_gallery)
                    }
                    fileHeading.addView(filePreview, LinearLayout.LayoutParams(dp(54), dp(54)))
                    fileHeading.addView(TextView(this).apply {
                        text = name + "\n" + size
                        textSize = 12f
                        maxLines = 2
                        setTextColor(if (darkMode) -1 else 0xFF14213D.toInt())
                        setPadding(dp(10), 0, 0, 0)
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(fileHeading)
                    thumbnailExecutor.execute {
                        var icon: android.graphics.drawable.Drawable? = null
                        var thumb: android.graphics.Bitmap? = null
                        try {
                            if (name.endsWith(".apk", true)) {
                                val folder = java.io.File(cacheDir, "received_history_icons").apply { mkdirs() }
                                val temp = java.io.File(folder, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                                contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
                                val info = packageManager.getPackageArchiveInfo(temp.absolutePath, 0)
                                val appInfo = info?.applicationInfo
                                if (appInfo != null) {
                                    appInfo.sourceDir = temp.absolutePath
                                    appInfo.publicSourceDir = temp.absolutePath
                                    icon = packageManager.getApplicationIcon(appInfo)
                                }
                            } else if (Build.VERSION.SDK_INT >= 29) {
                                thumb = contentResolver.loadThumbnail(uri, android.util.Size(96, 96), null)
                            }
                        } catch (_: Exception) {}
                        runOnUiThread {
                            if (filePreview.parent != null) {
                                if (icon != null) filePreview.setImageDrawable(icon)
                                else if (thumb != null) filePreview.setImageBitmap(thumb)
                            }
                        }
                    }
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

    companion object { private const val REQUEST_WIFI_DIRECT = 2001; private const val REQUEST_FILE = 2002; private const val REQUEST_MEDIA = 2003; private const val REQUEST_TREE = 2004; private const val REQUEST_CAMERA = 2005 }
}
