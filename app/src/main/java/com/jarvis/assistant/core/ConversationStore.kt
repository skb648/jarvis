package com.jarvis.assistant.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Conversation history — saved on-device, exportable to any app.
 */
object ConversationStore {

    data class Entry(val role: String, val text: String, val emotion: String)

    private const val MAX_ENTRIES = 80
    private val lock = ReentrantLock()

    private fun file(context: Context): File = File(context.filesDir, "jarvis_conversation.json")

    suspend fun load(context: Context): List<Entry> = withContext(Dispatchers.IO) {
        lock.withLock {
            val f = file(context)
            if (!f.exists()) return@withLock emptyList()
            try {
                val arr = JSONArray(f.readText())
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    Entry(o.optString("role"), o.optString("text"), o.optString("emotion"))
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    suspend fun save(context: Context, entries: List<Entry>) = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                val arr = JSONArray()
                entries.takeLast(MAX_ENTRIES).forEach { e ->
                    arr.put(
                        JSONObject()
                            .put("role", e.role)
                            .put("text", e.text)
                            .put("emotion", e.emotion)
                    )
                }
                file(context).writeText(arr.toString())
            } catch (_: Exception) {}
        }
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        lock.withLock { runCatching { file(context).delete() } }
    }

    /** Export the saved history to a shareable file in cache. */
    suspend fun export(context: Context): File? = withContext(Dispatchers.IO) {
        lock.withLock {
            val src = file(context)
            if (!src.exists()) return@withLock null
            val out = File(context.cacheDir, "jarvis_history.json")
            src.copyTo(out, overwrite = true)
            out
        }
    }
}
