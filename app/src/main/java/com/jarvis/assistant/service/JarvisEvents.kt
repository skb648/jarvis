package com.jarvis.assistant.service

import com.jarvis.assistant.model.Emotion
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Bridge between the always-on JARVIS service and the Compose UI.
 */
object JarvisEvents {

    enum class JarvisState { WAKE, LISTENING, THINKING, SPEAKING, IDLE }

    sealed class Event {
        data class State(val state: JarvisState) : Event()
        data class EmotionTick(val emotion: Emotion, val confidence: Float, val rms: Float) : Event()
        data class Partial(val text: String) : Event()
        data class UserMsg(val text: String, val emotion: Emotion) : Event()
        data class JarvisMsg(val text: String, val emotion: Emotion) : Event()
        data class AgentStep(val text: String) : Event()
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 256)
    val events = _events.asSharedFlow()

    fun emit(event: Event) {
        _events.tryEmit(event)
    }
}
