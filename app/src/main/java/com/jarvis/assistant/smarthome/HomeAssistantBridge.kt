package com.jarvis.assistant.smarthome

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Home Assistant Bridge — REST API integration.
 *
 * Provides direct control of Home Assistant entities via the REST API.
 * All public methods are suspend functions to avoid blocking the calling thread.
 * Uses OkHttp with proper timeouts, retry, and connection pooling.
 *
 * Supports:
 *   - Fetching all entity states
 *   - Calling services (turn_on, turn_off, toggle)
 *   - Auto-detecting domain from entity_id prefix
 *   - Getting individual entity states
 */
object HomeAssistantBridge {

    private const val TAG = "JarvisHABridge"

    /** Shared OkHttp client for Home Assistant REST calls */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

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
    suspend fun getStates(): JSONArray? {
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
    suspend fun callService(domain: String, service: String, entityId: String, serviceData: JSONObject? = null): JSONObject? {
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
    suspend fun toggleEntity(entityId: String): JSONObject? {
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
    suspend fun turnOn(entityId: String): JSONObject? {
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
    suspend fun turnOff(entityId: String): JSONObject? {
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
    suspend fun getEntityState(entityId: String): JSONObject? {
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
    suspend fun getConfig(): JSONObject? {
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

    // ─── HTTP Helpers (OkHttp) ──────────────────────────────────

    private suspend fun httpGet(path: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl$path")
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .get()
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.code != 200) {
                    Log.w(TAG, "HTTP GET $path returned ${response.code}")
                    val errorBody = response.body?.string() ?: ""
                    response.close()
                    Log.d(TAG, "Error body: ${errorBody.take(500)}")
                    return@withContext ""
                }
                val body = response.body?.string() ?: ""
                response.close()
                body
            } catch (e: Exception) {
                Log.e(TAG, "HTTP GET $path failed", e)
                ""
            }
        }
    }

    private suspend fun httpPost(path: String, body: JSONObject): String {
        return withContext(Dispatchers.IO) {
            try {
                val requestBody = body.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url("$baseUrl$path")
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseBody = try {
                    response.body?.string() ?: ""
                } catch (e: Exception) {
                    "" // Some responses have empty body
                }
                response.close()
                responseBody
            } catch (e: Exception) {
                Log.e(TAG, "HTTP POST $path failed", e)
                ""
            }
        }
    }
}
