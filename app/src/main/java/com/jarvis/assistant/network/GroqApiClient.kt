package com.jarvis.assistant.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * GroqApiClient — Centralized Groq API client using the OpenAI-compatible chat completions endpoint.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * KEY FEATURES:
 *
 *  1. OpenAI-COMPATIBLE FORMAT:
 *     - Uses /openai/v1/chat/completions endpoint
 *     - Supports function/tool calling in OpenAI format
 *     - Messages with role: system, user, assistant, tool
 *     - Bearer token authentication
 *
 *  2. AUTOMATIC RETRY with exponential backoff:
 *     - 503 Service Unavailable → retry after 1s, 2s, 4s
 *     - 429 Rate Limited → retry after 2s, 4s, 8s
 *     - 500 Internal Server Error → retry after 1s, 2s, 4s
 *     - Network timeouts → retry after 1s, 2s
 *
 *  3. MODEL FALLBACK:
 *     - llama-3.1-8b-instant (fastest, 14,400 req/day free)
 *     - mixtral-8x7b-32768 (larger context window)
 *     - llama-3.3-70b-versatile (most capable)
 *
 *  4. OKHTTP CLIENT:
 *     - Connection timeout: 15s, Read timeout: 90s, Write timeout: 30s
 *
 *  5. GROQ FREE TIER:
 *     - 14,400 requests/day
 *     - Chat, Vision, STT (Whisper) all included
 *     - Function/tool calling supported
 * ═══════════════════════════════════════════════════════════════════════
 */
object GroqApiClient {

    private const val TAG = "GroqApiClient"

    /** Maximum number of retry attempts per request */
    private const val MAX_RETRIES = 3

    /** Groq API base URL — OpenAI-compatible endpoint */
    const val BASE_URL = "https://api.groq.com/openai/v1/chat/completions"

    /** Groq Whisper STT endpoint */
    const val STT_URL = "https://api.groq.com/openai/v1/audio/transcriptions"

    /** Model fallback list — tried in order until one succeeds */
    val GROQ_MODELS = listOf(
        "llama-3.1-8b-instant",
        "mixtral-8x7b-32768",
        "llama-3.3-70b-versatile"
    )

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /** Shared OkHttp client with proper timeouts */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Shorter-timeout client for quick API key tests */
    private val testHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** Client for Whisper STT requests — longer write timeout for audio uploads */
    private val sttHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Result of a Groq API call.
     */
    sealed class ApiResult {
        data class Success(val responseBody: String, val model: String) : ApiResult()
        data class HttpError(val code: Int, val message: String) : ApiResult()
        data class NetworkError(val message: String) : ApiResult()
    }

    /**
     * Inject/replace the model field in a JSON request body.
     * The model name goes in the JSON body (not the URL) for OpenAI-compatible APIs.
     */
    private fun injectModel(requestBody: String, model: String): String {
        return try {
            val json = JSONObject(requestBody)
            json.put("model", model)
            json.toString()
        } catch (e: Exception) {
            Log.w(TAG, "[injectModel] Failed to inject model, using body as-is: ${e.message}")
            requestBody
        }
    }

