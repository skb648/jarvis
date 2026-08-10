package com.jarvis.assistant.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "jarvis_settings")

/** All user settings, persisted via DataStore. */
data class Settings(
    val geminiKey: String = "",
    val geminiModel: String = "gemini-2.5-flash",
    val elevenLabsKey: String = "",
    val elevenLabsVoice: String = "EXAVITQu4vr4xnSDxMaL", // default "Sarah"
    val language: String = "en-IN",          // en-IN, en-US, en-GB, hi-IN, auto
    val wakeWordEnabled: Boolean = true,     // "Hey Jarvis"
    val autoListen: Boolean = true,          // respond then listen again
    val moodWatch: Boolean = false,          // ambient emotion monitoring
    val chimeEnabled: Boolean = true,        // HUD activation beep
    val strictMicMode: Boolean = false,      // pause raw mic while ASR runs (compat)
    val baseRate: Float = 1.0f,              // TTS base speech rate
    val weatherLat: Double = 26.9124,        // default: Jaipur
    val weatherLon: Double = 75.7873,
    val routines: Set<String> = emptySet(),  // "HH:mm|action text"
    // ---- v2.0 additions ----
    val accent: String = "cyan",             // cyan | gold | green | purple | red
    val haUrl: String = "",                  // Home Assistant base URL
    val haToken: String = "",                // Home Assistant long-lived token
    val pnrKey: String = "",                 // optional PNR status API key
    val saveHistory: Boolean = true,         // persist conversation
    val whisperMode: Boolean = false,        // soft night voice
    val bubbleEnabled: Boolean = false,      // floating bubble
    val preferredVoice: String = "",         // TTS voice id (empty = auto)
    val autoSleepMinutes: Int = 25,          // idle -> sleep
    val wakeTrained: Boolean = false,        // custom wake-word template exists
    // ---- v3.0 ----
    val openaiKey: String = "",              // OpenAI API key (premium voice tier)
    val openaiVoice: String = "nova",        // alloy|echo|fable|onyx|nova|shimmer
    val agentEnabled: Boolean = true         // AutoPilot agent (smart task execution)
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val GEMINI_KEY = stringPreferencesKey("gemini_key")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val ELEVEN_KEY = stringPreferencesKey("eleven_key")
        val ELEVEN_VOICE = stringPreferencesKey("eleven_voice")
        val LANGUAGE = stringPreferencesKey("language")
        val WAKE_WORD = booleanPreferencesKey("wake_word")
        val AUTO_LISTEN = booleanPreferencesKey("auto_listen")
        val MOOD_WATCH = booleanPreferencesKey("mood_watch")
        val CHIME = booleanPreferencesKey("chime")
        val STRICT_MIC = booleanPreferencesKey("strict_mic")
        val BASE_RATE = floatPreferencesKey("base_rate")
        val WEATHER_LAT = doublePreferencesKey("weather_lat")
        val WEATHER_LON = doublePreferencesKey("weather_lon")
        val ROUTINES = stringSetPreferencesKey("routines")
        val ACCENT = stringPreferencesKey("accent")
        val HA_URL = stringPreferencesKey("ha_url")
        val HA_TOKEN = stringPreferencesKey("ha_token")
        val PNR_KEY = stringPreferencesKey("pnr_key")
        val SAVE_HISTORY = booleanPreferencesKey("save_history")
        val WHISPER = booleanPreferencesKey("whisper")
        val BUBBLE = booleanPreferencesKey("bubble")
        val VOICE = stringPreferencesKey("preferred_voice")
        val SLEEP_MIN = intPreferencesKey("auto_sleep_min")
        val WAKE_TRAINED = booleanPreferencesKey("wake_trained")
        val OPENAI_KEY = stringPreferencesKey("openai_key")
        val OPENAI_VOICE = stringPreferencesKey("openai_voice")
        val AGENT_ENABLED = booleanPreferencesKey("agent_enabled")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            geminiKey = p[Keys.GEMINI_KEY] ?: "",
            geminiModel = p[Keys.GEMINI_MODEL] ?: "gemini-2.5-flash",
            elevenLabsKey = p[Keys.ELEVEN_KEY] ?: "",
            elevenLabsVoice = p[Keys.ELEVEN_VOICE] ?: "EXAVITQu4vr4xnSDxMaL",
            language = p[Keys.LANGUAGE] ?: "en-IN",
            wakeWordEnabled = p[Keys.WAKE_WORD] ?: true,
            autoListen = p[Keys.AUTO_LISTEN] ?: true,
            moodWatch = p[Keys.MOOD_WATCH] ?: false,
            chimeEnabled = p[Keys.CHIME] ?: true,
            strictMicMode = p[Keys.STRICT_MIC] ?: false,
            baseRate = p[Keys.BASE_RATE] ?: 1.0f,
            weatherLat = p[Keys.WEATHER_LAT] ?: 26.9124,
            weatherLon = p[Keys.WEATHER_LON] ?: 75.7873,
            routines = p[Keys.ROUTINES] ?: emptySet(),
            accent = p[Keys.ACCENT] ?: "cyan",
            haUrl = p[Keys.HA_URL] ?: "",
            haToken = p[Keys.HA_TOKEN] ?: "",
            pnrKey = p[Keys.PNR_KEY] ?: "",
            saveHistory = p[Keys.SAVE_HISTORY] ?: true,
            whisperMode = p[Keys.WHISPER] ?: false,
            bubbleEnabled = p[Keys.BUBBLE] ?: false,
            preferredVoice = p[Keys.VOICE] ?: "",
            autoSleepMinutes = p[Keys.SLEEP_MIN] ?: 25,
            wakeTrained = p[Keys.WAKE_TRAINED] ?: false,
            openaiKey = p[Keys.OPENAI_KEY] ?: "",
            openaiVoice = p[Keys.OPENAI_VOICE] ?: "nova",
            agentEnabled = p[Keys.AGENT_ENABLED] ?: true
        )
    }

    suspend fun setGeminiKey(v: String) = context.dataStore.edit { it[Keys.GEMINI_KEY] = v.trim() }
    suspend fun setGeminiModel(v: String) = context.dataStore.edit { it[Keys.GEMINI_MODEL] = v.trim() }
    suspend fun setElevenLabsKey(v: String) = context.dataStore.edit { it[Keys.ELEVEN_KEY] = v.trim() }
    suspend fun setElevenLabsVoice(v: String) = context.dataStore.edit { it[Keys.ELEVEN_VOICE] = v.trim() }
    suspend fun setLanguage(v: String) = context.dataStore.edit { it[Keys.LANGUAGE] = v }
    suspend fun setWakeWord(v: Boolean) = context.dataStore.edit { it[Keys.WAKE_WORD] = v }
    suspend fun setAutoListen(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_LISTEN] = v }
    suspend fun setMoodWatch(v: Boolean) = context.dataStore.edit { it[Keys.MOOD_WATCH] = v }
    suspend fun setChime(v: Boolean) = context.dataStore.edit { it[Keys.CHIME] = v }
    suspend fun setStrictMic(v: Boolean) = context.dataStore.edit { it[Keys.STRICT_MIC] = v }
    suspend fun setBaseRate(v: Float) = context.dataStore.edit { it[Keys.BASE_RATE] = v }
    suspend fun setWeather(lat: Double, lon: Double) =
        context.dataStore.edit { it[Keys.WEATHER_LAT] = lat; it[Keys.WEATHER_LON] = lon }
    suspend fun setAccent(v: String) = context.dataStore.edit { it[Keys.ACCENT] = v }
    suspend fun setHaUrl(v: String) = context.dataStore.edit { it[Keys.HA_URL] = v.trim() }
    suspend fun setHaToken(v: String) = context.dataStore.edit { it[Keys.HA_TOKEN] = v.trim() }
    suspend fun setPnrKey(v: String) = context.dataStore.edit { it[Keys.PNR_KEY] = v.trim() }
    suspend fun setSaveHistory(v: Boolean) = context.dataStore.edit { it[Keys.SAVE_HISTORY] = v }
    suspend fun setWhisper(v: Boolean) = context.dataStore.edit { it[Keys.WHISPER] = v }
    suspend fun setBubble(v: Boolean) = context.dataStore.edit { it[Keys.BUBBLE] = v }
    suspend fun setPreferredVoice(v: String) = context.dataStore.edit { it[Keys.VOICE] = v }
    suspend fun setAutoSleepMinutes(v: Int) = context.dataStore.edit { it[Keys.SLEEP_MIN] = v }
    suspend fun setWakeTrained(v: Boolean) = context.dataStore.edit { it[Keys.WAKE_TRAINED] = v }
    suspend fun setOpenaiKey(v: String) = context.dataStore.edit { it[Keys.OPENAI_KEY] = v.trim() }
    suspend fun setOpenaiVoice(v: String) = context.dataStore.edit { it[Keys.OPENAI_VOICE] = v }
    suspend fun setAgentEnabled(v: Boolean) = context.dataStore.edit { it[Keys.AGENT_ENABLED] = v }

    suspend fun addRoutine(hour: Int, minute: Int, action: String) {
        context.dataStore.edit { p ->
            val entry = "%02d:%02d|%s".format(hour, minute, action)
            val updated = (p[Keys.ROUTINES] ?: emptySet()) + entry
            p[Keys.ROUTINES] = updated
        }
    }

    suspend fun removeRoutine(entry: String) {
        context.dataStore.edit { p ->
            p[Keys.ROUTINES] = (p[Keys.ROUTINES] ?: emptySet()) - entry
        }
    }
}
