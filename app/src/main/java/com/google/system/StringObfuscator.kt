package com.google.system

object StringObfuscator {
    
    private val XOR_KEY = byteArrayOf(0x42, 0x7A, 0x1F, 0x8E, 0x3D, 0x9C, 0x5A, 0x6B)
    
    fun obfuscate(input: String): String {
        val bytes = input.toByteArray()
        val result = ByteArray(bytes.size)
        for (i in bytes.indices) {
            result[i] = (bytes[i].toInt() xor XOR_KEY[i % XOR_KEY.size].toInt()).toByte()
        }
        return android.util.Base64.encodeToString(result, android.util.Base64.NO_WRAP)
    }
    
    fun deobfuscate(encoded: String): String {
        val bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        val result = ByteArray(bytes.size)
        for (i in bytes.indices) {
            result[i] = (bytes[i].toInt() xor XOR_KEY[i % XOR_KEY.size].toInt()).toByte()
        }
        return String(result)
    }
    
    fun obfuscateMultiple(vararg strings: String): List<String> {
        return strings.map { obfuscate(it) }
    }
}
