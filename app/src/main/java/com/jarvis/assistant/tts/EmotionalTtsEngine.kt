package com.jarvis.assistant.tts

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.model.Emotion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Emotional voice engine.
 *
 * HOW IT WORKS (no API key needed):
 *  1. The utterance is synthesized with Google's neural TTS engine
 *     (choose the en-IN / en-GB / hi-IN voice for a natural accent)
 *  2. Instead of playing it flat, JARVIS re-synthesizes it through
 *     MediaPlayer with emotion-specific parameters:
 *        HAPPY    -> +18% pitch, +12% speed, warm volume
 *        SAD      -> -18% pitch, -16% speed, soft volume, long pre-pause
 *        ANGRY    -> -8% pitch, +22% speed, full volume
 *        EXCITED  -> +30% pitch, +30% speed
 *        CALM     -> -5% pitch, -12% speed, low volume, breathing pause
 *     That pitch/speed/volume "body language" is what makes it feel human.
 *  3. AUDIO DUCKING: jab JARVIS bolta hai, music volume khud gir jaata hai
 *     aur baat khatam hote hi wapas aa jaata hai (professional assistant feel).
 *  4. OPTIONAL PRO MODE: ElevenLabs key ho to expressive neural voice,
 *     network fail hone par automatic free-engine fallback.
 */
class EmotionalTtsEngine(context: Context) {

    interface Callback {
        fun onStart() {}
        fun onDone() {}
        fun onError(message: String) {}
    }

    data class VoiceInfo(val id: String, val name: String, val quality: Int, val offline: Boolean)

    private data class SpeakTask(
        val text: String,
        val emotion: Emotion,
        val callback: Callback?
    )

    private val appContext = context.applicationContext
    private val app get() = appContext as JarvisApp
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    private var tts: TextToSpeech? = null
    private val ready = CompletableFuture<Boolean>()
    private var utteranceCounter = 0
    private var currentPlayer: MediaPlayer? = null

    private val queue = ArrayDeque<SpeakTask>()
    private val pendingById = mutableMapOf<String, SpeakTask>()
    private var busy = false
    private var ducked = false
    private var duckedVolume = -1

    @Volatile
    var baseRate: Float = 1.0f

    @Volatile
    var language: String = "en-IN"

    @Volatile
    var whisperMode: Boolean = false

    @Volatile
    var preferredVoiceId: String? = null

    // ---- v3.0 diagnostics ----
    @Volatile
    var lastEngine: String = "google"   // google | openai | elevenlabs

    @Volatile
    var lastError: String? = null

