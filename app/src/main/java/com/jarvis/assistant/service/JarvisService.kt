package com.jarvis.assistant.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.SpeechRecognizer
import androidx.core.app.ServiceCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.SpeechToText
import com.jarvis.assistant.audio.AudioCapturer
import com.jarvis.assistant.audio.AudioFeatures
import com.jarvis.assistant.audio.EmotionAnalyzer
import com.jarvis.assistant.audio.WakeWordDetector
import com.jarvis.assistant.control.FindMyPhone
import com.jarvis.assistant.core.NotificationHelper
import com.jarvis.assistant.core.Settings
import com.jarvis.assistant.model.Emotion
import com.jarvis.assistant.service.JarvisEvents.JarvisState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.ArrayDeque

/**
 * The always-on JARVIS service.
 *
 * State machine:
 *   WAKE      -> wake word detection (custom MFCC+DTW detector when trained,
 *                else on-device ASR "Hey Jarvis" loop)
 *   LISTENING -> microphone recording your command + feeling your emotion
 *   THINKING  -> brain processing (intent engine or Gemini)
 *   SPEAKING  -> emotional voice reply
 *   IDLE      -> waiting for manual activation (auto-sleeps after inactivity)
 */
class JarvisService : Service() {

    companion object {
        private const val ACTION_START = "com.jarvis.assistant.ACTION_START"
        const val ACTION_STOP = NotificationHelper.ACTION_STOP
        const val ACTION_TALK = NotificationHelper.ACTION_TALK
        const val ACTION_STOP_LISTENING = "com.jarvis.assistant.ACTION_STOP_LISTENING"
        const val ACTION_TEXT = "com.jarvis.assistant.ACTION_TEXT"

        fun start(context: Context) {
            val i = Intent(context, JarvisService::class.java).setAction(ACTION_START)
            context.startForegroundService(i)
        }
    }

    private val app get() = application as JarvisApp
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var asr: SpeechToText? = null
    private var micAttached = false
    private var chunkListener: AudioCapturer.Listener? = null
    private var rawListener: AudioCapturer.RawListener? = null
    private var running = false
    private var processing = false

    private var state: JarvisState = JarvisState.IDLE
    private var lastPartial = ""
    private var watchDog: Runnable? = null
    private var restartWake: Runnable? = null
    private var sleepCheck: Runnable? = null
    private var lastActivityMs = 0L

    private val emotionResults = ArrayList<EmotionAnalyzer.Result>(64)
    private val emotionSmoother = ArrayDeque<Emotion>(9)
    private var lastMoodAlert = 0L
    private var collectedJob: Job? = null

    // settings cache
    private var wakeEnabled = true
    private var autoListen = true
    private var chimeEnabled = true
    private var strictMic = false
    private var wakeTrained = false
    private var autoSleepMin = 25

