package com.jarvis.assistant.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.jarvis.assistant.privacy.PrivacyVault
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking

/**
 * Persistent settings repository using Android DataStore (Preferences).
 *
 * All API keys, MQTT credentials, and user preferences are saved here
 * so they survive app restarts and process death.
 *
 * SECURITY: API keys (Gemini, ElevenLabs) are encrypted via PrivacyVault
 * (AES-256-GCM with Android KeyStore) before storage. They are decrypted
 * on read. Legacy plaintext keys are automatically re-encrypted on first read.
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

    // ─── PrivacyVault — lazy init requires Context ──────────────
    // PrivacyVault uses Android KeyStore which needs Context.
    // Since SettingsRepository may be created before the full Application
    // Context is available (e.g., in tests), we use lazy initialization.

    private var privacyVault: PrivacyVault? = null

    /**
     * Initialize the PrivacyVault with the given Context.
     * Must be called from JarvisApp.onCreate() before any API key operations.
     */
    fun initPrivacyVault(context: Context) {
        if (privacyVault == null) {
            privacyVault = PrivacyVault(context)
            Log.i(TAG, "PrivacyVault initialized — API keys will be encrypted")
        }
    }

    // ─── Key Definitions ─────────────────────────────────────────

    companion object {
        private const val TAG = "JarvisSettings"

        // AI Keys
        val GROQ_API_KEY = stringPreferencesKey("groq_api_key")
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

        // Web Search
        val WEB_SEARCH_API_KEY = stringPreferencesKey("web_search_api_key")
        val WEB_SEARCH_CX = stringPreferencesKey("web_search_cx")

        // Notifications
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")

        // System
        val KEEP_ALIVE_ENABLED = booleanPreferencesKey("keep_alive_enabled")
        val RUST_CORE_INITIALIZED = booleanPreferencesKey("rust_core_initialized")

        // Default voice ID for ElevenLabs (Rachel)
        const val DEFAULT_VOICE_ID = "21m00Tcm4TlvDq8ikWAM"

        // PrivacyVault key aliases for encrypting sensitive values
        private const val VAULT_KEY_GROQ = "groq_api_key"
        private const val VAULT_KEY_ELEVENLABS = "elevenlabs_api_key"
    }

    // ─── Read Operations (suspend) ───────────────────────────────
    // API keys are decrypted via PrivacyVault on read.
    // If decryption fails (key was stored as plaintext before the vault
    // was integrated), the raw value is used and re-encrypted.

    suspend fun getGroqApiKey(): String = readEncryptedString(GROQ_API_KEY, VAULT_KEY_GROQ, "")
    suspend fun getElevenLabsApiKey(): String = readEncryptedString(ELEVENLABS_API_KEY, VAULT_KEY_ELEVENLABS, "")
    suspend fun getTtsVoiceId(): String = readString(TTS_VOICE_ID, DEFAULT_VOICE_ID)
    suspend fun isWakeWordEnabled(): Boolean = readBoolean(WAKE_WORD_ENABLED, false)
    suspend fun getMqttBrokerUrl(): String = readString(MQTT_BROKER_URL, "")
    suspend fun getMqttUsername(): String = readString(MQTT_USERNAME, "")
    suspend fun getMqttPassword(): String = readString(MQTT_PASSWORD, "")
    suspend fun getHomeAssistantUrl(): String = readString(HOME_ASSISTANT_URL, "")
    suspend fun getHomeAssistantToken(): String = readString(HOME_ASSISTANT_TOKEN, "")
    suspend fun getWebSearchApiKey(): String = readString(WEB_SEARCH_API_KEY, "")
    suspend fun getWebSearchCx(): String = readString(WEB_SEARCH_CX, "")
    suspend fun isNotificationsEnabled(): Boolean = readBoolean(NOTIFICATIONS_ENABLED, false)
    suspend fun isKeepAliveEnabled(): Boolean = readBoolean(KEEP_ALIVE_ENABLED, false)
    suspend fun isRustCoreInitialized(): Boolean = readBoolean(RUST_CORE_INITIALIZED, false)

    // ─── Write Operations (suspend) ──────────────────────────────
    // API keys are encrypted via PrivacyVault before storing in DataStore.

    suspend fun setGroqApiKey(key: String) = writeEncryptedString(GROQ_API_KEY, VAULT_KEY_GROQ, key)
    suspend fun setElevenLabsApiKey(key: String) = writeEncryptedString(ELEVENLABS_API_KEY, VAULT_KEY_ELEVENLABS, key)
    suspend fun setTtsVoiceId(id: String) = writeString(TTS_VOICE_ID, id)
    suspend fun setWakeWordEnabled(enabled: Boolean) = writeBoolean(WAKE_WORD_ENABLED, enabled)
    suspend fun setMqttBrokerUrl(url: String) = writeString(MQTT_BROKER_URL, url)
    suspend fun setMqttUsername(username: String) = writeString(MQTT_USERNAME, username)
    suspend fun setMqttPassword(password: String) = writeString(MQTT_PASSWORD, password)
    suspend fun setHomeAssistantUrl(url: String) = writeString(HOME_ASSISTANT_URL, url)
    suspend fun setHomeAssistantToken(token: String) = writeString(HOME_ASSISTANT_TOKEN, token)
    suspend fun setWebSearchApiKey(key: String) = writeString(WEB_SEARCH_API_KEY, key)
    suspend fun setWebSearchCx(cx: String) = writeString(WEB_SEARCH_CX, cx)
    suspend fun setNotificationsEnabled(enabled: Boolean) = writeBoolean(NOTIFICATIONS_ENABLED, enabled)
    suspend fun setKeepAliveEnabled(enabled: Boolean) = writeBoolean(KEEP_ALIVE_ENABLED, enabled)
    suspend fun setRustCoreInitialized(initialized: Boolean) = writeBoolean(RUST_CORE_INITIALIZED, initialized)

    // ─── Save all settings at once (transactional) ───────────────

    suspend fun saveAllSettings(
        groqApiKey: String,
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
        // Encrypt API keys before storing
        val encryptedGroq = encryptValue(VAULT_KEY_GROQ, groqApiKey)
        val encryptedElevenLabs = encryptValue(VAULT_KEY_ELEVENLABS, elevenLabsApiKey)

        context.dataStore.edit { prefs ->
            prefs[GROQ_API_KEY] = encryptedGroq
            prefs[ELEVENLABS_API_KEY] = encryptedElevenLabs
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

    fun getGroqApiKeyBlocking(): String = readEncryptedStringBlocking(GROQ_API_KEY, VAULT_KEY_GROQ, "")
    fun getElevenLabsApiKeyBlocking(): String = readEncryptedStringBlocking(ELEVENLABS_API_KEY, VAULT_KEY_ELEVENLABS, "")
    fun isWakeWordEnabledBlocking(): Boolean = readBooleanBlocking(WAKE_WORD_ENABLED, false)
    fun isKeepAliveEnabledBlocking(): Boolean = readBooleanBlocking(KEEP_ALIVE_ENABLED, false)

    // ─── Encryption Helpers ──────────────────────────────────────

    /**
     * Encrypt a value via PrivacyVault. If the vault is not initialized,
     * returns the plaintext value (graceful degradation).
     */
    private fun encryptValue(vaultKey: String, plaintext: String): String {
        val vault = privacyVault ?: return plaintext
        if (plaintext.isEmpty()) return plaintext
        return try {
            val encrypted = vault.encrypt(plaintext)
            // Also store in the vault's own SharedPreferences for retrieval
            vault.storeSecure(vaultKey, plaintext)
            encrypted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt value for key: $vaultKey", e)
            plaintext
        }
    }

    /**
     * Decrypt a value via PrivacyVault. If decryption fails (key was stored
     * as plaintext before vault integration), use the raw value and re-encrypt it.
     */
    private fun decryptValue(vaultKey: String, storedValue: String): String {
        val vault = privacyVault ?: return storedValue
        if (storedValue.isEmpty()) return storedValue

        // First, try retrieving from the vault's own SharedPreferences
        val fromVault = vault.retrieveSecure(vaultKey)
        if (fromVault != null) return fromVault

        // Vault doesn't have it — try decrypting the DataStore value directly
        return try {
            vault.decrypt(storedValue)
        } catch (e: Exception) {
            // Decryption failed — this is a legacy plaintext value.
            // Re-encrypt it and store in the vault for future reads.
            Log.i(TAG, "Legacy plaintext value detected for key: $vaultKey — re-encrypting")
            try {
                vault.storeSecure(vaultKey, storedValue)
            } catch (storeErr: Exception) {
                Log.e(TAG, "Failed to re-encrypt legacy value for key: $vaultKey", storeErr)
            }
            storedValue
        }
    }

    // ─── Internal Helpers ────────────────────────────────────────

    private suspend fun readString(key: Preferences.Key<String>, default: String): String {
        return context.dataStore.data.map { it[key] ?: default }.first()
    }

    private suspend fun readBoolean(key: Preferences.Key<Boolean>, default: Boolean): Boolean {
        return context.dataStore.data.map { it[key] ?: default }.first()
    }

    /**
     * Read and decrypt an encrypted string from DataStore.
     * Handles legacy plaintext values by re-encrypting them.
     */
    private suspend fun readEncryptedString(key: Preferences.Key<String>, vaultKey: String, default: String): String {
        val rawValue = context.dataStore.data.map { it[key] ?: default }.first()
        if (rawValue.isEmpty()) return rawValue
        val decrypted = decryptValue(vaultKey, rawValue)
        // If we decrypted a legacy plaintext value, re-encrypt it in DataStore
        if (decrypted == rawValue && privacyVault != null) {
            try {
                val encrypted = encryptValue(vaultKey, decrypted)
                if (encrypted != rawValue) {
                    context.dataStore.edit { it[key] = encrypted }
                    Log.i(TAG, "Re-encrypted legacy plaintext value for key: $vaultKey")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to re-encrypt value in DataStore for key: $vaultKey", e)
            }
        }
        return decrypted
    }

    /**
     * Write an encrypted string to DataStore.
     */
    private suspend fun writeEncryptedString(key: Preferences.Key<String>, vaultKey: String, value: String) {
        val encrypted = encryptValue(vaultKey, value)
        context.dataStore.edit { it[key] = encrypted }
    }

    private suspend fun writeString(key: Preferences.Key<String>, value: String) {
        context.dataStore.edit { it[key] = value }
    }

    private suspend fun writeBoolean(key: Preferences.Key<Boolean>, value: Boolean) {
        context.dataStore.edit { it[key] = value }
    }

    private fun readStringBlocking(key: Preferences.Key<String>, default: String): String {
        warnIfMainThread("readStringBlocking")
        return runBlocking {
            try {
                withTimeout(2000L) {
                    context.dataStore.data.map { it[key] ?: default }.first()
                }
            } catch (e: Exception) {
                Log.w(TAG, "readStringBlocking failed for key=${key.name}: ${e.message}")
                default
            }
        }
    }

    private fun readEncryptedStringBlocking(key: Preferences.Key<String>, vaultKey: String, default: String): String {
        warnIfMainThread("readEncryptedStringBlocking")
        return runBlocking {
            try {
                withTimeout(2000L) {
                    val rawValue = context.dataStore.data.map { it[key] ?: default }.first()
                    if (rawValue.isEmpty()) rawValue else decryptValue(vaultKey, rawValue)
                }
            } catch (e: Exception) {
                Log.w(TAG, "readEncryptedStringBlocking failed for key=${key.name}: ${e.message}")
                default
            }
        }
    }

    private fun readBooleanBlocking(key: Preferences.Key<Boolean>, default: Boolean): Boolean {
        warnIfMainThread("readBooleanBlocking")
        return runBlocking {
            try {
                withTimeout(2000L) {
                    context.dataStore.data.map { it[key] ?: default }.first()
                }
            } catch (e: Exception) {
                Log.w(TAG, "readBooleanBlocking failed for key=${key.name}: ${e.message}")
                default
            }
        }
    }

    /**
     * Warn if a blocking method is called from the main thread.
     * This can cause ANRs and should be avoided. Use the suspend
     * alternatives instead.
     */
    private fun warnIfMainThread(methodName: String) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            Log.w(TAG, "$methodName called on main thread — this can cause ANRs. Use suspend alternative instead.")
        }
    }
}
