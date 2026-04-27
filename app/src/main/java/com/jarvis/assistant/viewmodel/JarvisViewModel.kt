package com.jarvis.assistant.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.jarvis.assistant.actions.ActionHandler
import com.jarvis.assistant.audio.AudioEngine
import com.jarvis.assistant.channels.JarviewModel
import com.jarvis.assistant.data.SettingsRepository
import com.jarvis.assistant.jni.RustBridge
import com.jarvis.assistant.router.CommandRouter
import com.jarvis.assistant.shizuku.ShizukuManager
import com.jarvis.assistant.ui.orb.BrainState
import com.jarvis.assistant.ui.screens.ChatMessage
import com.jarvis.assistant.ui.screens.SmartDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileOutputStream

/** Result of a "Save & Apply" API key operation — consumed once for snackbar/toast. */
enum class ApiKeySaveResult { NONE, SUCCESS, FAILURE }

/**
 * JarvisViewModel — Central state holder and orchestrator.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * KEY ARCHITECTURE DECISIONS:
 *
 * 1. REAL-TIME AMPLITUDE VIA STATEFLOW:
 *    [_audioAmplitude] is a `MutableStateFlow<Float>` that receives
 *    RMS values from AudioEngine ~22 times/second (44.1kHz / 2048 samples).
 *    The JarvisMainScreen observes this flow and uses `animateFloatAsState`
 *    to smoothly interpolate the orb's scale, glow, and blur.
 *
 * 2. HOT-SWAP API KEYS:
 *    [saveAndApplyApiKeys] writes keys to DataStore AND immediately
 *    calls [RustBridge.initialize] which propagates to the Rust backend's
 *    `RwLock<ApiKeys>`. No restart needed.
 *
 * 3. MIC TOGGLE — INSTANT:
 *    [toggleListening] starts/stops the AudioEngine immediately.
 *    When OFF, amplitude drops to 0 and AudioRecord is released.
 *    No SpeechRecognizer, no system beeps.
 *
 * 4. SHIZUKU STATE OBSERVATION:
 *    The ViewModel registers a listener on ShizukuManager so the
 *    isShizukuAvailable StateFlow updates in real-time when
 *    Shizuku starts, stops, or permission changes.
 * ═══════════════════════════════════════════════════════════════════════
 */
