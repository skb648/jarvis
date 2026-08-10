package com.jarvis.assistant.control

import android.content.Context
import com.jarvis.assistant.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Home Assistant integration — "lights on", "fan off", "AC 24 degree".
 * Settings me HA URL + long-lived token daalo (Settings > Smart Home).
 */
class SmartHomeController(private val context: Context) {

    private val app = context.applicationContext as JarvisApp
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val entityMap = mapOf(
        "lights" to listOf("light.all_lights", "light.living_room", "light.bedroom"),
        "fan" to listOf("fan.all_fans", "fan.living_room"),
        "ac" to listOf("climate.ac", "climate.living_room_ac")
    )

    suspend fun execute(device: String, action: String, value: Int?): String = withContext(Dispatchers.IO) {
        val settings = app.settings.settings.first()
        val base = settings.haUrl.trimEnd('/')
        if (base.isEmpty() || settings.haToken.isEmpty()) {
            return@withContext "Smart home ke liye Settings me Home Assistant URL aur token daalo."
        }
        val entities = entityMap[device] ?: return@withContext "$device support nahi karta abhi."
        val token = settings.haToken

        val results = mutableListOf<String>()
        for (entity in entities) {
            val (domain, service) = when (device) {
                "ac" -> when (action) {
                    "temp" -> "climate" to "set_temperature"
                    "on" -> "climate" to "turn_on"
                    else -> "climate" to "turn_off"
                }
                else -> when (action) {
                    "on" -> domainOf(entity) to "turn_on"
                    else -> domainOf(entity) to "turn_off"
                }
            }
            val body = JSONObject().put("entity_id", entity)
            if (action == "temp" && value != null) {
                body.put("temperature", value)
            }
            val request = Request.Builder()
                .url("$base/api/services/$domain/$service")
                .header("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody(jsonMedia))
                .build()
            val ok = runCatching {
                client.newCall(request).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (ok) results.add(entity)
        }
        if (results.isEmpty()) {
            "Smart home device ka response nahi aaya — URL/token check karo."
        } else {
            ""
        }
    }

    private fun domainOf(entity: String): String = entity.substringBefore('.')

    /** Quick reachability check for the Settings screen. */
    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val settings = app.settings.settings.first()
        val base = settings.haUrl.trimEnd('/')
        if (base.isEmpty() || settings.haToken.isEmpty()) return@withContext "URL/token set karo pehle."
        val request = Request.Builder()
            .url("$base/api/")
            .header("Authorization", "Bearer ${settings.haToken}")
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) "Connection OK!" else "HTTP ${resp.code}"
            }
        } catch (e: Exception) {
            "Connection fail: ${e.message}"
        }
    }
}
