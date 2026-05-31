package com.google.system

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GatewayWebSocket(
    private val scope: CoroutineScope,
    private val callbacks: Callbacks,
) {
    companion object {
        private const val OP_DISPATCH = 0
        private const val OP_HEARTBEAT = 1
        private const val OP_IDENTIFY = 2
        private const val OP_RESUME = 6
        private const val OP_RECONNECT = 7
        private const val OP_INVALID_SESSION = 9
        private const val OP_HELLO = 10
        private const val OP_HEARTBEAT_ACK = 11
        private val FATAL_CLOSE_CODES = setOf(4004, 4010, 4011, 4012, 4013, 4014)
    }

    interface Callbacks {
        fun onStatus(s: String)
        fun onDispatch(type: String, data: Any?)
        fun onConnected()
        fun onDisconnected(code: Int)
        fun onFatalError(code: Int, reason: String)
        fun onHeartbeatTimeout()
        fun isClosing(): Boolean
    }

    private val wsClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var heartbeatJob: Job? = null
    @Volatile private var reconnectJob: Job? = null
    @Volatile private var connectionMonitorJob: Job? = null
    @Volatile var heartbeatInterval = 41250L
    @Volatile var seq: Int? = null
    @Volatile var sessionId: String? = null
    @Volatile var reconnectAttempt = 0
    @Volatile private var reconnecting = false
    @Volatile var resuming = false
    @Volatile var fatalError = false
    @Volatile var gaveUpAt = 0L
    @Volatile private var lastHeartbeatAck = 0L
    private var connectVersion = 0L
    private var monitorStarted = false

    fun isConnected(): Boolean = ws != null && !callbacks.isClosing() && !fatalError

    fun connect() {
        if (callbacks.isClosing()) return
        if (fatalError) {
            if (gaveUpAt == 0L) gaveUpAt = System.currentTimeMillis()
            if (System.currentTimeMillis() - gaveUpAt < 300000L) return
            fatalError = false
            gaveUpAt = 0L
            reconnectAttempt = 0
            callbacks.onStatus("Recovery - reconnecting")
        }
        connectVersion++
        try { ws?.close(1000, "reconnecting") } catch (_: Exception) {}
        ws = null
        val req = Request.Builder().url(DiscordConfig.GATEWAY_URL).build()
        try {
            ws = wsClient.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    reconnecting = false
                    callbacks.onStatus("WS open")
                    callbacks.onConnected()
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    ws.close(code, reason)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    handleClose(code, reason)
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    callbacks.onStatus("WS fail")
                    if (!callbacks.isClosing()) scheduleReconnect()
                }
            })
        } catch (_: Exception) {
            callbacks.onStatus("Conn err")
            if (!callbacks.isClosing()) scheduleReconnect()
        }
        if (!monitorStarted) {
            monitorStarted = true
            startConnectionMonitor()
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        connectionMonitorJob?.cancel()
        try { ws?.close(1000, "shutdown") } catch (_: Exception) {}
        ws = null
    }

    fun sendRaw(json: String) {
        ws?.send(json)
    }

    private fun handleClose(code: Int, reason: String) {
        if (callbacks.isClosing()) {
            reconnecting = false
            return
        }
        callbacks.onStatus("Close $code")
        if (code in FATAL_CLOSE_CODES) {
            callbacks.onStatus("Fatal $code")
            fatalError = true
            gaveUpAt = System.currentTimeMillis()
            reconnecting = false
            callbacks.onFatalError(code, reason)
            return
        }
        if (code == 1006 || code == 1001 || code == 1012) {
            reconnectAttempt = 0
        }
        callbacks.onDisconnected(code)
        scheduleReconnect()
    }

    private fun handleMessage(text: String) {
        try {
            val msg = JSONObject(text)
            val op = msg.optInt("op", -1)
            val d = msg.opt("d")
            val s = msg.optInt("s", -1)
            if (s > 0) {
                seq = s
            }
            when (op) {
                OP_HELLO -> {
                    val hello = d as JSONObject
                    heartbeatInterval = hello.optLong("heartbeat_interval", 41250)
                    reconnectJob?.cancel()
                    if (resuming && sessionId != null) {
                        resuming = false
                        callbacks.onStatus("Resuming...")
                        resume()
                    } else {
                        resuming = false
                        sessionId = null
                        callbacks.onStatus("Identifying...")
                        identify()
                    }
                    startHeartbeat()
                }
                OP_DISPATCH -> callbacks.onDispatch(msg.optString("t", ""), d)
                OP_HEARTBEAT_ACK -> {
                    lastHeartbeatAck = System.currentTimeMillis()
                }
                OP_RECONNECT -> {
                    resuming = sessionId != null
                    scheduleReconnect()
                }
                OP_INVALID_SESSION -> {
                    val canResume = d as? Boolean ?: false
                    if (canResume && sessionId != null) {
                        resuming = true
                        scheduleReconnect()
                    } else {
                        sessionId = null
                        seq = null
                        resuming = false
                        callbacks.onStatus("Session rejected, re-identifying")
                        scheduleReconnect()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun identify() {
        val payload = JSONObject().apply {
            put("op", OP_IDENTIFY)
            put("d", JSONObject().apply {
                put("token", DiscordConfig.BOT_TOKEN)
                put("intents", DiscordConfig.INTENTS)
                put("properties", JSONObject().apply {
                    put("os", "android")
                    put("browser", "okhttp")
                    put("device", "android")
                })
            })
        }
        ws?.send(payload.toString())
    }

    private fun resume() {
        val sid = sessionId ?: return
        val payload = JSONObject().apply {
            put("op", OP_RESUME)
            put("d", JSONObject().apply {
                put("token", DiscordConfig.BOT_TOKEN)
                put("session_id", sid)
                put("seq", seq ?: JSONObject.NULL)
            })
        }
        ws?.send(payload.toString())
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        lastHeartbeatAck = System.currentTimeMillis()
        heartbeatJob = scope.launch {
            while (isActive) {
                val payload = JSONObject().apply {
                    put("op", OP_HEARTBEAT)
                    put("d", seq ?: JSONObject.NULL)
                }
                ws?.send(payload.toString())
                delay(heartbeatInterval)
                val now = System.currentTimeMillis()
                if (now - lastHeartbeatAck > heartbeatInterval * 10) {
                    callbacks.onStatus("Heartbeat timeout")
                    callbacks.onHeartbeatTimeout()
                    try { ws?.close(1001, "hb timeout") } catch (_: Exception) {}
                    ws = null
                    scheduleReconnect()
                    break
                }
            }
        }
    }

    private fun startConnectionMonitor() {
        connectionMonitorJob = scope.launch {
            while (isActive) {
                delay(60000)
                if (callbacks.isClosing()) break
                if (ws == null && !reconnecting && !resuming && !callbacks.isClosing()) {
                    android.util.Log.w("GatewayWS", "Conn monitor: no ws, reconnecting")
                    callbacks.onStatus("Conn monitor: reconnecting")
                    scheduleReconnect()
                }
            }
        }
    }

    private fun scheduleReconnect() {
        synchronized(this) {
            if (reconnecting) return
            reconnecting = true
        }
        reconnectJob?.cancel()
        val scheduleVersion = connectVersion
        if (callbacks.isClosing()) return
        reconnectJob = scope.launch {
            if (callbacks.isClosing()) return@launch
            val delay = (DiscordConfig.RECONNECT_BASE_DELAY * (1 shl reconnectAttempt.coerceAtMost(10)))
                .coerceAtMost(DiscordConfig.MAX_RECONNECT_DELAY)
            reconnectAttempt++
            callbacks.onStatus("Recon $reconnectAttempt")
            delay(delay)
            reconnecting = false
            if (!callbacks.isClosing() && connectVersion == scheduleVersion) connect()
        }
    }
}