    /**
     * Send a POST request to the Groq API with automatic retry and model fallback.
     *
     * IMPORTANT: The request body MUST contain a "model" field. If the model
     * specified in the body fails, we replace it with fallback models and retry.
     *
     * @param requestBody JSON request body (must include "model" field)
     * @param apiKey Groq API key
     * @param maxRetries Maximum retry attempts per model
     * @return ApiResult with the response or error
     */
    suspend fun chatCompletion(
        requestBody: String,
        apiKey: String,
        maxRetries: Int = MAX_RETRIES
    ): ApiResult = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isBlank()) return@withContext ApiResult.NetworkError("API key is empty")

        var lastError: ApiResult = ApiResult.NetworkError("No models available")

        for (model in GROQ_MODELS) {
            // Inject the current model into the request body
            val bodyWithModel = injectModel(requestBody, model)
            var retries = 0

            while (retries < maxRetries) {
                try {
                    val request = Request.Builder()
                        .url(BASE_URL)
                        .addHeader("Authorization", "Bearer $trimmedKey")
                        .addHeader("Content-Type", "application/json")
                        .post(bodyWithModel.toRequestBody(JSON_MEDIA_TYPE))
                        .build()

                    val response = httpClient.newCall(request).execute()

                    try {
                        when (response.code) {
                            200 -> {
                                val responseBody = response.body?.string() ?: ""
                                Log.i(TAG, "[chatCompletion] SUCCESS with model=$model (attempt=${retries + 1})")
                                return@withContext ApiResult.Success(responseBody, model)
                            }

                            503 -> {
                                val backoffMs = (1000L * (1 shl retries))
                                Log.w(TAG, "[chatCompletion] 503 for model=$model (attempt=${retries + 1}/$maxRetries)")
                                delay(backoffMs)
                                retries++
                            }

                            429 -> {
                                val backoffMs = (2000L * (1 shl retries))
                                Log.w(TAG, "[chatCompletion] 429 for model=$model (attempt=${retries + 1}/$maxRetries)")
                                delay(backoffMs)
                                retries++
                            }

                            500 -> {
                                val backoffMs = (1000L * (1 shl retries))
                                Log.w(TAG, "[chatCompletion] 500 for model=$model (attempt=${retries + 1}/$maxRetries)")
                                delay(backoffMs)
                                retries++
                            }

                            else -> {
                                val errorBody = try {
                                    response.body?.string() ?: ""
                                } catch (_: Exception) { "" }
                                val friendlyMsg = when (response.code) {
                                    400 -> "Bad request (400)"
                                    401 -> "Invalid API key (401)"
                                    403 -> "Not authorized (403)"
                                    404 -> "Model not found (404) — try a different model"
                                    else -> "HTTP ${response.code}"
                                }
                                Log.e(TAG, "[chatCompletion] $friendlyMsg for model=$model: ${errorBody.take(300)}")
                                lastError = ApiResult.HttpError(response.code, "$friendlyMsg — ${errorBody.take(200)}")
                                // Try next model
                                retries = maxRetries // Force exit while loop → moves to next model
                            }
                        }
                    } finally {
                        response.close()
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    Log.w(TAG, "[chatCompletion] Timeout for model=$model (attempt=${retries + 1}/$maxRetries)")
                    if (retries < maxRetries - 1) {
                        delay(1000L)
                        retries++
                    } else {
                        lastError = ApiResult.NetworkError("Timeout: ${e.message}")
                        retries = maxRetries // Force exit while loop → moves to next model
                    }
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "[chatCompletion] IO error for model=$model: ${e.message}")
                    if (retries < maxRetries - 1) {
                        delay(1000L)
                        retries++
                    } else {
                        lastError = ApiResult.NetworkError("Network error: ${e.message}")
                        retries = maxRetries // Force exit while loop → moves to next model
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[chatCompletion] Error for model=$model: ${e.message}")
                    lastError = ApiResult.NetworkError("Error: ${e.message?.take(100)}")
                    retries = maxRetries // Force exit while loop → moves to next model
                }
            }
        }

        Log.e(TAG, "[chatCompletion] ALL models failed. Last error: $lastError")
        return@withContext lastError
    }

    /**
     * Test a Groq API key by sending a minimal request.
     * Returns (success, errorMessage) pair.
     * Treats 429 (rate limited) as valid — the key works but is throttled.
     */
    suspend fun testApiKey(apiKey: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isBlank()) return@withContext Pair(false, "Key is empty")
        if (trimmedKey.length < 20) return@withContext Pair(false, "Key too short (${trimmedKey.length} chars)")

        try {
            val testBody = JSONObject().apply {
                put("model", "llama-3.1-8b-instant")
                put("messages", org.json.JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", "Say hello in one word.")
                    }
                ))
                put("max_tokens", 10)
            }.toString()

            val request = Request.Builder()
                .url(BASE_URL)
                .addHeader("Authorization", "Bearer $trimmedKey")
                .addHeader("Content-Type", "application/json")
                .post(testBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = testHttpClient.newCall(request).execute()

            response.use {
                val code = it.code

                when {
                    code == 200 -> {
                        Log.i(TAG, "[testApiKey] SUCCESS — key is valid")
                        Pair(true, "")
                    }
                    code == 429 -> {
                        // Rate limited but key IS valid
                        Log.i(TAG, "[testApiKey] 429 rate limited — key is valid but throttled")
                        Pair(true, "")
                    }
                    else -> {
                        val errorBody = try {
                            it.body?.string() ?: ""
                        } catch (_: Exception) { "" }
                        val friendlyMsg = when (code) {
                            401 -> "Invalid API key (401) — check your Groq key"
                            403 -> "Access denied (403) — key may not have Groq access"
                            404 -> "Model not found (404)"
                            else -> "HTTP $code"
                        }
                        Log.e(TAG, "[testApiKey] FAILED — $friendlyMsg: ${errorBody.take(300)}")
                        Pair(false, "$friendlyMsg — ${errorBody.take(150)}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[testApiKey] Network error: ${e.message}")
            Pair(false, "Network error: ${e.message?.take(100)}")
        }
    }

    /**
     * Transcribe audio using Groq Whisper STT endpoint.
     *
     * This consolidates the Whisper STT logic that was previously scattered in JarvisViewModel.
     * Accepts raw audio data (WAV format preferred), sends it to the Groq Whisper API,
     * and returns the transcribed text.
     *
     * @param audioData Audio bytes in WAV format (16kHz, 16-bit, mono recommended)
     * @param apiKey    Groq API key
     * @return ApiResult.Success with transcription text, or ApiResult.HttpError/NetworkError on failure
     */
    suspend fun transcribeAudio(
        audioData: ByteArray,
        apiKey: String
    ): ApiResult = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isBlank()) return@withContext ApiResult.NetworkError("API key is empty")
        if (audioData.isEmpty()) return@withContext ApiResult.NetworkError("Audio data is empty")

        try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "distil-whisper-large-v3-en")
                .addFormDataPart("response_format", "json")
                .addFormDataPart(
                    "file", "recording.wav",
                    audioData.toRequestBody("audio/wav".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url(STT_URL)
                .addHeader("Authorization", "Bearer $trimmedKey")
                .post(requestBody)
                .build()

            val response = sttHttpClient.newCall(request).execute()

            response.use {
                when (it.code) {
                    200 -> {
                        val responseBody = it.body?.string() ?: ""
                        val transcribedText = try {
                            val json = JSONObject(responseBody)
                            json.optString("text", "")
                        } catch (e: Exception) {
                            Log.e(TAG, "[transcribeAudio] JSON parse error: ${e.message}")
                            ""
                        }
                        Log.i(TAG, "[transcribeAudio] SUCCESS — transcription: \"${transcribedText.take(100)}\"")
                        ApiResult.Success(responseBody, "distil-whisper-large-v3-en")
                    }
                    else -> {
                        val errorBody = try {
                            it.body?.string() ?: ""
                        } catch (_: Exception) { "" }
                        val friendlyMsg = when (it.code) {
                            401 -> "Invalid API key (401)"
                            413 -> "Audio file too large (413)"
                            429 -> "Rate limited (429)"
                            else -> "HTTP ${it.code}"
                        }
                        Log.e(TAG, "[transcribeAudio] $friendlyMsg: ${errorBody.take(300)}")
                        ApiResult.HttpError(it.code, "$friendlyMsg — ${errorBody.take(200)}")
                    }
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "[transcribeAudio] Timeout: ${e.message}")
            ApiResult.NetworkError("Timeout: ${e.message}")
        } catch (e: java.io.IOException) {
            Log.w(TAG, "[transcribeAudio] IO error: ${e.message}")
            ApiResult.NetworkError("Network error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "[transcribeAudio] Error: ${e.message}")
            ApiResult.NetworkError("Error: ${e.message?.take(100)}")
        }
    }
}