    @Volatile
    var ttsReady: Boolean = false

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureVoice()
                ready.complete(true)
            } else {
                ready.complete(false)
            }
        }
    }

    private fun configureVoice() {
        val inst = tts ?: return
        // 1) explicit user-chosen voice
        val chosen = preferredVoiceId
        if (!chosen.isNullOrBlank()) {
            val v = inst.voices?.firstOrNull { it.name == chosen }
            if (v != null) {
                runCatching { inst.voice = v }
                return
            }
        }
        // 2) language-based auto pick (offline neural first)
        val locales = listOf(language, "en-IN", "en-GB", "en-US", "hi-IN")
            .mapNotNull { runCatching { Locale.forLanguageTag(it) }.getOrNull() }
            .distinct()
        for (l in locales) {
            if (inst.setLanguage(l) != TextToSpeech.LANG_MISSING_DATA &&
                inst.setLanguage(l) != TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                val voice = inst.voices
                    ?.filter { it.locale.language == l.language && !it.isNetworkConnectionRequired }
                    ?.maxByOrNull { it.quality }
                if (voice != null) runCatching { inst.voice = voice }
                break
            }
        }
        inst.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                pendingById[utteranceId]?.callback?.onStart()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handleSynthError(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                handleSynthError(utteranceId)
            }

            override fun onDone(utteranceId: String?) {
                val task = pendingById.remove(utteranceId) ?: return
                val file = File(appContext.cacheDir, "jarvis_speech_$utteranceId.wav")
                if (file.exists() && file.length() > 44) {
                    playFile(task, file, modulate = true)
                } else {
                    finish(task, null, null)
                }
            }
        })
    }

    private fun handleSynthError(utteranceId: String?) {
        val task = pendingById.remove(utteranceId) ?: return
        File(appContext.cacheDir, "jarvis_speech_$utteranceId.wav").delete()
        directSpeak(task, "jarvis_direct_${utteranceCounter++}")
    }

    // ---------------------------------------------------------------- public

    fun speak(text: String, emotion: Emotion, callback: Callback? = null) {
        if (text.isBlank()) {
            callback?.onDone()
            return
        }
        mainHandler.post {
            queue.addLast(SpeakTask(text, emotion, callback))
            pump()
        }
    }

    fun stop() {
        mainHandler.post {
            try {
                currentPlayer?.stop()
                currentPlayer?.release()
            } catch (_: Exception) {}
            currentPlayer = null
            try {
                tts?.stop()
            } catch (_: Exception) {}
            queue.clear()
            pendingById.clear()
            busy = false
            unDuck()
        }
    }

    fun shutdown() {
        stop()
        mainHandler.post {
            try {
                tts?.shutdown()
            } catch (_: Exception) {}
        }
    }

    fun isReady(): Boolean = ready.isDone && runCatching { ready.get() }.getOrDefault(false)

    /** Pre-warm the engine (loads voice data) so the first reply is fast. */
    fun warmup() {
        val inst = tts
        if (inst == null || !isReady()) return
        mainHandler.post {
            try {
                val id = "jarvis_warmup_${utteranceCounter++}"
                val f = File(appContext.cacheDir, "jarvis_speech_$id.wav")
                inst.synthesizeToFile("jarvis", Bundle(), f, id)
            } catch (_: Exception) {}
        }
    }

    fun availableVoices(): List<VoiceInfo> {
        val inst = tts ?: return emptyList()
        if (!isReady()) return emptyList()
        return runCatching {
            (inst.voices ?: emptySet())
                .filter { it.locale.language in setOf("en", "hi") }
                .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.name })
                .map { VoiceInfo(it.name, it.name.substringAfterLast(' '), it.quality, !it.isNetworkConnectionRequired) }
                .distinctBy { it.name }
                .take(30)
        }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------------- engine

    private fun pump() {
        if (busy) return
        val task = queue.pollFirst() ?: return
        busy = true
        execute(task)
    }

    private fun execute(task: SpeakTask) {
        val settings = runCatching { runBlocking { app.settings.settings.first() } }.getOrNull()
        val eleven = settings?.elevenLabsKey.orEmpty()
        val openai = settings?.openaiKey.orEmpty()
        when {
            eleven.isNotBlank() -> {
                lastEngine = "elevenlabs"
                cloudSpeak(task, eleven)
            }
            openai.isNotBlank() -> {
                lastEngine = "openai"
                openaiSpeak(task, openai, settings?.openaiVoice ?: "nova")
            }
            else -> {
                lastEngine = "google"
                localSpeak(task)
            }
        }
    }

    /** OpenAI TTS premium tier. */
    private fun openaiSpeak(task: SpeakTask, apiKey: String, voice: String) {
        Thread {
            val ok = runBlocking {
                OpenAITtsClient(appContext).synthesize(
                    apiKey, voice, task.text, task.emotion,
                    File(appContext.cacheDir, "jarvis_openai_${utteranceCounter++}.mp3")
                )
            }
            if (!ok) {
                lastError = "openai failed -> google fallback"
                mainHandler.post { localSpeak(task) }
            } else {
                val file = File(appContext.cacheDir, "jarvis_openai_${utteranceCounter - 1}.mp3")
                mainHandler.post { playFile(task, file, modulate = false) }
            }
        }.start()
    }

    private fun localSpeak(task: SpeakTask) {
        val inst = tts
        ttsReady = isReady()
        if (inst == null || !isReady()) {
            lastError = "TTS engine not ready"
            finish(task, null, "TTS engine not ready")
            return
        }
        val id = "jarvis_${utteranceCounter++}"
        val file = File(appContext.cacheDir, "jarvis_speech_$id.wav")
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
        }
        val result = try {
            inst.setPitch(1f)
            inst.setSpeechRate(baseRate)
            inst.synthesizeToFile(task.text, params, file, id)
        } catch (e: Exception) {
            TextToSpeech.ERROR
        }
        if (result != TextToSpeech.SUCCESS) {
            directSpeak(task, "jarvis_direct_${utteranceCounter++}")
            return
        }
        pendingById[id] = task
        // Watchdog: if onDone never fires, force-play whatever we have
        mainHandler.postDelayed({
            val still = pendingById.remove(id) ?: return@postDelayed
            if (file.exists() && file.length() > 44) {
                playFile(still, file, modulate = true)
            } else {
                directSpeak(still, "jarvis_direct_${utteranceCounter++}")
            }
        }, 12_000)
    }

    private fun directSpeak(task: SpeakTask, id: String) {
        val inst = tts ?: run {
            finish(task, null, "TTS unavailable")
            return
        }
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
        }
        pendingById[id] = task
        val result = try {
            val (pitch, rate) = effectiveParams(task.emotion)
            inst.setPitch(pitch)
            inst.setSpeechRate(rate)
            inst.speak(task.text, TextToSpeech.QUEUE_ADD, params, id)
        } catch (e: Exception) {
            TextToSpeech.ERROR
        }
        if (result != TextToSpeech.SUCCESS) {
            pendingById.remove(id)
            finish(task, null, "TTS error $result")
        } else {
            duck()
            task.callback?.onStart()
        }
    }

    /** Emotion parameters, optionally softened by whisper mode. */
    private fun effectiveParams(emotion: Emotion): Pair<Float, Float> {
        var pitch = emotion.ttsPitch
        var rate = emotion.ttsRate * baseRate
        if (whisperMode) {
            pitch *= 0.92f
            rate *= 0.85f
        }
        return pitch to rate
    }

    private fun playFile(task: SpeakTask, file: File, modulate: Boolean) {
        requestAudioFocus()
        ensureAudibleVolume()
        val player = MediaPlayer()
        try {
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener { mp ->
                if (modulate) {
                    try {
                        val (pitch, rate) = effectiveParams(task.emotion)
                        mp.playbackParams = PlaybackParams()
                            .setPitch(pitch)
                            .setSpeed(rate)
                    } catch (_: Exception) {}
                }
                var vol = task.emotion.ttsVolume
                if (whisperMode) vol *= 0.5f
                try {
                    mp.setVolume(vol, vol)
                } catch (_: Exception) {}
                duck()
                task.callback?.onStart()
                mainHandler.postDelayed({ runCatching { mp.start() } }, task.emotion.prePauseMs)
            }
            player.setOnCompletionListener { mp ->
                file.delete()
                finish(task, mp, null)
            }
            player.setOnErrorListener { mp, _, _ ->
                file.delete()
                finish(task, mp, "playback error")
                true
            }
            currentPlayer = player
            player.prepareAsync()
        } catch (e: Exception) {
            runCatching { player.release() }
            finish(task, null, e.message)
        }
    }

    // --------------------------------------------------------- audio ducking

    private fun duck() {
        if (ducked) return
        ducked = true
        try {
            val stream = AudioManager.STREAM_MUSIC
            duckedVolume = audioManager.getStreamVolume(stream)
            val max = audioManager.getStreamMaxVolume(stream)
            val low = (max * 0.20f).toInt().coerceAtLeast(1)
            audioManager.setStreamVolume(stream, low, 0)
        } catch (_: Exception) {
            ducked = false
        }
    }

    private fun unDuck() {
        if (!ducked) return
        ducked = false
        try {
            if (duckedVolume >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, duckedVolume, 0)
                duckedVolume = -1
            }
        } catch (_: Exception) {}
    }

    // -------------------------------------------------------- ElevenLabs pro

    private fun cloudSpeak(task: SpeakTask, apiKey: String) {
        Thread {
            try {
                val settings = runBlocking { app.settings.settings.first() }
                val body = JSONObject()
                    .put("text", task.text)
                    .put(
                        "voice_settings",
                        JSONObject()
                            .put("stability", 0.30f)
                            .put("similarity_boost", 0.85f)
                            .put("style", 0.55f)
                    )
                    .toString()
                val request = Request.Builder()
                    .url("https://api.elevenlabs.io/v1/text-to-speech/${settings.elevenLabsVoice}?output_format=mp3_44100_128")
                    .header("xi-api-key", apiKey)
                    .post(body.toRequestBody(jsonMedia))
                    .build()
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        mainHandler.post { localSpeak(task) }
                        return@use
                    }
                    val file = File(appContext.cacheDir, "jarvis_eleven_${utteranceCounter++}.mp3")
                    resp.body?.byteStream()?.use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                    if (file.length() > 1000) {
                        mainHandler.post { playFile(task, file, modulate = false) }
                    } else {
                        mainHandler.post { localSpeak(task) }
                    }
                }
            } catch (e: Exception) {
                mainHandler.post { localSpeak(task) }
            }
        }.start()
    }

    private fun finish(task: SpeakTask, mp: MediaPlayer?, error: String?) {
        mainHandler.post {
            try {
                mp?.release()
            } catch (_: Exception) {}
            if (currentPlayer === mp) currentPlayer = null
            if (error != null) {
                lastError = error
                task.callback?.onError(error)
            } else {
                task.callback?.onDone()
            }
            unDuck()
            abandonAudioFocus()
            busy = false
            pump()
        }
    }

    // ------------------------------------------------- audio focus + volume

    private fun requestAudioFocus() {
        runCatching {
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun abandonAudioFocus() {
        runCatching { audioManager.abandonAudioFocus(null) }
    }

    /** Agar music volume 0 hai to thoda upar karo taaki JARVIS sunai de. */
    private fun ensureAudibleVolume() {
        runCatching {
            val vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (vol == 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.25f).toInt().coerceAtLeast(1), 0)
                lastError = "volume was 0 - raised temporarily"
            }
        }
    }
}
