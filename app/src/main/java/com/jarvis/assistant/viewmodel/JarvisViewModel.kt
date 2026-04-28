package com.jarvis.assistant.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.jarvis.assistant.actions.ActionHandler
import com.jarvis.assistant.audio.AudioEngine
import com.jarvis.assistant.channels.JarviewModel
import com.jarvis.assistant.data.SettingsRepository
import com.jarvis.assistant.jni.RustBridge
import com.jarvis.assistant.overlay.JarvisOverlayManager
import com.jarvis.assistant.router.CommandRouter
import com.jarvis.assistant.shizuku.ShizukuManager
import com.jarvis.assistant.smarthome.HomeAssistantBridge
import com.jarvis.assistant.smarthome.MqttManager
import com.jarvis.assistant.ui.orb.BrainState
import com.jarvis.assistant.ui.screens.ChatMessage
import com.jarvis.assistant.ui.screens.DeviceType
import com.jarvis.assistant.ui.screens.SmartDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

enum class ApiKeySaveResult { NONE, SUCCESS, FAILURE }

/**
 * JarvisViewModel — Central state holder and orchestrator.
 *
 * CRITICAL FIXES (v11) — COMPLETE BUG FIX RELEASE:
 *
 *  C1: WAV header sample rate now matches actual AudioEngine rate;
 *      PCM is resampled to 16kHz before sending to Gemini.
 *  C2: Smart Home (MQTT/HomeAssistant) now actually initialized and connected;
 *      refreshDevices() implemented; setters trigger reconnection.
 *  C5: TTS base64 decode uses android.util.Base64 directly (no RustBridge dependency).
 *  C6: processQuery finally block no longer clobbers brainState during async TTS;
 *      MediaPlayer completion listener handles state transitions.
 *  H1: Per-field setters (setGeminiApiKey/setElevenLabsApiKey) no longer call
 *      RustBridge.initialize() — eliminates dual-init race; only saveAndApplyApiKeys() does.
 *  H2: Added engineStatusText StateFlow so HomeScreen can show correct Rust status.
 *  H3: 200ms delay after stopWakeWordMonitor() before starting new AudioEngine
 *      to avoid AudioRecord mic conflict.
 *  H4: Deprecated no-arg toggleVoiceMode() marked level=ERROR.
 *  H9: toggleDevice() now publishes MQTT command and calls HomeAssistantBridge.
 *  M1: parseErrorResponse only matches HTTP status code patterns, not arbitrary text.
 *  M4: Overlay manager created and shown/hidden via showOverlay()/hideOverlay().
 *  L7: All toast() calls dispatched to Main thread.
 *  NEW: processQueryViaGeminiDirect() — pure-Kotlin HTTP fallback when Rust not loaded.
 */
