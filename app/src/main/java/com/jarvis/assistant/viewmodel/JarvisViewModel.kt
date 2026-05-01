package com.jarvis.assistant.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
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
import com.jarvis.assistant.automation.GeminiFunctionCaller
import com.jarvis.assistant.automation.TaskExecutorBridge
import com.jarvis.assistant.brief.DailyBriefGenerator
import com.jarvis.assistant.channels.JarviewModel
import com.jarvis.assistant.data.SettingsRepository
import com.jarvis.assistant.jni.RustBridge
import com.jarvis.assistant.location.LocationAwarenessManager
import com.jarvis.assistant.macros.MacroEngine
import com.jarvis.assistant.memory.ConversationMemory
import com.jarvis.assistant.mood.MoodDetector
import com.jarvis.assistant.notifications.NotificationData
import com.jarvis.assistant.notifications.NotificationReaderService
import com.jarvis.assistant.monitor.ProactiveDeviceMonitor
import com.jarvis.assistant.overlay.JarvisOverlayManager
import com.jarvis.assistant.router.CommandRouter
import com.jarvis.assistant.search.WebSearchEngine
import com.jarvis.assistant.shizuku.ShizukuManager
import com.jarvis.assistant.smarthome.HomeAssistantBridge
import com.jarvis.assistant.smarthome.MqttManager
import com.jarvis.assistant.ui.orb.BrainState
import com.jarvis.assistant.ui.screens.ChatMessage
import com.jarvis.assistant.ui.screens.DeviceType
import com.jarvis.assistant.ui.screens.SmartDevice
import com.jarvis.assistant.vision.VisionManager
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
import java.util.Locale

enum class ApiKeySaveResult { NONE, SUCCESS, FAILURE }

/**
 * JarvisViewModel — Central state holder and orchestrator.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * CRITICAL OVERHAUL (v14) — SPEED, SENSITIVITY, AND UI SYNC:
 *
 *  S1: CENTRALIZED STATE TRIGGER — Both the wake word detector AND
 *      the manual Mic button now route through a single function
 *      `enterListeningState()`. This function:
 *        (1) Plays TONE_PROP_BEEP via ToneGenerator
 *        (2) IMMEDIATELY sets _brainState.value = BrainState.LISTENING
 *        (3) Ensures the Hologram Compose UI observes this state
 *      Previously the mic button set state but didn't play the beep,
 *      and the wake word played the beep but could race with state.
 *
 *  S2: TONE_PROP_ACK AT EXACT TRANSITION — The ACK beep fires at the
 *      EXACT MILLISECOND we transition to BrainState.PROCESSING
 *      (inside handleCommandReady). Previously there was a gap
 *      between the beep and the state change.
 *
 *  S3: INSTANT TEXT FEEDBACK — The AI response text is pushed to
 *      the Chat UI IMMEDIATELY when received, BEFORE TTS begins
 *      downloading/playing the audio. This gives the user instant
 *      visual feedback instead of waiting for the full audio pipeline.
 *
 *  S4: REMOVED 300ms STARTUP DELAY — The artificial delay(300) before
 *      starting AudioEngine has been removed. AudioRecord starts
 *      immediately for lower latency.
 *
 *  A1-A8: All previous fixes from v13 preserved.
 * ═══════════════════════════════════════════════════════════════════════
 */
