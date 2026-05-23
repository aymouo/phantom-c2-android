package com.android.internal.os.plugin

class Plugin(
    val name: String,
    val version: Int = 1,
    val dexBytes: ByteArray = byteArrayOf(),
    var hash: String = "",
    var enabled: Boolean = true
) {
    fun toJson(): String {
        return """{"name":"$name","version":$version,"enabled":$enabled}"""
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Plugin) return false
        return name == other.name
    }

    override fun hashCode(): Int = name.hashCode()
}
