package com.jarvis.assistant.smarthome

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Home Assistant Bridge — REST API integration.
 *
 * Provides direct control of Home Assistant entities via the REST API.
 * Supports:
 *   - Fetching all entity states
 *   - Calling services (turn_on, turn_off, toggle)
 *   - Auto-detecting domain from entity_id prefix
 *   - Getting individual entity states
 */
object HomeAssistantBridge {

    private const val TAG = "JarvisHABridge"

    private var baseUrl: String = ""
    private var token: String = ""
    private var isConfigured = false

    // Domain mapping for common HA entity types
    private val DOMAIN_MAP = mapOf(
        "light" to "light",
        "switch" to "switch",
        "fan" to "fan",
        "humidifier" to "humidifier",
        "input_boolean" to "input_boolean",
        "automation" to "automation",
        "script" to "script",
        "group" to "group",
        "climate" to "climate",
        "cover" to "cover",
        "lock" to "lock",
        "media_player" to "media_player",
        "camera" to "camera",
        "sensor" to "sensor",
        "binary_sensor" to "binary_sensor"
    )

    /**
     * Configure the bridge with Home Assistant URL and long-lived access token.
     */
    fun configure(url: String, accessToken: String) {
        baseUrl = url.trimEnd('/')
        token = accessToken
        isConfigured = true
        Log.i(TAG, "Home Assistant configured: $baseUrl")
    }

    fun isConfigured(): Boolean = isConfigured

    /**
     * Fetch all entity states from Home Assistant.
     */
    fun getStates(): JSONArray? {
        if (!isConfigured) return null
        return try {
            val response = httpGet("/api/states")
            JSONArray(response)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch states", e)
            null
        }
    }

    /**
     * Call a Home Assistant service.
     */
    fun callService(domain: String, service: String, entityId: String, serviceData: JSONObject? = null): JSONObject? {
        if (!isConfigured) return null
        return try {
            val body = JSONObject().apply {
                put("entity_id", entityId)
                serviceData?.keys()?.forEach { key ->
                    put(key, serviceData.get(key))
                }
            }
            val response = httpPost("/api/services/$domain/$service", body)
            if (response.isNotEmpty()) JSONObject(response) else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to call service $domain.$service for $entityId", e)
            null
        }
    }

    /**
     * Toggle an entity — auto-detects domain from entity_id.
     * Uses domain-specific toggle logic since not all domains support the "toggle" service.
     */
    fun toggleEntity(entityId: String): JSONObject? {
        val domain = getDomainFromEntityId(entityId)
        return when (domain) {
            "climate" -> {
                // Toggle between hvac modes
                val state = getEntityState(entityId)
                val currentMode = state?.optString("state", "off") ?: "off"
                if (currentMode == "off") {
                    callService(domain, "turn_on", entityId)
                } else {
                    callService(domain, "turn_off", entityId)
                }
            }
            "cover" -> callService(domain, "toggle", entityId)
            "lock" -> {
                val state = getEntityState(entityId)
                val currentLocked = state?.optString("state", "locked") ?: "locked"
                if (currentLocked == "locked") callService(domain, "unlock", entityId)
                else callService(domain, "lock", entityId)
            }
            "media_player" -> callService(domain, "media_play_pause", entityId)
            else -> callService(domain, "toggle", entityId)
        }
    }

    /**
     * Turn on an entity — uses domain-specific service where needed.
     */
    fun turnOn(entityId: String): JSONObject? {
        val domain = getDomainFromEntityId(entityId)
        return when (domain) {
            "lock" -> callService(domain, "lock", entityId)
            "cover" -> callService(domain, "open_cover", entityId)
            "media_player" -> callService(domain, "media_play", entityId)
            else -> callService(domain, "turn_on", entityId)
        }
    }

    /**
     * Turn off an entity — uses domain-specific service where needed.
     */
    fun turnOff(entityId: String): JSONObject? {
        val domain = getDomainFromEntityId(entityId)
        return when (domain) {
            "lock" -> callService(domain, "unlock", entityId)
            "cover" -> callService(domain, "close_cover", entityId)
            "media_player" -> callService(domain, "media_pause", entityId)
            else -> callService(domain, "turn_off", entityId)
        }
    }

    /**
     * Get the state of a specific entity.
     */
    fun getEntityState(entityId: String): JSONObject? {
        if (!isConfigured) return null
        return try {
            val response = httpGet("/api/states/$entityId")
            JSONObject(response)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get state for $entityId", e)
            null
        }
    }

    /**
     * Get Home Assistant configuration.
     */
    fun getConfig(): JSONObject? {
        if (!isConfigured) return null
        return try {
            val response = httpGet("/api/config")
            JSONObject(response)
        } catch (e: Exception) {
            null
        }
    }

    // ─── Domain Detection ───────────────────────────────────────

    private fun getDomainFromEntityId(entityId: String): String {
        val prefix = entityId.substringBefore(".", "")
        return DOMAIN_MAP[prefix] ?: "homeassistant"
    }

    // ─── HTTP Helpers ───────────────────────────────────────────

    // BUG FIX: Read from errorStream when response code is not 200.
    // Previously, reading conn.inputStream on 4xx/5xx responses threw IOException.
    // Also added .use {} to close BufferedReader and prevent resource leaks.
    private fun httpGet(path: String): String {
        val url = URL("$baseUrl$path")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 10000
            readTimeout = 10000
        }

        try {
            val responseCode = conn.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP GET $path returned $responseCode")
                // Read error body for diagnostics
                val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.d(TAG, "Error body: ${errorBody.take(500)}")
                return ""
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPost(path: String, body: JSONObject): String {
        val url = URL("$baseUrl$path")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 10000
            readTimeout = 10000
        }

        try {
            conn.outputStream.use { os ->
                val input = body.toString().toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }

            val responseCode = conn.responseCode
            return try {
                // BUG FIX: Use errorStream for non-200 responses to avoid IOException.
                // Added .use {} to close BufferedReader and prevent resource leaks.
                val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (e: Exception) {
                "" // Some responses have empty body
            }
        } finally {
            conn.disconnect()
        }
    }
}