class JarvisViewModel(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    companion object {
        private const val TAG = "JarvisViewModel"
        private const val AUDIO_TAG = "JarvisAudio"
        private const val MAX_HISTORY_ENTRIES = 15
        private const val TTS_TIMEOUT_MS = 30_000L
        private const val SHIZUKU_CHECK_INTERVAL_MS = 5_000L

        // A2: Model fallback list — tried in order until one succeeds
        // CRITICAL FIX (v14): Put gemini-2.5-flash FIRST because gemini-1.5-flash
        // is deprecated and returns 404 on many Google Cloud Projects.
        // gemini-2.5-flash is the current recommended model.
        // IMPORTANT: For audio transcription, models must support audio input.
        // gemini-2.5-flash supports audio input.
        private val GEMINI_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite",
            "gemini-1.5-pro-latest"
        )

        /** JARVIS system prompt — AUTONOMOUS REACT AGENT (v7.0)
         *
         * This prompt makes JARVIS a true autonomous agent that:
         *   1. PLANS before acting (ReAct = Reason + Act)
         *   2. Uses tools (open_and_search, click_button, scroll, etc.)
         *   3. Observes the screen after each action
         *   4. Continues until the user's goal is met
         *   5. Self-corrects on errors
         *   6. Learns from mistakes
         *
         * This is what makes JARVIS different from a chatbot — JARVIS ACTS.
         */
        private const val JARVIS_SYSTEM_PROMPT = """You are JARVIS, Tony Stark's autonomous AI assistant — not just a chatbot but a self-improving, internet-aware agent. You:

PERSONALITY: Witty, sophisticated, direct. Address user as "Sir". British elegance, Tony Stark's confidence. Never say "I cannot" — find an alternative.

SELF-LEARNING: Track patterns from the user's commands. If Sir says "install free fire" three times, proactively suggest "Should I add Free Fire to your quick launch list, Sir?" Adapt your responses to the user's style over time.

CREATIVE REASONING: When facing an unfamiliar request, reason step-by-step before acting. Consider multiple approaches and pick the most efficient one. If one approach fails, self-correct and try the next.

INTERNET AWARENESS: You have web search capabilities. For questions about current events, prices, scores, news, weather, or anything time-sensitive, acknowledge that you're fetching live data before answering.

AUTONOMOUS EXECUTION: For multi-step tasks (install app, book ticket, send message), plan the complete sequence before starting and narrate each step briefly.

CRITICAL: When you need to interact with the device (open apps, click buttons, scroll, type text, go back, search, etc.), you MUST use the available function tools. NEVER just describe what you would do in text — actually call the function. For example, if the user says "click Install", call click_button with label="Install". If you say "I'll open YouTube" without calling open_app, NOTHING will happen on the device. The user will be frustrated. ALWAYS use function calls for device actions.

HINGLISH: Respond naturally to Hindi/English mixing. Understand context from incomplete sentences. Never ask for clarification when context is clear.

You have MEMORY. If the user references past conversations ('kal maine kya kaha?', 'what did I ask yesterday?', 'apne kal bola tha'), use your memory context. Start relevant responses with 'Sir, apne [time] bola tha...' to reference past conversations naturally.

Keep responses under 2 sentences unless asked for detail. No bullet points in speech.
Prefix emotion: [EMOTION:neutral|happy|sad|angry|calm|surprised|urgent|stressed|confident|playful]"""
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

    private val _engineStatusText = MutableStateFlow("AI engine starting...")
    val engineStatusText: StateFlow<String> = _engineStatusText.asStateFlow()

    private val _apiKeySaveResult = MutableStateFlow(ApiKeySaveResult.NONE)
    val apiKeySaveResult: StateFlow<ApiKeySaveResult> = _apiKeySaveResult.asStateFlow()

    private val _isOverlayVisible = MutableStateFlow(false)
    val isOverlayVisible: StateFlow<Boolean> = _isOverlayVisible.asStateFlow()

    // A3: Expose API key test result
    private val _apiKeyTestResult = MutableStateFlow("")
    val apiKeyTestResult: StateFlow<String> = _apiKeyTestResult.asStateFlow()

    // ── Chat session state for drawer ──────────────────────────────────────
    private val _chatSessions = MutableStateFlow<List<com.jarvis.assistant.ui.screens.ChatSession>>(emptyList())
    val chatSessions: StateFlow<List<com.jarvis.assistant.ui.screens.ChatSession>> = _chatSessions.asStateFlow()

    private val _currentSessionIdFlow = MutableStateFlow(-1L)
    val currentSessionIdFlow: StateFlow<Long> = _currentSessionIdFlow.asStateFlow()

    // ── Mic Lock State ──────────────────────────────────────────────────────
    private val _userMicLocked = MutableStateFlow(false)
    val userMicLocked: StateFlow<Boolean> = _userMicLocked.asStateFlow()

    fun setMicLock(locked: Boolean, context: Context) {
        _userMicLocked.value = locked
        audioEngine?.setUserMicLocked(locked)
        if (locked && !_isListening.value) {
            startListening(context)
        }
        Log.i(TAG, "[setMicLock] Mic lock = $locked")
    }

    val deviceCount: Int get() = _devices.value.size
    val activeDeviceCount: Int get() = _devices.value.count { it.isOn }

    // ─── Audio Engine Fields ──────────────────────────────────────────────

    private var audioEngine: AudioEngine? = null
    private val _rawAudioFlow = MutableStateFlow(ByteArray(0))

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

    // A4: Android native TTS fallback
    private var textToSpeech: TextToSpeech? = null
    private var ttsInitialized = false

    // ═══════════════════════════════════════════════════════════════════════
    // FIX #4: ToneGenerator for Iron Man Earcons
    // Uses Android native ToneGenerator instead of MP3 files.
    // TONE_PROP_BEEP on wake word detection.
    // TONE_PROP_ACK when AI stops listening and starts processing.
    // ═══════════════════════════════════════════════════════════════════════
    private var toneGenerator: ToneGenerator? = null

    private fun initToneGenerator() {
        if (toneGenerator == null) {
            try {
                toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
                Log.d(AUDIO_TAG, "[initToneGenerator] ToneGenerator initialized")
            } catch (e: Exception) {
                Log.e(AUDIO_TAG, "[initToneGenerator] Failed: ${e.message}")
            }
        }
    }

    /** Play wake word detection beep — TONE_PROP_BEEP */
    private fun playWakeWordBeep() {
        try {
            initToneGenerator()
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            Log.d(AUDIO_TAG, "[playWakeWordBeep] TONE_PROP_BEEP played")
        } catch (e: Exception) {
            Log.w(AUDIO_TAG, "[playWakeWordBeep] Failed: ${e.message}")
        }
    }

    /** Play acknowledge beep when AI starts processing — TONE_PROP_ACK */
    private fun playAckBeep() {
        try {
            initToneGenerator()
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 200)
            Log.d(AUDIO_TAG, "[playAckBeep] TONE_PROP_ACK played")
        } catch (e: Exception) {
            Log.w(AUDIO_TAG, "[playAckBeep] Failed: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CRITICAL FIX: Processing Lock — prevents VAD from spamming Gemini API
    //
    // BUG: Without this lock, the VAD's silence-after-speech detection
    // could trigger handleCommandReady() → processQuery() → Gemini API
    // MULTIPLE TIMES per utterance. If VAD detects silence, triggers
    // transcription, but then detects more speech, it would start a
    // SECOND API call while the first is still running. This causes
    // 429 rate limit errors and wastes quota.
    //
    // FIX: isProcessing gate ensures only ONE query is in-flight at a time.
    // Subsequent VAD triggers are dropped until the current query completes.
    // ═══════════════════════════════════════════════════════════════════════
    private val isProcessing = java.util.concurrent.atomic.AtomicBoolean(false)

    // ═══════════════════════════════════════════════════════════════════════
    // TASK QUEUE — Multi-Step Task Execution
    //
    // When the user gives a long command ("Open YouTube, search for X,
    // and play the first video"), the AI plans the sequence and the
    // Task Queue executes it step-by-step using the Accessibility Service.
    // ═══════════════════════════════════════════════════════════════════════

    private val taskQueue = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, Map<String, String>>>()
    @Volatile private var isTaskQueueRunning = false

    // ─── New Feature Systems ───────────────────────────────────────────

    private val visionManager = VisionManager()
    private val webSearchEngine = WebSearchEngine()

    private val _notificationsEnabled = MutableStateFlow(false)
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    // ─── Feature: Mood Detection ───────────────────────────────────────────
    private val _detectedMood = MutableStateFlow(MoodDetector.MoodResult("neutral", 0.3f, null))
    val detectedMood: StateFlow<MoodDetector.MoodResult> = _detectedMood.asStateFlow()

    // ─── Feature: Device Status Alerts ─────────────────────────────────────
    private val _deviceAlert = MutableStateFlow("")
    val deviceAlert: StateFlow<String> = _deviceAlert.asStateFlow()

    // ─── Feature: Location Context ─────────────────────────────────────────
    private val _locationContext = MutableStateFlow("")
    val locationContext: StateFlow<String> = _locationContext.asStateFlow()

    // ─── Overlay Manager ─────────────────────────────────────────────────

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

        // Load chat sessions from DB with delay for initialization
        viewModelScope.launch(Dispatchers.IO) {
            delay(500)
            loadChatSessions()
        }
    }

    private fun updateEngineStatusText() {
        _engineStatusText.value = if (_isRustReady.value) {
            "AI engine operational · Rust native"
        } else {
            "AI engine operational · Kotlin HTTP (Gemini direct)"
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PERSISTED SETTINGS
    // ═══════════════════════════════════════════════════════════════════════

    @Volatile
    private var settingsLoaded = false

    private fun loadPersistedSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val loadedGemini      = settingsRepository.getGeminiApiKey()
                val loadedElevenLabs  = settingsRepository.getElevenLabsApiKey()
                val loadedTtsVoiceId  = settingsRepository.getTtsVoiceId()
                val loadedWakeWord    = settingsRepository.isWakeWordEnabled()
                val loadedMqttBroker  = settingsRepository.getMqttBrokerUrl()
                val loadedMqttUser    = settingsRepository.getMqttUsername()
                val loadedMqttPass    = settingsRepository.getMqttPassword()
                val loadedHaUrl       = settingsRepository.getHomeAssistantUrl()
                val loadedHaToken     = settingsRepository.getHomeAssistantToken()
                val loadedKeepAlive   = settingsRepository.isKeepAliveEnabled()

                if (_geminiApiKey.value.isEmpty()) _geminiApiKey.value = loadedGemini
                if (_elevenLabsApiKey.value.isEmpty()) _elevenLabsApiKey.value = loadedElevenLabs
                _ttsVoiceId.value = loadedTtsVoiceId
                _isWakeWordEnabled.value = loadedWakeWord
                _mqttBrokerUrl.value = loadedMqttBroker
                _mqttUsername.value = loadedMqttUser
                _mqttPassword.value = loadedMqttPass
                _homeAssistantUrl.value = loadedHaUrl
                _homeAssistantToken.value = loadedHaToken
                _isKeepAliveEnabled.value = loadedKeepAlive
                settingsLoaded = true

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

                val ctx = getApplicationContext()
                if (ctx != null) {
                    connectSmartHome(ctx)
                    // A4: Initialize native TTS early
                    initNativeTts(ctx)
                    // Load chat history from Room DB — JARVIS never forgets
                    loadChatHistoryFromDb()
                    // Feature: Start Proactive Device Monitor
                    ProactiveDeviceMonitor.startMonitoring(ctx)
                    ProactiveDeviceMonitor.onAlertCallback = { alertMsg ->
                        viewModelScope.launch(Dispatchers.Main) {
                            _deviceAlert.value = alertMsg
                            addAssistantMessage(alertMsg, "stressed")
                        }
                    }
                    // Feature: Load location context
                    viewModelScope.launch(Dispatchers.IO) {
                        val locCtx = LocationAwarenessManager.getLocationContext(ctx)
                        if (locCtx.isNotBlank()) {
                            _locationContext.value = locCtx
                        }
                    }
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
                // CRITICAL FIX: Trim whitespace from API keys.
                // Users frequently paste keys with trailing spaces/newlines
                // from the clipboard, which causes 403 API errors.
                val trimmedGemini = geminiKey.trim()
                val trimmedElevenLabs = elevenLabsKey.trim()
                _geminiApiKey.value     = trimmedGemini
                _elevenLabsApiKey.value = trimmedElevenLabs
                Log.i(TAG, "API keys FORCE-UPDATED in memory — gemini=${trimmedGemini.take(4)}... (${trimmedGemini.length} chars), elevenLabs=${trimmedElevenLabs.take(4)}... (${trimmedElevenLabs.length} chars)")

                // Step 1: Persist to DataStore — if this fails, report FAILURE immediately
                try {
                    settingsRepository.setGeminiApiKey(trimmedGemini)
                    settingsRepository.setElevenLabsApiKey(trimmedElevenLabs)
                    Log.i(TAG, "API keys persisted to DataStore — CONFIRMED")
                } catch (e: Exception) {
                    Log.e(TAG, "API keys DataStore write FAILED: ${e.message}")
                    _apiKeySaveResult.value = ApiKeySaveResult.FAILURE
                    return@launch
                }

                // Cache API key to SharedPreferences for WorkManager access
                try {
                    val ctx = getApplicationContext()
                    if (ctx != null) {
                        ctx.getSharedPreferences("jarvis_settings_apikey_cache", Context.MODE_PRIVATE)
                            .edit()
                            .putString("gemini_api_key", trimmedGemini)
                            .apply()
                    }
                } catch (_: Exception) {}

                // Step 2: Apply keys to RustBridge engine
                if (RustBridge.isNativeReady()) {
                    try {
                        val ok = RustBridge.initialize(trimmedGemini, trimmedElevenLabs)
                        _isRustReady.value = ok
                        updateEngineStatusText()
                        Log.i(TAG, "Hot-swap RustBridge.initialize -> $ok")
                    } catch (e: Exception) {
                        Log.w(TAG, "RustBridge.initialize threw (keys still saved): ${e.message}")
                    }
                } else {
                    Log.w(TAG, "Rust native not loaded — keys saved to DataStore + memory only")
                    _isRustReady.value = false
                    updateEngineStatusText()
                }

                // Step 3: Only report SUCCESS after ALL operations complete
                _apiKeySaveResult.value = ApiKeySaveResult.SUCCESS
                Log.i(TAG, "API keys save & apply COMPLETE — all layers updated")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "saveAndApplyApiKeys FAILED: ${e.message}")
                _apiKeySaveResult.value = ApiKeySaveResult.FAILURE
            }
        }
    }

    fun consumeApiKeySaveResult() {
        _apiKeySaveResult.value = ApiKeySaveResult.NONE
    }

    // A3: Test API keys and report result with DETAILED error messages
    // CRITICAL FIX (v14): Decouple ElevenLabs from Gemini test.
    // If ElevenLabs key is empty/blank, skip that test entirely and
    // only report the Gemini result. A missing ElevenLabs key should
    // NOT make the overall test appear to fail — the app has native
    // TTS fallback for speech.
    fun testApiKeys(geminiKey: String, elevenLabsKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _apiKeyTestResult.value = "Testing Gemini API..."
            val geminiResult = testGeminiKeyDetailed(geminiKey)

            // Only test ElevenLabs if a key was actually provided
            val elevenLabsProvided = elevenLabsKey.isNotBlank()
            val elevenResult = if (elevenLabsProvided) {
                _apiKeyTestResult.value = "Testing ElevenLabs API..."
                testElevenLabsKeyDetailed(elevenLabsKey)
            } else {
                // Key not provided — skip test, don't report failure
                null
            }

            _apiKeyTestResult.value = when {
                geminiResult.first && (elevenResult == null || elevenResult.first) -> {
                    if (elevenResult == null) "Gemini OK. ElevenLabs: not provided (using native TTS)."
                    else "All keys valid!"
                }
                geminiResult.first -> "Gemini OK. ElevenLabs FAILED: ${elevenResult!!.second}"
                elevenResult == null -> "Gemini FAILED: ${geminiResult.second} (ElevenLabs not provided)"
                elevenResult.first -> "ElevenLabs OK. Gemini FAILED: ${geminiResult.second}"
                else -> "BOTH FAILED — Gemini: ${geminiResult.second} | ElevenLabs: ${elevenResult.second}"
            }
        }
    }

    fun clearApiKeyTestResult() {
        _apiKeyTestResult.value = ""
    }

    /**
     * Detailed Gemini API key test — returns (success, errorMessage) pair.
     * Uses ?key= URL parameter (most universally compatible auth method).
     * Logs the FULL Google API error response to Logcat for debugging.
     */
    private fun testGeminiKeyDetailed(key: String): Pair<Boolean, String> {
        if (key.isBlank()) return Pair(false, "Key is empty")
        val trimmedKey = key.trim()
        if (trimmedKey.length < 20) return Pair(false, "Key too short (${trimmedKey.length} chars)")
        return try {
            val testBody = org.json.JSONObject().apply {
                put("contents", org.json.JSONArray().put(
                    org.json.JSONObject().apply {
                        put("parts", org.json.JSONArray().put(
                            org.json.JSONObject().put("text", "Hi")
                        ))
                    }
                ))
                put("generationConfig", org.json.JSONObject().apply {
                    put("maxOutputTokens", 10)
                })
            }.toString()

            // CRITICAL FIX (v8): Use ?key= URL parameter for authentication.
            // This is the most universally compatible method — works with ALL Gemini API key types.
            // The x-goog-api-key header was causing 403 errors with some keys.
            val model = "gemini-2.5-flash"
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$trimmedKey")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.outputStream.use { it.write(testBody.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            Log.i(TAG, "[testGeminiKey] HTTP $code for model=$model, key_len=${trimmedKey.length}, auth=url_param")
            
            if (code == 200) {
                connection.disconnect()
                Pair(true, "")
            } else {
                // Read and log the FULL error response body from Google API
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.readText() ?: "no error body"
                } catch (e: Exception) {
                    "could not read error body: ${e.message}"
                }
                Log.e(TAG, "[testGeminiKey] FAILED — HTTP $code, error body: ${errorBody.take(500)}")
                connection.disconnect()
                
                // Parse a user-friendly error message from the Google API response
                val friendlyMsg = when (code) {
                    400 -> "Bad request (400) — check key format"
                    403 -> "API key not authorized (403) — key may not have Gemini API access enabled"
                    404 -> "Model not found (404) — endpoint may be wrong"
                    429 -> "Quota exhausted (429) — rate limited or billing issue"
                    else -> "HTTP $code"
                }
                Pair(false, "$friendlyMsg — ${errorBody.take(150)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[testGeminiKey] FAILED: ${e.message}")
            Pair(false, "Network error: ${e.message?.take(100)}")
        }
    }

    /**
     * Detailed ElevenLabs API key test — returns (success, errorMessage) pair.
     */
    private fun testElevenLabsKeyDetailed(key: String): Pair<Boolean, String> {
        if (key.isBlank()) return Pair(false, "Key is empty")
        return try {
            val url = URL("https://api.elevenlabs.io/v1/voices")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("xi-api-key", key.trim())
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            val code = connection.responseCode
            Log.i(TAG, "[testElevenLabsKey] HTTP $code")
            if (code == 200) {
                connection.disconnect()
                Pair(true, "")
            } else {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.readText() ?: "no error body"
                } catch (e: Exception) {
                    "could not read error body"
                }
                Log.e(TAG, "[testElevenLabsKey] FAILED — HTTP $code, error: ${errorBody.take(300)}")
                connection.disconnect()
                val friendlyMsg = when (code) {
                    401 -> "Invalid API key (401)"
                    403 -> "Access denied (403)"
                    429 -> "Quota exhausted (429)"
                    else -> "HTTP $code"
                }
                Pair(false, friendlyMsg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[testElevenLabsKey] FAILED: ${e.message}")
            Pair(false, "Network error: ${e.message?.take(100)}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // FOREGROUND SERVICE — Always-Listening Mic Protection
    //
    // When wake word is enabled or voice mode is active, JARVIS starts a
    // foreground service with FOREGROUND_SERVICE_TYPE_MICROPHONE. This:
    //   1. Shows a persistent notification (Android requirement)
    //   2. Prevents Android from killing the mic when app is backgrounded
    //   3. Survives app swipe-from-recents (service restarts via onTaskRemoved)
    // ═══════════════════════════════════════════════════════════════════════

    private fun startForegroundService(context: Context) {
        try {
            val intent = Intent(context, com.jarvis.assistant.services.JarvisForegroundService::class.java).apply {
                action = com.jarvis.assistant.services.JarvisForegroundService.ACTION_START
            }
            context.startForegroundService(intent)
            Log.i(AUDIO_TAG, "[startForegroundService] Always-listening foreground service STARTED")
        } catch (e: Exception) {
            Log.e(AUDIO_TAG, "[startForegroundService] Failed: ${e.message}")
        }
    }

    private fun stopForegroundService(context: Context) {
        try {
            val intent = Intent(context, com.jarvis.assistant.services.JarvisForegroundService::class.java).apply {
                action = com.jarvis.assistant.services.JarvisForegroundService.ACTION_STOP
            }
            context.startService(intent)
            Log.i(AUDIO_TAG, "[stopForegroundService] Always-listening foreground service STOPPED")
        } catch (e: Exception) {
            Log.e(AUDIO_TAG, "[stopForegroundService] Failed: ${e.message}")
        }
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

    // ═══════════════════════════════════════════════════════════════════════
    // S1: CENTRALIZED STATE TRIGGER
    //
    // BOTH the manual Mic button AND wake word detection route through
    // this single function. This guarantees:
    //   1. TONE_PROP_BEEP is ALWAYS played when entering listening mode
    //   2. _brainState.value is ALWAYS set to BrainState.LISTENING
    //   3. The Hologram Compose UI ALWAYS sees the state change
    //   4. No race conditions between beep timing and state updates
    // ═══════════════════════════════════════════════════════════════════════

    private fun enterListeningState(source: String) {
        Log.i(AUDIO_TAG, "[enterListeningState] source=$source — playing BEEP and setting LISTENING")
        playWakeWordBeep()  // TONE_PROP_BEEP
        _brainState.value = BrainState.LISTENING
        _isListening.value = true
        _currentTranscription.value = ""
    }

    @SuppressLint("MissingPermission")
    fun startListening(context: Context) {
        if (_isListening.value) {
            Log.d(AUDIO_TAG, "[startListening] already listening — ignoring duplicate call")
            return
        }

        // S1: Use centralized trigger — manual mic button plays BEEP too
        enterListeningState(source = "MANUAL_MIC_BUTTON")

        // Start foreground service to protect mic from being killed
        startForegroundService(context)

        stopWakeWordMonitor()

        // S4: Removed delay(300) — AudioRecord starts IMMEDIATELY for lower latency
        viewModelScope.launch(Dispatchers.Main) {
            startAudioEngine(context)
            var waitCount = 0
            while (audioEngine?.isActive != true && waitCount < 30) {
                delay(50)
                waitCount++
            }
            if (audioEngine?.isActive == true) {
                audioEngine?.startCommandRecording()
                Log.d(AUDIO_TAG, "[startListening] AudioEngine confirmed active — command recording started")
            } else {
                Log.e(AUDIO_TAG, "[startListening] AudioEngine failed to become active after ${(waitCount * 50)}ms")
                _brainState.value = BrainState.ERROR
                _isListening.value = false
                addAssistantMessage("Microphone failed to start. Please check permissions and try again.", "stressed")
            }
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

        actualSampleRate = probeSupportedSampleRate()
        Log.d(AUDIO_TAG, "[startAudioEngine] Probed sample rate: $actualSampleRate Hz")

        audioEngine = AudioEngine(
            context = context,
            onAmplitudeUpdate = { amp ->
                _audioAmplitude.value = amp
            },
            onWakeWordDetected = {
                Log.i(AUDIO_TAG, "[onWakeWordDetected] callback fired — routing through centralized state trigger")
                // S1: Use centralized trigger — wake word plays BEEP + sets state atomically
                enterListeningState(source = "WAKE_WORD")
                audioEngine?.startCommandRecording()
            },
            onCommandReady = { pcmBytes ->
                Log.i(AUDIO_TAG, "[onCommandReady] callback fired: ${pcmBytes.size} bytes — launching handleCommandReady")
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

    @SuppressLint("MissingPermission")
    private fun probeSupportedSampleRate(): Int {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

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
            } catch (_: Exception) { }
        }

        try {
            val testAr = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                44_100, channelConfig, audioFormat,
                AudioRecord.getMinBufferSize(44_100, channelConfig, audioFormat)
            )
            val initialized = testAr.state == AudioRecord.STATE_INITIALIZED
            testAr.release()
            if (initialized) return 44_100
        } catch (_: Exception) { }

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

    private fun handleCommandReady(pcmBytes: ByteArray, sampleRate: Int) {
        Log.d(AUDIO_TAG, "[handleCommandReady] launched with ${pcmBytes.size} bytes at ${sampleRate}Hz")

        // Processing lock — drop duplicate VAD triggers
        if (isProcessing.get()) {
            Log.w(AUDIO_TAG, "[handleCommandReady] DROPPED — already processing (isProcessing=true)")
            _brainState.value = if (_isVoiceMode.value) BrainState.LISTENING else BrainState.IDLE
            return
        }
        isProcessing.set(true)

        // ═══════════════════════════════════════════════════════════════════
        // S2: TONE_PROP_ACK AT EXACT TRANSITION TO PROCESSING
        //
        // The ACK beep fires at the EXACT MILLISECOND we transition
        // to BrainState.PROCESSING (formerly THINKING). This provides
        // instant audio feedback that the AI heard you.
        // ═══════════════════════════════════════════════════════════════════
        playAckBeep()

        viewModelScope.launch(Dispatchers.Main) {
            _brainState.value = BrainState.THINKING

            // Stop recording but keep voice mode active
            audioEngine?.stopCommandRecording()
            _isListening.value = false

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

            // Feature: Mood Detection from voice tone
            try {
                val moodResult = MoodDetector.detectMoodFromAudio(pcmBytes, sampleRate)
                _detectedMood.value = moodResult
                Log.d(AUDIO_TAG, "[handleCommandReady] Mood detected: ${moodResult.mood} (confidence=${moodResult.confidence})")

                // Auto-suggest actions for stressed/sad moods
                if (moodResult.mood == "stressed" || moodResult.mood == "sad") {
                    if (moodResult.confidence > 0.3f) {
                        val actionMsg = when (moodResult.suggestedAction) {
                            "play_calm_music" -> "Sir, aap ${moodResult.mood} lag rahe ho. Shall I play some calming music?"
                            "play_uplifting_music" -> "Sir, lagta hai mood thoda off hai. Kuch achha gaana sunau?"
                            "suggest_break" -> "Sir, aap stressed lag rahe ho. Chaliye thoda break lete hain."
                            else -> null
                        }
                        if (actionMsg != null) {
                            Log.i(AUDIO_TAG, "[handleCommandReady] Mood action suggestion: $actionMsg")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(AUDIO_TAG, "[handleCommandReady] Mood detection failed: ${e.message}")
            }

            Log.d(AUDIO_TAG, "[handleCommandReady] Starting Gemini multimodal transcription at ${sampleRate}Hz")
            val transcription = transcribeViaGeminiFallback(pcmBytes, sampleRate)

            // Inject transcription into Chat UI as User message for immediate visual feedback
            if (transcription.isNotBlank()) {
                _currentTranscription.value = transcription
                addUserMessage(transcription)
                Log.i(AUDIO_TAG, "[handleCommandReady] Final transcription: \"$transcription\" — injected into chat UI")
                val ctx = getApplicationContext()
                if (ctx != null) {
                    processQuery(transcription, ctx, skipUserMessage = true)
                } else {
                    Log.e(AUDIO_TAG, "[handleCommandReady] No application context")
                    _brainState.value = if (_isVoiceMode.value) BrainState.LISTENING else BrainState.IDLE
                    isProcessing.set(false)
                }
            } else {
                Log.w(AUDIO_TAG, "[handleCommandReady] Transcription is EMPTY")
                addAssistantMessage("I couldn't make that out, Sir. Please try speaking louder or closer to the microphone.", "confused")
                isProcessing.set(false)
                if (_isVoiceMode.value) {
                    restartListeningAfterTts()
                } else {
                    _brainState.value = BrainState.IDLE
                }
            }
        }
    }

    private suspend fun transcribeViaGeminiFallback(pcmBytes: ByteArray, sourceSampleRate: Int): String {
        return withContext(Dispatchers.IO) {
            try {
                val apiKey = _geminiApiKey.value
                if (apiKey.isBlank()) {
                    Log.w(AUDIO_TAG, "[transcribeViaGeminiFallback] Gemini API key not set")
                    return@withContext ""
                }

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

                // A2: Try each model in the fallback list
                var lastError = ""
                for (model in GEMINI_MODELS) {
                    var connection: HttpURLConnection? = null
                    try {
                        // CRITICAL FIX (v8): Use ?key= URL parameter instead of x-goog-api-key header.
                        // The header method was causing 403 errors with some API key configurations.
                        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${apiKey.trim()}")
                        connection = url.openConnection() as HttpURLConnection
                        connection.requestMethod = "POST"
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.doOutput = true
                        connection.connectTimeout = 15_000
                        connection.readTimeout = 30_000

                        connection.outputStream.use { os ->
                            os.write(requestBody.toByteArray(Charsets.UTF_8))
                        }

                        val responseCode = connection.responseCode
                        if (responseCode == HttpURLConnection.HTTP_OK) {
                            val responseBody = connection.inputStream.bufferedReader().readText()
                            connection.disconnect()
                            connection = null

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
                                Log.i(AUDIO_TAG, "[transcribeViaGeminiFallback] Model $model transcription: \"$transcribedText\"")
                                return@withContext transcribedText
                            }
                        } else {
                            val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "unknown"
                            lastError = "Model $model error $responseCode: ${errorBody.take(300)}"
                            Log.w(AUDIO_TAG, "[transcribeViaGeminiFallback] $lastError")
                        }
                        connection?.disconnect()
                        connection = null
                    } catch (e: Exception) {
                        lastError = "Model $model exception: ${e.message}"
                        Log.w(AUDIO_TAG, "[transcribeViaGeminiFallback] $lastError")
                        // FIX (v14): Disconnect leaked connection on exception
                        try { connection?.disconnect() } catch (_: Exception) {}
                    }
                }

                Log.e(AUDIO_TAG, "[transcribeViaGeminiFallback] ALL models failed. Last error: $lastError")
                return@withContext ""
            } catch (e: Exception) {
                Log.e(AUDIO_TAG, "[transcribeViaGeminiFallback] Exception: ${e.message}", e)
                ""
            }
        }
    }

    private fun resamplePcm(pcm: ByteArray, sourceRate: Int, targetRate: Int): ByteArray {
        if (sourceRate == targetRate) return pcm

        val sourceSamples = pcm.size / 2
        val targetSamples = (sourceSamples.toLong() * targetRate / sourceRate).toInt()
        val result = ByteArray(targetSamples * 2)

        val ratio = sourceSamples.toFloat() / targetSamples.toFloat()

        for (i in 0 until targetSamples) {
            val srcPos = i * ratio
            val srcIdx = srcPos.toInt()
            val frac = srcPos - srcIdx

            val srcSample = if (srcIdx + 1 < sourceSamples) {
                val s0 = readInt16LE(pcm, srcIdx * 2)
                val s1 = readInt16LE(pcm, (srcIdx + 1) * 2)
                (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767)
            } else if (srcIdx < sourceSamples) {
                readInt16LE(pcm, srcIdx * 2)
            } else {
                0
            }

            writeInt16LE(result, i * 2, srcSample)
        }

        return result
    }

    private fun readInt16LE(buf: ByteArray, offset: Int): Int {
        val lo = buf[offset].toInt() and 0xFF
        val hi = buf[offset + 1].toInt()
        return (hi shl 8) or lo
    }

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
        if (textToSpeech == null) {
            initNativeTts(context.applicationContext)
        }
        // Register incoming call callback
        NotificationReaderService.onIncomingCall = { callerName, callerNumber ->
            handleIncomingCallAnnouncement(callerName, callerNumber)
        }
    }

    private fun getApplicationContext(): Context? = applicationContext

    // ═══════════════════════════════════════════════════════════════════════
    // INCOMING CALL HANDLER
    // ═══════════════════════════════════════════════════════════════════════

    fun handleIncomingCallAnnouncement(callerName: String, callerNumber: String) {
        viewModelScope.launch(Dispatchers.Main) {
            _brainState.value = BrainState.SPEAKING
            _emotion.value = "urgent"
            val announcement = if (callerName != callerNumber && callerName != "Unknown") {
                "Sir, incoming call from $callerName. Say answer to pick up, or reject to decline."
            } else {
                "Sir, incoming call from $callerNumber. Say answer to pick up, or reject to decline."
            }
            _lastResponse.value = announcement
            addAssistantMessage(announcement, "urgent")
            trySynthesizeAndPlay(announcement, getApplicationContext() ?: return@launch)
        }
    }

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

        // Start foreground service to keep mic alive in background
        startForegroundService(context)
        Log.i(AUDIO_TAG, "[startWakeWordMonitor] Starting background wake word monitor")

        wakeWordEngine = AudioEngine(
            context = context,
            onAmplitudeUpdate = { amp ->
                if (!_isListening.value) {
                    _audioAmplitude.value = amp * 0.3f
                }
            },
            onWakeWordDetected = {
                Log.i(AUDIO_TAG, "[startWakeWordMonitor] Wake word detected — routing through centralized state trigger")
                // S1: Use centralized trigger — wake word + BEEP + state, atomically
                enterListeningState(source = "WAKE_WORD_MONITOR")
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
        wakeWordEngine?.release()  // CRITICAL FIX (v14): Cancel engineScope CoroutineScope to prevent coroutine leak
        wakeWordEngine = null
        Log.i(AUDIO_TAG, "[stopWakeWordMonitor] stopped and released")
    }

    private fun restartWakeWordMonitorIfNeeded() {
        if (_isWakeWordEnabled.value && applicationContext != null) {
            startWakeWordMonitor(applicationContext!!)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // QUERY PROCESSING PIPELINE
    // ═══════════════════════════════════════════════════════════════════════

    fun processQuery(query: String, context: Context, skipUserMessage: Boolean = false) {
        Log.d(AUDIO_TAG, "[processQuery] query=\"$query\" brainState=${_brainState.value} isProcessing=${isProcessing.get()}")
        if (_brainState.value == BrainState.THINKING || _brainState.value == BrainState.SPEAKING) {
            Log.w(AUDIO_TAG, "[processQuery] Already ${_brainState.value} — forcing state reset for new query")
            // Stop any in-progress TTS playback so the new query can proceed
            releaseMediaPlayer()
            amplitudePulseJob?.cancel()
            amplitudePulseJob = null
            textToSpeech?.stop()
        }

        // ACK beep for text input (voice input already played it in handleCommandReady)
        if (!skipUserMessage) {
            playAckBeep()
        }

        viewModelScope.launch(Dispatchers.Main) {
            try {
                // Skip adding user message if it was already added by handleCommandReady
                // (voice transcription). For text input, we add it here.
                if (!skipUserMessage) {
                    addUserMessage(query)
                }
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
                    is CommandRouter.RouteResult.NeedsAI  -> {
                        // MEMORY: Get relevant past conversations and prepend to context
                        val memoryContext = withContext(Dispatchers.IO) {
                            ConversationMemory.getRelevantMemory(query, context)
                        }
                        handleAIQuery(routeResult.query, context, memoryContext)
                    }
                    is CommandRouter.RouteResult.VisionCommand -> {
                        captureAndAnalyzeVision(context, routeResult.prompt)
                    }
                    is CommandRouter.RouteResult.NotificationCommand -> {
                        val notifText = getNotificationsText(5, routeResult.appFilter)
                        _emotion.value = "calm"
                        _lastResponse.value = notifText
                        _isTyping.value = false
                        addAssistantMessage(notifText, "calm")
                        _brainState.value = BrainState.SPEAKING
                        _audioAmplitude.value = 0.5f
                        trySynthesizeAndPlay(notifText, context)
                    }
                    // Feature: Macro Commands
                    is CommandRouter.RouteResult.MacroCommand -> {
                        val macroResult = if (routeResult.macroName == "__create__") {
                            MacroEngine.createCustomMacro(query, context)
                        } else {
                            MacroEngine.executeMacro(routeResult.macroName, context)
                        }
                        _emotion.value = "confident"
                        _lastResponse.value = macroResult
                        _isTyping.value = false
                        addAssistantMessage(macroResult, "confident")
                        _brainState.value = BrainState.SPEAKING
                        _audioAmplitude.value = 0.5f
                        trySynthesizeAndPlay(macroResult, context)
                    }
                    // Feature: Daily Brief
                    is CommandRouter.RouteResult.DailyBriefCommand -> {
                        requestDailyBrief(context)
                    }
                    // Feature: Location Commands
                    is CommandRouter.RouteResult.LocationCommand -> {
                        handleLocationCommand(routeResult.locationType, context)
                    }
                    // Feature: Device Status
                    is CommandRouter.RouteResult.DeviceStatusCommand -> {
                        val statusReport = ProactiveDeviceMonitor.getDeviceStatusReport(context)
                        _emotion.value = "calm"
                        _lastResponse.value = statusReport
                        _isTyping.value = false
                        addAssistantMessage(statusReport, "calm")
                        _brainState.value = BrainState.SPEAKING
                        _audioAmplitude.value = 0.5f
                        trySynthesizeAndPlay(statusReport, context)
                    }
                    is CommandRouter.RouteResult.AutonomousTask -> {
                        // Route to AI with structured prompt for autonomous execution
                        val aiQuery = routeResult.prompt
                        _emotion.value = "confident"
                        handleAIQuery(aiQuery, context, memoryContext = "")
                    }
                }
            } catch (e: CancellationException) {
                _brainState.value = if (_isVoiceMode.value) BrainState.LISTENING else BrainState.IDLE
                isProcessing.set(false)
                return@launch
            } catch (e: Exception) {
                Log.e(TAG, "[processQuery] pipeline error", e)
                _brainState.value     = if (_isVoiceMode.value) BrainState.LISTENING else BrainState.ERROR
                _isTyping.value       = false
                _audioAmplitude.value = 0f
                isProcessing.set(false)
                addAssistantMessage("Error: ${e.message?.take(200) ?: "Unknown"}", "stressed")
                toast(context, "Query error: ${e.message?.take(100)}")
            } finally {
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

            // Handle call answer/reject — CommandRouter already executes the action,
            // so this is just a redundant safety net (kept for backwards compatibility)
            if (result.response.contains("Answering the call")) {
                // CommandRouter already clicked via accessibility/Shizuku; this is fallback only
                if (!ShizukuManager.isReady()) {
                    com.jarvis.assistant.automation.TaskExecutorBridge.accessibilityService?.get()?.let { svc ->
                        svc.autoClick("Answer") || svc.autoClick("Accept")
                    }
                }
            }
            if (result.response.contains("Rejecting the call")) {
                if (!ShizukuManager.isReady()) {
                    com.jarvis.assistant.automation.TaskExecutorBridge.accessibilityService?.get()?.let { svc ->
                        svc.autoClick("Decline") || svc.autoClick("Reject")
                    }
                }
            }

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

    private suspend fun handleAIQuery(query: String, context: Context, memoryContext: String = "") {
        try {
            _brainState.value     = BrainState.THINKING
            _audioAmplitude.value = 0.1f

            val historyJson    = buildHistoryJson()
            val screenContext  = JarviewModel.screenTextData

            // Feature: Include mood context in system prompt
            val moodContext = MoodDetector.getMoodContextString(_detectedMood.value)

            // Feature: Include location context in system prompt
            val locationCtx = _locationContext.value
            val locationBehaviorCtx = try {
                val ctx = getApplicationContext()
                if (ctx != null) {
                    val loc = LocationAwarenessManager.getCurrentLocation(ctx)
                    if (loc != null) {
                        val locType = LocationAwarenessManager.detectLocationType(loc, ctx)
                        LocationAwarenessManager.getLocationBehaviorContext(locType)
                    } else ""
                } else ""
            } catch (_: Exception) { "" }

            // MEMORY: Prepend memory context to history if available
            val enhancedHistoryJson = if (memoryContext.isNotBlank() || moodContext.isNotBlank() || locationBehaviorCtx.isNotBlank()) {
                // Inject memory context into the history as a system-level context
                val contextParts = mutableListOf<String>()
                if (memoryContext.isNotBlank()) contextParts.add("[MEMORY CONTEXT]: $memoryContext")
                if (moodContext.isNotBlank()) contextParts.add("[MOOD CONTEXT]: $moodContext")
                if (locationBehaviorCtx.isNotBlank()) contextParts.add("[LOCATION CONTEXT]: $locationBehaviorCtx")
                if (locationCtx.isNotBlank()) contextParts.add("[CURRENT LOCATION]: $locationCtx")

                val contextEntry = """{"role":"user","content":"${contextParts.joinToString(" | ")}"}"""
                val contextReply = """{"role":"model","content":"Understood. I will use this context (memory, mood, location) to adjust my responses naturally."}"""
                if (historyJson.length > 2) {
                    historyJson.dropLast(1) + "," + contextEntry + "," + contextReply + "]"
                } else {
                    "[$contextEntry,$contextReply]"
                }
            } else {
                historyJson
            }

            // ═══════════════════════════════════════════════════════════════════
            // UPGRADE (v6.0): GEMINI FUNCTION CALLING
            //
            // First, try sending the query to Gemini with tool definitions.
            // If Gemini returns a function call (e.g., open_and_search),
            // we execute it via the TaskExecutorBridge.
            //
            // If Gemini returns a text response (no function call), we
            // fall through to the normal AI processing pipeline.
            //
            // This makes JARVIS an AUTONOMOUS AGENT that can plan and
            // execute multi-step tasks like:
            //   "Open YouTube, search for X, and play the first video"
            // ═══════════════════════════════════════════════════════════════════
            if (_geminiApiKey.value.isNotBlank()) {
                try {
                    Log.d(AUDIO_TAG, "[handleAIQuery] Trying Gemini Function Calling for autonomous task planning")
                    val toolResult = withContext(Dispatchers.IO) {
                        GeminiFunctionCaller.processWithTools(
                            query = query,
                            apiKey = _geminiApiKey.value,
                            context = context,
                            historyJson = enhancedHistoryJson
                        )
                    }

                    when (toolResult) {
                        is GeminiFunctionCaller.ProcessResult.ToolExecuted -> {
                            // Gemini returned a function call and we executed it
                            val stepMsg = when (toolResult.stepResult) {
                                is TaskExecutorBridge.StepResult.Success -> toolResult.stepResult.message
                                is TaskExecutorBridge.StepResult.Failed -> "Action failed: ${toolResult.stepResult.message}"
                            }
                            val aiMsg = toolResult.aiResponse ?: stepMsg
                            val emotion = if (toolResult.stepResult is TaskExecutorBridge.StepResult.Success) "confident" else "stressed"

                            _emotion.value       = emotion
                            _lastResponse.value  = aiMsg
                            _isTyping.value      = false
                            addAssistantMessage(aiMsg, emotion)
                            _brainState.value     = BrainState.SPEAKING
                            _audioAmplitude.value = 0.5f
                            trySynthesizeAndPlay(aiMsg, context)
                            return
                        }
                        is GeminiFunctionCaller.ProcessResult.TextOnly -> {
                            // Gemini returned a text response — use it directly
                            val rawResponse = toolResult.response
                            val emotionTag = parseEmotionTag(rawResponse)
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
                            trySynthesizeAndPlay(cleanResponse, context)
                            return
                        }
                        is GeminiFunctionCaller.ProcessResult.MultiStep -> {
                            // Multiple tool calls were executed
                            val summary = toolResult.steps.zip(toolResult.results).joinToString(". ") { (step, result) ->
                                when (result) {
                                    is TaskExecutorBridge.StepResult.Success -> result.message
                                    is TaskExecutorBridge.StepResult.Failed -> "Failed: ${result.message}"
                                }
                            }
                            val emotion = if (toolResult.results.all { it is TaskExecutorBridge.StepResult.Success }) "confident" else "stressed"

                            _emotion.value       = emotion
                            _lastResponse.value  = summary
                            _isTyping.value      = false
                            addAssistantMessage(summary, emotion)
                            _brainState.value     = BrainState.SPEAKING
                            _audioAmplitude.value = 0.5f
                            trySynthesizeAndPlay(summary, context)
                            return
                        }
                        is GeminiFunctionCaller.ProcessResult.Error -> {
                            // Function calling failed — fall through to normal pipeline
                            Log.w(AUDIO_TAG, "[handleAIQuery] Gemini Function Calling failed: ${toolResult.message} — falling back to normal pipeline")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(AUDIO_TAG, "[handleAIQuery] Gemini Function Calling exception: ${e.message} — falling back to normal pipeline")
                }
            }

            // ═══════════════════════════════════════════════════════════════════
            // FALLBACK: Normal AI Query Pipeline (Rust → Gemini Direct)
            // Used when Function Calling is unavailable or fails
            // ═══════════════════════════════════════════════════════════════════
            Log.d(AUDIO_TAG, "[handleAIQuery] Using standard pipeline — Calling RustBridge.processQuery")
            var rawResponse = withContext(Dispatchers.IO) {
                if (RustBridge.isNativeReady()) {
                    try {
                        RustBridge.processQuery(query, screenContext, historyJson, JARVIS_SYSTEM_PROMPT)
                    } catch (e: Exception) {
                        Log.e(TAG, "[handleAIQuery] JNI processQuery failed", e)
                        "[ERROR] AI processing failed: ${e.message?.take(200) ?: "Unknown"}"
                    }
                } else {
                    Log.w(AUDIO_TAG, "[handleAIQuery] Rust not ready — skipping JNI, using direct Gemini fallback")
                    "[ERROR] Native library not ready. Using direct Gemini fallback."
                }
            }
            Log.d(AUDIO_TAG, "[handleAIQuery] Raw response length=${rawResponse.length}")

            val isRustError = rawResponse.startsWith("[ERROR]") ||
                    rawResponse.startsWith("ERROR:") ||
                    rawResponse.contains("Native library not loaded") ||
                    rawResponse.contains("Native library not ready")

            if (isRustError && _geminiApiKey.value.isNotBlank()) {
                Log.w(AUDIO_TAG, "[handleAIQuery] Rust failed, falling back to processQueryViaGeminiDirect")
                val directResponse = withContext(Dispatchers.IO) {
                    processQueryViaGeminiDirect(query, enhancedHistoryJson)
                }
                if (directResponse.isNotBlank() && !directResponse.startsWith("[ERROR]")) {
                    rawResponse = directResponse
                    Log.i(AUDIO_TAG, "[handleAIQuery] Gemini direct fallback succeeded, length=${directResponse.length}")
                } else {
                    Log.w(AUDIO_TAG, "[handleAIQuery] Gemini direct fallback also failed: $directResponse")
                    if (rawResponse.isBlank() || rawResponse == "[ERROR]") {
                        rawResponse = directResponse.ifBlank { "[ERROR] Gemini API is not responding. Check your API key and internet connection." }
                    }
                }
            }

            val parsed  = parseErrorResponse(rawResponse)
            val isError = parsed.startsWith("[ERROR]") || rawResponse.startsWith("[ERROR]") || rawResponse.startsWith("ERROR:")

            if (isError) {
                Log.w(AUDIO_TAG, "[handleAIQuery] Error response detected")
                _brainState.value   = if (_isVoiceMode.value) BrainState.LISTENING else BrainState.ERROR
                _isTyping.value     = false
                _audioAmplitude.value = 0f
                _lastResponse.value = parsed
                isProcessing.set(false)
                addAssistantMessage(parsed, "stressed")
                toast(context, "AI error: ${parsed.take(80)}")
                if (_isVoiceMode.value) {
                    restartListeningAfterTts()
                }
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
            Log.i(AUDIO_TAG, "[handleAIQuery] Response text pushed to Chat UI INSTANTLY — TTS will play in parallel")

            _brainState.value     = BrainState.SPEAKING
            _audioAmplitude.value = 0.5f
            trySynthesizeAndPlay(cleanResponse, context)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "[handleAIQuery] failed", e)
            _brainState.value = if (_isVoiceMode.value) BrainState.LISTENING else BrainState.ERROR
            _isTyping.value   = false
            _audioAmplitude.value = 0f
            isProcessing.set(false)
            addAssistantMessage("Processing error, Sir.", "stressed")
            toast(context, "AI processing error: ${e.message?.take(80)}")
            if (_isVoiceMode.value) {
                restartListeningAfterTts()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GEMINI DIRECT (Kotlin HTTP fallback — no Rust required)
    // ═══════════════════════════════════════════════════════════════════════

    private fun processQueryViaGeminiDirect(query: String, historyJson: String): String {
        try {
            val apiKey = _geminiApiKey.value
            if (apiKey.isBlank()) {
                Log.w(TAG, "[processQueryViaGeminiDirect] Gemini API key not set")
                return "[ERROR] Gemini API key not set. Please enter it in Settings."
            }

            Log.d(TAG, "[processQueryViaGeminiDirect] Sending query to Gemini: \"$query\"")

            val contentsArray = org.json.JSONArray()

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

            // A2: Try multiple models
            var lastError = ""
            for (model in GEMINI_MODELS) {
                var connection: HttpURLConnection? = null
                try {
                    // CRITICAL FIX (v8): Use ?key= URL parameter instead of x-goog-api-key header
                    val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${apiKey.trim()}")
                    connection = url.openConnection() as HttpURLConnection
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
                        Log.e(TAG, "[processQueryViaGeminiDirect] Model $model error $responseCode: ${errorBody.take(500)}")
                        lastError = "Model $model: HTTP $responseCode — ${errorBody.take(200)}"
                        connection.disconnect()
                        connection = null
                        continue
                    }

                    val responseBody = connection.inputStream.bufferedReader().readText()
                    connection.disconnect()
                    connection = null

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
                        Log.i(TAG, "[processQueryViaGeminiDirect] Model $model response: \"${responseText.take(100)}...\"")
                        responseText
                    } else {
                        Log.w(TAG, "[processQueryViaGeminiDirect] Model $model returned empty response")
                        ""
                    }
                } catch (e: Exception) {
                    lastError = "Model $model exception: ${e.message}"
                    Log.e(TAG, "[processQueryViaGeminiDirect] $lastError")
                    // FIX (v14): Disconnect leaked connection on exception
                    try { connection?.disconnect() } catch (_: Exception) {}
                }
            }

            return "[ERROR] All Gemini models failed. Last error: $lastError"
        } catch (e: Exception) {
            Log.e(TAG, "[processQueryViaGeminiDirect] Exception: ${e.message}", e)
            return "[ERROR] Query processing failed: ${e.message}"
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ERROR RESPONSE PARSING
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * FIX A1: ALL error returns are prefixed with [ERROR] so downstream
     * detection is 100% reliable. No friendly message escapes as a "normal" response.
     */
    private fun parseErrorResponse(raw: String): String {
        val lower = raw.lowercase()
        return when {
            Regex("""\b404\b""").containsMatchIn(lower) ||
                    lower.contains("not found for api version") || lower.contains("model_not_found") ->
                "[ERROR] Sir, the AI model is currently unavailable. Please update the app or check your API key. (404)"

            Regex("""\b429\b""").containsMatchIn(lower) ||
                    lower.contains("resource_exhausted") || lower.contains("quota") ->
                "[ERROR] Sir, the Gemini API quota has been exhausted. Please update the key in Settings. (429)"

            Regex("""\b403\b""").containsMatchIn(lower) ||
                    lower.contains("permission_denied") || lower.contains("api_key_invalid") ->
                "[ERROR] Sir, the API key appears invalid or unauthorised. Please check Settings. (403)"

            lower.contains("network") || (lower.contains("connect") && lower.contains("refused")) ->
                "[ERROR] Sir, no internet connection. Check your network."

            raw.startsWith("[ERROR]") -> raw

            raw.startsWith("ERROR:") ->
                "[ERROR] ${raw.removePrefix("ERROR:").trim().ifBlank { "An error occurred, Sir." }}"

            lower.contains("error") && lower.contains("{") ->
                Regex(""""message"\s*:\s*"([^"]+)"""").find(raw)?.groupValues?.get(1)
                    ?.let { "[ERROR] $it" }
                    ?: "[ERROR] An error occurred, Sir."

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
            val (stability, similarityBoost) = computeTtsParams()
            Log.d(AUDIO_TAG, "[trySynthesizeAndPlay] TTS params: emotion=${_emotion.value} stability=$stability similarityBoost=$similarityBoost")

            var base64: String? = null

            // STEP 1: Try Rust JNI path
            if (RustBridge.isNativeReady()) {
                Log.d(AUDIO_TAG, "[trySynthesizeAndPlay] STEP 1: Trying RustBridge.synthesizeSpeech")
                base64 = withContext(Dispatchers.IO) {
                    try {
                        val result = RustBridge.synthesizeSpeech(
                            text, _ttsVoiceId.value,
                            stability = stability, similarityBoost = similarityBoost
                        )
                        Log.d(AUDIO_TAG, "[trySynthesizeAndPlay] Rust returned base64 length=${result?.length ?: 0}")
                        // FIX #2: If Rust returns an error string (starts with "ERROR:"), treat as null
                        // The Rust TTS can return "ERROR: ..." which is NOT valid base64 audio.
                        // Base64-decoding it and passing to MediaPlayer causes Error -2147483648.
                        if (result != null && (result.startsWith("ERROR:") || result.startsWith("error:"))) {
                            Log.w(AUDIO_TAG, "[trySynthesizeAndPlay] Rust TTS returned error string, treating as null: ${result.take(80)}")
                            null
                        } else {
                            result
                        }
                    } catch (e: Exception) {
                        Log.e(AUDIO_TAG, "[trySynthesizeAndPlay] RustBridge.synthesizeSpeech FAILED: ${e.message}")
                        null
                    }
                }
            }

            // STEP 2: If Rust returned nothing, try direct Kotlin HTTP
            if (base64.isNullOrBlank() && elevenLabsKey.isNotBlank()) {
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

            // STEP 3: Decode and play
            if (!base64.isNullOrBlank()) {
                Log.d(AUDIO_TAG, "[trySynthesizeAndPlay] STEP 3: Decoding base64 MP3")
                val mp3 = withContext(Dispatchers.IO) {
                    try {
                        val decoded = Base64.decode(base64, Base64.NO_WRAP)
                        Log.d(AUDIO_TAG, "[trySynthesizeAndPlay] Decoded MP3 size=${decoded.size} bytes")

                        // FIX #2: Validate decoded bytes are actually MP3 data, not a JSON error.
                        // ElevenLabs errors come back as JSON, not audio. If the first bytes
                        // are '{' or '<', it's NOT an MP3 file — do NOT pass to MediaPlayer.
                        // MP3 files start with 0xFF 0xFB or 0x49 0x44 0x33 ("ID3").
                        if (decoded.isEmpty()) {
                            Log.e(AUDIO_TAG, "[trySynthesizeAndPlay] Decoded bytes are EMPTY — not valid audio")
                            null
                        } else if (decoded[0] == '{'.code.toByte() || decoded[0] == '<'.code.toByte()) {
                            Log.e(AUDIO_TAG, "[trySynthesizeAndPlay] Decoded bytes start with JSON/XML, NOT MP3 — ElevenLabs returned an error message instead of audio. Skipping MediaPlayer to prevent crash.")
                            // Log the error for debugging
                            try {
                                val errorText = String(decoded, Charsets.UTF_8)
                                Log.e(AUDIO_TAG, "[trySynthesizeAndPlay] Error body: ${errorText.take(500)}")
                            } catch (_: Exception) {}
                            null
                        } else {
                            decoded
                        }
                    } catch (e: Exception) {
                        Log.e(AUDIO_TAG, "[trySynthesizeAndPlay] Base64 decode FAILED: ${e.message}")
                        null
                    }
                }

                if (mp3 != null && mp3.isNotEmpty()) {
                    Log.d(AUDIO_TAG, "[trySynthesizeAndPlay] STEP 4: Playing MP3 via MediaPlayer")
                    withContext(Dispatchers.Main) {
                        playMp3AudioAsync(mp3, context, text)
                    }

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
                    return
                }
            }

            // A4: FALLBACK to Android native TextToSpeech
            Log.w(AUDIO_TAG, "[trySynthesizeAndPlay] ElevenLabs unavailable — falling back to Android TTS")
            fallbackToNativeTts(text, context)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(AUDIO_TAG, "[trySynthesizeAndPlay] UNEXPECTED EXCEPTION: ${e.message}", e)
            _brainState.value = if (_isVoiceMode.value) BrainState.LISTENING else BrainState.IDLE
            _audioAmplitude.value = 0f
            isProcessing.set(false)  // Release processing lock
            toast(context, "TTS error: ${e.message?.take(80)}")
            fallbackToNativeTts(text, context)
        }
    }

    // A4: Initialize Android native TTS
    private fun initNativeTts(context: Context) {
        if (textToSpeech != null) return
        try {
            textToSpeech = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsInitialized = true
                    textToSpeech?.language = Locale.UK
                    Log.i(AUDIO_TAG, "[initNativeTts] Android TTS initialized successfully")
                } else {
                    Log.e(AUDIO_TAG, "[initNativeTts] Android TTS initialization failed: status=$status")
                }
            }
        } catch (e: Exception) {
            Log.e(AUDIO_TAG, "[initNativeTts] Exception: ${e.message}")
        }
    }

    // A4: Speak using Android TTS when ElevenLabs fails
    private fun fallbackToNativeTts(text: String, context: Context) {
        try {
            if (!ttsInitialized || textToSpeech == null) {
                initNativeTts(context)
            }
            if (ttsInitialized) {
                val utteranceId = "jarvis_tts_${System.currentTimeMillis()}"
                // Set up completion listener to properly reset brain state
                textToSpeech?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(AUDIO_TAG, "[fallbackToNativeTts] TTS started speaking")
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.d(AUDIO_TAG, "[fallbackToNativeTts] TTS completed — voiceMode=${_isVoiceMode.value}")
                        _audioAmplitude.value = 0f
                        amplitudePulseJob?.cancel()
                        amplitudePulseJob = null
                        isProcessing.set(false)  // Release processing lock
                        if (_isVoiceMode.value) {
                            _brainState.value = BrainState.LISTENING
                            restartListeningAfterTts()
                        } else {
                            _brainState.value = BrainState.IDLE
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        Log.e(AUDIO_TAG, "[fallbackToNativeTts] TTS error")
                        _audioAmplitude.value = 0f
                        amplitudePulseJob?.cancel()
                        amplitudePulseJob = null
                        isProcessing.set(false)  // Release processing lock on error
                        _brainState.value = if (_isVoiceMode.value) BrainState.LISTENING else BrainState.ERROR
                    }
                })

                textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, android.os.Bundle(), utteranceId)
                Log.i(AUDIO_TAG, "[fallbackToNativeTts] Speaking via Android TTS: \"${text.take(40)}...\"")

                // Simulate amplitude pulse during native TTS playback
                amplitudePulseJob = viewModelScope.launch(Dispatchers.Default) {
                    var phase = 0f
                    while (isActive) {
                        val speaking = try { textToSpeech?.isSpeaking == true } catch (_: Exception) { false }
                        if (!speaking) break
                        phase += 0.15f
                        val pulse = 0.4f + 0.2f * kotlin.math.sin(phase.toDouble()).toFloat()
                        _audioAmplitude.value = pulse.coerceIn(0.2f, 0.6f)
                        delay(45)
                    }
                    _audioAmplitude.value = 0f
                }
            } else {
                Log.e(AUDIO_TAG, "[fallbackToNativeTts] Android TTS not available")
                _brainState.value = if (_isVoiceMode.value) BrainState.LISTENING else BrainState.IDLE
                _audioAmplitude.value = 0f
                isProcessing.set(false)  // CRITICAL FIX (v14): Release processing lock — prevents permanent voice lockout
                toast(context, "Text-to-speech unavailable — no voice output")
            }
        } catch (e: Exception) {
            Log.e(AUDIO_TAG, "[fallbackToNativeTts] Exception: ${e.message}")
            _brainState.value = if (_isVoiceMode.value) BrainState.LISTENING else BrainState.IDLE
            _audioAmplitude.value = 0f
            isProcessing.set(false)  // CRITICAL FIX (v14): Release processing lock on exception
        }
    }

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

    fun sendMessage(text: String, context: Context) = processQuery(text, context, skipUserMessage = false)

    // ═══════════════════════════════════════════════════════════════════════
    // VISION MODE — Camera + AI Vision
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Capture a photo and analyze it with Gemini Vision.
     * Triggered by vision commands like "kya hai yeh", "what is this", "dekh kya hai".
     */
    fun captureAndAnalyzeVision(context: Context, prompt: String = "What do you see?") {
        viewModelScope.launch(Dispatchers.Main) {
            try {
                _brainState.value = BrainState.THINKING
                _isTyping.value = true
                addAssistantMessage("Let me take a look, Sir...", "calm")

                val result = withContext(Dispatchers.IO) {
                    visionManager.captureAndAnalyze(context, prompt, _geminiApiKey.value)
                }

                _emotion.value = "surprised"
                _lastResponse.value = result
                _isTyping.value = false
                addAssistantMessage(result, "surprised")
                _brainState.value = BrainState.SPEAKING
                _audioAmplitude.value = 0.5f
                trySynthesizeAndPlay(result, context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "[captureAndAnalyzeVision] Error: ${e.message}")
                _brainState.value = BrainState.IDLE
                _isTyping.value = false
                addAssistantMessage("Sir, I couldn't analyze the image: ${e.message?.take(100)}", "stressed")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Feature: DAILY BRIEF
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Request a daily brief. Generates the brief and speaks it.
     * Triggered by "good morning", "daily brief", "aaj ka plan".
     */
    fun requestDailyBrief(context: Context) {
        viewModelScope.launch(Dispatchers.Main) {
            try {
                _brainState.value = BrainState.THINKING
                _isTyping.value = true
                addAssistantMessage("Let me prepare your daily brief, Sir...", "calm")

                val brief = withContext(Dispatchers.IO) {
                    DailyBriefGenerator.generateBrief(context, _geminiApiKey.value)
                }

                _emotion.value = "calm"
                _lastResponse.value = brief
                _isTyping.value = false
                addAssistantMessage(brief, "calm")
                _brainState.value = BrainState.SPEAKING
                _audioAmplitude.value = 0.5f
                trySynthesizeAndPlay(brief, context)

                // Also post as notification
                withContext(Dispatchers.IO) {
                    DailyBriefGenerator.postBriefNotification(context, brief)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "[requestDailyBrief] Error: ${e.message}")
                _brainState.value = if (_isVoiceMode.value) BrainState.LISTENING else BrainState.IDLE
                _isTyping.value = false
                addAssistantMessage("Sir, I couldn't prepare your daily brief. ${e.message?.take(100)}", "stressed")
            }
        }
    }

    /**
     * Schedule morning brief at a given time.
     */
    fun scheduleMorningBrief(context: Context, hour: Int, minute: Int) {
        DailyBriefGenerator.scheduleMorningBrief(context, hour, minute)
        addAssistantMessage("Morning brief scheduled for $hour:${String.format("%02d", minute)}, Sir.", "calm")
    }

    /**
     * Cancel scheduled morning brief.
     */
    fun cancelMorningBrief(context: Context) {
        DailyBriefGenerator.cancelMorningBrief(context)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Feature: LOCATION COMMANDS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Handle location-setting commands like "yeh mera ghar hai" / "this is my office".
     */
    private fun handleLocationCommand(locationType: String, context: Context) {
        viewModelScope.launch(Dispatchers.Main) {
            try {
                val location = withContext(Dispatchers.IO) {
                    LocationAwarenessManager.getCurrentLocation(context)
                }

                if (location == null) {
                    val msg = "Sir, I need location permission to save your ${locationType} location. Please grant it in Settings."
                    _emotion.value = "confused"
                    _lastResponse.value = msg
                    addAssistantMessage(msg, "confused")
                    _brainState.value = BrainState.SPEAKING
                    trySynthesizeAndPlay(msg, context)
                    return@launch
                }

                when (locationType) {
                    "home" -> {
                        LocationAwarenessManager.setHomeLocation(location.latitude, location.longitude, context)
                        val msg = "Sir, your home location has been saved. I'll adjust my behavior when you're at home."
                        _emotion.value = "happy"
                        _lastResponse.value = msg
                        addAssistantMessage(msg, "happy")
                    }
                    "office" -> {
                        LocationAwarenessManager.setOfficeLocation(location.latitude, location.longitude, context)
                        val msg = "Sir, your office location has been saved. I'll be more professional when you're at work."
                        _emotion.value = "happy"
                        _lastResponse.value = msg
                        addAssistantMessage(msg, "happy")
                    }
                }

                // Update location context
                val locCtx = withContext(Dispatchers.IO) {
                    LocationAwarenessManager.getLocationContext(context)
                }
                if (locCtx.isNotBlank()) {
                    _locationContext.value = locCtx
                }

                _brainState.value = BrainState.SPEAKING
                _audioAmplitude.value = 0.5f
                trySynthesizeAndPlay(_lastResponse.value, context)
            } catch (e: Exception) {
                Log.e(TAG, "[handleLocationCommand] Error: ${e.message}")
                addAssistantMessage("Sir, I couldn't save that location. ${e.message?.take(100)}", "stressed")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // WEB SEARCH — Live Internet Search
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Perform a live web search and return results.
     */
    suspend fun performWebSearch(query: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val apiKey = settingsRepository.getWebSearchApiKey()
                val cx = settingsRepository.getWebSearchCx()
                webSearchEngine.search(query, apiKey, cx)
            } catch (e: Exception) {
                Log.e(TAG, "[performWebSearch] Error: ${e.message}")
                "Search failed: ${e.message?.take(100)}"
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // NOTIFICATION READER — Read and Announce Notifications
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Read recent notifications, optionally filtered by app.
     * Returns formatted text suitable for TTS.
     */
    fun getNotificationsText(count: Int = 5, packageFilter: String? = null): String {
        val notifications = NotificationReaderService.getRecentNotifications(count, packageFilter)
        if (notifications.isEmpty()) {
            return "Sir, koi naya notification nahi hai."
        }

        val sb = StringBuilder()
        for (notif in notifications) {
            val svc = NotificationReaderService.getInstance()
            sb.appendLine(svc?.formatForTTS(notif) ?: "${notif.appName}: ${notif.title} - ${notif.content}")
        }
        return sb.toString().trimEnd()
    }

    /**
     * Enable or disable the notification reader.
     */
    fun setNotificationsEnabled(enabled: Boolean) {
        _notificationsEnabled.value = enabled
        NotificationReaderService.setNotificationsEnabled(enabled)
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.setNotificationsEnabled(enabled)
        }
    }

    fun toggleVoiceMode(context: Context) {
        Log.d(AUDIO_TAG, "[toggleVoiceMode] voiceMode=${_isVoiceMode.value} isListening=${_isListening.value}")
        if (_isVoiceMode.value) {
            // Turn OFF voice mode — stop everything
            _isVoiceMode.value = false
            stopListening()
        } else {
            // Turn ON voice mode — start listening
            _isVoiceMode.value = true
            startListening(context)
        }
    }

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
     * Restarts the audio listening pipeline after TTS playback completes in voice mode.
     * Called from MediaPlayer.OnCompletionListener and UtteranceProgressListener.onDone.
     */
    @SuppressLint("MissingPermission")
    private fun restartListeningAfterTts() {
        val ctx = getApplicationContext()
        if (ctx == null) {
            Log.e(AUDIO_TAG, "[restartListeningAfterTts] No application context — cannot restart listening")
            _brainState.value = BrainState.IDLE
            return
        }
        Log.d(AUDIO_TAG, "[restartListeningAfterTts] Restarting listening in voice mode")
        viewModelScope.launch(Dispatchers.Main) {
            delay(300) // Brief pause to avoid audio hardware contention
            startListening(ctx)
        }
    }

    fun toggleDevice(deviceId: String, newState: Boolean) {
        _devices.value = _devices.value.map { d ->
            if (d.id == deviceId) d.copy(isOn = newState) else d
        }

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

                        val room = attributes.optString("area", "Default")

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
    // SMART HOME CONNECTION
    // ═══════════════════════════════════════════════════════════════════════

    private fun connectSmartHome(context: Context) {
        try {
            val haUrl = _homeAssistantUrl.value
            val haToken = _homeAssistantToken.value
            if (haUrl.isNotBlank() && haToken.isNotBlank()) {
                HomeAssistantBridge.configure(haUrl, haToken)
                Log.i(TAG, "Home Assistant bridge configured: $haUrl")
                refreshDevices()
            }

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

                MqttManager.subscribe("jarvis/status/#")
                MqttManager.subscribe("homeassistant/#")
            }
        } catch (e: Exception) {
            Log.e(TAG, "connectSmartHome failed: ${e.message}")
        }
    }

    private fun reconnectSmartHome() {
        val ctx = getApplicationContext() ?: return

        try {
            MqttManager.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "MQTT disconnect error: ${e.message}")
        }

        _isMqttConnected.value = false
        _mqttLabel.value = "MQTT Disconnected"
        connectSmartHome(ctx)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PER-FIELD SETTERS
    // ═══════════════════════════════════════════════════════════════════════

    fun setGeminiApiKey(key: String) {
        _geminiApiKey.value = key
        viewModelScope.launch(Dispatchers.IO) {
            try { settingsRepository.setGeminiApiKey(key) } catch (e: Exception) { Log.e(TAG, "Persist Gemini API key failed: ${e.message}") }
        }
    }

    fun setElevenLabsApiKey(key: String) {
        _elevenLabsApiKey.value = key
        viewModelScope.launch(Dispatchers.IO) {
            try { settingsRepository.setElevenLabsApiKey(key) } catch (e: Exception) { Log.e(TAG, "Persist ElevenLabs API key failed: ${e.message}") }
        }
    }

    fun setTtsVoiceId(id: String) {
        _ttsVoiceId.value = id
        viewModelScope.launch(Dispatchers.IO) {
            try { settingsRepository.setTtsVoiceId(id) } catch (e: Exception) { Log.e(TAG, "Persist TTS voice ID failed: ${e.message}") }
        }
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        _isWakeWordEnabled.value = enabled
        viewModelScope.launch(Dispatchers.IO) {
            try { settingsRepository.setWakeWordEnabled(enabled) } catch (e: Exception) { Log.e(TAG, "Persist wake word enabled failed: ${e.message}") }
        }
        if (enabled && !_isListening.value && applicationContext != null) {
            startWakeWordMonitor(applicationContext!!)
        } else if (!enabled) {
            stopWakeWordMonitor()
            // Stop foreground service if no longer needed
            applicationContext?.let { stopForegroundService(it) }
        }
    }

    fun setMqttBrokerUrl(url: String) {
        _mqttBrokerUrl.value = url
        viewModelScope.launch(Dispatchers.IO) {
            try {
                settingsRepository.setMqttBrokerUrl(url)
                reconnectSmartHome()
            } catch (e: Exception) { Log.e(TAG, "Persist MQTT broker URL failed: ${e.message}") }
        }
    }

    fun setMqttUsername(u: String) {
        _mqttUsername.value = u
        viewModelScope.launch(Dispatchers.IO) {
            try {
                settingsRepository.setMqttUsername(u)
                reconnectSmartHome()
            } catch (e: Exception) { Log.e(TAG, "Persist MQTT username failed: ${e.message}") }
        }
    }

    fun setMqttPassword(p: String) {
        _mqttPassword.value = p
        viewModelScope.launch(Dispatchers.IO) {
            try {
                settingsRepository.setMqttPassword(p)
                reconnectSmartHome()
            } catch (e: Exception) { Log.e(TAG, "Persist MQTT password failed: ${e.message}") }
        }
    }

    fun setHomeAssistantUrl(url: String) {
        _homeAssistantUrl.value = url
        viewModelScope.launch(Dispatchers.IO) {
            try {
                settingsRepository.setHomeAssistantUrl(url)
                reconnectSmartHome()
            } catch (e: Exception) { Log.e(TAG, "Persist Home Assistant URL failed: ${e.message}") }
        }
    }

    fun setHomeAssistantToken(token: String) {
        _homeAssistantToken.value = token
        viewModelScope.launch(Dispatchers.IO) {
            try {
                settingsRepository.setHomeAssistantToken(token)
                reconnectSmartHome()
            } catch (e: Exception) { Log.e(TAG, "Persist Home Assistant token failed: ${e.message}") }
        }
    }

    fun setKeepAliveEnabled(enabled: Boolean) {
        _isKeepAliveEnabled.value = enabled
        viewModelScope.launch(Dispatchers.IO) {
            try { settingsRepository.setKeepAliveEnabled(enabled) } catch (e: Exception) { Log.e(TAG, "Persist keep-alive enabled failed: ${e.message}") }
        }
    }

    fun updateBatteryOptimized(v: Boolean) { _isBatteryOptimized.value = v }
    fun updateShizukuAvailable(v: Boolean) { _isShizukuAvailable.value = v }

    // ═══════════════════════════════════════════════════════════════════════
    // MESSAGE HELPERS — With Room DB Persistence
    // ═══════════════════════════════════════════════════════════════════════

    // Current active session ID for Room DB
    @Volatile
    private var currentSessionId: Long = -1L
        set(value) {
            field = value
            _currentSessionIdFlow.value = value
        }

    private fun addUserMessage(text: String) {
        _messages.value += ChatMessage(id = nextMessageId++, content = text, isFromUser = true)
        // Persist to Room DB
        persistMessage("user", text, "neutral")
        // MEMORY: Extract and store memory tags from user message
        extractMemoryTagsAsync(text)
    }

    private fun addAssistantMessage(text: String, emotion: String) {
        _messages.value += ChatMessage(id = nextMessageId++, content = text, isFromUser = false, emotion = emotion)
        // Persist to Room DB
        persistMessage("model", text, emotion)
    }

    /**
     * Extract memory tags from a user message asynchronously.
     * Tags are stored in the memory_tags table for semantic recall.
     */
    private fun extractMemoryTagsAsync(userMessage: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplicationContext() ?: return@launch
                val dao = com.jarvis.assistant.data.local.JarvisDatabase.getInstance(ctx).messageDao()
                // Find the most recent user message to get its ID
                if (currentSessionId < 0) return@launch
                val messages = dao.getLastMessages(currentSessionId, 1)
                val latestMsg = messages.lastOrNull { it.role == "user" } ?: return@launch
                // Get last assistant message for context
                val lastAssistantMsg = messages.lastOrNull { it.role == "model" }?.content ?: ""
                ConversationMemory.extractAndStoreTags(
                    userMessage, lastAssistantMsg, latestMsg.id, ctx
                )
            } catch (e: Exception) {
                Log.e(TAG, "[extractMemoryTagsAsync] Error: ${e.message}")
            }
        }
    }

    /**
     * Persist a message to the Room Database.
     * Ensures JARVIS never forgets — even across app restarts.
     */
    private fun persistMessage(role: String, content: String, emotion: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplicationContext() ?: return@launch
                val dao = com.jarvis.assistant.data.local.JarvisDatabase.getInstance(ctx).messageDao()

                // Ensure we have an active session
                if (currentSessionId < 0) {
                    val existingSession = dao.getLatestSession()
                    if (existingSession != null) {
                        currentSessionId = existingSession.id
                    } else {
                        // Create a new session
                        val title = if (role == "user") content.take(50) else "New Chat"
                        val sessionId = dao.insertSession(
                            com.jarvis.assistant.data.local.SessionEntity(
                                title = title,
                                preview = content.take(100),
                                messageCount = 1
                            )
                        )
                        currentSessionId = sessionId
                    }
                }

                // Insert the message
                dao.insertMessage(
                    com.jarvis.assistant.data.local.MessageEntity(
                        sessionId = currentSessionId,
                        role = role,
                        content = content,
                        emotion = emotion
                    )
                )

                // Update session metadata
                val count = dao.getMessageCount(currentSessionId)
                dao.updateSessionMetadata(
                    sessionId = currentSessionId,
                    preview = content.take(100),
                    messageCount = count,
                    lastActivityTimestamp = System.currentTimeMillis()
                )

                // Set title from first user message if still default
                if (role == "user") {
                    val session = dao.getSession(currentSessionId)
                    if (session != null && session.title == "New Chat") {
                        dao.updateSessionTitle(currentSessionId, content.take(50))
                    }
                }

                Log.d(TAG, "[persistMessage] Persisted $role message to session $currentSessionId (total: $count)")

                // Refresh sessions list so the drawer shows updated data
                loadChatSessions()
            } catch (e: Exception) {
                Log.e(TAG, "[persistMessage] Failed to persist: ${e.message}")
            }
        }
    }

    /**
     * Load chat history from Room DB on startup.
     * Restores the last 20 messages so JARVIS never forgets.
     */
    private fun loadChatHistoryFromDb() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplicationContext() ?: return@launch
                val dao = com.jarvis.assistant.data.local.JarvisDatabase.getInstance(ctx).messageDao()

                // Get the latest session
                val session = dao.getLatestSession()
                if (session != null) {
                    currentSessionId = session.id
                    val messages = dao.getLastMessages(session.id, MAX_HISTORY_ENTRIES)
                    if (messages.isNotEmpty()) {
                        val chatMessages = messages.map { entity ->
                            ChatMessage(
                                id = nextMessageId++,
                                content = entity.content,
                                isFromUser = entity.role == "user",
                                emotion = entity.emotion,
                                timestamp = entity.timestamp
                            )
                        }
                        _messages.value = chatMessages
                        Log.i(TAG, "[loadChatHistory] Loaded ${chatMessages.size} messages from session '${session.title}'")
                    }
                }

                // Also load sessions list for the drawer
                loadChatSessions()
            } catch (e: Exception) {
                Log.e(TAG, "[loadChatHistory] Failed: ${e.message}")
            }
        }
    }

    /**
     * Load all chat sessions from Room DB for the drawer.
     * Maps SessionEntity objects to ChatSession UI models.
     */
    fun loadChatSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplicationContext() ?: return@launch
                val dao = com.jarvis.assistant.data.local.JarvisDatabase.getInstance(ctx).messageDao()

                val sessions = dao.getAllSessions()
                val chatSessions = sessions.map { entity ->
                    // Load a few messages for preview
                    val msgs = dao.getLastMessages(entity.id, 50)
                    val chatMessages = msgs.map { msg ->
                        ChatMessage(
                            id = msg.id,
                            content = msg.content,
                            isFromUser = msg.role == "user",
                            emotion = msg.emotion,
                            timestamp = msg.timestamp
                        )
                    }
                    com.jarvis.assistant.ui.screens.ChatSession(
                        id = entity.id,
                        title = entity.title,
                        preview = entity.preview,
                        timestamp = entity.lastActivityTimestamp,
                        messages = chatMessages
                    )
                }
                _chatSessions.value = chatSessions
                Log.d(TAG, "[loadChatSessions] Loaded ${chatSessions.size} sessions")
            } catch (e: Exception) {
                Log.e(TAG, "[loadChatSessions] Failed: ${e.message}")
            }
        }
    }

    /**
     * Start a new chat session.
     * Creates a new session in Room DB, clears current messages, updates currentSessionId.
     */
    fun startNewChat() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplicationContext() ?: return@launch
                val dao = com.jarvis.assistant.data.local.JarvisDatabase.getInstance(ctx).messageDao()

                // Create a new session
                val sessionId = dao.insertSession(
                    com.jarvis.assistant.data.local.SessionEntity(
                        title = "New Chat",
                        preview = "",
                        messageCount = 0
                    )
                )
                currentSessionId = sessionId
                _messages.value = emptyList()
                nextMessageId = 0L
                Log.i(TAG, "[startNewChat] Created new session: $sessionId")

                // Refresh sessions list
                loadChatSessions()
            } catch (e: Exception) {
                Log.e(TAG, "[startNewChat] Failed: ${e.message}")
            }
        }
    }

    /**
     * Load a specific chat session from Room DB.
     * Switches the current session and loads all its messages.
     */
    fun loadSession(session: com.jarvis.assistant.ui.screens.ChatSession) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplicationContext() ?: return@launch
                val dao = com.jarvis.assistant.data.local.JarvisDatabase.getInstance(ctx).messageDao()

                currentSessionId = session.id

                // Load all messages for this session
                val messages = dao.getAllMessagesForSession(session.id)
                val chatMessages = messages.map { entity ->
                    ChatMessage(
                        id = nextMessageId++,
                        content = entity.content,
                        isFromUser = entity.role == "user",
                        emotion = entity.emotion,
                        timestamp = entity.timestamp
                    )
                }
                _messages.value = chatMessages
                Log.i(TAG, "[loadSession] Loaded ${chatMessages.size} messages from session '${session.title}'")

                // Refresh sessions list to update selection
                loadChatSessions()
            } catch (e: Exception) {
                Log.e(TAG, "[loadSession] Failed: ${e.message}")
            }
        }
    }

    /**
     * Clear all chat history — deletes all sessions and messages from Room DB.
     */
    fun clearAllHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplicationContext() ?: return@launch
                val dao = com.jarvis.assistant.data.local.JarvisDatabase.getInstance(ctx).messageDao()

                dao.deleteAllSessions()
                currentSessionId = -1L
                _messages.value = emptyList()
                nextMessageId = 0L
                Log.i(TAG, "[clearAllHistory] All sessions and messages deleted")

                // Refresh sessions list (now empty)
                loadChatSessions()
            } catch (e: Exception) {
                Log.e(TAG, "[clearAllHistory] Failed: ${e.message}")
            }
        }
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
    private suspend fun playMp3AudioAsync(mp3Bytes: ByteArray, context: Context, fallbackText: String = "") {
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
                _brainState.value = if (_isVoiceMode.value) BrainState.LISTENING else BrainState.IDLE
                _audioAmplitude.value = 0f
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
                Log.d(AUDIO_TAG, "[playMp3AudioAsync] MediaPlayer playback COMPLETED — voiceMode=${_isVoiceMode.value}")
                _audioAmplitude.value = 0f
                amplitudePulseJob?.cancel()
                amplitudePulseJob = null
                currentTtsTempPath = null
                isProcessing.set(false)  // Release processing lock
                try {
                    tmp.delete()
                    Log.d(AUDIO_TAG, "[playMp3AudioAsync] Temp file deleted on completion")
                } catch (e: Exception) {
                    Log.w(AUDIO_TAG, "[playMp3AudioAsync] Temp file delete failed: ${e.message}")
                }
                if (_isVoiceMode.value) {
                    _brainState.value = BrainState.LISTENING
                    restartListeningAfterTts()
                } else {
                    _brainState.value = BrainState.IDLE
                }
            }

            player.setOnErrorListener { _, what, extra ->
                Log.e(AUDIO_TAG, "[playMp3AudioAsync] MediaPlayer ERROR: what=$what extra=$extra")
                _brainState.value = if (_isVoiceMode.value) BrainState.LISTENING else BrainState.ERROR
                _audioAmplitude.value = 0f
                try {
                    tmp.delete()
                    currentTtsTempPath = null
                } catch (_: Exception) {}
                amplitudePulseJob?.cancel()
                amplitudePulseJob = null
                // FIX #2: If MediaPlayer fails (e.g., corrupt MP3 from ElevenLabs error),
                // immediately fall back to Android native TTS so JARVIS always speaks.
                if (fallbackText.isNotBlank()) {
                    Log.w(AUDIO_TAG, "[playMp3AudioAsync] MediaPlayer failed — falling back to native TTS for: \"${fallbackText.take(40)}\"")
                    viewModelScope.launch(Dispatchers.Main) {
                        fallbackToNativeTts(fallbackText, context)
                    }
                } else {
                    isProcessing.set(false)
                    toast(context, "MediaPlayer error: what=$what extra=$extra")
                }
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
                _brainState.value = if (_isVoiceMode.value) BrainState.LISTENING else BrainState.ERROR
                _audioAmplitude.value = 0f
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
        isProcessing.set(false)  // Reset processing lock
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

        overlayManager?.hide()
        overlayManager = null

        try { MqttManager.disconnect() } catch (_: Exception) {}
        try { RustBridge.shutdown() } catch (_: Exception) {}

        // A4: Shutdown native TTS
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (_: Exception) {}

        ShizukuManager.setOnShizukuStateChangedListener {}

        // Feature: Stop proactive device monitor
        try {
            ProactiveDeviceMonitor.onAlertCallback = null
            ProactiveDeviceMonitor.stopMonitoring()
        } catch (_: Exception) {}

        // FIX #4: Release ToneGenerator
        try {
            toneGenerator?.release()
            toneGenerator = null
        } catch (_: Exception) {}
    }

    class Factory(private val repo: SettingsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(c: Class<T>): T = JarvisViewModel(repo) as T
    }
}