    private val wakeDetector = WakeWordDetector()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TALK -> {
                ensureRunning()
                startListening()
            }
            ACTION_STOP_LISTENING -> {
                if (state == JarvisState.LISTENING) {
                    asr?.stopListening()
                }
            }
            ACTION_TEXT -> {
                ensureRunning()
                val text = intent.getStringExtra("text").orEmpty()
                if (text.isNotBlank()) processUtterance(text, Emotion.NEUTRAL, null)
            }
            else -> ensureRunning()
        }
        return START_STICKY
    }

    // ------------------------------------------------------------- startup

    private fun ensureRunning() {
        if (running) {
            refreshNotification()
            if (state == JarvisState.IDLE && wakeEnabled) enterWake()
            return
        }
        running = true
        ServiceCompat.startForeground(
            this, 1,
            NotificationHelper.serviceNotification(this, "JARVIS online."),
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else 0
        )
        collectSettings()
        attachMic()
        app.tts.warmup() // latency: pre-load the voice engine
        if (wakeEnabled) enterWake() else setState(JarvisState.IDLE)
        refreshNotification()
    }

    private fun collectSettings() {
        collectedJob?.cancel()
        collectedJob = scope.launch {
            app.settings.settings.collect { s: Settings ->
                wakeEnabled = s.wakeWordEnabled
                autoListen = s.autoListen
                chimeEnabled = s.chimeEnabled
                strictMic = s.strictMicMode
                autoSleepMin = s.autoSleepMinutes
                asr?.language = s.language
                if (s.wakeTrained != wakeTrained) {
                    wakeTrained = s.wakeTrained
                    if (wakeTrained) loadWakeTemplate() else wakeDetector.reset()
                    if (running && state == JarvisState.WAKE) enterWake()
                }
            }
        }
    }

    private fun loadWakeTemplate() {
        val file = File(filesDir, "jarvis_wake_template.bin")
        wakeDetector.loadTemplate(file)
    }

    private fun attachMic() {
        if (micAttached) return
        micAttached = true
        chunkListener = AudioCapturer.Listener { features: AudioFeatures ->
            handleChunk(features)
        }
        rawListener = AudioCapturer.RawListener { samples: ShortArray ->
            if (state == JarvisState.WAKE && wakeTrained) {
                wakeDetector.feedChunk(samples)
            }
        }
        wakeDetector.listener = object : WakeWordDetector.Listener {
            override fun onWakeWordDetected() {
                wakeTriggered()
            }
        }
        app.capturer.addListener(chunkListener!!)
        app.capturer.addRawListener(rawListener!!)
        app.capturer.start()
    }

    private fun refreshNotification() {
        val text = when (state) {
            JarvisState.WAKE -> if (wakeTrained) "Apna wake word bolo — main sun raha hoon (on-device)" else "\"Hey Jarvis\" bolo — main sun raha hoon"
            JarvisState.LISTENING -> "Sun raha hoon... aapki awaaz me emotion feel kar raha hoon"
            JarvisState.THINKING -> "Soch raha hoon..."
            JarvisState.SPEAKING -> "Bol raha hoon..."
            JarvisState.IDLE -> "Tap karke baat shuru karo"
        }
        ServiceCompat.startForeground(
            this, 1,
            NotificationHelper.serviceNotification(this, text),
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else 0
        )
    }

    // ------------------------------------------------------------ emotion

    private fun handleChunk(features: AudioFeatures) {
        val result = EmotionAnalyzer.classify(features)

        // emotion smoothing: mode of the last 9 chunks prevents flicker
        emotionSmoother.addLast(result.emotion)
        if (emotionSmoother.size > 9) emotionSmoother.removeFirst()
        val smoothed = emotionSmoother.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: result.emotion

        JarvisEvents.emit(
            JarvisEvents.Event.EmotionTick(smoothed, result.confidence, features.rms)
        )
        if (state == JarvisState.LISTENING) {
            emotionResults.add(result)
            if (emotionResults.size > 96) emotionResults.removeAt(0)
        }
        // Ambient mood watch (only while idle/wake)
        if (state == JarvisState.WAKE || state == JarvisState.IDLE) {
            checkMoodWatch(result)
        }
    }

    private fun checkMoodWatch(result: EmotionAnalyzer.Result) {
        val moodWatch = runCatching { kotlinx.coroutines.runBlocking { app.settings.settings.first().moodWatch } }
            .getOrDefault(false)
        if (!moodWatch) return
        if (result.emotion != Emotion.STRESSED && result.emotion != Emotion.ANGRY) return
        val now = System.currentTimeMillis()
        if (now - lastMoodAlert < 60_000) return
        lastMoodAlert = now
        val nm = getSystemService(android.app.NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        nm.notify(
            9001,
            NotificationHelper.commandNotification(
                this,
                "JARVIS Mood Watch",
                "Aap thode ${result.emotion.label.lowercase()} lag rahe ho... sab theek hai na, boss?"
            )
        )
    }

    // ------------------------------------------------------------- states

    private fun setState(s: JarvisState) {
        if (state == s) return
        state = s
        JarvisEvents.emit(JarvisEvents.Event.State(s))
        refreshNotification()
    }

    private fun enterWake() {
        setState(JarvisState.WAKE)
        markActivity()
        scheduleSleepCheck()
        if (wakeTrained) {
            // custom on-device detector is always listening via raw chunks
            wakeDetector.reset()
        } else {
            restartWake?.let { mainHandler.removeCallbacks(it) }
            restartWake = Runnable { startWakeAsr() }
            mainHandler.postDelayed(restartWake!!, 350)
        }
    }

    private fun scheduleSleepCheck() {
        sleepCheck?.let { mainHandler.removeCallbacks(it) }
        sleepCheck = Runnable {
            if (!running) return@Runnable
            val idleMs = System.currentTimeMillis() - lastActivityMs
            if (state == JarvisState.WAKE && idleMs > autoSleepMin * 60_000L) {
                // battery saver: go quiet until next activation
                asr?.cancel()
                setState(JarvisState.IDLE)
            } else if (running) {
                scheduleSleepCheck()
            }
        }
        mainHandler.postDelayed(sleepCheck!!, 60_000)
    }

    private fun markActivity() {
        lastActivityMs = System.currentTimeMillis()
    }

    private fun startWakeAsr() {
        if (!running || state != JarvisState.WAKE || wakeTrained) return
        val sr = asr ?: SpeechToText(this).also { asr = it }
        sr.language = runCatching { kotlinx.coroutines.runBlocking { app.settings.settings.first().language } }
            .getOrDefault("en-IN")
        sr.start(object : SpeechToText.Listener {
            override fun onListening() {}
            override fun onPartial(text: String) {
                if (containsWakeWord(text) && state == JarvisState.WAKE) {
                    wakeTriggered()
                }
            }

            override fun onResult(text: String) {
                if (state != JarvisState.WAKE) return
                if (containsWakeWord(text)) wakeTriggered()
                else restartWakeAsr()
            }

            override fun onError(code: Int) {
                if (state != JarvisState.WAKE) return
                if (code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    mainHandler.postDelayed({ restartWakeAsr() }, 700)
                } else {
                    restartWakeAsr()
                }
            }
        })
    }

    private fun restartWakeAsr() {
        if (!running || state != JarvisState.WAKE || wakeTrained) return
        mainHandler.postDelayed({ startWakeAsr() }, 250)
    }

    private fun containsWakeWord(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("jarvis")
    }

    private fun wakeTriggered() {
        markActivity()
        if (chimeEnabled) beep()
        startListening()
    }

    private fun beep() {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 120)
            mainHandler.postDelayed({ tg.release() }, 250)
        }
    }

    // ----------------------------------------------------------- listening

    private fun startListening() {
        if (processing) return
        if (state == JarvisState.LISTENING) return
        markActivity()
        if (state == JarvisState.WAKE) {
            asr?.cancel()
        }
        lastPartial = ""
        emotionResults.clear()
        setState(JarvisState.LISTENING)
        if (strictMic) app.capturer.stop()
        app.capturer.beginUtterance()
        val sr = asr ?: SpeechToText(this).also { asr = it }
        sr.language = runCatching { kotlinx.coroutines.runBlocking { app.settings.settings.first().language } }
            .getOrDefault("en-IN")
        sr.start(object : SpeechToText.Listener {
            override fun onListening() {}

            override fun onPartial(text: String) {
                lastPartial = text
                JarvisEvents.emit(JarvisEvents.Event.Partial(text))
            }

            override fun onResult(text: String) {
                finishListening(text)
            }

            override fun onError(code: Int) {
                if (state != JarvisState.LISTENING) return
                if (code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    mainHandler.postDelayed({ sr.start(this) }, 500)
                    return
                }
                finishListening(lastPartial)
            }
        })
        watchDog?.let { mainHandler.removeCallbacks(it) }
        watchDog = Runnable { finishListening(lastPartial) }
        mainHandler.postDelayed(watchDog!!, 12_000)
    }

    private fun finishListening(text: String) {
        if (state != JarvisState.LISTENING) return
        watchDog?.let { mainHandler.removeCallbacks(it) }
        watchDog = null
        asr?.cancel()
        val audioFile = File(cacheDir, "jarvis_last_utterance.wav")
        app.capturer.endUtterance(audioFile)
        if (strictMic && running) {
            app.capturer.start()
        }
        val aggregated = EmotionAnalyzer.aggregate(emotionResults)
        val emotion = aggregated.emotion
        emotionResults.clear()

        if (text.isBlank() && emotion == Emotion.NEUTRAL) {
            // nothing was said — go back to wake silently
            if (running) {
                if (wakeEnabled) enterWake() else setState(JarvisState.IDLE)
            }
            return
        }
        processUtterance(text, emotion, audioFile)
    }

    // ------------------------------------------------------------ thinking

    private fun processUtterance(text: String, emotion: Emotion, audioFile: File?) {
        if (processing) return
        processing = true
        markActivity()
        setState(JarvisState.THINKING)
        JarvisEvents.emit(JarvisEvents.Event.UserMsg(text, emotion))

        scope.launch {
            val response = try {
                app.brain.processUtterance(text, emotion, audioFile) { step ->
                    JarvisEvents.emit(JarvisEvents.Event.AgentStep(step))
                }
            } catch (e: Exception) {
                com.jarvis.assistant.ai.JarvisBrain.BrainResponse(
                    "Kuch gadbad ho gayi, boss. Thodi der baad try karte hain.",
                    Emotion.NEUTRAL
                )
            }
            JarvisEvents.emit(JarvisEvents.Event.JarvisMsg(response.text, response.emotion))
            setState(JarvisState.SPEAKING)
            app.tts.speak(
                response.text,
                response.emotion,
                object : com.jarvis.assistant.tts.EmotionalTtsEngine.Callback {
                    override fun onError(message: String) {
                        afterSpeaking()
                    }

                    override fun onDone() {
                        afterSpeaking()
                    }
                }
            )
        }
    }

    private fun afterSpeaking() {
        processing = false
        markActivity()
        if (!running) return
        if (autoListen) {
            startListening()
        } else if (wakeEnabled) {
            enterWake()
        } else {
            setState(JarvisState.IDLE)
        }
    }

    // -------------------------------------------------------------- shutdown

    private fun shutdown() {
        running = false
        processing = false
        watchDog?.let { mainHandler.removeCallbacks(it) }
        restartWake?.let { mainHandler.removeCallbacks(it) }
        sleepCheck?.let { mainHandler.removeCallbacks(it) }
        asr?.destroy()
        asr = null
        chunkListener?.let { app.capturer.removeListener(it) }
        chunkListener = null
        rawListener?.let { app.capturer.removeRawListener(it) }
        rawListener = null
        micAttached = false
        app.capturer.stop()
        app.tts.stop()
        FindMyPhone.stop()
        setState(JarvisState.IDLE)
    }

    override fun onDestroy() {
        shutdown()
        scope.cancel()
        super.onDestroy()
    }
}
