package com.jarvis.assistant.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * JARVIS's long-term memory — file-backed, 100% on-device.
 * "mera naam Rohan hai", "mujhe chai pasand hai", "mera birthday 5 March hai"...
 */
class MemoryStore(private val context: Context) {

    private val file = File(context.filesDir, "jarvis_memory.json")
    private val lock = ReentrantLock()
    private val cache = loadLocked()

    private fun loadLocked(): MutableMap<String, String> {
        if (!file.exists()) return mutableMapOf()
        return try {
            val obj = JSONObject(file.readText())
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { map[it] = obj.getString(it) }
            map
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun saveLocked() {
        try {
            val obj = JSONObject()
            cache.forEach { (k, v) -> obj.put(k, v) }
            file.writeText(obj.toString())
        } catch (_: Exception) {}
    }

    suspend fun remember(key: String, value: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            cache[key] = value
            saveLocked()
        }
    }

    suspend fun recall(key: String): String? = withContext(Dispatchers.IO) {
        lock.withLock { cache[key] }
    }

    suspend fun all(): Map<String, String> = withContext(Dispatchers.IO) {
        lock.withLock { cache.toMap() }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        lock.withLock {
            cache.clear()
            saveLocked()
        }
    }
}
