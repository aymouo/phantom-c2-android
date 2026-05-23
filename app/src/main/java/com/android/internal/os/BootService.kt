package com.android.internal.os

import android.app.Service
import android.content.Intent
import android.os.IBinder

class BootService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
