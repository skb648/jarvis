package com.jarvis.assistant.tts

import android.content.Context
import com.jarvis.assistant.model.Emotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * OpenAI TTS — premium, sasta, natural voice.
 * Model: gpt-4o-mini-tts (dynamic emotion instructions supported).
 * Settings me OpenAI key daalo → JARVIS automatically is tier pe shift ho jata hai.
 */
class OpenAITtsClient(context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** Synthesize to mp3 file. Returns true on success. */
    suspend fun synthesize(
        apiKey: String,
        voice: String,
        text: String,
        emotion: Emotion,
        outFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject()
                .put("model", "gpt-4o-mini-tts")
                .put("voice", voice)
                .put("input", text)
                .put(
                    "instructions",
                    "Speak like a warm human personal assistant named JARVIS. " +
                        "Tone: ${emotion.description}. Natural rhythm, slight pauses, no robotic monotone."
                )
            val request = Request.Builder()
                .url("https://api.openai.com/v1/audio/speech")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toString().toRequestBody(jsonMedia))
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val bytes = resp.body?.bytes() ?: return@use false
                if (bytes.size < 1000) return@use false
                outFile.writeBytes(bytes)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
