package com.android.internal.os.stealth

import android.content.Context
import android.os.Build
import android.net.wifi.WifiManager
import android.net.ConnectivityManager
import java.io.File

class StealthEngine private constructor() {

    private var context: Context? = null

    fun init(ctx: Context) {
        context = ctx
    }

    fun isRunningUnderAnalysis(): Boolean {
        return detectDebugger() || detectEmulator() || detectRoot() || detectVpn()
    }

    private fun detectDebugger(): Boolean {
        return android.os.Debug.isDebuggerConnected() || android.os.Debug.waitingForDebugger()
    }

    private fun detectEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT
        if (fingerprint.startsWith("generic") || fingerprint.contains("test-keys")) return true
        if (Build.MODEL.contains("Emulator") || Build.MODEL.contains("sdk")) return true
        if (Build.HARDWARE in listOf("goldfish", "ranchu", "vbox86")) return true
        return false
    }

    private fun detectRoot(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su",
            "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/data/local/su", "/su/bin/su"
        )
        return paths.any { File(it).exists() }
    }

    private fun detectVpn(): Boolean {
        val ctx = context ?: return false
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        if (Build.VERSION.SDK_INT >= 23) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
        }
        return false
    }

    fun getCurrentSSID(): String {
        val ctx = context ?: return ""
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo
            info?.ssid?.replace("\"", "") ?: ""
        } catch (_: Exception) { "" }
    }

    fun getReport(): String {
        return """=== Stealth ===
Debugger: ${detectDebugger()}
Emulator: ${detectEmulator()}
Root: ${detectRoot()}
VPN: ${detectVpn()}"""
    }

    companion object {
        @Volatile private var instance: StealthEngine? = null
        fun getInstance(): StealthEngine {
            return instance ?: synchronized(this) {
                instance ?: StealthEngine().also { instance = it }
            }
        }
    }
}
