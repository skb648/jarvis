package com.jarvis.assistant.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * Persistent settings repository using Android DataStore (Preferences).
 *
 * All API keys, MQTT credentials, and user preferences are saved here
 * so they survive app restarts and process death.
 *
 * DataStore is the modern replacement for SharedPreferences:
 *  - Coroutine-based (non-blocking)
 *  - Type-safe
 *  - Transactional writes (no partial data)
 *  - No ANR risk
 */
class SettingsRepository(private val context: Context) {

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
        name = "jarvis_settings"
    )

    // ─── Key Definitions ─────────────────────────────────────────

    companion object {
        // AI Keys
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        val ELEVENLABS_API_KEY = stringPreferencesKey("elevenlabs_api_key")
        val TTS_VOICE_ID = stringPreferencesKey("tts_voice_id")

        // Wake Word
        val WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")

        // MQTT
        val MQTT_BROKER_URL = stringPreferencesKey("mqtt_broker_url")
        val MQTT_USERNAME = stringPreferencesKey("mqtt_username")
        val MQTT_PASSWORD = stringPreferencesKey("mqtt_password")

        // Home Assistant
        val HOME_ASSISTANT_URL = stringPreferencesKey("home_assistant_url")
        val HOME_ASSISTANT_TOKEN = stringPreferencesKey("home_assistant_token")

        // System
        val KEEP_ALIVE_ENABLED = booleanPreferencesKey("keep_alive_enabled")
        val RUST_CORE_INITIALIZED = booleanPreferencesKey("rust_core_initialized")

        // Default voice ID for ElevenLabs (Rachel)
        const val DEFAULT_VOICE_ID = "21m00Tcm4TlvDq8ikWAM"
    }

    // ─── Read Operations (suspend) ───────────────────────────────

    suspend fun getGeminiApiKey(): String = readString(GEMINI_API_KEY, "")
    suspend fun getElevenLabsApiKey(): String = readString(ELEVENLABS_API_KEY, "")
    suspend fun getTtsVoiceId(): String = readString(TTS_VOICE_ID, DEFAULT_VOICE_ID)
    suspend fun isWakeWordEnabled(): Boolean = readBoolean(WAKE_WORD_ENABLED, false)
    suspend fun getMqttBrokerUrl(): String = readString(MQTT_BROKER_URL, "")
    suspend fun getMqttUsername(): String = readString(MQTT_USERNAME, "")
    suspend fun getMqttPassword(): String = readString(MQTT_PASSWORD, "")
    suspend fun getHomeAssistantUrl(): String = readString(HOME_ASSISTANT_URL, "")
    suspend fun getHomeAssistantToken(): String = readString(HOME_ASSISTANT_TOKEN, "")
    suspend fun isKeepAliveEnabled(): Boolean = readBoolean(KEEP_ALIVE_ENABLED, false)
    suspend fun isRustCoreInitialized(): Boolean = readBoolean(RUST_CORE_INITIALIZED, false)

    // ─── Write Operations (suspend) ──────────────────────────────

    suspend fun setGeminiApiKey(key: String) = writeString(GEMINI_API_KEY, key)
    suspend fun setElevenLabsApiKey(key: String) = writeString(ELEVENLABS_API_KEY, key)
    suspend fun setTtsVoiceId(id: String) = writeString(TTS_VOICE_ID, id)
    suspend fun setWakeWordEnabled(enabled: Boolean) = writeBoolean(WAKE_WORD_ENABLED, enabled)
    suspend fun setMqttBrokerUrl(url: String) = writeString(MQTT_BROKER_URL, url)
    suspend fun setMqttUsername(username: String) = writeString(MQTT_USERNAME, username)
    suspend fun setMqttPassword(password: String) = writeString(MQTT_PASSWORD, password)
    suspend fun setHomeAssistantUrl(url: String) = writeString(HOME_ASSISTANT_URL, url)
    suspend fun setHomeAssistantToken(token: String) = writeString(HOME_ASSISTANT_TOKEN, token)
    suspend fun setKeepAliveEnabled(enabled: Boolean) = writeBoolean(KEEP_ALIVE_ENABLED, enabled)
    suspend fun setRustCoreInitialized(initialized: Boolean) = writeBoolean(RUST_CORE_INITIALIZED, initialized)

    // ─── Save all settings at once (transactional) ───────────────

    suspend fun saveAllSettings(
        geminiApiKey: String,
        elevenLabsApiKey: String,
        ttsVoiceId: String,
        wakeWordEnabled: Boolean,
        mqttBrokerUrl: String,
        mqttUsername: String,
        mqttPassword: String,
        homeAssistantUrl: String,
        homeAssistantToken: String,
        keepAliveEnabled: Boolean
    ) {
        context.dataStore.edit { prefs ->
            prefs[GEMINI_API_KEY] = geminiApiKey
            prefs[ELEVENLABS_API_KEY] = elevenLabsApiKey
            prefs[TTS_VOICE_ID] = ttsVoiceId
            prefs[WAKE_WORD_ENABLED] = wakeWordEnabled
            prefs[MQTT_BROKER_URL] = mqttBrokerUrl
            prefs[MQTT_USERNAME] = mqttUsername
            prefs[MQTT_PASSWORD] = mqttPassword
            prefs[HOME_ASSISTANT_URL] = homeAssistantUrl
            prefs[HOME_ASSISTANT_TOKEN] = homeAssistantToken
            prefs[KEEP_ALIVE_ENABLED] = keepAliveEnabled
        }
    }

    // ─── Blocking read (for Application.onCreate / BroadcastReceiver) ───

    fun getGeminiApiKeyBlocking(): String = readStringBlocking(GEMINI_API_KEY, "")
    fun getElevenLabsApiKeyBlocking(): String = readStringBlocking(ELEVENLABS_API_KEY, "")
    fun isWakeWordEnabledBlocking(): Boolean = readBooleanBlocking(WAKE_WORD_ENABLED, false)
    fun isKeepAliveEnabledBlocking(): Boolean = readBooleanBlocking(KEEP_ALIVE_ENABLED, false)

    // ─── Internal Helpers ────────────────────────────────────────

    private suspend fun readString(key: Preferences.Key<String>, default: String): String {
        return context.dataStore.data.map { it[key] ?: default }.first()
    }

    private suspend fun readBoolean(key: Preferences.Key<Boolean>, default: Boolean): Boolean {
        return context.dataStore.data.map { it[key] ?: default }.first()
    }

    private suspend fun writeString(key: Preferences.Key<String>, value: String) {
        context.dataStore.edit { it[key] = value }
    }

    private suspend fun writeBoolean(key: Preferences.Key<Boolean>, value: Boolean) {
        context.dataStore.edit { it[key] = value }
    }

    private fun readStringBlocking(key: Preferences.Key<String>, default: String): String {
        return runBlocking {
            try {
                context.dataStore.data.map { it[key] ?: default }.first()
            } catch (e: Exception) {
                default
            }
        }
    }

    private fun readBooleanBlocking(key: Preferences.Key<Boolean>, default: Boolean): Boolean {
        return runBlocking {
            try {
                context.dataStore.data.map { it[key] ?: default }.first()
            } catch (e: Exception) {
                default
            }
        }
    }
}