class JarvisViewModel(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    companion object {
        private const val TAG = "JarvisViewModel"
        private const val MAX_HISTORY_ENTRIES = 10
        private const val TTS_TIMEOUT_MS = 30_000L
        private const val SHIZUKU_CHECK_INTERVAL_MS = 5_000L
    }

    // ── Core state ─────────────────────────────────────────────────────────────
    private val _brainState = MutableStateFlow(BrainState.IDLE)
    val brainState: StateFlow<BrainState> = _brainState.asStateFlow()

    /**
     * Real-time audio amplitude in [0f..1f].
     *
     * Updated ~22 times/second by AudioEngine's RMS calculation.
     * The JarvisMainScreen observes this via `collectAsState()` and
     * feeds it into `animateFloatAsState()` for smooth orb animation.
     *
     * When the mic is OFF, this drops to 0f.
     * When the user speaks, this spikes proportionally to voice volume.
     * When JARVIS speaks (TTS), this is set to a synthetic 0.5f for visual feedback.
     */
    private val _audioAmplitude = MutableStateFlow(0f)
    val audioAmplitude: StateFlow<Float> = _audioAmplitude.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isTyping = MutableStateFlow(false)
    val isTyping: StateFlow<Boolean> = _isTyping.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isVoiceMode = MutableStateFlow(false)
    val isVoiceMode: StateFlow<Boolean> = _isVoiceMode.asStateFlow()

    private val _currentTranscription = MutableStateFlow("")
    val currentTranscription: StateFlow<String> = _currentTranscription.asStateFlow()

    private val _lastResponse = MutableStateFlow("")
    val lastResponse: StateFlow<String> = _lastResponse.asStateFlow()

    private val _emotion = MutableStateFlow("neutral")
    val emotion: StateFlow<String> = _emotion.asStateFlow()

    // ── Device / MQTT state ────────────────────────────────────────────────────
    private val _devices = MutableStateFlow<List<SmartDevice>>(emptyList())
    val devices: StateFlow<List<SmartDevice>> = _devices.asStateFlow()

    private val _isMqttConnected = MutableStateFlow(false)
    val isMqttConnected: StateFlow<Boolean> = _isMqttConnected.asStateFlow()

    private val _mqttLabel = MutableStateFlow("MQTT Disconnected")
    val mqttLabel: StateFlow<String> = _mqttLabel.asStateFlow()

    // ── Settings state ─────────────────────────────────────────────────────────
    private val _geminiApiKey = MutableStateFlow("")
    val geminiApiKey: StateFlow<String> = _geminiApiKey.asStateFlow()

    private val _elevenLabsApiKey = MutableStateFlow("")
    val elevenLabsApiKey: StateFlow<String> = _elevenLabsApiKey.asStateFlow()

    private val _ttsVoiceId = MutableStateFlow(SettingsRepository.DEFAULT_VOICE_ID)
    val ttsVoiceId: StateFlow<String> = _ttsVoiceId.asStateFlow()

    private val _isWakeWordEnabled = MutableStateFlow(false)
    val isWakeWordEnabled: StateFlow<Boolean> = _isWakeWordEnabled.asStateFlow()

    private val _mqttBrokerUrl = MutableStateFlow("")
    val mqttBrokerUrl: StateFlow<String> = _mqttBrokerUrl.asStateFlow()

    private val _mqttUsername = MutableStateFlow("")
    val mqttUsername: StateFlow<String> = _mqttUsername.asStateFlow()

    private val _mqttPassword = MutableStateFlow("")
    val mqttPassword: StateFlow<String> = _mqttPassword.asStateFlow()

    private val _homeAssistantUrl = MutableStateFlow("")
    val homeAssistantUrl: StateFlow<String> = _homeAssistantUrl.asStateFlow()

    private val _homeAssistantToken = MutableStateFlow("")
    val homeAssistantToken: StateFlow<String> = _homeAssistantToken.asStateFlow()

    private val _isKeepAliveEnabled = MutableStateFlow(false)
    val isKeepAliveEnabled: StateFlow<Boolean> = _isKeepAliveEnabled.asStateFlow()

    private val _isBatteryOptimized = MutableStateFlow(true)
    val isBatteryOptimized: StateFlow<Boolean> = _isBatteryOptimized.asStateFlow()

    private val _isShizukuAvailable = MutableStateFlow(false)
    val isShizukuAvailable: StateFlow<Boolean> = _isShizukuAvailable.asStateFlow()

    private val _isRustReady = MutableStateFlow(false)
    val isRustReady: StateFlow<Boolean> = _isRustReady.asStateFlow()

    /** Emits once after a Save & Apply; reset via [consumeApiKeySaveResult]. */
    private val _apiKeySaveResult = MutableStateFlow(ApiKeySaveResult.NONE)
    val apiKeySaveResult: StateFlow<ApiKeySaveResult> = _apiKeySaveResult.asStateFlow()

    val deviceCount: Int get() = _devices.value.size
    val activeDeviceCount: Int get() = _devices.value.count { it.isOn }

    // ── Audio engine ───────────────────────────────────────────────────────────
    /** Live AudioRecord engine — null when mic is off. */
    private var audioEngine: AudioEngine? = null

    // ── ExoPlayer for TTS ──────────────────────────────────────────────────────
    private var exoPlayer: ExoPlayer? = null
    private var nextMessageId = 0L

    init {
        loadPersistedSettings()
        _isRustReady.value = RustBridge.isNativeReady()

        // ── Register Shizuku state listener ────────────────────────────────
        // This updates isShizukuAvailable in real-time when Shizuku
        // starts, stops, or permission changes.
        ShizukuManager.setOnShizukuStateChangedListener { available ->
            _isShizukuAvailable.value = available
            Log.i(TAG, "Shizuku state changed: available=$available")
        }

        // Check initial state
        _isShizukuAvailable.value = ShizukuManager.isReady() && ShizukuManager.hasPermission()

        // ── Periodic Shizuku health check ──────────────────────────────────
        // Shizuku can die without firing the binder dead listener in some
        // edge cases (e.g., Shizuku app force-stopped). This coroutine
        // rechecks every 5 seconds to catch stale state.
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(SHIZUKU_CHECK_INTERVAL_MS)
                val ready = ShizukuManager.isReady()
                val permitted = ShizukuManager.hasPermission()
                val available = ready && permitted
                if (_isShizukuAvailable.value != available) {
                    _isShizukuAvailable.value = available
                    Log.d(TAG, "Periodic Shizuku check: ready=$ready permitted=$permitted")
                }
            }
        }
    }

    // ── Settings loading ───────────────────────────────────────────────────────

    private fun loadPersistedSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _geminiApiKey.value        = settingsRepository.getGeminiApiKey()
                _elevenLabsApiKey.value    = settingsRepository.getElevenLabsApiKey()
                _ttsVoiceId.value          = settingsRepository.getTtsVoiceId()
                _isWakeWordEnabled.value   = settingsRepository.isWakeWordEnabled()
                _mqttBrokerUrl.value       = settingsRepository.getMqttBrokerUrl()
                _mqttUsername.value        = settingsRepository.getMqttUsername()
                _mqttPassword.value        = settingsRepository.getMqttPassword()
                _homeAssistantUrl.value    = settingsRepository.getHomeAssistantUrl()
                _homeAssistantToken.value  = settingsRepository.getHomeAssistantToken()
                _isKeepAliveEnabled.value  = settingsRepository.isKeepAliveEnabled()
                Log.i(TAG, "Settings loaded")

                // Hot-swap: push persisted keys into Rust immediately on startup
                if (_geminiApiKey.value.isNotEmpty() && RustBridge.isNativeReady()) {
                    try {
                        _isRustReady.value = RustBridge.initialize(
                            _geminiApiKey.value, _elevenLabsApiKey.value
                        )
                        Log.i(TAG, "Rust initialized with persisted keys on startup")
                    } catch (e: Exception) {
                        Log.e(TAG, "Rust init on load failed: ${e.message}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "loadPersistedSettings: ${e.message}")
            }
        }
    }

    // ── Hot-swap API keys ──────────────────────────────────────────────────────

    /**
     * Saves both keys to DataStore AND calls [RustBridge.initialize] immediately.
     *
     * This is the HOT-SWAP fix. The flow is:
     * 1. Write to DataStore (persistent storage)
     * 2. Update in-memory StateFlow values
     * 3. Call RustBridge.initialize(geminiKey, elevenLabsKey)
     *    → This calls nativeInitialize JNI
     *    → Which calls gemini::set_api_keys()
     *    → Which writes to RwLock<ApiKeys> in Rust
     *    → Subsequent API calls use the NEW keys immediately
     */
    fun saveAndApplyApiKeys(geminiKey: String, elevenLabsKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Step 1 — persist to DataStore
                settingsRepository.setGeminiApiKey(geminiKey)
                settingsRepository.setElevenLabsApiKey(elevenLabsKey)
                _geminiApiKey.value     = geminiKey
                _elevenLabsApiKey.value = elevenLabsKey

                // Step 2 — IMMEDIATELY push to Rust backend (hot-swap)
                if (RustBridge.isNativeReady()) {
                    try {
                        val ok = RustBridge.initialize(geminiKey, elevenLabsKey)
                        _isRustReady.value = ok
                        Log.i(TAG, "Hot-swap RustBridge.initialize -> $ok")
                    } catch (e: Exception) {
                        Log.w(TAG, "RustBridge.initialize threw (keys still saved): ${e.message}")
                    }
                } else {
                    Log.w(TAG, "Rust native not loaded — keys saved to DataStore only")
                }

                _apiKeySaveResult.value = ApiKeySaveResult.SUCCESS

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "saveAndApplyApiKeys DataStore write failed: ${e.message}")
                _apiKeySaveResult.value = ApiKeySaveResult.FAILURE
            }
        }
    }

    /** Call after consuming the save result to reset the one-shot signal. */
    fun consumeApiKeySaveResult() {
        _apiKeySaveResult.value = ApiKeySaveResult.NONE
    }

    // ── Mic toggle — wires AudioEngine ────────────────────────────────────────

    /**
     * Toggle the microphone on/off.
     *
     * ON  → creates a fresh [AudioEngine], calls startListening(),
     *       amplitude StateFlow begins receiving RMS values.
     * OFF → calls stopListening(), releases AudioRecord hardware,
     *       amplitude StateFlow drops to 0f.
     *
     * The toggle is instant (no blocking). AudioRecord runs in a
     * background coroutine on Dispatchers.IO.
     */
    fun toggleListening(context: Context) {
        if (_isListening.value) {
            // ── Mic OFF ────────────────────────────────────────────────────────
            stopAudioEngine()
            _isListening.value       = false
            _brainState.value        = BrainState.IDLE
            _audioAmplitude.value    = 0f
            _currentTranscription.value = ""
        } else {
            // ── Mic ON ─────────────────────────────────────────────────────────
            startListening(context)
        }
    }

    /**
     * EXPLICIT start listening — called from the "Voice" quick action
     * button and from intent shortcuts. If already listening, this is
     * a no-op (unlike toggleListening which would STOP it).
     */
    @SuppressLint("MissingPermission")
    fun startListening(context: Context) {
        if (_isListening.value) return  // Already listening — no-op

        _isListening.value       = true
        _brainState.value        = BrainState.LISTENING
        _currentTranscription.value = ""
        startAudioEngine(context)
    }

    /**
     * EXPLICIT stop listening — called from services or when the
     * app loses audio focus. Safe to call even when not listening.
     */
    fun stopListening() {
        if (!_isListening.value) return
        stopAudioEngine()
        _isListening.value       = false
        _brainState.value        = BrainState.IDLE
        _audioAmplitude.value    = 0f
        _currentTranscription.value = ""
    }

    @SuppressLint("MissingPermission")
    private fun startAudioEngine(context: Context) {
        stopAudioEngine()   // release any stale instance

        audioEngine = AudioEngine(
            context = context,
            onAmplitudeUpdate = { amp ->
                // Already dispatched on Main by AudioEngine.
                // This drives the orb animation in real-time.
                _audioAmplitude.value = amp
            },
            onWakeWordDetected = {
                _brainState.value = BrainState.LISTENING
                audioEngine?.startCommandRecording()
                viewModelScope.launch {
                    delay(5_000)
                    audioEngine?.stopCommandRecording()
                }
            },
            onCommandReady = { pcmBytes ->
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val result = RustBridge.analyzeAudio(pcmBytes, 44_100)
                        if (result.isNotBlank() && !result.startsWith("[ERROR]")) {
                            withContext(Dispatchers.Main) {
                                processQuery(result, context)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "analyzeAudio failed: ${e.message}")
                    }
                }
            }
        )

        try {
            audioEngine?.startListening()
        } catch (e: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO permission denied: ${e.message}")
            _brainState.value  = BrainState.ERROR
            _isListening.value = false
            _audioAmplitude.value = 0f
            addAssistantMessage("Microphone permission is required. Please grant it in Settings.", "neutral")
        } catch (e: Exception) {
            Log.e(TAG, "AudioEngine start error: ${e.message}")
            _brainState.value  = BrainState.ERROR
            _isListening.value = false
            _audioAmplitude.value = 0f
        }
    }

    private fun stopAudioEngine() {
        audioEngine?.stopListening()
        audioEngine = null
    }

    // ── Query processing ───────────────────────────────────────────────────────

    fun processQuery(query: String, context: Context) {
        // Allow text queries even when mic is on (LISTENING) — the user
        // should be able to type while the orb is listening.
        // Only block if already THINKING or SPEAKING to prevent duplicate AI calls.
        if (_brainState.value == BrainState.THINKING || _brainState.value == BrainState.SPEAKING) return

        viewModelScope.launch(Dispatchers.Main) {
            try {
                addUserMessage(query)
                _brainState.value = BrainState.THINKING
                _isTyping.value   = true

                val routeResult = withContext(Dispatchers.Default) {
                    CommandRouter.route(query, context)
                }

                when (routeResult) {
                    is CommandRouter.RouteResult.Handled  -> handleSystemCommandResult(routeResult, context)
                    is CommandRouter.RouteResult.NeedsAI  -> handleAIQuery(routeResult.query, context)
                }
            } catch (e: CancellationException) {
                _brainState.value = BrainState.IDLE
                return@launch
            } catch (e: Exception) {
                Log.e(TAG, "Query pipeline error", e)
                _brainState.value     = BrainState.ERROR
                _isTyping.value       = false
                _audioAmplitude.value = 0f
                addAssistantMessage("I encountered an error: ${e.message?.take(200) ?: "Unknown"}", "stressed")
            } finally {
                _brainState.value     = BrainState.IDLE
                _audioAmplitude.value = if (_isListening.value) _audioAmplitude.value else 0f
                _isTyping.value       = false
            }
        }
    }

    private suspend fun handleSystemCommandResult(
        result: CommandRouter.RouteResult.Handled,
        context: Context
    ) {
        try {
            _emotion.value        = result.emotion
            _lastResponse.value   = result.response
            _isTyping.value       = false
            addAssistantMessage(result.response, result.emotion)
            _brainState.value     = BrainState.SPEAKING
            _audioAmplitude.value = 0.5f
            trySynthesizeAndPlay(result.response, context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "handleSystemCommandResult: ${e.message}")
        }
    }

    private suspend fun handleAIQuery(query: String, context: Context) {
        try {
            _brainState.value     = BrainState.THINKING
            _audioAmplitude.value = 0.1f

            val historyJson    = buildHistoryJson()
            val screenContext  = JarviewModel.screenTextData

            val rawResponse = withContext(Dispatchers.IO) {
                try {
                    RustBridge.processQuery(query, screenContext, historyJson)
                } catch (e: Exception) {
                    Log.e(TAG, "JNI processQuery failed", e)
                    "[ERROR] AI processing failed: ${e.message?.take(200) ?: "Unknown"}"
                }
            }

            // Clean, human-readable error — never show raw JSON
            val parsed  = parseErrorResponse(rawResponse)
            val isError = parsed !== rawResponse || rawResponse.startsWith("[ERROR]")

            if (isError) {
                _brainState.value   = BrainState.ERROR
                _isTyping.value     = false
                _lastResponse.value = parsed
                addAssistantMessage(parsed, "stressed")
                return
            }

            val emotionTag   = parseEmotionTag(rawResponse)
            var cleanResponse = stripEmotionTag(rawResponse)

            // ── ACTION HANDLER: Intercept AI response and execute REAL actions ──
            val (finalResponse, actionResult) = withContext(Dispatchers.Default) {
                ActionHandler.interceptAndExecute(cleanResponse, context)
            }
            cleanResponse = finalResponse

            // If action failed, update the emotion to reflect the failure
            val finalEmotion = when (actionResult) {
                is ActionHandler.ActionResult.Failed -> "stressed"
                is ActionHandler.ActionResult.Success -> emotionTag
                is ActionHandler.ActionResult.NoAction -> emotionTag
            }

            _emotion.value       = finalEmotion
            _lastResponse.value  = cleanResponse
            _isTyping.value      = false
            addAssistantMessage(cleanResponse, finalEmotion)

            _brainState.value     = BrainState.SPEAKING
            _audioAmplitude.value = 0.5f
            trySynthesizeAndPlay(cleanResponse, context)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "handleAIQuery failed", e)
            _brainState.value = BrainState.ERROR
            _isTyping.value   = false
            addAssistantMessage("I had trouble processing that.", "stressed")
        }
    }

    // ── Smart error parser ─────────────────────────────────────────────────────

    private fun parseErrorResponse(raw: String): String {
        val lower = raw.lowercase()
        return when {
            lower.contains("429") || lower.contains("resource_exhausted") || lower.contains("quota") ->
                "Sir, the Gemini API quota has been exhausted. Please update the key in Settings."

            lower.contains("403") || lower.contains("permission_denied") || lower.contains("api_key_invalid") ->
                "Sir, the API key appears invalid or unauthorised. Please check Settings."

            lower.contains("model_not_found") ->
                "Sir, the requested model is unavailable. Please try again."

            lower.contains("network") || (lower.contains("connect") && lower.contains("refused")) ->
                "Sir, there is no internet connection. Please check your network."

            raw.startsWith("[ERROR]") ->
                raw.removePrefix("[ERROR]").trim().ifBlank { "An error occurred. Please try again." }

            lower.contains("error") && lower.contains("{") ->
                Regex(""""message"\s*:\s*"([^"]+)"""").find(raw)?.groupValues?.get(1)
                    ?: "An error occurred. Please try again."

            else -> raw
        }
    }

    // ── Audio chunk (direct API path) ──────────────────────────────────────────

    fun processAudioChunk(audioData: ByteArray, sampleRate: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RustBridge.analyzeAudio(audioData, sampleRate)
            } catch (e: Exception) {
                Log.e(TAG, "processAudioChunk: ${e.message}")
            }
        }
    }

    /**
     * Compute dynamic ElevenLabs TTS parameters based on current emotion.
     *
     * - "Urgent" / "Excited" / "Angry"  -> HIGH expressiveness
     * - "Calm" / "Neutral" / "Sad"     -> LOW expressiveness
     */
    private fun computeTtsParams(): Pair<Float, Float> {
        val emotion = _emotion.value.lowercase()
        return when {
            emotion in listOf("urgent", "excited", "angry", "stressed", "surprised") -> {
                Pair(0.25f, 0.90f)
            }
            emotion in listOf("happy", "joy", "confident", "playful") -> {
                Pair(0.40f, 0.80f)
            }
            emotion in listOf("sad", "fearful", "disgusted") -> {
                Pair(0.65f, 0.70f)
            }
            else -> {
                Pair(0.50f, 0.75f)
            }
        }
    }

    private suspend fun trySynthesizeAndPlay(text: String, context: Context) {
        try {
            val (stability, similarityBoost) = computeTtsParams()
            Log.d(TAG, "TTS params: emotion=${_emotion.value} stability=$stability similarityBoost=$similarityBoost")

            val base64 = withContext(Dispatchers.IO) {
                try {
                    RustBridge.synthesizeSpeech(
                        text, _ttsVoiceId.value,
                        stability = stability, similarityBoost = similarityBoost
                    )
                } catch (e: Exception) { null }
            } ?: return

            val mp3 = withContext(Dispatchers.IO) {
                try { RustBridge.decodeBase64Audio(base64) } catch (e: Exception) { null }
            } ?: return

            withContext(Dispatchers.Main) { playMp3Audio(mp3, context) }

            withTimeoutOrNull(TTS_TIMEOUT_MS) {
                while (isActive && exoPlayer?.isPlaying == true) delay(100)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "TTS failed: ${e.message}")
        }
    }

    // ── Public helpers ─────────────────────────────────────────────────────────

    fun sendMessage(text: String, context: Context) = processQuery(text, context)

    fun toggleVoiceMode() { _isVoiceMode.value = !_isVoiceMode.value }

    fun toggleDevice(deviceId: String, newState: Boolean) {
        _devices.value = _devices.value.map { d ->
            if (d.id == deviceId) d.copy(isOn = newState) else d
        }
    }

    fun refreshDevices() { viewModelScope.launch(Dispatchers.IO) { } }

    /**
     * Direct amplitude setter for external use (e.g., from services).
     */
    fun updateAmplitude(amplitude: Float) {
        _audioAmplitude.value = amplitude.coerceIn(0f, 1f)
    }

    /**
     * Request Shizuku permission — called from Settings screen.
     */
    fun requestShizukuPermission() {
        if (ShizukuManager.isReady() && !ShizukuManager.hasPermission()) {
            ShizukuManager.requestPermission()
        }
    }

    // ── Per-field setters ──────────────────────────────────────────────────────

    fun setGeminiApiKey(key: String) {
        _geminiApiKey.value = key
        viewModelScope.launch(Dispatchers.IO) {
            try {
                settingsRepository.setGeminiApiKey(key)
                if (key.isNotEmpty() && RustBridge.isNativeReady()) {
                    _isRustReady.value = RustBridge.initialize(key, _elevenLabsApiKey.value)
                }
            } catch (e: Exception) { Log.e(TAG, "setGeminiApiKey: ${e.message}") }
        }
    }

    fun setElevenLabsApiKey(key: String) {
        _elevenLabsApiKey.value = key
        viewModelScope.launch(Dispatchers.IO) {
            try {
                settingsRepository.setElevenLabsApiKey(key)
                if (_geminiApiKey.value.isNotEmpty() && RustBridge.isNativeReady()) {
                    _isRustReady.value = RustBridge.initialize(_geminiApiKey.value, key)
                }
            } catch (e: Exception) { Log.e(TAG, "setElevenLabsApiKey: ${e.message}") }
        }
    }

    fun setTtsVoiceId(id: String) {
        _ttsVoiceId.value = id
        viewModelScope.launch(Dispatchers.IO) {
            try { settingsRepository.setTtsVoiceId(id) } catch (e: Exception) {}
        }
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        _isWakeWordEnabled.value = enabled
        viewModelScope.launch(Dispatchers.IO) {
            try { settingsRepository.setWakeWordEnabled(enabled) } catch (e: Exception) {}
        }
    }

    fun setMqttBrokerUrl(url: String) {
        _mqttBrokerUrl.value = url
        viewModelScope.launch(Dispatchers.IO) {
            try { settingsRepository.setMqttBrokerUrl(url) } catch (e: Exception) {}
        }
    }

    fun setMqttUsername(u: String) {
        _mqttUsername.value = u
        viewModelScope.launch(Dispatchers.IO) {
            try { settingsRepository.setMqttUsername(u) } catch (e: Exception) {}
        }
    }

    fun setMqttPassword(p: String) {
        _mqttPassword.value = p
        viewModelScope.launch(Dispatchers.IO) {
            try { settingsRepository.setMqttPassword(p) } catch (e: Exception) {}
        }
    }

    fun setHomeAssistantUrl(url: String) {
        _homeAssistantUrl.value = url
        viewModelScope.launch(Dispatchers.IO) {
            try { settingsRepository.setHomeAssistantUrl(url) } catch (e: Exception) {}
        }
    }

    fun setHomeAssistantToken(token: String) {
        _homeAssistantToken.value = token
        viewModelScope.launch(Dispatchers.IO) {
            try { settingsRepository.setHomeAssistantToken(token) } catch (e: Exception) {}
        }
    }

    fun setKeepAliveEnabled(enabled: Boolean) {
        _isKeepAliveEnabled.value = enabled
        viewModelScope.launch(Dispatchers.IO) {
            try { settingsRepository.setKeepAliveEnabled(enabled) } catch (e: Exception) {}
        }
    }

    fun updateBatteryOptimized(v: Boolean) { _isBatteryOptimized.value = v }
    fun updateShizukuAvailable(v: Boolean) { _isShizukuAvailable.value = v }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun addUserMessage(text: String) {
        _messages.value += ChatMessage(id = nextMessageId++, content = text, isFromUser = true)
    }

    private fun addAssistantMessage(text: String, emotion: String) {
        _messages.value += ChatMessage(id = nextMessageId++, content = text, isFromUser = false, emotion = emotion)
    }

    private fun buildHistoryJson(): String {
        val recent  = _messages.value.takeLast(MAX_HISTORY_ENTRIES)
        val entries = recent.map { m ->
            val role    = if (m.isFromUser) "user" else "model"
            // Use Gson for proper JSON escaping — prevents injection & broken JSON
            val escaped = com.google.gson.Gson().toJson(m.content)
            """{"role":"$role","content":$escaped}"""
        }
        return "[${entries.joinToString(",")}]"
    }

    private fun parseEmotionTag(r: String) =
        Regex("""\[EMOTION:(\w+)]""", RegexOption.IGNORE_CASE).find(r)?.groupValues?.get(1)?.lowercase() ?: "neutral"

    private fun stripEmotionTag(r: String) =
        Regex("""\[EMOTION:\w+]\s*""", RegexOption.IGNORE_CASE).replace(r, "").trim()

    private fun playMp3Audio(mp3Bytes: ByteArray, context: Context) {
        try {
            val tmp = File(context.cacheDir, "tts_${System.currentTimeMillis()}.mp3")
            FileOutputStream(tmp).use { it.write(mp3Bytes) }

            if (exoPlayer == null) {
                exoPlayer = ExoPlayer.Builder(context).build().apply {
                    addListener(object : Player.Listener {
                        override fun onPlayerError(e: PlaybackException) {
                            Log.e(TAG, "ExoPlayer error: ${e.message}")
                            _brainState.value = BrainState.ERROR
                        }
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED) {
                                if (!_isListening.value) {
                                    _audioAmplitude.value = 0f
                                }
                                _brainState.value = if (_isListening.value) BrainState.LISTENING else BrainState.IDLE
                            }
                        }
                    })
                }
            }
            exoPlayer?.apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(tmp)))
                prepare()
                playWhenReady = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "playMp3Audio: ${e.message}")
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopAudioEngine()
        try { exoPlayer?.release() } catch (_: Exception) {}
        exoPlayer = null
        try { RustBridge.shutdown() } catch (_: Exception) {}
        ShizukuManager.setOnShizukuStateChangedListener {}
    }

    class Factory(private val repo: SettingsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(c: Class<T>): T = JarvisViewModel(repo) as T
    }
}
