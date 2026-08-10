package com.jarvis.assistant.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Wrapper around the on-device SpeechRecognizer.
 * Used for the wake word loop ("Hey Jarvis") and for command recognition.
 * All calls must happen on the main thread.
 */
class SpeechToText(private val context: Context) {

    interface Listener {
        fun onPartial(text: String)
        fun onResult(text: String)
        fun onError(code: Int)
        fun onListening()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listener: Listener? = null
    private var active = false
    var language: String = "en-IN"
        set(value) {
            field = value
            if (active) {
                cancel()
            }
        }

    private fun buildIntent(): Intent {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // "auto" = best-effort Hinglish mix: English primary, Hindi fallback
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                if (language == "auto") "en-IN" else language
            )
            if (language == "auto") {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN,en-IN")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        return intent
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            listener?.onListening()
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            active = false
            listener?.onError(error)
        }

        override fun onResults(results: Bundle?) {
            active = false
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            listener?.onResult(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) listener?.onPartial(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun start(l: Listener) {
        listener = l
        handler.post {
            if (active) return@post
            val sr = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
                recognizer = it
            }
            sr.setRecognitionListener(recognitionListener)
            val intent = buildIntent()
            active = true
            try {
                sr.startListening(intent)
            } catch (e: Exception) {
                active = false
                listener?.onError(SpeechRecognizer.ERROR_CLIENT)
            }
        }
    }

    fun stopListening() {
        handler.post {
            if (active) {
                try {
                    recognizer?.stopListening()
                } catch (_: Exception) {}
            }
        }
    }

    fun cancel() {
        handler.post {
            active = false
            try {
                recognizer?.cancel()
            } catch (_: Exception) {}
        }
    }

    fun destroy() {
        handler.post {
            active = false
            try {
                recognizer?.destroy()
            } catch (_: Exception) {}
            recognizer = null
        }
    }
}
