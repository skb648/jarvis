package com.jarvis.assistant.ui

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.core.ConversationStore
import com.jarvis.assistant.model.Emotion
import com.jarvis.assistant.service.JarvisEvents
import com.jarvis.assistant.service.JarvisService
import com.jarvis.assistant.ui.theme.accentColor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.ArrayDeque

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val jarvisApp = app as JarvisApp

    enum class Role { USER, JARVIS }

    data class Message(
        val id: Long,
        val role: Role,
        val text: String,
        val emotion: Emotion
    )

    data class UiState(
        val serviceState: JarvisEvents.JarvisState = JarvisEvents.JarvisState.IDLE,
        val currentEmotion: Emotion = Emotion.NEUTRAL,
        val confidence: Float = 0f,
        val waveform: List<Float> = List(48) { 0f },
        val partial: String = "",
        val accent: Color = Color(0xFF00E5FF),
        val moodTrend: List<Emotion> = emptyList(),
        val messages: List<Message> = listOf(
            Message(
                0, Role.JARVIS,
                "Namaste boss! Main JARVIS hoon. Mic dabao ya \"Hey Jarvis\" bolo — main aapki awaaz se emotion feel karke jawab dunga. Ab main cricket, gold rate, news, smart home — sab kuch kar sakta hoon!",
                Emotion.HAPPY
            )
        )
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val waveBuffer = ArrayDeque<Float>(48)
    private val trendBuffer = ArrayDeque<Emotion>(48)
    private var nextMsgId = 1L
    private var saveJob: Job? = null
    private var saveHistoryEnabled = true
    private var historyLoaded = false

    init {
        repeat(48) { waveBuffer.addLast(0f) }
        viewModelScope.launch {
            JarvisEvents.events.collect { event -> handleEvent(event) }
        }
        // accent + history preference
        viewModelScope.launch {
            jarvisApp.settings.settings.collect { s ->
                _uiState.update { it.copy(accent = accentColor(s.accent)) }
                saveHistoryEnabled = s.saveHistory
            }
        }
        // load saved conversation
        viewModelScope.launch {
            val entries = ConversationStore.load(jarvisApp)
            if (entries.isNotEmpty()) {
                val restored = entries.map { e ->
                    Message(
                        nextMsgId++,
                        if (e.role == "user") Role.USER else Role.JARVIS,
                        e.text,
                        Emotion.fromName(e.emotion)
                    )
                }
                _uiState.update {
                    it.copy(messages = (it.messages + restored).takeLast(80))
                }
            }
            historyLoaded = true
        }
    }

    private fun handleEvent(event: JarvisEvents.Event) {
        when (event) {
            is JarvisEvents.Event.State ->
                _uiState.update { it.copy(serviceState = event.state) }

            is JarvisEvents.Event.EmotionTick -> {
                waveBuffer.addLast(event.rms)
                if (waveBuffer.size > 48) waveBuffer.removeFirst()
                trendBuffer.addLast(event.emotion)
                if (trendBuffer.size > 48) trendBuffer.removeFirst()
                _uiState.update {
                    it.copy(
                        currentEmotion = event.emotion,
                        confidence = event.confidence,
                        waveform = waveBuffer.toList(),
                        moodTrend = trendBuffer.toList()
                    )
                }
            }

            is JarvisEvents.Event.Partial ->
                _uiState.update { it.copy(partial = event.text) }

            is JarvisEvents.Event.UserMsg ->
                addMessage(Role.USER, event.text, event.emotion)

            is JarvisEvents.Event.JarvisMsg ->
                addMessage(Role.JARVIS, event.text, event.emotion)

            is JarvisEvents.Event.AgentStep ->
                _uiState.update { it.copy(partial = "⚙️ ${event.text}") }
        }
    }

    private fun addMessage(role: Role, text: String, emotion: Emotion) {
        _uiState.update {
            val newList = it.messages + Message(nextMsgId++, role, text, emotion)
            it.copy(messages = if (newList.size > 80) newList.takeLast(80) else newList)
        }
        scheduleSave()
    }

    private fun scheduleSave() {
        if (!saveHistoryEnabled) return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(1000)
            val msgs = _uiState.value.messages
            ConversationStore.save(
                jarvisApp,
                msgs.map { ConversationStore.Entry(it.role.name.lowercase(), it.text, it.emotion.name) }
            )
        }
    }

    // ------------------------------------------------------------ actions

    fun activate() = JarvisService.start(jarvisApp)

    fun deactivate() {
        jarvisApp.stopService(android.content.Intent(jarvisApp, JarvisService::class.java))
    }

    fun talk() {
        jarvisApp.startForegroundService(
            android.content.Intent(jarvisApp, JarvisService::class.java)
                .setAction(JarvisService.ACTION_TALK)
        )
    }

    fun stopListening() {
        jarvisApp.startService(
            android.content.Intent(jarvisApp, JarvisService::class.java)
                .setAction(JarvisService.ACTION_STOP_LISTENING)
        )
    }

    fun sendText(text: String) {
        if (text.isBlank()) return
        jarvisApp.startForegroundService(
            android.content.Intent(jarvisApp, JarvisService::class.java)
                .setAction(JarvisService.ACTION_TEXT)
                .putExtra("text", text)
        )
    }

    fun quickAction(kind: String) {
        val command = when (kind) {
            "timer" -> "2 minute ka timer"
            "torch" -> "torch on"
            "weather" -> "weather batao"
            "music" -> "gaana chalao"
            "cricket" -> "cricket score batao"
            "briefing" -> "daily briefing"
            else -> ""
        }
        if (command.isNotEmpty()) sendText(command)
    }

    fun clearHistory() {
        viewModelScope.launch {
            ConversationStore.clear(jarvisApp)
            _uiState.update {
                it.copy(messages = it.messages.take(1))
            }
        }
    }

    fun isActive(): Boolean = _uiState.value.serviceState != JarvisEvents.JarvisState.IDLE
}
