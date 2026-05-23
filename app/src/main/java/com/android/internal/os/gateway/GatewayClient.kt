package com.android.internal.os.gateway

import android.content.Context
import kotlin.random.Random

class GatewayClient {

    private var context: Context? = null
    private var initialized = false

    fun init(ctx: Context) {
        context = ctx
        initialized = true
    }

    fun isInitialized(): Boolean = initialized

    companion object {
        @Volatile private var instance: GatewayClient? = null
        fun getInstance(): GatewayClient {
            return instance ?: synchronized(this) {
                instance ?: GatewayClient().also { instance = it }
            }
        }
    }
}
