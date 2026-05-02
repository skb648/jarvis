package com.jarvis.assistant.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
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

                    when (response.code) {
                        200 -> {
                            val responseBody = response.body?.string() ?: ""
                            response.close()
                            Log.i(TAG, "[chatCompletion] SUCCESS with model=$model (attempt=${retries + 1})")
                            return@withContext ApiResult.Success(responseBody, model)
                        }

                        503 -> {
                            val backoffMs = (1000L * (1 shl retries))
                            Log.w(TAG, "[chatCompletion] 503 for model=$model (attempt=${retries + 1}/$maxRetries)")
                            response.close()
                            delay(backoffMs)
                            retries++
                            continue
                        }

                        429 -> {
                            val backoffMs = (2000L * (1 shl retries))
                            Log.w(TAG, "[chatCompletion] 429 for model=$model (attempt=${retries + 1}/$maxRetries)")
                            response.close()
                            delay(backoffMs)
                            retries++
                            continue
                        }

                        500 -> {
                            val backoffMs = (1000L * (1 shl retries))
                            Log.w(TAG, "[chatCompletion] 500 for model=$model (attempt=${retries + 1}/$maxRetries)")
                            response.close()
                            delay(backoffMs)
                            retries++
                            continue
                        }

                        else -> {
                            val errorBody = try {
                                response.body?.string() ?: ""
                            } catch (_: Exception) { "" }
                            response.close()
                            val friendlyMsg = when (response.code) {
                                400 -> "Bad request (400)"
                                401 -> "Invalid API key (401)"
                                403 -> "Not authorized (403)"
                                404 -> "Model not found (404) — try a different model"
                                else -> "HTTP ${response.code}"
                            }
                            Log.e(TAG, "[chatCompletion] $friendlyMsg for model=$model: ${errorBody.take(300)}")
                            lastError = ApiResult.HttpError(response.code, "$friendlyMsg — ${errorBody.take(200)}")
                            break // Try next model
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    Log.w(TAG, "[chatCompletion] Timeout for model=$model (attempt=${retries + 1}/$maxRetries)")
                    if (retries < maxRetries - 1) {
                        delay(1000L)
                        retries++
                    } else {
                        lastError = ApiResult.NetworkError("Timeout: ${e.message}")
                        break
                    }
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "[chatCompletion] IO error for model=$model: ${e.message}")
                    if (retries < maxRetries - 1) {
                        delay(1000L)
                        retries++
                    } else {
                        lastError = ApiResult.NetworkError("Network error: ${e.message}")
                        break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[chatCompletion] Error for model=$model: ${e.message}")
                    lastError = ApiResult.NetworkError("Error: ${e.message?.take(100)}")
                    break
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
    fun testApiKey(apiKey: String): Pair<Boolean, String> {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isBlank()) return Pair(false, "Key is empty")
        if (trimmedKey.length < 20) return Pair(false, "Key too short (${trimmedKey.length} chars)")

        return try {
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
            val code = response.code

            when {
                code == 200 -> {
                    response.close()
                    Log.i(TAG, "[testApiKey] SUCCESS — key is valid")
                    Pair(true, "")
                }
                code == 429 -> {
                    // Rate limited but key IS valid
                    response.close()
                    Log.i(TAG, "[testApiKey] 429 rate limited — key is valid but throttled")
                    Pair(true, "")
                }
                else -> {
                    val errorBody = try {
                        response.body?.string() ?: ""
                    } catch (_: Exception) { "" }
                    response.close()
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
        } catch (e: Exception) {
            Log.e(TAG, "[testApiKey] Network error: ${e.message}")
            Pair(false, "Network error: ${e.message?.take(100)}")
        }
    }
}
