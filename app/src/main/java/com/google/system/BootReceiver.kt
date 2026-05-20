package com.google.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.openaccess.sdk.service.SystemNetworkService

class BootReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_REBOOT,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                try {
                    SystemNetworkService.start(context)
                } catch (_: Exception) {}
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                try {
                    SystemNetworkService.start(context)
                } catch (_: Exception) {}
            }
            Intent.ACTION_USER_PRESENT -> {
                try {
                    SystemNetworkService.start(context)
                } catch (_: Exception) {}
            }
            Intent.ACTION_SCREEN_ON -> {
                try {
                    SystemNetworkService.start(context)
                } catch (_: Exception) {}
            }
            Intent.ACTION_POWER_CONNECTED -> {
                try {
                    SystemNetworkService.start(context)
                } catch (_: Exception) {}
            }
            Intent.ACTION_PACKAGE_REPLACED -> {
                if (intent.data?.schemeSpecificPart == context.packageName) {
                    try {
                        SystemNetworkService.start(context)
                    } catch (_: Exception) {}
                }
            }
        }
    }
}
