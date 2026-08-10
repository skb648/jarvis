package com.jarvis.assistant

import android.app.Application
import com.jarvis.assistant.ai.JarvisBrain
import com.jarvis.assistant.audio.AudioCapturer
import com.jarvis.assistant.core.ConversationStore
import com.jarvis.assistant.core.MemoryStore
import com.jarvis.assistant.core.NotificationHelper
import com.jarvis.assistant.core.SettingsStore
import com.jarvis.assistant.tts.EmotionalTtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * JARVIS — application entry point.
 * App-level singletons so the foreground service, receivers and UI share one state.
 */
class JarvisApp : Application() {

    lateinit var settings: SettingsStore
        private set
    lateinit var tts: EmotionalTtsEngine
        private set
    lateinit var brain: JarvisBrain
        private set
    lateinit var memory: MemoryStore
        private set
    val capturer: AudioCapturer by lazy { AudioCapturer() }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        settings = SettingsStore(this)
        memory = MemoryStore(this)
        tts = EmotionalTtsEngine(this)
        brain = JarvisBrain(this)

        // Keep the TTS engine in sync with settings
        appScope.launch {
            settings.settings.collect { s ->
                tts.baseRate = s.baseRate
                tts.language = s.language
                tts.whisperMode = s.whisperMode
                tts.preferredVoiceId = s.preferredVoice.ifBlank { null }
            }
        }
    }

    override fun onTerminate() {
        appScope.cancel()
        super.onTerminate()
    }
}
