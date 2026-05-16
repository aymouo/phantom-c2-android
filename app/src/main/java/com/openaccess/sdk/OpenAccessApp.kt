package com.openaccess.sdk

import android.app.Application
import android.util.Log

class OpenAccessApp : Application() {
    companion object {
        private const val TAG = "OpenAccess"
    }

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            Log.e(TAG, "CRASH: ${ex.message}", ex)
        }
    }
}
