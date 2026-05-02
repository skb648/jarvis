package com.jarvis.assistant.jni

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * JNI bridge to the Rust native library (libjarvis_rust.so).
 *
 * Declares all external native methods and provides safe Kotlin wrappers
 * that:
 *  - Switch to [Dispatchers.IO] for ALL native calls (never blocks main thread)
 *  - Catch JNI / native crashes and return fallback values
 *  - Expose suspend-based APIs for coroutine consumers
 *
 * IMPORTANT: Every `external fun` signature MUST exactly match the
 * corresponding Rust function in lib.rs — parameter count, types,
 * and return type must align with the JNI naming convention.
 *
 * LOAD ORDER:
 *  1. System.loadLibrary("jarvis_rust") — loads the Rust .so with all JNI impls
 *  2. If that fails (Rust not built), try the CMake bridge stub
 *  3. If both fail, all native calls return fallbacks via try-catch
 */
/**
 * Result of a voice pattern detection call.
 *
 * @property id The pattern identifier (e.g., "call_answer", "call_reject")
 * @property confidence Confidence score 0.0-1.0
 */
data class VoicePatternResult(
    val id: String,
    val confidence: Float
)

object RustBridge {

    @Volatile
    private var nativeLoaded = false

    init {
        try {
            // Primary: load the Rust shared library directly.
            System.loadLibrary("jarvis_rust")
            nativeLoaded = true
            android.util.Log.i("RustBridge", "Rust native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.w("RustBridge", "libjarvis_rust.so not found, trying JNI bridge stub...")
            try {
                // Fallback: load the CMake bridge (which links Rust .so if it exists,
                // or provides a stub with safe default implementations).
                System.loadLibrary("jarvis_jni_bridge")
                // FIX v12: Do NOT set nativeLoaded = true here. The stub library's
                // nativeInitialize() returns FALSE, which tells us Rust is not really
                // available. The nativeLoaded flag should only be true when the REAL
                // Rust .so is loaded with actual AI functionality.
                // This ensures the app correctly uses Kotlin HTTP fallback.
                nativeLoaded = false
                android.util.Log.i("RustBridge", "JNI bridge stub library loaded — Rust core NOT available, using Kotlin HTTP fallback")
            } catch (e2: UnsatisfiedLinkError) {
                android.util.Log.e("RustBridge", "No native library available — using fallback mode. " +
                    "Build Rust core with: ./gradlew buildRustDebug")
                nativeLoaded = false
            }
        }
    }

    /** Check if the native Rust library is loaded and ready */
    fun isNativeReady(): Boolean = nativeLoaded

    // ─── External JNI Declarations ──────────────────────────────────
    // These MUST match the Rust function signatures in lib.rs exactly.

    @JvmStatic
    external fun nativeInitialize(geminiKey: String, elevenLabsKey: String): Boolean

    @JvmStatic
    external fun nativeProcessQuery(query: String, context: String, historyJson: String, systemPrompt: String): String

    @JvmStatic
    external fun nativeProcessQueryWithImage(query: String, imageBase64: String, mimeType: String, systemPrompt: String): String

    @JvmStatic
    external fun nativeAnalyzeAudio(audioData: ByteArray, sampleRate: Int): String

    @JvmStatic
    external fun nativeDetectWakeWord(audioData: ByteArray, sampleRate: Int): Boolean

    /**
     * Detect voice patterns (e.g., call answer/reject keywords) in audio data.
     * Returns a JSON string with pattern info, or null if no pattern detected.
     *
     * Expected JSON format: {"id": "call_answer", "confidence": 0.85}
     */
    @JvmStatic
    external fun nativeDetectVoicePattern(audioData: ByteArray, sampleRate: Int): String?

    @JvmStatic
    external fun nativeAnalyzeEmotion(text: String): String

    @JvmStatic
    external fun nativeSynthesizeSpeech(text: String, voiceId: String, stability: Float, similarityBoost: Float): String

    @JvmStatic
    external fun nativeGetAudioAmplitude(audioData: ByteArray): Double

    @JvmStatic
    external fun nativeHealthCheck(): Boolean

    @JvmStatic
    external fun nativeShutdown()

    // ─── Safe Suspend Wrappers ──────────────────────────────────────
    // ALL wrappers use Dispatchers.IO to NEVER block the main thread.
    // JNI calls involve network I/O (Gemini API, ElevenLabs TTS)
    // and can take seconds — they MUST run off the main thread.

    suspend fun initialize(geminiKey: String, elevenLabsKey: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                nativeInitialize(geminiKey, elevenLabsKey)
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e("RustBridge", "nativeInitialize: Native library not loaded — ${e.message}")
                false
            } catch (e: Exception) {
                android.util.Log.e("RustBridge", "nativeInitialize failed: ${e.message}")
                false
            }
        }
    }

    /**
     * Process a user query — runs on [Dispatchers.IO].
     * This makes network calls to Gemini API — must NEVER be on main thread.
     */
    suspend fun processQuery(query: String, context: String = "", historyJson: String = "[]", systemPrompt: String = ""): String {
        return withContext(Dispatchers.IO) {
            try {
                nativeProcessQuery(query, context, historyJson, systemPrompt)
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e("RustBridge", "nativeProcessQuery: Native library not loaded — ${e.message}")
                "[ERROR] Native library not loaded. Build Rust core with: ./gradlew buildRustDebug"
            } catch (e: Exception) {
                android.util.Log.e("RustBridge", "Query processing failed: ${e.message}")
                "[ERROR] Query processing failed: ${e.message}"
            }
        }
    }

    suspend fun processQueryWithImage(query: String, imageBase64: String, mimeType: String = "image/jpeg", systemPrompt: String = ""): String {
        return withContext(Dispatchers.IO) {
            try {
                nativeProcessQueryWithImage(query, imageBase64, mimeType, systemPrompt)
            } catch (e: UnsatisfiedLinkError) {
                "[ERROR] Native library not loaded"
            } catch (e: Exception) {
                "[ERROR] Multimodal query failed: ${e.message}"
            }
        }
    }

    suspend fun analyzeAudio(audioData: ByteArray, sampleRate: Int = 16000): String {
        return withContext(Dispatchers.IO) {
            try {
                nativeAnalyzeAudio(audioData, sampleRate)
            } catch (e: UnsatisfiedLinkError) {
                """{"error":"Native library not loaded"}"""
            } catch (e: Exception) {
                """{"error":"${e.message}"}"""
            }
        }
    }

    suspend fun detectWakeWord(audioData: ByteArray, sampleRate: Int = 16000): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                nativeDetectWakeWord(audioData, sampleRate)
            } catch (e: UnsatisfiedLinkError) {
                false
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Detect voice patterns (e.g., call answer/reject keywords) in audio data.
     * Returns a [VoicePatternResult] if a pattern is detected, or null otherwise.
     *
     * This is a safe wrapper around [nativeDetectVoicePattern] that handles
     * native library errors gracefully. The native method returns a JSON string
     * like {"id": "call_answer", "confidence": 0.85} which is parsed here.
     *
     * NOTE: This runs on [Dispatchers.IO] to avoid blocking the calling thread.
     * Previously this was a synchronous call that could block if invoked from
     * the wrong context. The AudioEngine already calls this from its IO coroutine,
     * but wrapping in withContext ensures safety from any calling context.
     */
    suspend fun detectVoicePattern(audioData: ByteArray, sampleRate: Int): VoicePatternResult? {
        return withContext(Dispatchers.IO) {
            try {
                val json = nativeDetectVoicePattern(audioData, sampleRate)
                if (json.isNullOrEmpty()) return@withContext null
                parseVoicePatternJson(json)
            } catch (e: UnsatisfiedLinkError) {
                // Native method not available — voice pattern detection not supported
                null
            } catch (e: Exception) {
                android.util.Log.w("RustBridge", "detectVoicePattern failed: ${e.message}")
                null
            }
        }
    }

    /**
     * Parse the JSON result from nativeDetectVoicePattern into a VoicePatternResult.
     * Handles malformed JSON gracefully.
     */
    private fun parseVoicePatternJson(json: String): VoicePatternResult? {
        return try {
            // Simple JSON parsing without org.json dependency issues
            // Expected format: {"id": "call_answer", "confidence": 0.85}
            val idMatch = """"id"\s*:\s*"([^"]+)"""".toRegex().find(json)
            val confMatch = """"confidence"\s*:\s*([0-9.]+)""".toRegex().find(json)
            if (idMatch != null) {
                val id = idMatch.groupValues[1]
                val confidence = confMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 0.5f
                VoicePatternResult(id, confidence)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun analyzeEmotion(text: String): String {
        return withContext(Dispatchers.IO) {
            try {
                nativeAnalyzeEmotion(text)
            } catch (e: UnsatisfiedLinkError) {
                """{"emotion":"neutral","confidence":0.5}"""
            } catch (e: Exception) {
                """{"emotion":"neutral","confidence":0.5}"""
            }
        }
    }

    /**
     * Synthesize speech — runs on [Dispatchers.IO].
     * This makes network calls to ElevenLabs API — must NEVER be on main thread.
     */
    suspend fun synthesizeSpeech(
        text: String,
        voiceId: String,
        stability: Float = 0.5f,
        similarityBoost: Float = 0.75f
    ): String? {
        return withContext(Dispatchers.IO) {
            try {
                val result = nativeSynthesizeSpeech(text, voiceId, stability, similarityBoost)
                if (result.isEmpty()) null else result
            } catch (e: UnsatisfiedLinkError) {
                null
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun getAudioAmplitude(audioData: ByteArray): Double {
        return withContext(Dispatchers.IO) {
            try {
                nativeGetAudioAmplitude(audioData)
            } catch (e: UnsatisfiedLinkError) {
                0.0
            } catch (e: Exception) {
                0.0
            }
        }
    }

    suspend fun healthCheck(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                nativeHealthCheck()
            } catch (e: UnsatisfiedLinkError) {
                false
            } catch (e: Exception) {
                false
            }
        }
    }

    fun shutdown() {
        try {
            // FIX: Call nativeShutdown() directly instead of using runBlocking
            // which can deadlock if called from a coroutine already on Dispatchers.IO.
            // nativeShutdown() is a plain JNI call — no suspension needed.
            nativeShutdown()
        } catch (e: UnsatisfiedLinkError) {
            // Library not loaded — nothing to shut down
        } catch (e: Exception) {
            // Ignore shutdown errors
        }
    }

    fun decodeBase64Audio(base64Audio: String): ByteArray {
        return Base64.decode(base64Audio, Base64.NO_WRAP)
    }
}