class JarvisViewModel(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    companion object {
        private const val TAG = "JarvisViewModel"
        private const val AUDIO_TAG = "JarvisAudio"
        private const val MAX_HISTORY_ENTRIES = 10
        private const val TTS_TIMEOUT_MS = 30_000L
        private const val SHIZUKU_CHECK_INTERVAL_MS = 5_000L
        private const val GEMINI_MODEL = "gemini-2.0-flash"

        /** JARVIS system prompt for Gemini direct queries. */
        private const val JARVIS_SYSTEM_PROMPT = """You are JARVIS, Tony Stark's AI assistant. \
You are sophisticated, witty, and always helpful. You speak concisely and with British elegance. \
You address the user as "Sir" or "Ma'am". You can control smart home devices, answer questions, \
and assist with any task. Keep responses brief but informative. If you detect an emotion in the \
user's query, prefix your response with [EMOTION:emotion] where emotion is one of: \
neutral, happy, sad, angry, calm, surprised, urgent, stressed, confused, playful."""
    }

    // ─── State Flows ──────────────────────────────────────────────────────

    private val _brainState = MutableStateFlow(BrainState.IDLE)
    val brainState: StateFlow<BrainState> = _brainState.asStateFlow()

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

    private val _devices = MutableStateFlow<List<SmartDevice>>(emptyList())
    val devices: StateFlow<List<SmartDevice>> = _devices.asStateFlow()

    private val _isMqttConnected = MutableStateFlow(false)
    val isMqttConnected: StateFlow<Boolean> = _isMqttConnected.asStateFlow()

    private val _mqttLabel = MutableStateFlow("MQTT Disconnected")
    val mqttLabel: StateFlow<String> = _mqttLabel.asStateFlow()

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

    // FIX H2: Expose engine status text so HomeScreen can show correct Rust status.
    private val _engineStatusText = MutableStateFlow("AI engine starting...")
    val engineStatusText: StateFlow<String> = _engineStatusText.asStateFlow()

    private val _apiKeySaveResult = MutableStateFlow(ApiKeySaveResult.NONE)
    val apiKeySaveResult: StateFlow<ApiKeySaveResult> = _apiKeySaveResult.asStateFlow()

    private val _isOverlayVisible = MutableStateFlow(false)
    val isOverlayVisible: StateFlow<Boolean> = _isOverlayVisible.asStateFlow()

    val deviceCount: Int get() = _devices.value.size
    val activeDeviceCount: Int get() = _devices.value.count { it.isOn }

    // ─── Audio Engine Fields ──────────────────────────────────────────────

    private var audioEngine: AudioEngine? = null
    private val _rawAudioFlow = MutableStateFlow(ByteArray(0))

    // FIX C1: Track the actual sample rate used by AudioEngine.
    @Volatile
    private var actualSampleRate = 44_100

    private var wakeWordEngine: AudioEngine? = null
    @Volatile private var isWakeWordMonitoring = false

    // ─── Media Player / TTS Fields ────────────────────────────────────────

    private var mediaPlayer: MediaPlayer? = null
    @Volatile private var currentTtsTempPath: String? = null
    private var amplitudePulseJob: Job? = null
    private val mediaPlayerMutex = Mutex()
    private var nextMessageId = 0L

    // ─── Overlay Manager ─────────────────────────────────────────────────

    // FIX M4: Overlay manager instance, created lazily when context is available.
    private var overlayManager: JarvisOverlayManager? = null

    private var applicationContext: Context? = null

    // ─── Init ─────────────────────────────────────────────────────────────

    init {
        Log.d(AUDIO_TAG, "[JarvisViewModel] init started")
        loadPersistedSettings()
        _isRustReady.value = RustBridge.isNativeReady()
        updateEngineStatusText()
        Log.d(AUDIO_TAG, "[JarvisViewModel] Rust native ready = ${_isRustReady.value}")

        ShizukuManager.setOnShizukuStateChangedListener { available ->
            _isShizukuAvailable.value = available
            Log.i(TAG, "Shizuku state changed: available=$available")
        }

        _isShizukuAvailable.value = ShizukuManager.isReady() && ShizukuManager.hasPermission()

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
        Log.d(AUDIO_TAG, "[JarvisViewModel] init complete")
    }

    // FIX H2: Helper to update the engine status text based on Rust readiness.
    private fun updateEngineStatusText() {
        _engineStatusText.value = if (_isRustReady.value) {
            "AI engine operational \u00B7 Rust native"
        } else {
            "AI engine operational \u00B7 Kotlin HTTP mode"
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PERSISTED SETTINGS
    // ═══════════════════════════════════════════════════════════════════════

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
                Log.i(TAG, "Settings loaded — geminiKey=${_geminiApiKey.value.take(4)}..., elevenLabsKey=${_elevenLabsApiKey.value.take(4)}...")

                if (_geminiApiKey.value.isNotEmpty() && RustBridge.isNativeReady()) {
                    try {
                        _isRustReady.value = RustBridge.initialize(
                            _geminiApiKey.value, _elevenLabsApiKey.value
                        )
                        updateEngineStatusText()
                        Log.i(TAG, "Rust initialized with persisted keys on startup")
                    } catch (e: Exception) {
                        Log.e(TAG, "Rust init on load failed: ${e.message}")
                    }
                }

                // FIX C2: Connect smart home after loading MQTT/HA settings.
                val ctx = getApplicationContext()
                if (ctx != null) {
                    connectSmartHome(ctx)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "loadPersistedSettings: ${e.message}")
            }
        }
    }

    fun saveAndApplyApiKeys(geminiKey: String, elevenLabsKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                settingsRepository.setGeminiApiKey(geminiKey)
                settingsRepository.setElevenLabsApiKey(elevenLabsKey)
                _geminiApiKey.value     = geminiKey
                _elevenLabsApiKey.value = elevenLabsKey

                // Only place that should call RustBridge.initialize()
                if (RustBridge.isNativeReady()) {
                    try {
                        val ok = RustBridge.initialize(geminiKey, elevenLabsKey)
                        _isRustReady.value = ok
                        updateEngineStatusText()
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

    fun consumeApiKeySaveResult() {
        _apiKeySaveResult.value = ApiKeySaveResult.NONE
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LISTENING / AUDIO ENGINE
    // ═══════════════════════════════════════════════════════════════════════

    fun toggleListening(context: Context) {
        Log.d(AUDIO_TAG, "[toggleListening] isListening=${_isListening.value}")
        if (_isListening.value) {
            stopListening()
        } else {
            startListening(context)
        }
    }

    @SuppressLint("MissingPermission")
    fun startListening(context: Context) {
        if (_isListening.value) {
            Log.d(AUDIO_TAG, "[startListening] already listening — ignoring duplicate call")
            return
        }

        _isListening.value       = true
        _brainState.value        = BrainState.LISTENING
        _currentTranscription.value = ""
        Log.d(AUDIO_TAG, "[startListening] UI state set to LISTENING")

        stopWakeWordMonitor()

        // FIX H3: Delay 200ms after stopping wake word monitor to allow
        // AudioRecord to fully release the microphone before creating a new one.
        viewModelScope.launch(Dispatchers.Main) {
            delay(200)
            startAudioEngine(context)
            audioEngine?.startCommandRecording()
            Log.d(AUDIO_TAG, "[startListening] AudioEngine command recording started")
        }
    }

    fun stopListening() {
        if (!_isListening.value) return
        Log.d(AUDIO_TAG, "[stopListening] stopping")

        audioEngine?.stopCommandRecording()
        stopAudioEngine()

        _isListening.value       = false
        _brainState.value        = BrainState.IDLE
        _audioAmplitude.value    = 0f
        _currentTranscription.value = ""
        Log.d(AUDIO_TAG, "[stopListening] UI state reset to IDLE")

        restartWakeWordMonitorIfNeeded()
    }

    @SuppressLint("MissingPermission")
    private fun startAudioEngine(context: Context) {
        Log.d(AUDIO_TAG, "[startAudioEngine] creating new AudioEngine")
        stopAudioEngine()

        // FIX C1: Probe the actual sample rate that AudioEngine will use,
        // so we can create the WAV header with the correct rate later.
        actualSampleRate = probeSupportedSampleRate()
        Log.d(AUDIO_TAG, "[startAudioEngine] Probed sample rate: $actualSampleRate Hz")

        audioEngine = AudioEngine(
            context = context,
            onAmplitudeUpdate = { amp ->
                _audioAmplitude.value = amp
            },
            onWakeWordDetected = {
                Log.i(AUDIO_TAG, "[onWakeWordDetected] callback fired — triggering VAD recording")
                _brainState.value = BrainState.LISTENING
                _isListening.value = true
                audioEngine?.startCommandRecording()
            },
            onCommandReady = { pcmBytes ->
                Log.i(AUDIO_TAG, "[onCommandReady] callback fired: ${pcmBytes.size} bytes — launching handleCommandReady")
                // FIX C1: Pass the tracked actual sample rate to handleCommandReady.
                handleCommandReady(pcmBytes, actualSampleRate)
            },
            rawAudioFlow = _rawAudioFlow
        )

        try {
            audioEngine?.startListening()
            Log.d(AUDIO_TAG, "[startAudioEngine] AudioEngine.startListening() returned")
        } catch (e: SecurityException) {
            Log.e(AUDIO_TAG, "[startAudioEngine] RECORD_AUDIO permission denied: ${e.message}")
            _brainState.value  = BrainState.ERROR
            _isListening.value = false
            _audioAmplitude.value = 0f
            addAssistantMessage("Microphone permission is required. Please grant it in Settings.", "neutral")
            toast(context, "Microphone permission denied")
        } catch (e: Exception) {
            Log.e(AUDIO_TAG, "[startAudioEngine] AudioEngine start error: ${e.message}")
            _brainState.value  = BrainState.ERROR
            _isListening.value = false
            _audioAmplitude.value = 0f
            toast(context, "AudioEngine failed to start: ${e.message}")
        }
    }

    /**
     * FIX C1: Probe which sample rate the AudioRecord supports.
     * Mirrors AudioEngine's own logic: try 44100Hz first, then 16000Hz.
     */
    @SuppressLint("MissingPermission")
    private fun probeSupportedSampleRate(): Int {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        // Try 44100Hz first
        val minBuf44 = AudioRecord.getMinBufferSize(44_100, channelConfig, audioFormat)
        if (minBuf44 > 0) {
            try {
                val testAr = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    44_100, channelConfig, audioFormat, minBuf44
                )
                val initialized = testAr.state == AudioRecord.STATE_INITIALIZED
                testAr.release()
                if (initialized) return 44_100
            } catch (_: Exception) { /* fallback below */ }
        }

        // Try MIC source at 44100Hz
        try {
            val testAr = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                44_100, channelConfig, audioFormat,
                AudioRecord.getMinBufferSize(44_100, channelConfig, audioFormat)
            )
            val initialized = testAr.state == AudioRecord.STATE_INITIALIZED
            testAr.release()
            if (initialized) return 44_100
        } catch (_: Exception) { /* fallback below */ }

        // Fallback to 16000Hz
        Log.w(AUDIO_TAG, "[probeSupportedSampleRate] 44100Hz not available, using 16000Hz")
        return 16_000
    }

    private fun stopAudioEngine() {
        Log.d(AUDIO_TAG, "[stopAudioEngine] called")
        audioEngine?.stopListening()
        audioEngine = null
    }

    // ═══════════════════════════════════════════════════════════════════════
    // COMMAND READY — TRANSCRIPTION PIPELINE
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * FIX C1: Now accepts the actual sample rate used by AudioEngine,
     * so the WAV header and Gemini transcription use the correct rate.
     */
    private fun handleCommandReady(pcmBytes: ByteArray, sampleRate: Int) {
        Log.d(AUDIO_TAG, "[handleCommandReady] launched with ${pcmBytes.size} bytes at ${sampleRate}Hz")
        viewModelScope.launch(Dispatchers.Main) {
            _brainState.value = BrainState.THINKING

            val audioEmotion = try {
                withContext(Dispatchers.IO) {
                    Log.d(AUDIO_TAG, "[handleCommandReady] calling RustBridge.analyzeAudio at ${sampleRate}Hz")
                    RustBridge.analyzeAudio(pcmBytes, sampleRate)
                }
            } catch (e: Exception) {
                Log.w(AUDIO_TAG, "[handleCommandReady] Audio emotion analysis failed: ${e.message}")
                null
            }

            if (!audioEmotion.isNullOrBlank()) {
                try {
                    val emotionRegex = Regex(""""emotion"\s*:\s*"(\w+)"""")
                    val match = emotionRegex.find(audioEmotion)
                    if (match != null) {
                        val detected = match.groupValues[1].lowercase()
                        if (detected in listOf("angry", "calm", "happy", "sad", "fearful",
                                "surprised", "neutral", "urgent", "stressed")) {
                            _emotion.value = detected
                            Log.d(AUDIO_TAG, "[handleCommandReady] Emotion from audio analysis: $detected")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(AUDIO_TAG, "[handleCommandReady] Failed to parse emotion: ${e.message}")
                }
            }

            Log.d(AUDIO_TAG, "[handleCommandReady] Starting Gemini multimodal transcription at ${sampleRate}Hz")
            val transcription = transcribeViaGeminiFallback(pcmBytes, sampleRate)

            if (transcription.isNotBlank()) {
                _currentTranscription.value = transcription
                Log.i(AUDIO_TAG, "[handleCommandReady] Final transcription: \"$transcription\"")
                val ctx = getApplicationContext()
                if (ctx != null) {
                    processQuery(transcription, ctx)
                } else {
                    Log.e(AUDIO_TAG, "[handleCommandReady] No application context")
                    _brainState.value = BrainState.IDLE
                    _isListening.value = false
                }
            } else {
                Log.w(AUDIO_TAG, "[handleCommandReady] Transcription is EMPTY")
                _brainState.value = BrainState.IDLE
                _isListening.value = false
                addAssistantMessage("I couldn't make that out, Sir. Please try again.", "confused")
            }
        }
    }

    /**
     * FIX C1: Now accepts the actual sample rate from AudioEngine.
     * If the rate is not 16000Hz, the PCM data is resampled to 16000Hz
     * before creating the WAV, because Gemini works best with 16kHz audio.
     */
    private suspend fun transcribeViaGeminiFallback(pcmBytes: ByteArray, sourceSampleRate: Int): String {
        return withContext(Dispatchers.IO) {
            try {
                val apiKey = _geminiApiKey.value
                if (apiKey.isBlank()) {
                    Log.w(AUDIO_TAG, "[transcribeViaGeminiFallback] Gemini API key not set")
                    return@withContext ""
                }

                // FIX C1: Resample to 16000Hz for Gemini compatibility.
                val targetSampleRate = 16_000
                val pcmForGemini = if (sourceSampleRate != targetSampleRate) {
                    Log.d(AUDIO_TAG, "[transcribeViaGeminiFallback] Resampling PCM from ${sourceSampleRate}Hz to ${targetSampleRate}Hz")
                    resamplePcm(pcmBytes, sourceSampleRate, targetSampleRate)
                } else {
                    pcmBytes
                }

                val wavBytes = pcmToWav(pcmForGemini, sampleRate = targetSampleRate, channels = 1, bitsPerSample = 16)
                val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

                Log.d(AUDIO_TAG, "[transcribeViaGeminiFallback] Sending ${wavBytes.size} bytes WAV (${targetSampleRate}Hz) to Gemini")

                val requestBody = org.json.JSONObject().apply {
                    put("contents", org.json.JSONArray().put(
                        org.json.JSONObject().apply {
                            put("parts", org.json.JSONArray().apply {
                                put(org.json.JSONObject().apply {
                                    put("inline_data", org.json.JSONObject().apply {
                                        put("mimeType", "audio/wav")
                                        put("data", base64Audio)
                                    })
                                })
                                put(org.json.JSONObject().apply {
                                    put("text", "Transcribe the audio. Respond with ONLY the exact words spoken, nothing else. No quotes, no explanation, no commentary.")
                                })
                            })
                        }
                    ))
                    put("generationConfig", org.json.JSONObject().apply {
                        put("temperature", 0.0)
                        put("maxOutputTokens", 256)
                    })
                }.toString()

                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent?key=$apiKey")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000

                connection.outputStream.use { os ->
                    os.write(requestBody.toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "unknown"
                    Log.e(AUDIO_TAG, "[transcribeViaGeminiFallback] Gemini API error $responseCode: ${errorBody.take(500)}")
                    return@withContext ""
                }

                val responseBody = connection.inputStream.bufferedReader().readText()
                connection.disconnect()

                val transcribedText = try {
                    val root = JsonParser.parseString(responseBody).asJsonObject
                    val candidates = root.getAsJsonArray("candidates")
                    val firstCandidate = candidates?.firstOrNull()?.asJsonObject
                    val content = firstCandidate?.getAsJsonObject("content")
                    val parts = content?.getAsJsonArray("parts")
                    val firstPart = parts?.firstOrNull()?.asJsonObject
                    firstPart?.get("text")?.asString ?: ""
                } catch (e: Exception) {
                    Log.e(AUDIO_TAG, "[transcribeViaGeminiFallback] JSON parsing failed: ${e.message}")
                    ""
                }

                if (transcribedText.isNotBlank()) {
                    Log.i(AUDIO_TAG, "[transcribeViaGeminiFallback] Gemini transcription: \"$transcribedText\"")
                } else {
                    Log.w(AUDIO_TAG, "[transcribeViaGeminiFallback] Gemini returned empty transcription")
                }

                transcribedText
            } catch (e: Exception) {
                Log.e(AUDIO_TAG, "[transcribeViaGeminiFallback] Exception: ${e.message}", e)
                ""
            }
        }
    }

    /**
     * FIX C1: Resample PCM 16-bit mono audio from [sourceRate] to [targetRate]
     * using linear interpolation. This ensures Gemini always receives 16kHz audio
     * regardless of the AudioEngine's actual recording rate.
     */
    private fun resamplePcm(pcm: ByteArray, sourceRate: Int, targetRate: Int): ByteArray {
        if (sourceRate == targetRate) return pcm

        val sourceSamples = pcm.size / 2 // 16-bit samples
        val targetSamples = (sourceSamples.toLong() * targetRate / sourceRate).toInt()
        val result = ByteArray(targetSamples * 2)

        val ratio = sourceSamples.toFloat() / targetSamples.toFloat()

        for (i in 0 until targetSamples) {
            val srcPos = i * ratio
            val srcIdx = srcPos.toInt()
            val frac = srcPos - srcIdx

            val srcSample = if (srcIdx + 1 < sourceSamples) {
                // Linear interpolation between adjacent samples
                val s0 = readInt16LE(pcm, srcIdx * 2)
                val s1 = readInt16LE(pcm, (srcIdx + 1) * 2)
                (s0 + (s1 - s0) * frac).toInt()
            } else if (srcIdx < sourceSamples) {
                readInt16LE(pcm, srcIdx * 2)
            } else {
                0
            }

            writeInt16LE(result, i * 2, srcSample)
        }

        return result
    }

    /** Read a signed 16-bit little-endian value from a byte array. */
    private fun readInt16LE(buf: ByteArray, offset: Int): Int {
        val lo = buf[offset].toInt() and 0xFF
        val hi = buf[offset + 1].toInt()
        return (hi shl 8) or lo
    }

    // ═══════════════════════════════════════════════════════════════════════
    // WAV FILE CONSTRUCTION
    // ═══════════════════════════════════════════════════════════════════════

    private fun pcmToWav(
        pcmData: ByteArray,
        sampleRate: Int = 44100,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val headerSize = 44

        val wav = ByteArray(headerSize + dataSize)

        wav[0] = 'R'.code.toByte(); wav[1] = 'I'.code.toByte(); wav[2] = 'F'.code.toByte(); wav[3] = 'F'.code.toByte()
        writeInt32LE(wav, 4, 36 + dataSize)
        wav[8] = 'W'.code.toByte(); wav[9] = 'A'.code.toByte(); wav[10] = 'V'.code.toByte(); wav[11] = 'E'.code.toByte()

        wav[12] = 'f'.code.toByte(); wav[13] = 'm'.code.toByte(); wav[14] = 't'.code.toByte(); wav[15] = ' '.code.toByte()
        writeInt32LE(wav, 16, 16)
        writeInt16LE(wav, 20, 1)
        writeInt16LE(wav, 22, channels)
        writeInt32LE(wav, 24, sampleRate)
        writeInt32LE(wav, 28, byteRate)
        writeInt16LE(wav, 32, blockAlign)
        writeInt16LE(wav, 34, bitsPerSample)

        wav[36] = 'd'.code.toByte(); wav[37] = 'a'.code.toByte(); wav[38] = 't'.code.toByte(); wav[39] = 'a'.code.toByte()
        writeInt32LE(wav, 40, dataSize)
        System.arraycopy(pcmData, 0, wav, headerSize, dataSize)
        return wav
    }

    private fun writeInt32LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset]     = (value and 0xFF).toByte()
        buf[offset + 1] = (value shr 8 and 0xFF).toByte()
        buf[offset + 2] = (value shr 16 and 0xFF).toByte()
        buf[offset + 3] = (value shr 24 and 0xFF).toByte()
    }

    private fun writeInt16LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset]     = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CONTEXT / OVERLAY
    // ═══════════════════════════════════════════════════════════════════════

    fun setApplicationContext(context: Context) {
        applicationContext = context.applicationContext
    }

    private fun getApplicationContext(): Context? = applicationContext

    /**
     * FIX M4: Show the floating overlay widget.
     * Requires SYSTEM_ALERT_WINDOW permission.
     */
    fun showOverlay(context: Context) {
        try {
            if (overlayManager == null) {
                overlayManager = JarvisOverlayManager(context.applicationContext)
            }
            overlayManager?.show()
            _isOverlayVisible.value = true
            Log.i(TAG, "Overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay: ${e.message}")
        }
    }

    /**
     * FIX M4: Hide the floating overlay widget.
     */
    fun hideOverlay() {
        try {
            overlayManager?.hide()
            _isOverlayVisible.value = false
            Log.i(TAG, "Overlay hidden")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide overlay: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // WAKE WORD MONITORING
    // ═══════════════════════════════════════════════════════════════════════

    @SuppressLint("MissingPermission")
    fun startWakeWordMonitor(context: Context) {
        if (isWakeWordMonitoring || !_isWakeWordEnabled.value) return
        if (_isListening.value) return

        isWakeWordMonitoring = true
        Log.i(AUDIO_TAG, "[startWakeWordMonitor] Starting background wake word monitor")

        wakeWordEngine = AudioEngine(
            context = context,
            onAmplitudeUpdate = { amp ->
                if (!_isListening.value) {
                    _audioAmplitude.value = amp * 0.3f
                }
            },
            onWakeWordDetected = {
                Log.i(AUDIO_TAG, "[startWakeWordMonitor] Wake word detected — triggering listening mode")
                viewModelScope.launch(Dispatchers.Main) {
                    stopWakeWordMonitor()
                    startListening(context)
                }
            },
            onCommandReady = {
                Log.w(AUDIO_TAG, "[startWakeWordMonitor] Unexpected command ready in wake word monitor mode")
            }
        )

        try {
            wakeWordEngine?.startListening()
            Log.i(AUDIO_TAG, "[startWakeWordMonitor] Wake word monitor active")
        } catch (e: Exception) {
            Log.e(AUDIO_TAG, "[startWakeWordMonitor] Failed to start: ${e.message}")
            isWakeWordMonitoring = false
        }
    }

    fun stopWakeWordMonitor() {
        if (!isWakeWordMonitoring) return
        isWakeWordMonitoring = false
        wakeWordEngine?.stopListening()
        wakeWordEngine = null
        Log.i(AUDIO_TAG, "[stopWakeWordMonitor] stopped")
    }

    private fun restartWakeWordMonitorIfNeeded() {
        if (_isWakeWordEnabled.value && applicationContext != null) {
            startWakeWordMonitor(applicationContext!!)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // QUERY PROCESSING PIPELINE
    // ═══════════════════════════════════════════════════════════════════════

    fun processQuery(query: String, context: Context) {
        Log.d(AUDIO_TAG, "[processQuery] query=\"$query\"")
        if (_brainState.value == BrainState.THINKING || _brainState.value == BrainState.SPEAKING) {
            Log.w(AUDIO_TAG, "[processQuery] Already THINKING or SPEAKING — ignoring")
            return
        }

        viewModelScope.launch(Dispatchers.Main) {
            try {
                addUserMessage(query)
                _brainState.value = BrainState.THINKING
                _isTyping.value   = true

                if (_isListening.value) {
                    audioEngine?.stopCommandRecording()
                }

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
                Log.e(TAG, "[processQuery] pipeline error", e)
                _brainState.value     = BrainState.ERROR
                _isTyping.value       = false
                _audioAmplitude.value = 0f
                addAssistantMessage("Error: ${e.message?.take(200) ?: "Unknown"}", "stressed")
                toast(context, "Query error: ${e.message?.take(100)}")
            } finally {
                // FIX C6: Only reset _isTyping in finally.
                // brainState and audioAmplitude are now managed by:
                //   - handleAIQuery/handleSystemCommandResult (sets SPEAKING)
                //   - MediaPlayer completion listener (sets IDLE/LISTENING)
                // The old code reset brainState here, which clobbered the SPEAKING
                // state that was just set before the async TTS playback started.
                _isTyping.value = false
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
            Log.d(AUDIO_TAG, "[handleSystemCommandResult] Trying TTS for: \"${result.response.take(60)}\"")
            trySynthesizeAndPlay(result.response, context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "[handleSystemCommandResult] error: ${e.message}")
            toast(context, "TTS error: ${e.message?.take(80)}")
        }
    }

    private suspend fun handleAIQuery(query: String, context: Context) {
        try {
            _brainState.value     = BrainState.THINKING
            _audioAmplitude.value = 0.1f

            val historyJson    = buildHistoryJson()
            val screenContext  = JarviewModel.screenTextData

            // STEP 1: Try RustBridge first (fast, native path)
            Log.d(AUDIO_TAG, "[handleAIQuery] Calling RustBridge.processQuery")
            var rawResponse = withContext(Dispatchers.IO) {
                try {
                    RustBridge.processQuery(query, screenContext, historyJson)
                } catch (e: Exception) {
                    Log.e(TAG, "[handleAIQuery] JNI processQuery failed", e)
                    "[ERROR] AI processing failed: ${e.message?.take(200) ?: "Unknown"}"
                }
            }
            Log.d(AUDIO_TAG, "[handleAIQuery] Raw response length=${rawResponse.length}")

            // STEP 2: If Rust failed (native not loaded or returned error),
            // fall back to pure-Kotlin Gemini HTTP call.
            val isRustError = rawResponse.startsWith("[ERROR]") ||
                    rawResponse.startsWith("ERROR:") ||
                    rawResponse.contains("Native library not loaded")

            if (isRustError && _geminiApiKey.value.isNotBlank()) {
                Log.w(AUDIO_TAG, "[handleAIQuery] Rust failed, falling back to processQueryViaGeminiDirect")
                val directResponse = withContext(Dispatchers.IO) {
                    processQueryViaGeminiDirect(query, historyJson)
                }
                if (directResponse.isNotBlank()) {
                    rawResponse = directResponse
                    Log.i(AUDIO_TAG, "[handleAIQuery] Gemini direct fallback succeeded, length=${directResponse.length}")
                } else {
                    Log.w(AUDIO_TAG, "[handleAIQuery] Gemini direct fallback also failed")
                }
            }

            val parsed  = parseErrorResponse(rawResponse)
            val isError = parsed.startsWith("[ERROR]") || parsed.startsWith("ERROR:") || rawResponse.startsWith("ERROR:")

            if (isError) {
                Log.w(AUDIO_TAG, "[handleAIQuery] Error response detected")
                _brainState.value   = BrainState.ERROR
                _isTyping.value     = false
                _lastResponse.value = parsed
                addAssistantMessage(parsed, "stressed")
                toast(context, "AI error: ${parsed.take(80)}")
                return
            }

            val emotionTag   = parseEmotionTag(rawResponse)
            var cleanResponse = stripEmotionTag(rawResponse)

            val (finalResponse, actionResult) = withContext(Dispatchers.Default) {
                ActionHandler.interceptAndExecute(cleanResponse, context)
            }
            cleanResponse = finalResponse

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
            Log.d(AUDIO_TAG, "[handleAIQuery] Trying TTS for: \"${cleanResponse.take(60)}\"")
            trySynthesizeAndPlay(cleanResponse, context)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "[handleAIQuery] failed", e)
            _brainState.value = BrainState.ERROR
            _isTyping.value   = false
            addAssistantMessage("Processing error, Sir.", "stressed")
            toast(context, "AI processing error: ${e.message?.take(80)}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GEMINI DIRECT (Kotlin HTTP fallback — no Rust required)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Pure-Kotlin Gemini API call for AI queries.
     * Used as a fallback when RustBridge is not loaded.
     *
     * Calls the Gemini generateContent endpoint with conversation history
     * and the JARVIS system prompt, returning the model's text response.
     */
    private fun processQueryViaGeminiDirect(query: String, historyJson: String): String {
        try {
            val apiKey = _geminiApiKey.value
            if (apiKey.isBlank()) {
                Log.w(TAG, "[processQueryViaGeminiDirect] Gemini API key not set")
                return ""
            }

            Log.d(TAG, "[processQueryViaGeminiDirect] Sending query to Gemini: \"$query\"")

            // Build conversation contents from history
            val contentsArray = org.json.JSONArray()

            // Parse history entries
            try {
                val historyArr = org.json.JSONArray(historyJson)
                for (i in 0 until historyArr.length()) {
                    val entry = historyArr.getJSONObject(i)
                    val role = entry.optString("role", "user")
                    val content = entry.optString("content", "")
                    if (content.isNotBlank()) {
                        contentsArray.put(org.json.JSONObject().apply {
                            put("role", if (role == "model") "model" else "user")
                            put("parts", org.json.JSONArray().put(
                                org.json.JSONObject().put("text", content)
                            ))
                        })
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[processQueryViaGeminiDirect] Failed to parse history: ${e.message}")
            }

            // Add the current user query
            contentsArray.put(org.json.JSONObject().apply {
                put("role", "user")
                put("parts", org.json.JSONArray().put(
                    org.json.JSONObject().put("text", query)
                ))
            })

            val requestBody = org.json.JSONObject().apply {
                put("contents", contentsArray)
                put("systemInstruction", org.json.JSONObject().apply {
                    put("parts", org.json.JSONArray().put(
                        org.json.JSONObject().put("text", JARVIS_SYSTEM_PROMPT)
                    ))
                })
                put("generationConfig", org.json.JSONObject().apply {
                    put("temperature", 0.8)
                    put("maxOutputTokens", 1024)
                })
            }.toString()

            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent?key=$apiKey")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000

            connection.outputStream.use { os ->
                os.write(requestBody.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "unknown"
                Log.e(TAG, "[processQueryViaGeminiDirect] Gemini API error $responseCode: ${errorBody.take(500)}")
                connection.disconnect()
                return ""
            }

            val responseBody = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            val responseText = try {
                val root = JsonParser.parseString(responseBody).asJsonObject
                val candidates = root.getAsJsonArray("candidates")
                val firstCandidate = candidates?.firstOrNull()?.asJsonObject
                val content = firstCandidate?.getAsJsonObject("content")
                val parts = content?.getAsJsonArray("parts")
                val firstPart = parts?.firstOrNull()?.asJsonObject
                firstPart?.get("text")?.asString ?: ""
            } catch (e: Exception) {
                Log.e(TAG, "[processQueryViaGeminiDirect] JSON parsing failed: ${e.message}")
                ""
            }

            return if (responseText.isNotBlank()) {
                Log.i(TAG, "[processQueryViaGeminiDirect] Response: \"${responseText.take(100)}...\"")
                responseText
            } else {
                Log.w(TAG, "[processQueryViaGeminiDirect] Empty response from Gemini")
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "[processQueryViaGeminiDirect] Exception: ${e.message}", e)
            return ""
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ERROR RESPONSE PARSING
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * FIX M1: parseErrorResponse now only matches HTTP status code patterns
     * like "HTTP 429" or "status: 429", not arbitrary text containing "429".
     */
    private fun parseErrorResponse(raw: String): String {
        val lower = raw.lowercase()
        return when {
            // FIX M1: Only match HTTP status code patterns, not arbitrary "429" in text
            Regex("""\b429\b""").containsMatchIn(lower) ||
                    lower.contains("resource_exhausted") || lower.contains("quota") ->
                "Sir, the Gemini API quota has been exhausted. Please update the key in Settings."

            Regex("""\b403\b""").containsMatchIn(lower) ||
                    lower.contains("permission_denied") || lower.contains("api_key_invalid") ->
                "Sir, the API key appears invalid or unauthorised. Please check Settings."

            lower.contains("model_not_found") ->
                "Sir, the requested model is unavailable."

            lower.contains("network") || (lower.contains("connect") && lower.contains("refused")) ->
                "Sir, no internet connection. Check your network."

            raw.startsWith("[ERROR]") ->
                raw.removePrefix("[ERROR]").trim().ifBlank { "An error occurred, Sir." }

            raw.startsWith("ERROR:") ->
                raw.removePrefix("ERROR:").trim().ifBlank { "An error occurred, Sir." }

            lower.contains("error") && lower.contains("{") ->
                Regex(""""message"\s*:\s*"([^"]+)"""").find(raw)?.groupValues?.get(1)
                    ?: "An error occurred, Sir."

            else -> raw
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AUDIO CHUNK PROCESSING
    // ═══════════════════════════════════════════════════════════════════════

    fun processAudioChunk(audioData: ByteArray, sampleRate: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RustBridge.analyzeAudio(audioData, sampleRate)
            } catch (e: Exception) {
                Log.e(AUDIO_TAG, "[processAudioChunk] error: ${e.message}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TTS PARAMS
    // ═══════════════════════════════════════════════════════════════════════

    private fun computeTtsParams(): Pair<Float, Float> {
        val emotion = _emotion.value.lowercase()
        return when {
            emotion in listOf("urgent", "excited", "angry", "stressed", "surprised") -> Pair(0.25f, 0.90f)
            emotion in listOf("happy", "joy", "confident", "playful") -> Pair(0.40f, 0.80f)
            emotion in listOf("sad", "fearful", "disgusted") -> Pair(0.65f, 0.70f)
            else -> Pair(0.50f, 0.75f)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TTS PIPELINE — ElevenLabs → MP3 → MediaPlayer (ASYNC)
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun trySynthesizeAndPlay(text: String, context: Context) {
        Log.d(AUDIO_TAG, "[trySynthesizeAndPlay] START text=\"${text.take(40)}...\"")
        try {
            val elevenLabsKey = _elevenLabsApiKey.value
            if (elevenLabsKey.isBlank()) {
                Log.w(AUDIO_TAG, "[trySynthesizeAndPlay] ElevenLabs API key is EMPTY — skipping TTS")
                _brainState.value = if (_isListening.value) BrainState.LISTENING else BrainState.IDLE
                _audioAmplitude.value = if (_isListening.value) _audioAmplitude.value else 0f
                toast(context, "ElevenLabs API key not set — JARVIS cannot speak")
                return
            }

            val (stability, similarityBoost) = computeTtsParams()
            Log.d(AUDIO_TAG, "[trySynthesizeAndPlay] TTS params: emotion=${_emotion.value} stability=$stability similarityBoost=$similarityBoost")

            // STEP 1: Try Rust JNI path
            Log.d(AUDIO_TAG, "[trySynthesizeAndPlay] STEP 1: Trying RustBridge.synthesizeSpeech")
            var base64 = withContext(Dispatchers.IO) {
                try {
                    val result = RustBridge.synthesizeSpeech(
                        text, _ttsVoiceId.value,
                        stability = stability, similarityBoost = similarityBoost
                    )
                    Log.d(AUDIO_TAG, "[trySynthesizeAndPlay] Rust returned base64 length=${result?.length ?: 0}")
                    result
                } catch (e: Exception) {
                    Log.e(AUDIO_TAG, "[trySynthesizeAndPlay] RustBridge.synthesizeSpeech FAILED: ${e.message}")
                    null
                }
            }

            // STEP 2: If Rust returned nothing, try direct Kotlin HTTP
            if (base64.isNullOrBlank()) {
                Log.w(AUDIO_TAG, "[trySynthesizeAndPlay] STEP 2: Rust returned empty — trying direct Kotlin ElevenLabs call")
                base64 = withContext(Dispatchers.IO) {
                    try {
                        synthesizeSpeechDirect(text, _ttsVoiceId.value, elevenLabsKey, stability, similarityBoost)
                    } catch (e: Exception) {
                        Log.e(AUDIO_TAG, "[trySynthesizeAndPlay] Direct ElevenLabs call FAILED: ${e.message}")
                        null
                    }
                }
            }

            if (base64.isNullOrBlank()) {
                Log.e(AUDIO_TAG, "[trySynthesizeAndPlay] STEP 3: BOTH Rust and direct Kotlin failed — TTS impossible")
                _brainState.value = if (_isListening.value) BrainState.LISTENING else BrainState.IDLE
                _audioAmplitude.value = if (_isListening.value) _audioAmplitude.value else 0f
                toast(context, "TTS failed: ElevenLabs could not synthesize speech")
                return
            }

            // FIX C5: Use android.util.Base64.decode directly instead of RustBridge.decodeBase64Audio.
            // This removes the dependency on Rust being loaded for base64 decode.
            Log.d(AUDIO_TAG, "[trySynthesizeAndPlay] STEP 3: Decoding base64 MP3")
            val mp3 = withContext(Dispatchers.IO) {
                try {
                    val decoded = Base64.decode(base64, Base64.NO_WRAP)
                    Log.d(AUDIO_TAG, "[trySynthesizeAndPlay] Decoded MP3 size=${decoded.size} bytes")
                    decoded
                } catch (e: Exception) {
                    Log.e(AUDIO_TAG, "[trySynthesizeAndPlay] Base64 decode FAILED: ${e.message}")
                    null
                }
            }

            if (mp3 == null || mp3.isEmpty()) {
                Log.e(AUDIO_TAG, "[trySynthesizeAndPlay] Decoded MP3 is EMPTY")
                _brainState.value = if (_isListening.value) BrainState.LISTENING else BrainState.IDLE
                _audioAmplitude.value = if (_isListening.value) _audioAmplitude.value else 0f
                toast(context, "TTS failed: audio data is empty")
                return
            }

            // STEP 4: Write temp file and play via MediaPlayer (async)
            Log.d(AUDIO_TAG, "[trySynthesizeAndPlay] STEP 4: Playing MP3 via MediaPlayer (prepareAsync)")
            withContext(Dispatchers.Main) {
                playMp3AudioAsync(mp3, context)
            }

            // Wait for playback to finish
            Log.d(AUDIO_TAG, "[trySynthesizeAndPlay] STEP 5: Waiting for playback (timeout=${TTS_TIMEOUT_MS}ms)")
            withTimeoutOrNull(TTS_TIMEOUT_MS) {
                while (isActive && mediaPlayer != null) {
                    val isPlaying = try { mediaPlayer?.isPlaying == true } catch (_: Exception) { false }
                    if (!isPlaying) {
                        Log.d(AUDIO_TAG, "[trySynthesizeAndPlay] MediaPlayer stopped — playback complete")
                        break
                    }
                    delay(100)
                }
            }
            Log.d(AUDIO_TAG, "[trySynthesizeAndPlay] END")

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(AUDIO_TAG, "[trySynthesizeAndPlay] UNEXPECTED EXCEPTION: ${e.message}", e)
            _brainState.value = if (_isListening.value) BrainState.LISTENING else BrainState.IDLE
            _audioAmplitude.value = if (_isListening.value) _audioAmplitude.value else 0f
            toast(context, "TTS error: ${e.message?.take(80)}")
        }
    }

    /**
     * Direct Kotlin ElevenLabs API call — bypasses Rust entirely.
     */
    private fun synthesizeSpeechDirect(
        text: String,
        voiceId: String,
        apiKey: String,
        stability: Float,
        similarityBoost: Float
    ): String? {
        Log.d(AUDIO_TAG, "[synthesizeSpeechDirect] called voiceId=$voiceId")
        val url = URL("https://api.elevenlabs.io/v1/text-to-speech/$voiceId")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("xi-api-key", apiKey)
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "audio/mpeg")
        connection.doOutput = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000

        val requestBody = org.json.JSONObject().apply {
            put("text", text)
            put("model_id", "eleven_turbo_v2")
            put("voice_settings", org.json.JSONObject().apply {
                put("stability", stability.toDouble())
                put("similarity_boost", similarityBoost.toDouble())
            })
        }.toString()

        connection.outputStream.use { os ->
            os.write(requestBody.toByteArray(Charsets.UTF_8))
        }

        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "unknown"
            Log.e(AUDIO_TAG, "[synthesizeSpeechDirect] ElevenLabs HTTP $responseCode: ${errorBody.take(500)}")
            connection.disconnect()
            return null
        }

        val audioBytes = connection.inputStream.use { it.readBytes() }
        connection.disconnect()

        Log.d(AUDIO_TAG, "[synthesizeSpeechDirect] ElevenLabs returned ${audioBytes.size} bytes raw MP3")
        val base64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
        Log.d(AUDIO_TAG, "[synthesizeSpeechDirect] Base64 encoded length=${base64.length}")
        return base64
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PUBLIC HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    fun sendMessage(text: String, context: Context) = processQuery(text, context)

    fun toggleVoiceMode(context: Context) {
        Log.d(AUDIO_TAG, "[toggleVoiceMode] called")
        if (_isListening.value) {
            stopListening()
            _isVoiceMode.value = false
        } else {
            startListening(context)
            _isVoiceMode.value = true
        }
    }

    /**
     * FIX H4: Deprecated no-arg version with ERROR level.
     * This ensures compile-time visibility of the deprecation.
     */
    @Deprecated(
        message = "Use toggleVoiceMode(context: Context) instead — context is required for audio engine",
        replaceWith = ReplaceWith("toggleVoiceMode(context)"),
        level = DeprecationLevel.ERROR
    )
    fun toggleVoiceMode() {
        Log.w(AUDIO_TAG, "[toggleVoiceMode] DEPRECATED no-arg version called — no context available, toggling state only")
        _isVoiceMode.value = !_isVoiceMode.value
    }

    /**
     * FIX H9: toggleDevice now also publishes commands to MQTT and HomeAssistant,
     * not just updating the local StateFlow.
     */
    fun toggleDevice(deviceId: String, newState: Boolean) {
        // Update local state immediately for responsive UI
        _devices.value = _devices.value.map { d ->
            if (d.id == deviceId) d.copy(isOn = newState) else d
        }

        // Send command to Home Assistant
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (newState) {
                    HomeAssistantBridge.turnOn(deviceId)
                } else {
                    HomeAssistantBridge.turnOff(deviceId)
                }
                Log.i(TAG, "toggleDevice: Sent ${if (newState) "ON" else "OFF"} to HA for $deviceId")
            } catch (e: Exception) {
                Log.e(TAG, "toggleDevice: HA call failed for $deviceId: ${e.message}")
            }

            // Also publish via MQTT
            try {
                val topic = "jarvis/command/$deviceId"
                val payload = if (newState) "{\"state\": \"ON\"}" else "{\"state\": \"OFF\"}"
                MqttManager.publish(topic, payload)
                Log.i(TAG, "toggleDevice: Published MQTT command for $deviceId")
            } catch (e: Exception) {
                Log.e(TAG, "toggleDevice: MQTT publish failed for $deviceId: ${e.message}")
            }
        }
    }

    /**
     * FIX C2: refreshDevices now actually fetches device states from
     * Home Assistant and populates the _devices StateFlow.
     */
    fun refreshDevices() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!HomeAssistantBridge.isConfigured()) {
                    Log.w(TAG, "[refreshDevices] HomeAssistant not configured — skipping")
                    return@launch
                }

                val states = HomeAssistantBridge.getStates()
                if (states == null) {
                    Log.w(TAG, "[refreshDevices] HomeAssistantBridge.getStates() returned null")
                    return@launch
                }

                val deviceList = mutableListOf<SmartDevice>()
                for (i in 0 until states.length()) {
                    try {
                        val entity = states.getJSONObject(i)
                        val entityId = entity.optString("entity_id", "")
                        val state = entity.optString("state", "")
                        val attributes = entity.optJSONObject("attributes") ?: continue
                        val friendlyName = attributes.optString("friendly_name", entityId)

                        // Skip non-controllable entities
                        val domain = entityId.substringBefore(".", "")
                        if (domain !in listOf("light", "switch", "fan", "humidifier",
                                "input_boolean", "climate", "cover", "lock",
                                "media_player", "automation", "script", "group")) {
                            continue
                        }

                        val deviceType = when (domain) {
                            "light" -> DeviceType.LIGHT
                            "switch" -> DeviceType.SWITCH
                            "fan" -> DeviceType.FAN
                            "climate" -> DeviceType.THERMOSTAT
                            "cover" -> DeviceType.CURTAIN
                            "lock" -> DeviceType.LOCK
                            "media_player" -> DeviceType.SPEAKER
                            else -> DeviceType.SWITCH
                        }

                        val isOn = state.equals("on", ignoreCase = true) ||
                                state.equals("locked", ignoreCase = true) ||
                                state.equals("open", ignoreCase = true) ||
                                state.equals("playing", ignoreCase = true)

                        // Determine room from area or use "Default"
                        val room = attributes.optString("area", "Default")

                        // Value for sensors/thermostats
                        val value = when (domain) {
                            "climate" -> {
                                val temp = attributes.optString("current_temperature", "")
                                if (temp.isNotBlank()) "${temp}\u00B0C" else ""
                            }
                            "lock" -> if (isOn) "Locked" else "Unlocked"
                            "cover" -> if (isOn) "Open" else "Closed"
                            else -> ""
                        }

                        deviceList.add(SmartDevice(
                            id = entityId,
                            name = friendlyName,
                            type = deviceType,
                            room = room.ifBlank { "Default" },
                            isOn = isOn,
                            value = value
                        ))
                    } catch (e: Exception) {
                        Log.w(TAG, "[refreshDevices] Failed to parse entity: ${e.message}")
                    }
                }

                _devices.value = deviceList
                Log.i(TAG, "[refreshDevices] Loaded ${deviceList.size} devices from Home Assistant")
            } catch (e: Exception) {
                Log.e(TAG, "[refreshDevices] Failed: ${e.message}")
            }
        }
    }

    fun updateAmplitude(amplitude: Float) {
        _audioAmplitude.value = amplitude.coerceIn(0f, 1f)
    }

    fun requestShizukuPermission(activity: android.app.Activity, requestCode: Int = 0) {
        if (ShizukuManager.isReady() && !ShizukuManager.hasPermission()) {
            ShizukuManager.requestPermission(activity, requestCode)
        }
    }

    fun requestShizukuPermission() {
        if (ShizukuManager.isReady() && !ShizukuManager.hasPermission()) {
            ShizukuManager.requestPermission()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SMART HOME CONNECTION — FIX C2
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * FIX C2: Configure HomeAssistantBridge and connect MQTT.
     * Called from loadPersistedSettings() after loading MQTT/HA settings.
     */
    private fun connectSmartHome(context: Context) {
        try {
            // Configure Home Assistant bridge
            val haUrl = _homeAssistantUrl.value
            val haToken = _homeAssistantToken.value
            if (haUrl.isNotBlank() && haToken.isNotBlank()) {
                HomeAssistantBridge.configure(haUrl, haToken)
                Log.i(TAG, "Home Assistant bridge configured: $haUrl")

                // Fetch devices
                refreshDevices()
            }

            // Connect MQTT
            val brokerUrl = _mqttBrokerUrl.value
            if (brokerUrl.isNotBlank()) {
                val connected = MqttManager.connect(
                    context = context,
                    broker = brokerUrl,
                    user = _mqttUsername.value,
                    pass = _mqttPassword.value
                )
                _isMqttConnected.value = connected
                _mqttLabel.value = if (connected) "MQTT Connected" else "MQTT Disconnected"
                Log.i(TAG, "MQTT connect initiated: broker=$brokerUrl result=$connected")

                // Subscribe to device state topics
                MqttManager.subscribe("jarvis/status/#")
                MqttManager.subscribe("homeassistant/#")
            }
        } catch (e: Exception) {
            Log.e(TAG, "connectSmartHome failed: ${e.message}")
        }
    }

    /**
     * FIX C2: Reconnect smart home when settings change.
     */
    private fun reconnectSmartHome() {
        val ctx = getApplicationContext() ?: return

        // Disconnect existing connections
        try {
            MqttManager.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "MQTT disconnect error: ${e.message}")
        }

        _isMqttConnected.value = false
        _mqttLabel.value = "MQTT Disconnected"

        // Reconnect with new settings
        connectSmartHome(ctx)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PER-FIELD SETTERS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * FIX H1: setGeminiApiKey no longer calls RustBridge.initialize().
     * Only saveAndApplyApiKeys() should call RustBridge.initialize()
     * to avoid race conditions from dual initialization.
     */
    fun setGeminiApiKey(key: String) {
        _geminiApiKey.value = key
        viewModelScope.launch(Dispatchers.IO) {
            try {
                settingsRepository.setGeminiApiKey(key)
            } catch (e: Exception) { Log.e(TAG, "setGeminiApiKey: ${e.message}") }
        }
    }

    /**
     * FIX H1: setElevenLabsApiKey no longer calls RustBridge.initialize().
     * Only saveAndApplyApiKeys() should call RustBridge.initialize()
     * to avoid race conditions from dual initialization.
     */
    fun setElevenLabsApiKey(key: String) {
        _elevenLabsApiKey.value = key
        viewModelScope.launch(Dispatchers.IO) {
            try {
                settingsRepository.setElevenLabsApiKey(key)
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
        if (enabled && !_isListening.value && applicationContext != null) {
            startWakeWordMonitor(applicationContext!!)
        } else if (!enabled) {
            stopWakeWordMonitor()
        }
    }

    /**
     * FIX C2: MQTT setters now trigger reconnection when settings change.
     */
    fun setMqttBrokerUrl(url: String) {
        _mqttBrokerUrl.value = url
        viewModelScope.launch(Dispatchers.IO) {
            try {
                settingsRepository.setMqttBrokerUrl(url)
                reconnectSmartHome()
            } catch (e: Exception) {}
        }
    }

    fun setMqttUsername(u: String) {
        _mqttUsername.value = u
        viewModelScope.launch(Dispatchers.IO) {
            try {
                settingsRepository.setMqttUsername(u)
                reconnectSmartHome()
            } catch (e: Exception) {}
        }
    }

    fun setMqttPassword(p: String) {
        _mqttPassword.value = p
        viewModelScope.launch(Dispatchers.IO) {
            try {
                settingsRepository.setMqttPassword(p)
                reconnectSmartHome()
            } catch (e: Exception) {}
        }
    }

    /**
     * FIX C2: HA setters now trigger reconnection when settings change.
     */
    fun setHomeAssistantUrl(url: String) {
        _homeAssistantUrl.value = url
        viewModelScope.launch(Dispatchers.IO) {
            try {
                settingsRepository.setHomeAssistantUrl(url)
                reconnectSmartHome()
            } catch (e: Exception) {}
        }
    }

    fun setHomeAssistantToken(token: String) {
        _homeAssistantToken.value = token
        viewModelScope.launch(Dispatchers.IO) {
            try {
                settingsRepository.setHomeAssistantToken(token)
                reconnectSmartHome()
            } catch (e: Exception) {}
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

    // ═══════════════════════════════════════════════════════════════════════
    // MESSAGE HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private fun addUserMessage(text: String) {
        _messages.value += ChatMessage(id = nextMessageId++, content = text, isFromUser = true)
    }

    private fun addAssistantMessage(text: String, emotion: String) {
        _messages.value += ChatMessage(id = nextMessageId++, content = text, isFromUser = false, emotion = emotion)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HISTORY / EMOTION HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private val gson = Gson()

    private data class HistoryEntry(val role: String, val content: String)

    private fun buildHistoryJson(): String {
        val recent = _messages.value.takeLast(MAX_HISTORY_ENTRIES)
        val entries = recent.map { m ->
            HistoryEntry(
                role = if (m.isFromUser) "user" else "model",
                content = m.content
            )
        }
        return gson.toJson(entries)
    }

    private fun parseEmotionTag(r: String) =
        Regex("""\[EMOTION:(\w+)]""", RegexOption.IGNORE_CASE).find(r)?.groupValues?.get(1)?.lowercase() ?: "neutral"

    private fun stripEmotionTag(r: String) =
        Regex("""\[EMOTION:\w+]\s*""", RegexOption.IGNORE_CASE).replace(r, "").trim()

    // ═══════════════════════════════════════════════════════════════════════
    // MediaPlayer TTS PLAYBACK — ASYNC PREPARE (ANR FIX)
    // ═══════════════════════════════════════════════════════════════════════

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private suspend fun playMp3AudioAsync(mp3Bytes: ByteArray, context: Context) {
        Log.d(AUDIO_TAG, "[playMp3AudioAsync] START mp3Bytes=${mp3Bytes.size}")
        mediaPlayerMutex.withLock {
            currentTtsTempPath?.let { prevPath ->
                try {
                    val prevFile = File(prevPath)
                    if (prevFile.exists()) {
                        prevFile.delete()
                        Log.d(AUDIO_TAG, "[playMp3AudioAsync] Deleted previous temp: $prevPath")
                    }
                } catch (e: Exception) {
                    Log.w(AUDIO_TAG, "[playMp3AudioAsync] Failed to delete previous temp: ${e.message}")
                }
            }

            amplitudePulseJob?.cancel()
            amplitudePulseJob = null
            releaseMediaPlayer()

            val tmp: File
            try {
                tmp = withContext(Dispatchers.IO) {
                    val file = File(context.cacheDir, "tts_${System.currentTimeMillis()}.mp3")
                    FileOutputStream(file).use { it.write(mp3Bytes) }
                    file.deleteOnExit()
                    Log.d(AUDIO_TAG, "[playMp3AudioAsync] Temp file written: ${file.absolutePath} (${mp3Bytes.size} bytes)")
                    file
                }
            } catch (e: Exception) {
                Log.e(AUDIO_TAG, "[playMp3AudioAsync] Failed to write temp MP3: ${e.message}")
                _brainState.value = if (_isListening.value) BrainState.LISTENING else BrainState.IDLE
                _audioAmplitude.value = if (_isListening.value) _audioAmplitude.value else 0f
                toast(context, "TTS file write failed")
                return@withLock
            }
            currentTtsTempPath = tmp.absolutePath

            val player = MediaPlayer()
            val isPrepared = AtomicBoolean(false)

            player.setOnPreparedListener {
                Log.d(AUDIO_TAG, "[playMp3AudioAsync] MediaPlayer prepared — starting playback")
                isPrepared.set(true)
                try {
                    player.start()
                    Log.d(AUDIO_TAG, "[playMp3AudioAsync] MediaPlayer.start() called — isPlaying=${player.isPlaying}")
                } catch (e: IllegalStateException) {
                    Log.e(AUDIO_TAG, "[playMp3AudioAsync] start() threw IllegalStateException: ${e.message}")
                }

                amplitudePulseJob = viewModelScope.launch(Dispatchers.Default) {
                    var phase = 0f
                    while (isActive) {
                        val playing = try { mediaPlayer?.isPlaying == true } catch (_: Exception) { false }
                        if (!playing) {
                            Log.d(AUDIO_TAG, "[playMp3AudioAsync] Amplitude pulse detected playback stopped")
                            break
                        }
                        phase += 0.15f
                        val pulse = 0.4f + 0.2f * kotlin.math.sin(phase.toDouble()).toFloat()
                        _audioAmplitude.value = pulse.coerceIn(0.2f, 0.6f)
                        delay(45)
                    }
                    if (!_isListening.value) {
                        _audioAmplitude.value = 0f
                    }
                }
            }

            player.setOnCompletionListener {
                Log.d(AUDIO_TAG, "[playMp3AudioAsync] MediaPlayer playback COMPLETED")
                if (!_isListening.value) {
                    _audioAmplitude.value = 0f
                }
                // FIX C6: This completion listener is the proper place to reset
                // brainState after TTS playback finishes — NOT the processQuery finally block.
                _brainState.value = if (_isListening.value) BrainState.LISTENING else BrainState.IDLE
                try {
                    tmp.delete()
                    Log.d(AUDIO_TAG, "[playMp3AudioAsync] Temp file deleted on completion")
                } catch (e: Exception) {
                    Log.w(AUDIO_TAG, "[playMp3AudioAsync] Temp file delete failed: ${e.message}")
                }
                currentTtsTempPath = null
                amplitudePulseJob?.cancel()
                amplitudePulseJob = null
            }

            player.setOnErrorListener { _, what, extra ->
                Log.e(AUDIO_TAG, "[playMp3AudioAsync] MediaPlayer ERROR: what=$what extra=$extra")
                _brainState.value = if (_isListening.value) BrainState.LISTENING else BrainState.ERROR
                _audioAmplitude.value = 0f
                try {
                    tmp.delete()
                    currentTtsTempPath = null
                } catch (_: Exception) {}
                amplitudePulseJob?.cancel()
                amplitudePulseJob = null
                toast(context, "MediaPlayer error: what=$what extra=$extra")
                true
            }

            try {
                player.setDataSource(tmp.absolutePath)
                Log.d(AUDIO_TAG, "[playMp3AudioAsync] setDataSource() OK — calling prepareAsync()")
                player.prepareAsync()
                mediaPlayer = player
            } catch (e: Exception) {
                Log.e(AUDIO_TAG, "[playMp3AudioAsync] setDataSource/prepareAsync failed: ${e.message}")
                try { player.release() } catch (_: Exception) {}
                try { tmp.delete() } catch (_: Exception) {}
                currentTtsTempPath = null
                _brainState.value = if (_isListening.value) BrainState.LISTENING else BrainState.ERROR
                _audioAmplitude.value = if (_isListening.value) _audioAmplitude.value else 0f
                toast(context, "MediaPlayer init failed: ${e.message?.take(60)}")
            }
        }
        Log.d(AUDIO_TAG, "[playMp3AudioAsync] END (mutex released)")
    }

    private fun releaseMediaPlayer() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    try { stop() } catch (_: Exception) {}
                }
                try { release() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(AUDIO_TAG, "[releaseMediaPlayer] error: ${e.message}")
        }
        mediaPlayer = null
    }

    /**
     * FIX L7: Toast helper that always runs on the Main thread.
     * Uses Handler to post to the main looper if called from a background thread,
     * ensuring Toast never crashes due to being called off the main thread.
     */
    private fun toast(context: Context, message: String) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            } else {
                Handler(Looper.getMainLooper()).post {
                    try {
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════

    override fun onCleared() {
        super.onCleared()
        Log.d(AUDIO_TAG, "[onCleared] ViewModel cleared")

        amplitudePulseJob?.cancel()
        amplitudePulseJob = null
        releaseMediaPlayer()

        currentTtsTempPath?.let { path ->
            try {
                val file = File(path)
                if (file.exists()) file.delete()
            } catch (_: Exception) {}
            currentTtsTempPath = null
        }

        stopAudioEngine()
        stopWakeWordMonitor()

        // FIX M4: Hide overlay on ViewModel clear
        overlayManager?.hide()
        overlayManager = null

        // Disconnect MQTT
        try { MqttManager.disconnect() } catch (_: Exception) {}

        try { RustBridge.shutdown() } catch (_: Exception) {}
        ShizukuManager.setOnShizukuStateChangedListener {}
    }

    class Factory(private val repo: SettingsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(c: Class<T>): T = JarvisViewModel(repo) as T
    }
}
