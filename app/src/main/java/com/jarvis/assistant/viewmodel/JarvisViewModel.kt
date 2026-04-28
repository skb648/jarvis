package com.jarvis.assistant.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.gson.Gson
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
import java.net.HttpURLConnection
import java.net.URL

/** Result of a "Save & Apply" API key operation — consumed once for snackbar/toast. */
enum class ApiKeySaveResult { NONE, SUCCESS, FAILURE }

/**
 * JarvisViewModel — Central state holder and orchestrator.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * CRITICAL FIXES (v6):
 *
 * 1. VAD (Voice Activity Detection) INTEGRATION:
 *    When the mic is started, AudioEngine now uses VAD to auto-stop
 *    recording when the user stops speaking. The ViewModel properly
 *    handles the VAD flow: start listening → detect speech → detect
 *    silence → auto-flush command → process via AI.
 *
 * 2. WAKE WORD → VAD TRIGGER:
 *    When the wake word is detected, the ViewModel automatically
 *    triggers VAD recording mode (same as pressing the mic button).
 *
 * 3. MIC BUTTONS FULLY WIRED:
 *    ALL mic buttons across ALL screens now call through to
 *    startListening()/stopListening(). The Chat screen voice button
 *    is no longer a dead toggle.
 *
 * 4. SPEECH-TO-TEXT VIA SpeechRecognizer (v7 FIX):
 *    CRITICAL BUG FIX: The previous implementation was a HALLUCINATION
 *    PIPELINE. It called RustBridge.analyzeAudio() which only returns
 *    pitch/volume/emotion metadata, then sent that JSON to Gemini asking
 *    "what did they likely say?" — Gemini would HALLUCINATE words from
 *    audio analysis JSON, not transcribe actual speech.
 *
 *    FIX: We now run Android's SpeechRecognizer in PARALLEL with the
 *    AudioEngine. When VAD detects end-of-speech, we use the actual
 *    transcription from SpeechRecognizer. If SpeechRecognizer fails or
 *    returns nothing, we fall back to Gemini's multimodal API by sending
 *    the raw audio (as WAV base64) for transcription — NOT audio metadata.
 *
 * 5. BACKGROUND WAKE WORD MONITORING:
 *    When the app is in the foreground and wake word is enabled,
 *    a lightweight AudioEngine monitors for the wake word and
 *    automatically triggers VAD listening when detected.
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

    // ── Background wake word monitor ───────────────────────────────────────────
    /** Low-power AudioEngine for background wake word detection when app is idle. */
    private var wakeWordEngine: AudioEngine? = null
    @Volatile private var isWakeWordMonitoring = false

    // ── ExoPlayer for TTS ──────────────────────────────────────────────────────
    private var exoPlayer: ExoPlayer? = null
    private var nextMessageId = 0L

    // ── Speech Recognition ──────────────────────────────────────────────────────
    private var speechRecognizer: SpeechRecognizer? = null

    /** Latest full transcription from SpeechRecognizer — updated in real-time. */
    @Volatile
    private var speechRecognizerResult: String = ""

    /** Latest partial transcription from SpeechRecognizer — updated in real-time. */
    @Volatile
    private var speechRecognizerPartial: String = ""

    /** Whether SpeechRecognizer is currently listening. */
    @Volatile
    private var isSpeechRecognizerActive: Boolean = false


    init {
        loadPersistedSettings()
        _isRustReady.value = RustBridge.isNativeReady()

        // ── Register Shizuku state listener ────────────────────────────────
        ShizukuManager.setOnShizukuStateChangedListener { available ->
            _isShizukuAvailable.value = available
            Log.i(TAG, "Shizuku state changed: available=$available")
        }

        // Check initial state
        _isShizukuAvailable.value = ShizukuManager.isReady() && ShizukuManager.hasPermission()

        // ── Periodic Shizuku health check ──────────────────────────────────
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

    fun saveAndApplyApiKeys(geminiKey: String, elevenLabsKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                settingsRepository.setGeminiApiKey(geminiKey)
                settingsRepository.setElevenLabsApiKey(elevenLabsKey)
                _geminiApiKey.value     = geminiKey
                _elevenLabsApiKey.value = elevenLabsKey

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

    fun consumeApiKeySaveResult() {
        _apiKeySaveResult.value = ApiKeySaveResult.NONE
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MIC TOGGLE — FULLY WIRED WITH VAD
    //
    // Flow:
    // 1. User presses mic → startListening()
    // 2. AudioEngine starts recording with VAD
    // 3. VAD detects speech → begins accumulating command frames
    // 4. VAD detects 1.5s silence after speech → auto-flushes command
    // 5. Command PCM bytes delivered to onCommandReady
    // 6. onCommandReady uses SpeechRecognizer to transcribe
    // 7. Transcribed text sent to processQuery()
    // ═══════════════════════════════════════════════════════════════════════

    fun toggleListening(context: Context) {
        if (_isListening.value) {
            stopListening()
        } else {
            startListening(context)
        }
    }

    @SuppressLint("MissingPermission")
    fun startListening(context: Context) {
        if (_isListening.value) return  // Already listening

        _isListening.value       = true
        _brainState.value        = BrainState.LISTENING
        _currentTranscription.value = ""

        // Stop background wake word monitor while actively listening
        stopWakeWordMonitor()

        startAudioEngine(context)

        // Start SpeechRecognizer in parallel for ACTUAL transcription
        // AudioEngine handles VAD (detecting speech/silence boundaries),
        // while SpeechRecognizer provides real-time speech-to-text.
        startSpeechRecognizer(context)

        // Auto-start VAD command recording — the mic button means "I'm talking now"
        audioEngine?.startCommandRecording()
    }

    fun stopListening() {
        if (!_isListening.value) return

        // If we're in the middle of recording, flush the command first
        audioEngine?.stopCommandRecording()
        stopAudioEngine()

        // Stop SpeechRecognizer — capture any pending results
        stopSpeechRecognizer()

        _isListening.value       = false
        _brainState.value        = BrainState.IDLE
        _audioAmplitude.value    = 0f
        _currentTranscription.value = ""

        // Restart wake word monitor if enabled
        restartWakeWordMonitorIfNeeded()
    }

    @SuppressLint("MissingPermission")
    private fun startAudioEngine(context: Context) {
        stopAudioEngine()   // release any stale instance

        audioEngine = AudioEngine(
            context = context,
            onAmplitudeUpdate = { amp ->
                _audioAmplitude.value = amp
            },
            onWakeWordDetected = {
                Log.i(TAG, "Wake word detected — triggering VAD recording")
                _brainState.value = BrainState.LISTENING
                _isListening.value = true
                audioEngine?.startCommandRecording()
            },
            onCommandReady = { pcmBytes ->
                Log.i(TAG, "Command ready: ${pcmBytes.size} bytes — transcribing via SpeechRecognizer")
                handleCommandReady(pcmBytes)
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

    // ═══════════════════════════════════════════════════════════════════════
    // SpeechRecognizer — PARALLEL LIVE TRANSCRIPTION
    //
    // Android's SpeechRecognizer captures audio from the microphone
    // independently and transcribes it in real-time. We run it in
    // parallel with AudioEngine's VAD so that when VAD detects
    // end-of-speech, we already have a transcription ready.
    //
    // Flow:
    //   startListening() → startSpeechRecognizer()
    //   SpeechRecognizer captures partial/full results in callbacks
    //   VAD fires onCommandReady → handleCommandReady()
    //   handleCommandReady() uses SpeechRecognizer result
    //   Fallback: Gemini multimodal API with WAV audio
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Lazily creates and configures the SpeechRecognizer.
     * Must be called on the main thread (SpeechRecognizer requirement).
     */
    @SuppressLint("MissingPermission")
    private fun ensureSpeechRecognizer(context: Context): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "SpeechRecognizer not available on this device")
            return false
        }

        // Destroy existing instance if any
        speechRecognizer?.destroy()

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "SpeechRecognizer: ready for speech")
                    isSpeechRecognizerActive = true
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "SpeechRecognizer: beginning of speech")
                }

                override fun onRmsChanged(rmsdB: Float) {
                    // No-op — we get amplitude from AudioEngine
                }

                override fun onBufferReceived(buffer: ByteArray?) {
                    // No-op — we don't use raw audio from SpeechRecognizer
                }

                override fun onEndOfSpeech() {
                    Log.d(TAG, "SpeechRecognizer: end of speech")
                    isSpeechRecognizerActive = false
                }

                override fun onError(error: Int) {
                    val errMsg = when (error) {
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
                        SpeechRecognizer.ERROR_NETWORK -> "network error"
                        SpeechRecognizer.ERROR_AUDIO -> "audio error"
                        SpeechRecognizer.ERROR_SERVER -> "server error"
                        SpeechRecognizer.ERROR_CLIENT -> "client error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech detected"
                        SpeechRecognizer.ERROR_NO_MATCH -> "no match found"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "insufficient permissions"
                        else -> "unknown error ($error)"
                    }
                    Log.w(TAG, "SpeechRecognizer error: $errMsg")
                    isSpeechRecognizerActive = false
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        speechRecognizerResult = matches[0]
                        Log.i(TAG, "SpeechRecognizer full result: \"$speechRecognizerResult\"")
                    }
                    isSpeechRecognizerActive = false
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        speechRecognizerPartial = matches[0]
                        Log.d(TAG, "SpeechRecognizer partial: \"$speechRecognizerPartial\"")
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {
                    // No-op
                }
            })
        }

        return true
    }

    /**
     * Start SpeechRecognizer listening in parallel with AudioEngine.
     * Must be called on the main thread.
     */
    @SuppressLint("MissingPermission")
    private fun startSpeechRecognizer(context: Context) {
        // Reset state
        speechRecognizerResult = ""
        speechRecognizerPartial = ""

        if (!ensureSpeechRecognizer(context)) {
            Log.w(TAG, "SpeechRecognizer unavailable — will rely on Gemini fallback")
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Keep listening for up to 30 seconds
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 30000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
        }

        try {
            speechRecognizer?.startListening(intent)
            isSpeechRecognizerActive = true
            Log.i(TAG, "SpeechRecognizer started — listening in parallel with AudioEngine")
        } catch (e: SecurityException) {
            Log.e(TAG, "SpeechRecognizer: RECORD_AUDIO permission denied: ${e.message}")
            isSpeechRecognizerActive = false
        } catch (e: Exception) {
            Log.e(TAG, "SpeechRecognizer failed to start: ${e.message}")
            isSpeechRecognizerActive = false
        }
    }

    /**
     * Stop SpeechRecognizer and return the best available transcription.
     * Must be called on the main thread.
     */
    private fun stopSpeechRecognizer(): String {
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.w(TAG, "SpeechRecognizer stopListening error: ${e.message}")
        }
        isSpeechRecognizerActive = false

        // Prefer full result, fall back to partial
        val result = speechRecognizerResult.ifBlank { speechRecognizerPartial }
        Log.i(TAG, "SpeechRecognizer stopped — best result: \"$result\"")
        return result
    }

    /**
     * Completely destroy the SpeechRecognizer instance.
     */
    private fun destroySpeechRecognizer() {
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "SpeechRecognizer destroy error: ${e.message}")
        }
        speechRecognizer = null
        isSpeechRecognizerActive = false
        speechRecognizerResult = ""
        speechRecognizerPartial = ""
    }

    // ═══════════════════════════════════════════════════════════════════════
    // COMMAND READY HANDLER — REAL TRANSCRIPTION PIPELINE (v7)
    //
    // When VAD flushes a command (user finished speaking), we need to
    // convert the speech to text. The pipeline is now:
    //
    //   1. Check SpeechRecognizer results (captured in parallel while
    //      the user was speaking). This is REAL speech-to-text, not
    //      hallucination.
    //
    //   2. If SpeechRecognizer returned nothing, fall back to Gemini's
    //      multimodal API by sending the actual audio as WAV base64
    //      for transcription. This is still real transcription, just
    //      server-side.
    //
    //   3. If both fail, ask the user to repeat.
    //
    // We also run RustBridge.analyzeAudio() for emotion metadata (this
    // is fine — it's for detecting the user's emotional tone, NOT for
    // transcription).
    // ═══════════════════════════════════════════════════════════════════════

    private fun handleCommandReady(pcmBytes: ByteArray) {
        viewModelScope.launch(Dispatchers.Main) {
            _brainState.value = BrainState.THINKING

            // ── Step 1: Stop SpeechRecognizer and grab its results ─────────
            // This is the PRIMARY transcription source — real speech-to-text.
            val recognizerTranscription = stopSpeechRecognizer()

            // ── Step 2: Also get emotion metadata from audio analysis ──────
            // This is NOT used for transcription — only for detecting
            // the user's emotional tone (angry, calm, urgent, etc.)
            val audioEmotion = try {
                withContext(Dispatchers.IO) {
                    RustBridge.analyzeAudio(pcmBytes, 44_100)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Audio emotion analysis failed: ${e.message}")
                null
            }

            // Update emotion state from audio analysis (not transcription)
            if (!audioEmotion.isNullOrBlank()) {
                try {
                    val emotionRegex = Regex(""""emotion"\s*:\s*"(\w+)"""")
                    val match = emotionRegex.find(audioEmotion)
                    if (match != null) {
                        val detected = match.groupValues[1].lowercase()
                        if (detected in listOf("angry", "calm", "happy", "sad", "fearful",
                                "surprised", "neutral", "urgent", "stressed")) {
                            _emotion.value = detected
                            Log.d(TAG, "Emotion from audio analysis: $detected")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse emotion from audio analysis: ${e.message}")
                }
            }

            // ── Step 3: Use SpeechRecognizer result if available ───────────
            var transcription = recognizerTranscription.trim()

            // ── Step 4: Fallback — Gemini multimodal API with audio ────────
            if (transcription.isBlank()) {
                Log.w(TAG, "SpeechRecognizer returned nothing — falling back to Gemini multimodal")
                transcription = transcribeViaGeminiFallback(pcmBytes)
            }

            // ── Step 5: Process the transcription ──────────────────────────
            if (transcription.isNotBlank()) {
                _currentTranscription.value = transcription
                Log.i(TAG, "Final transcription: \"$transcription\"")
                val context = getApplicationContext()
                if (context != null) {
                    processQuery(transcription, context)
                } else {
                    Log.e(TAG, "No application context — cannot process transcription")
                    _brainState.value = BrainState.IDLE
                    _isListening.value = false
                }
            } else {
                _brainState.value = BrainState.IDLE
                _isListening.value = false
                addAssistantMessage("I couldn't make that out, Sir. Please try again.", "confused")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GEMINI MULTIMODAL FALLBACK — SEND ACTUAL AUDIO FOR TRANSCRIPTION
    //
    // When Android's SpeechRecognizer is unavailable or returns nothing,
    // we fall back to sending the raw PCM audio (converted to WAV) to
    // Gemini's multimodal API for server-side transcription.
    //
    // This is REAL transcription — Gemini receives actual audio data
    // and returns what was said. This is NOT the old hallucination
    // pipeline that sent audio metadata JSON.
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Transcribe audio via Gemini's multimodal API.
     * Converts PCM to WAV, base64 encodes it, and sends it to Gemini
     * with a transcription prompt.
     *
     * @param pcmBytes Raw PCM audio data (16-bit, 44100Hz, mono)
     * @return Transcribed text, or empty string on failure
     */
    private suspend fun transcribeViaGeminiFallback(pcmBytes: ByteArray): String {
        return withContext(Dispatchers.IO) {
            try {
                val apiKey = _geminiApiKey.value
                if (apiKey.isBlank()) {
                    Log.w(TAG, "Gemini API key not set — cannot use multimodal fallback")
                    return@withContext ""
                }

                // Convert PCM to WAV format
                val wavBytes = pcmToWav(pcmBytes, sampleRate = 44100, channels = 1, bitsPerSample = 16)
                val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

                Log.d(TAG, "Sending ${wavBytes.size} bytes WAV (${pcmBytes.size} PCM) to Gemini for transcription")

                // Build the Gemini multimodal request using JSONObject for safe escaping
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

                // Make HTTP request to Gemini API
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey")
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
                    Log.e(TAG, "Gemini API error $responseCode: ${errorBody.take(500)}")
                    return@withContext ""
                }

                val responseBody = connection.inputStream.bufferedReader().readText()
                connection.disconnect()

                // Parse the response to extract transcribed text
                // Response format: {"candidates":[{"content":{"parts":[{"text":"..."}]}}]}
                val textRegex = Regex(""""text"\s*:\s*"((?:[^"\\]|\\.)*)"""")
                val match = textRegex.find(responseBody)
                val transcribedText = match?.groupValues?.get(1)
                    ?.replace("\\n", "\n")
                    ?.replace("\\\"", "\"")
                    ?.replace("\\\\", "\\")
                    ?.trim()
                    ?: ""

                if (transcribedText.isNotBlank()) {
                    Log.i(TAG, "Gemini multimodal transcription: \"$transcribedText\"")
                } else {
                    Log.w(TAG, "Gemini multimodal returned empty transcription")
                }

                transcribedText
            } catch (e: Exception) {
                Log.e(TAG, "Gemini multimodal fallback failed: ${e.message}")
                ""
            }
        }
    }

    /**
     * Convert raw PCM audio data to WAV format by prepending a WAV header.
     *
     * @param pcmData Raw PCM samples (16-bit signed little-endian)
     * @param sampleRate Sample rate in Hz
     * @param channels Number of channels (1=mono, 2=stereo)
     * @param bitsPerSample Bits per sample (16)
     * @return WAV-formatted byte array
     */
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

        // RIFF header
        wav[0] = 'R'.code.toByte()
        wav[1] = 'I'.code.toByte()
        wav[2] = 'F'.code.toByte()
        wav[3] = 'F'.code.toByte()
        writeInt32LE(wav, 4, 36 + dataSize)          // ChunkSize
        wav[8] = 'W'.code.toByte()
        wav[9] = 'A'.code.toByte()
        wav[10] = 'V'.code.toByte()
        wav[11] = 'E'.code.toByte()

        // fmt sub-chunk
        wav[12] = 'f'.code.toByte()
        wav[13] = 'm'.code.toByte()
        wav[14] = 't'.code.toByte()
        wav[15] = ' '.code.toByte()
        writeInt32LE(wav, 16, 16)                     // Subchunk1Size (PCM = 16)
        writeInt16LE(wav, 20, 1)                        // AudioFormat (1 = PCM)
        writeInt16LE(wav, 22, channels)                 // NumChannels
        writeInt32LE(wav, 24, sampleRate)               // SampleRate
        writeInt32LE(wav, 28, byteRate)                 // ByteRate
        writeInt16LE(wav, 32, blockAlign)               // BlockAlign
        writeInt16LE(wav, 34, bitsPerSample)            // BitsPerSample

        // data sub-chunk
        wav[36] = 'd'.code.toByte()
        wav[37] = 'a'.code.toByte()
        wav[38] = 't'.code.toByte()
        wav[39] = 'a'.code.toByte()
        writeInt32LE(wav, 40, dataSize)               // Subchunk2Size

        // Copy PCM data
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

    // Store application context for command processing
    private var applicationContext: Context? = null

    fun setApplicationContext(context: Context) {
        applicationContext = context.applicationContext
    }

    private fun getApplicationContext(): Context? = applicationContext

    // ═══════════════════════════════════════════════════════════════════════
    // BACKGROUND WAKE WORD MONITOR
    //
    // When the app is in the foreground and the user has enabled wake word,
    // this lightweight monitor continuously listens for "Jarvis" and
    // automatically triggers VAD listening when detected.
    // ═══════════════════════════════════════════════════════════════════════

    @SuppressLint("MissingPermission")
    fun startWakeWordMonitor(context: Context) {
        if (isWakeWordMonitoring || !_isWakeWordEnabled.value) return
        if (_isListening.value) return  // Don't start if already actively listening

        isWakeWordMonitoring = true
        Log.i(TAG, "Starting background wake word monitor")

        wakeWordEngine = AudioEngine(
            context = context,
            onAmplitudeUpdate = { amp ->
                // Only update amplitude if not actively listening
                if (!_isListening.value) {
                    _audioAmplitude.value = amp * 0.3f  // Subtle visual feedback
                }
            },
            onWakeWordDetected = {
                Log.i(TAG, "Wake word detected in background — triggering listening mode")
                viewModelScope.launch(Dispatchers.Main) {
                    stopWakeWordMonitor()
                    startListening(context)
                }
            },
            onCommandReady = {
                // Should not happen in wake word monitor mode, but handle gracefully
                Log.w(TAG, "Unexpected command ready in wake word monitor mode")
            }
        )

        try {
            wakeWordEngine?.startListening()
            Log.i(TAG, "Wake word monitor active — listening for 'Jarvis'")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start wake word monitor: ${e.message}")
            isWakeWordMonitoring = false
        }
    }

    fun stopWakeWordMonitor() {
        if (!isWakeWordMonitoring) return
        isWakeWordMonitoring = false
        wakeWordEngine?.stopListening()
        wakeWordEngine = null
        Log.i(TAG, "Wake word monitor stopped")
    }

    private fun restartWakeWordMonitorIfNeeded() {
        if (_isWakeWordEnabled.value && applicationContext != null) {
            startWakeWordMonitor(applicationContext!!)
        }
    }

    // ── Query processing ───────────────────────────────────────────────────────

    fun processQuery(query: String, context: Context) {
        // Allow text queries even when mic is on
        if (_brainState.value == BrainState.THINKING || _brainState.value == BrainState.SPEAKING) return

        viewModelScope.launch(Dispatchers.Main) {
            try {
                addUserMessage(query)
                _brainState.value = BrainState.THINKING
                _isTyping.value   = true

                // Stop listening while processing to avoid feedback loops
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
                Log.e(TAG, "Query pipeline error", e)
                _brainState.value     = BrainState.ERROR
                _isTyping.value       = false
                _audioAmplitude.value = 0f
                addAssistantMessage("Error: ${e.message?.take(200) ?: "Unknown"}", "stressed")
            } finally {
                _brainState.value     = if (_isListening.value) BrainState.LISTENING else BrainState.IDLE
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

            val parsed  = parseErrorResponse(rawResponse)
            val isError = parsed != rawResponse || rawResponse.startsWith("[ERROR]")

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
            addAssistantMessage("Processing error, Sir.", "stressed")
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
                "Sir, the requested model is unavailable."

            lower.contains("network") || (lower.contains("connect") && lower.contains("refused")) ->
                "Sir, no internet connection. Check your network."

            raw.startsWith("[ERROR]") ->
                raw.removePrefix("[ERROR]").trim().ifBlank { "An error occurred, Sir." }

            lower.contains("error") && lower.contains("{") ->
                Regex(""""message"\s*:\s*"([^"]+)"""").find(raw)?.groupValues?.get(1)
                    ?: "An error occurred, Sir."

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

    /**
     * CRITICAL FIX: toggleVoiceMode now ACTUALLY starts/stops the microphone.
     *
     * Previously, this just flipped a UI flag (_isVoiceMode) without
     * starting the AudioEngine. The mic button in the Chat screen was
     * completely dead. Now it properly triggers audio recording.
     */
    fun toggleVoiceMode(context: Context) {
        if (_isListening.value) {
            stopListening()
            _isVoiceMode.value = false
        } else {
            startListening(context)
            _isVoiceMode.value = true
        }
    }

    /** Legacy compat — no context version */
    fun toggleVoiceMode() {
        _isVoiceMode.value = !_isVoiceMode.value
    }

    fun toggleDevice(deviceId: String, newState: Boolean) {
        _devices.value = _devices.value.map { d ->
            if (d.id == deviceId) d.copy(isOn = newState) else d
        }
    }

    fun refreshDevices() { viewModelScope.launch(Dispatchers.IO) { } }

    fun updateAmplitude(amplitude: Float) {
        _audioAmplitude.value = amplitude.coerceIn(0f, 1f)
    }

    /**
     * Request Shizuku permission with an Activity context.
     * This is the preferred method — Shizuku needs an Activity to show
     * the permission dialog to the user.
     */
    fun requestShizukuPermission(activity: android.app.Activity, requestCode: Int = 0) {
        if (ShizukuManager.isReady() && !ShizukuManager.hasPermission()) {
            ShizukuManager.requestPermission(activity, requestCode)
        }
    }

    /**
     * Request Shizuku permission without an Activity context (fallback).
     * May not show the permission dialog on some devices.
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
        if (enabled && !_isListening.value && applicationContext != null) {
            startWakeWordMonitor(applicationContext!!)
        } else if (!enabled) {
            stopWakeWordMonitor()
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

    private fun playMp3Audio(mp3Bytes: ByteArray, context: Context) {
        val tmp = File(context.cacheDir, "tts_${System.currentTimeMillis()}.mp3")
        try {
            FileOutputStream(tmp).use { it.write(mp3Bytes) }
            tmp.deleteOnExit() // Safety net in case normal cleanup is missed

            if (exoPlayer == null) {
                exoPlayer = ExoPlayer.Builder(context).build().apply {
                    addListener(object : Player.Listener {
                        override fun onPlayerError(e: PlaybackException) {
                            Log.e(TAG, "ExoPlayer error: ${e.message}")
                            _brainState.value = BrainState.ERROR
                            tmp.delete()
                        }
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED) {
                                if (!_isListening.value) {
                                    _audioAmplitude.value = 0f
                                }
                                _brainState.value = if (_isListening.value) BrainState.LISTENING else BrainState.IDLE
                                // Clean up temp file
                                tmp.delete()
                            }
                        }
                    })
                }
            }
            exoPlayer?.apply {
                stop()   // Stop current playback before setting new media item
                setMediaItem(MediaItem.fromUri(Uri.fromFile(tmp)))
                prepare()
                playWhenReady = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "playMp3Audio: ${e.message}")
            tmp.delete() // Delete temp file on error too
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopAudioEngine()
        stopWakeWordMonitor()
        destroySpeechRecognizer()
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
