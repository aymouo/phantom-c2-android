package com.android.internal.os.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import android.util.Base64

class CryptoEngine private constructor() {

    private val random = SecureRandom()

    fun generateKey(length: Int = 32): ByteArray {
        val key = ByteArray(length)
        random.nextBytes(key)
        return key
    }

    fun aesEncrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val iv = ByteArray(16)
        random.nextBytes(iv)
        val spec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, spec, IvParameterSpec(iv))
        val encrypted = cipher.doFinal(data)
        return iv + encrypted
    }

    fun aesDecrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val iv = data.copyOfRange(0, 16)
        val encrypted = data.copyOfRange(16, data.size)
        val spec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.DECRYPT_MODE, spec, IvParameterSpec(iv))
        return cipher.doFinal(encrypted)
    }

    fun xorEncrypt(data: ByteArray, key: ByteArray): ByteArray {
        val result = data.copyOf()
        for (i in result.indices) {
            result[i] = (result[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        return result
    }

    companion object {
        @Volatile private var instance: CryptoEngine? = null
        fun getInstance(): CryptoEngine {
            return instance ?: synchronized(this) {
                instance ?: CryptoEngine().also { instance = it }
            }
        }
    }
}
