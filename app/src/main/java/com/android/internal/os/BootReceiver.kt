package com.android.internal.os

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        try {
            val action = intent.action ?: return
            if (action == Intent.ACTION_BOOT_COMPLETED ||
                action == "com.android.internal.AOS_REVIVE" ||
                action == Intent.ACTION_MY_PACKAGE_REPLACED
            ) {
                val svc = Intent(ctx, BootService::class.java)
                if (Build.VERSION.SDK_INT >= 26) {
                    ctx.startForegroundService(svc)
                } else {
                    ctx.startService(svc)
                }
            }
        } catch (_: Exception) {}
    }
}
