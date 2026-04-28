package com.jarvis.assistant.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaPlayer
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.sync.Mutex
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
 * CRITICAL FIXES (v8):
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
 * 4. REMOVED SpeechRecognizer (BUG #2 FIX):
 *    SpeechRecognizer and AudioEngine were both trying to capture
 *    audio simultaneously, causing microphone contention crashes on
 *    most Android devices. SpeechRecognizer has been entirely removed.
 *    Transcription now relies on the Gemini multimodal fallback,
 *    which sends actual WAV audio data for server-side transcription.
 *    This also fixes BUG #3 (stopSpeechRecognizer() reading results
 *    too early — SpeechRecognizer.stopListening() is async, and
 *    onResults() may arrive after stopSpeechRecognizer() returns).
 *
 * 5. BACKGROUND WAKE WORD MONITORING:
 *    When the app is in the foreground and wake word is enabled,
 *    a lightweight AudioEngine monitors for the wake word and
 *    automatically triggers VAD listening when detected.
 *
 * 6. ExoPlayer REPLACED with android.media.MediaPlayer:
 *    ExoPlayer was overkill for simple TTS MP3 playback and caused
 *    temp file leaks (BUG #4). MediaPlayer is used instead, with
 *    proper temp file cleanup and synthetic amplitude pulsing for
 *    the hologram orb animation during TTS playback.
 *
 * 7. ElevenLabs API KEY VALIDATION (BUG #10 FIX):
 *    Added a check for empty ElevenLabs API key before calling
 *    synthesizeSpeech, preventing unnecessary network calls and
 *    confusing errors.
 *
 * 8. DEPRECATED toggleVoiceMode() no-context version (BUG #13 FIX):
 *    The legacy no-context version of toggleVoiceMode() is now
 *    marked @Deprecated — it cannot actually start/stop the mic
 *    without a Context.
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
     * When JARVIS speaks (TTS), this pulses with a synthetic value for visual feedback.
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

    // ── MediaPlayer for TTS (replaces ExoPlayer) ───────────────────────────────
    /** MediaPlayer instance for TTS audio playback. Managed lifecycle-aware. */
    private var mediaPlayer: MediaPlayer? = null

    /** Path to the current TTS temp file, tracked for cleanup. */
    @Volatile
    private var currentTtsTempPath: String? = null

    /** Job that pulses _audioAmplitude while MediaPlayer is playing. */
    private var amplitudePulseJob: Job? = null

    /** Mutex for synchronizing MediaPlayer operations. */
    private val mediaPlayerMutex = Mutex()

    private var nextMessageId = 0L


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
    // MIC TOGGLE — FULLY WIRED WITH VAD (SpeechRecognizer REMOVED)
    //
    // BUG #2 FIX: SpeechRecognizer was removed because it and AudioEngine
    // both tried to capture audio simultaneously, causing microphone
    // contention crashes. Now only AudioEngine captures audio.
    // Transcription is done via Gemini multimodal fallback (sends actual
    // WAV audio for server-side transcription).
    //
    // Flow:
    // 1. User presses mic → startListening()
    // 2. AudioEngine starts recording with VAD
    // 3. VAD detects speech → begins accumulating command frames
    // 4. VAD detects 1.5s silence after speech → auto-flushes command
    // 5. Command PCM bytes delivered to onCommandReady
    // 6. onCommandReady uses Gemini multimodal fallback for transcription
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

        // NOTE: SpeechRecognizer was REMOVED (BUG #2 fix).
        // Only AudioEngine captures audio. Transcription is done via
        // Gemini multimodal fallback in handleCommandReady().

        // Auto-start VAD command recording — the mic button means "I'm talking now"
        audioEngine?.startCommandRecording()
    }

    fun stopListening() {
        if (!_isListening.value) return

        // If we're in the middle of recording, flush the command first
        audioEngine?.stopCommandRecording()
        stopAudioEngine()

        // NOTE: SpeechRecognizer was REMOVED (BUG #2/3 fix).
        // No more stopSpeechRecognizer() call needed.

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
                Log.i(TAG, "Command ready: ${pcmBytes.size} bytes — transcribing via Gemini multimodal")
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
    // COMMAND READY HANDLER — GEMINI MULTIMODAL TRANSCRIPTION PIPELINE (v8)
    //
    // BUG #2/3 FIX: SpeechRecognizer has been REMOVED entirely.
    // Both SpeechRecognizer and AudioEngine were trying to capture audio
    // simultaneously, causing microphone contention crashes. Additionally,
    // stopSpeechRecognizer() was reading results too early (BUG #3) since
    // SpeechRecognizer.stopListening() is async.
    //
    // Now the transcription pipeline is:
    //
    //   1. Get emotion metadata from audio analysis (NOT for transcription,
    //      only for detecting the user's emotional tone).
    //
    //   2. Transcribe via Gemini's multimodal API by sending the actual
    //      audio as WAV base64 for server-side transcription.
    //
    //   3. If transcription fails, ask the user to repeat.
    // ═══════════════════════════════════════════════════════════════════════

    private fun handleCommandReady(pcmBytes: ByteArray) {
        viewModelScope.launch(Dispatchers.Main) {
            _brainState.value = BrainState.THINKING

            // ── Step 1: Get emotion metadata from audio analysis ──────────
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

            // ── Step 2: Transcribe via Gemini multimodal API ──────────────
            // SpeechRecognizer was removed (BUG #2/3 fix). We now rely
            // entirely on Gemini's multimodal API for transcription.
            var transcription = transcribeViaGeminiFallback(pcmBytes)

            // ── Step 3: Process the transcription ──────────────────────────
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
    // We send the raw PCM audio (converted to WAV) to Gemini's multimodal
    // API for server-side transcription.
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
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
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

    // ═══════════════════════════════════════════════════════════════════════
    // TTS PIPELINE — ElevenLabs via RustBridge → Base64 → MP3 → MediaPlayer
    //
    // BUG #10 FIX: Validate ElevenLabs API key before calling synthesizeSpeech.
    // BUG #4 FIX: Properly clean up temp MP3 files when MediaPlayer is stopped
    //             before playback ends (no more relying on STATE_ENDED callback).
    // MAJOR FIX: Replaced ExoPlayer with android.media.MediaPlayer for TTS
    //            playback. MediaPlayer is simpler, lighter, and doesn't leak
    //            temp files the way ExoPlayer did.
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Synthesize speech via ElevenLabs and play the result through MediaPlayer.
     *
     * Pipeline: text → RustBridge.synthesizeSpeech() → Base64 MP3 → decode →
     * temp .mp3 file → MediaPlayer → amplitude pulse coroutine.
     *
     * BUG #10 FIX: Checks for empty ElevenLabs API key before calling
     * synthesizeSpeech, preventing unnecessary network calls.
     */
    private suspend fun trySynthesizeAndPlay(text: String, context: Context) {
        try {
            // BUG #10 FIX: Validate ElevenLabs API key before TTS call
            val elevenLabsKey = _elevenLabsApiKey.value
            if (elevenLabsKey.isBlank()) {
                Log.w(TAG, "ElevenLabs API key is empty — skipping TTS synthesis")
                _brainState.value = if (_isListening.value) BrainState.LISTENING else BrainState.IDLE
                _audioAmplitude.value = if (_isListening.value) _audioAmplitude.value else 0f
                return
            }

            val (stability, similarityBoost) = computeTtsParams()
            Log.d(TAG, "TTS params: emotion=${_emotion.value} stability=$stability similarityBoost=$similarityBoost")

            val base64 = withContext(Dispatchers.IO) {
                try {
                    RustBridge.synthesizeSpeech(
                        text, _ttsVoiceId.value,
                        stability = stability, similarityBoost = similarityBoost
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "RustBridge.synthesizeSpeech failed: ${e.message}")
                    null
                }
            }

            if (base64.isNullOrBlank()) {
                Log.w(TAG, "TTS synthesis returned empty result — skipping playback")
                _brainState.value = if (_isListening.value) BrainState.LISTENING else BrainState.IDLE
                _audioAmplitude.value = if (_isListening.value) _audioAmplitude.value else 0f
                return
            }

            val mp3 = withContext(Dispatchers.IO) {
                try { RustBridge.decodeBase64Audio(base64) } catch (e: Exception) {
                    Log.e(TAG, "Base64 decode of TTS audio failed: ${e.message}")
                    null
                }
            }

            if (mp3 == null || mp3.isEmpty()) {
                Log.w(TAG, "TTS decoded MP3 is empty — skipping playback")
                _brainState.value = if (_isListening.value) BrainState.LISTENING else BrainState.IDLE
                _audioAmplitude.value = if (_isListening.value) _audioAmplitude.value else 0f
                return
            }

            withContext(Dispatchers.Main) { playMp3Audio(mp3, context) }

            // Wait for MediaPlayer playback to finish (with timeout)
            withTimeoutOrNull(TTS_TIMEOUT_MS) {
                while (isActive && mediaPlayer != null) {
                    val isPlaying = try { mediaPlayer?.isPlaying == true } catch (_: Exception) { false }
                    if (!isPlaying) break
                    delay(100)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "TTS failed: ${e.message}")
            _brainState.value = if (_isListening.value) BrainState.LISTENING else BrainState.IDLE
            _audioAmplitude.value = if (_isListening.value) _audioAmplitude.value else 0f
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

    /**
     * Legacy compat — no context version.
     *
     * BUG #13 FIX: Marked @Deprecated because this version cannot actually
     * start/stop the microphone (requires a Context). Use the Context
     * version [toggleVoiceMode] instead.
     */
    @Deprecated(
        message = "Use toggleVoiceMode(context: Context) instead — this version cannot start/stop the mic",
        replaceWith = ReplaceWith("toggleVoiceMode(context)")
    )
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

    // ═══════════════════════════════════════════════════════════════════════
    // MediaPlayer TTS PLAYBACK (replaces ExoPlayer)
    //
    // MAJOR FIX: Replaced ExoPlayer with android.media.MediaPlayer for
    // TTS playback. ExoPlayer was overkill for simple MP3 playback and
    // caused temp file leaks (BUG #4) because STATE_ENDED callback
    // doesn't fire when stop() is called before playback ends.
    //
    // BUG #4 FIX: Before creating a new temp file, we delete the
    // previous one. We also track the current temp file path so it
    // can be cleaned up in all exit paths.
    //
    // MediaPlayer lifecycle:
    //   playMp3Audio() → delete previous temp → write new temp →
    //   release old player → create new player → setDataSource →
    //   prepare → start → amplitude pulse coroutine →
    //   onCompletion → delete temp → reset brain state
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Play MP3 audio bytes through MediaPlayer.
     *
     * BUG #4 FIX: Deletes the previous temp file before creating a new one,
     * ensuring no temp file leaks even when stop() is called before
     * playback ends.
     *
     * @param mp3Bytes Decoded MP3 audio data
     * @param context Android context for cache directory
     */
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun playMp3Audio(mp3Bytes: ByteArray, context: Context) {
        // ── Delete previous temp file (BUG #4 FIX) ─────────────────────
        currentTtsTempPath?.let { prevPath ->
            try {
                val prevFile = File(prevPath)
                if (prevFile.exists()) {
                    prevFile.delete()
                    Log.d(TAG, "Deleted previous TTS temp file: $prevPath")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete previous TTS temp file: ${e.message}")
            }
        }

        // ── Cancel previous amplitude pulse job ────────────────────────
        amplitudePulseJob?.cancel()
        amplitudePulseJob = null

        // ── Release previous MediaPlayer ───────────────────────────────
        releaseMediaPlayer()

        val tmp = File(context.cacheDir, "tts_${System.currentTimeMillis()}.mp3")
        currentTtsTempPath = tmp.absolutePath

        try {
            FileOutputStream(tmp).use { it.write(mp3Bytes) }
            tmp.deleteOnExit() // Safety net in case normal cleanup is missed

            val player = MediaPlayer().apply {
                setDataSource(tmp.absolutePath)
                setOnCompletionListener {
                    Log.d(TAG, "MediaPlayer: playback completed")
                    if (!_isListening.value) {
                        _audioAmplitude.value = 0f
                    }
                    _brainState.value = if (_isListening.value) BrainState.LISTENING else BrainState.IDLE
                    // Clean up temp file
                    tmp.delete()
                    currentTtsTempPath = null
                    // Cancel amplitude pulse
                    amplitudePulseJob?.cancel()
                    amplitudePulseJob = null
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    _brainState.value = if (_isListening.value) BrainState.LISTENING else BrainState.ERROR
                    _audioAmplitude.value = 0f
                    tmp.delete()
                    currentTtsTempPath = null
                    amplitudePulseJob?.cancel()
                    amplitudePulseJob = null
                    true  // Error was handled
                }
            }

            mediaPlayer = player
            player.prepare()
            player.start()

            // ── Start amplitude pulse coroutine ─────────────────────────
            // While MediaPlayer is playing, pulse _audioAmplitude with a
            // synthetic value so the hologram orb animates during TTS.
            amplitudePulseJob = viewModelScope.launch(Dispatchers.Default) {
                var phase = 0f
                while (isActive) {
                    val isPlaying = try { mediaPlayer?.isPlaying == true } catch (_: Exception) { false }
                    if (!isPlaying) break

                    // Generate a smooth pulsing amplitude between 0.2f and 0.6f
                    // using a sine wave for natural-looking orb animation.
                    phase += 0.15f  // Speed of the pulse
                    val pulse = 0.4f + 0.2f * kotlin.math.sin(phase.toDouble()).toFloat()
                    _audioAmplitude.value = pulse.coerceIn(0.2f, 0.6f)
                    delay(45)  // ~22 Hz update rate (matches AudioEngine)
                }
                // Reset amplitude when done (if not listening)
                if (!_isListening.value) {
                    _audioAmplitude.value = 0f
                }
            }

        } catch (e: Exception) {
            Log.w(TAG, "playMp3Audio: ${e.message}")
            tmp.delete()
            currentTtsTempPath = null
            _brainState.value = if (_isListening.value) BrainState.LISTENING else BrainState.IDLE
            _audioAmplitude.value = if (_isListening.value) _audioAmplitude.value else 0f
        }
    }

    /**
     * Safely release the current MediaPlayer instance.
     * Called before creating a new player and in onCleared().
     */
    private fun releaseMediaPlayer() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaPlayer release error: ${e.message}")
        }
        mediaPlayer = null
    }

    override fun onCleared() {
        super.onCleared()

        // Cancel amplitude pulse coroutine
        amplitudePulseJob?.cancel()
        amplitudePulseJob = null

        // Release MediaPlayer (replaces ExoPlayer release)
        releaseMediaPlayer()

        // Clean up any remaining temp file
        currentTtsTempPath?.let { path ->
            try {
                val file = File(path)
                if (file.exists()) file.delete()
            } catch (_: Exception) {}
            currentTtsTempPath = null
        }

        // NOTE: No destroySpeechRecognizer() call needed (BUG #2 fix — removed entirely)

        stopAudioEngine()
        stopWakeWordMonitor()
        try { RustBridge.shutdown() } catch (_: Exception) {}
        ShizukuManager.setOnShizukuStateChangedListener {}
    }

    class Factory(private val repo: SettingsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(c: Class<T>): T = JarvisViewModel(repo) as T
    }
}
