package com.jarvis.assistant.ai

import android.content.Context
import com.jarvis.assistant.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Optional multimodal Gemini client.
 * When an API key is configured, JARVIS sends the RAW AUDIO of your utterance
 * (plus recognized text + detected emotion) to Gemini, so it truly "hears" you.
 * Vision mode sends camera photos too. Without a key, everything runs 100% on-device.
 */
class GeminiClient(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val app get() = context.applicationContext as JarvisApp

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /**
     * @return reply text, or null if not configured / failed
     */
    suspend fun ask(
        systemPrompt: String,
        userText: String,
        userEmotion: String,
        audioFile: File?
    ): String? = withContext(Dispatchers.IO) {
        val settings = app.settings.settings.first()
        val key = settings.geminiKey
        if (key.isBlank()) return@withContext null

        try {
            val parts = JSONArray()

            val audioPart = buildAudioPart(audioFile)
            if (audioPart != null) parts.put(audioPart)

            parts.put(
                JSONObject().put(
                    "text",
                    "User said (speech-to-text): \"$userText\"\n" +
                        "Emotion detected from the audio: $userEmotion\n" +
                        "Respond naturally to this person."
                )
            )

            generate(settings.geminiModel, key, systemPrompt, parts)
        } catch (e: Exception) {
            null
        }
    }

    /** Vision mode — describe an image. */
    suspend fun askVision(imageFile: File, prompt: String): String? = withContext(Dispatchers.IO) {
        val settings = app.settings.settings.first()
        val key = settings.geminiKey
        if (key.isBlank() || !imageFile.exists()) return@withContext null
        if (imageFile.length() > 4_000_000) return@withContext null

        try {
            val parts = JSONArray()
            val encoded = Base64.getEncoder().encodeToString(imageFile.readBytes())
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", encoded)
                )
            )
            parts.put(JSONObject().put("text", prompt))

            generate(settings.geminiModel, key, null, parts)
        } catch (e: Exception) {
            null
        }
    }

    private fun generate(model: String, key: String, systemPrompt: String?, parts: JSONArray): String? {
        val body = JSONObject()
        if (systemPrompt != null) {
            body.put(
                "system_instruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            )
        }
        body.put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

        val result: String? = client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val json = JSONObject(resp.body?.string().orEmpty())
            val candidates = json.optJSONArray("candidates") ?: return@use null
            if (candidates.length() == 0) return@use null
            val content = candidates.getJSONObject(0).optJSONObject("content") ?: return@use null
            val partsArr = content.optJSONArray("parts") ?: return@use null
            val sb = StringBuilder()
            for (i in 0 until partsArr.length()) {
                sb.append(partsArr.getJSONObject(i).optString("text"))
            }
            val resultText = sb.toString().trim()
            if (resultText.isEmpty()) null else resultText
        }
        return result
    }

    private fun buildAudioPart(audioFile: File?): JSONObject? {
        if (audioFile == null || !audioFile.exists()) return null
        if (audioFile.length() > 1_500_000) return null // keep requests light
        val encoded = try {
            Base64.getEncoder().encodeToString(audioFile.readBytes())
        } catch (e: Exception) {
            null
        } ?: return null
        return JSONObject()
            .put("inline_data", JSONObject()
                .put("mime_type", "audio/wav")
                .put("data", encoded))
    }
}
