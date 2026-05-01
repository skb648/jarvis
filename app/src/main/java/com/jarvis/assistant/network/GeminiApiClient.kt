package com.jarvis.assistant.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * GeminiApiClient — Centralized Gemini API client with retry logic and model fallback.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * KEY FEATURES:
 *
 *  1. AUTOMATIC RETRY with exponential backoff:
 *     - 503 Service Unavailable → retry after 1s, 2s, 4s
 *     - 429 Rate Limited → retry after 2s, 4s, 8s
 *     - 500 Internal Server Error → retry after 1s, 2s, 4s
 *     - Network timeouts → retry after 1s, 2s
 *
 *  2. MODEL FALLBACK:
 *     - If the primary model fails, try fallback models in order
 *     - Supports gemini-2.5-flash-preview, gemini-2.5-flash, gemini-2.0-flash
 *
 *  3. PROPER CONNECTION MANAGEMENT:
 *     - Always disconnects HttpURLConnection in finally blocks
 *     - Sets appropriate timeouts (15s connect, 60s read)
 *     - Uses ?key= URL parameter for maximum API key compatibility
 * ═══════════════════════════════════════════════════════════════════════
 */
object GeminiApiClient {

    private const val TAG = "GeminiApiClient"

    /** Maximum number of retry attempts per request */
    private const val MAX_RETRIES = 3

    /** Model fallback list — tried in order until one succeeds */
    val GEMINI_MODELS = listOf(
        "gemini-2.5-flash-preview-05-20",
        "gemini-2.5-flash",
        "gemini-2.0-flash",
        "gemini-2.0-flash-lite"
    )

    /**
     * Result of a Gemini API call.
     */
    sealed class ApiResult {
        data class Success(val responseBody: String, val model: String) : ApiResult()
        data class HttpError(val code: Int, val message: String) : ApiResult()
        data class NetworkError(val message: String) : ApiResult()
    }

    /**
     * Send a POST request to the Gemini API with automatic retry and model fallback.
     *
     * @param requestBody JSON request body
     * @param apiKey Gemini API key
     * @param preferredModel Preferred model (optional, defaults to first in GEMINI_MODELS)
     * @param maxRetries Maximum retry attempts per model
     * @return ApiResult with the response or error
     */
    suspend fun postWithRetry(
        requestBody: String,
        apiKey: String,
        preferredModel: String? = null,
        maxRetries: Int = MAX_RETRIES
    ): ApiResult = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isBlank()) return@withContext ApiResult.NetworkError("API key is empty")

        // Build model list: preferred first, then fallbacks
        val models = if (preferredModel != null) {
            listOf(preferredModel) + GEMINI_MODELS.filter { it != preferredModel }
        } else {
            GEMINI_MODELS
        }

        var lastError: ApiResult = ApiResult.NetworkError("No models available")

        for (model in models) {
            var retries = 0
            while (retries < maxRetries) {
                var connection: HttpURLConnection? = null
                try {
                    val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$trimmedKey")
                    connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.doOutput = true
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 60_000

                    connection.outputStream.use { os ->
                        os.write(requestBody.toByteArray(Charsets.UTF_8))
                    }

                    val responseCode = connection.responseCode

                    when (responseCode) {
                        200 -> {
                            val responseBody = connection.inputStream.bufferedReader().readText()
                            connection.disconnect()
                            Log.i(TAG, "[postWithRetry] SUCCESS with model=$model (attempt=${retries + 1})")
                            return@withContext ApiResult.Success(responseBody, model)
                        }

                        503 -> {
                            val backoffMs = (1000L * (1 shl retries))
                            Log.w(TAG, "[postWithRetry] 503 for model=$model (attempt=${retries + 1}/$maxRetries), retry in ${backoffMs}ms")
                            connection.disconnect()
                            connection = null
                            delay(backoffMs)
                            retries++
                            continue
                        }

                        429 -> {
                            val backoffMs = (2000L * (1 shl retries))
                            Log.w(TAG, "[postWithRetry] 429 for model=$model (attempt=${retries + 1}/$maxRetries), retry in ${backoffMs}ms")
                            connection.disconnect()
                            connection = null
                            delay(backoffMs)
                            retries++
                            continue
                        }

                        500 -> {
                            val backoffMs = (1000L * (1 shl retries))
                            Log.w(TAG, "[postWithRetry] 500 for model=$model (attempt=${retries + 1}/$maxRetries), retry in ${backoffMs}ms")
                            connection.disconnect()
                            connection = null
                            delay(backoffMs)
                            retries++
                            continue
                        }

                        else -> {
                            val errorBody = try {
                                connection.errorStream?.bufferedReader()?.readText() ?: ""
                            } catch (_: Exception) { "" }
                            connection.disconnect()
                            connection = null
                            val friendlyMsg = when (responseCode) {
                                400 -> "Bad request (400)"
                                403 -> "API key not authorized (403)"
                                404 -> "Model not found (404)"
                                else -> "HTTP $responseCode"
                            }
                            Log.e(TAG, "[postWithRetry] $friendlyMsg for model=$model: ${errorBody.take(300)}")
                            lastError = ApiResult.HttpError(responseCode, "$friendlyMsg — ${errorBody.take(200)}")
                            break
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    Log.w(TAG, "[postWithRetry] Timeout for model=$model (attempt=${retries + 1}/$maxRetries)")
                    try { connection?.disconnect() } catch (_: Exception) {}
                    if (retries < maxRetries - 1) {
                        delay(1000L)
                        retries++
                    } else {
                        lastError = ApiResult.NetworkError("Timeout: ${e.message}")
                        break
                    }
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "[postWithRetry] IO error for model=$model (attempt=${retries + 1}/$maxRetries): ${e.message}")
                    try { connection?.disconnect() } catch (_: Exception) {}
                    if (retries < maxRetries - 1) {
                        delay(1000L)
                        retries++
                    } else {
                        lastError = ApiResult.NetworkError("Network error: ${e.message}")
                        break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[postWithRetry] Error for model=$model: ${e.message}")
                    try { connection?.disconnect() } catch (_: Exception) {}
                    lastError = ApiResult.NetworkError("Error: ${e.message?.take(100)}")
                    break
                } finally {
                    try { connection?.disconnect() } catch (_: Exception) {}
                }
            }
        }

        Log.e(TAG, "[postWithRetry] ALL models failed. Last error: $lastError")
        return@withContext lastError
    }
}
