package com.jarvis.assistant.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * VisionManager — Camera + AI Vision for JARVIS.
 *
 * Enables JARVIS to "see" through the device camera and analyze
 * what it sees using Groq's vision API (Llama 3.2 90B Vision).
 *
 * "Dekh kya hai" / "What is this?" → Captures a photo and sends
 * it to Groq Vision for analysis and description.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * USAGE:
 *
 *   val visionManager = VisionManager()
 *   val result = visionManager.captureAndAnalyze(context, "What do you see?", apiKey)
 *   // Returns: "Sir, I can see a laptop on a desk with a coffee cup nearby..."
 *
 * ARCHITECTURE:
 *   1. Opens the back camera using Camera2 API
 *   2. Captures a single frame as JPEG
 *   3. Converts to base64
 *   4. Sends to Groq Vision API (llama-3.2-90b-vision-preview, OpenAI-compatible)
 *   5. Returns the AI's description/analysis
 * ═══════════════════════════════════════════════════════════════════════
 */
class VisionManager {

    companion object {
        private const val TAG = "VisionManager"
        private const val GROQ_MODEL = "llama-3.2-90b-vision-preview"
        private const val GROQ_VISION_URL =
            com.jarvis.assistant.network.GroqApiClient.BASE_URL
        private const val JPEG_QUALITY = 80
        private const val MAX_IMAGE_DIMENSION = 1024  // Resize for API limits
        private const val CAPTURE_TIMEOUT_MS = 10_000L
    }

    /** Shared OkHttp client for vision requests — longer timeouts for large image payloads */
    private val visionHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)  // Longer timeout for vision
            .writeTimeout(60, TimeUnit.SECONDS)  // Large image payloads
            .retryOnConnectionFailure(true)
            .build()
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    /**
     * Capture a photo from the back camera and analyze it with Groq Vision.
     *
     * @param context Android context
     * @param prompt The question/prompt about the image (e.g., "What is this?")
     * @param apiKey Groq API key
     * @return AI's analysis of the captured image
     */
    suspend fun captureAndAnalyze(
        context: Context,
        prompt: String,
        apiKey: String
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                // Step 1: Capture a photo
                val imageBytes = capturePhoto(context)
                if (imageBytes == null) {
                    return@withContext "Sir, I couldn't capture an image. The camera may be in use by another app or unavailable."
                }

                Log.i(TAG, "[captureAndAnalyze] Photo captured: ${imageBytes.size} bytes")

                // Step 2: Resize the image if needed
                val resizedBytes = resizeImageIfNeeded(imageBytes)
                Log.i(TAG, "[captureAndAnalyze] Image resized: ${resizedBytes.size} bytes")

                // Step 3: Convert to base64
                val base64Image = Base64.encodeToString(resizedBytes, Base64.NO_WRAP)

                // Step 4: Send to Groq Vision API
                val response = sendToGroqVision(base64Image, prompt, apiKey)

                Log.i(TAG, "[captureAndAnalyze] Vision analysis complete: ${response.take(100)}")
                response
            } catch (e: SecurityException) {
                Log.e(TAG, "[captureAndAnalyze] Camera permission denied: ${e.message}")
                "Sir, I need camera permission to see. Please grant it in Settings."
            } catch (e: Exception) {
                Log.e(TAG, "[captureAndAnalyze] Error: ${e.message}")
                "Sir, I encountered an error while analyzing the image: ${e.message?.take(100)}"
            } finally {
                closeCamera()
            }
        }
    }

    /**
     * Capture a single photo using Camera2 API.
     * Returns JPEG bytes or null if capture failed.
     */
    private suspend fun capturePhoto(context: Context): ByteArray? {
        return suspendCancellableCoroutine { continuation ->
            try {
                startBackgroundThread()

                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = findBackCameraId(cameraManager)
                    ?: run {
                        continuation.resume(null)
                        return@suspendCancellableCoroutine
                    }

                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val streamConfigMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: run {
                        continuation.resume(null)
                        return@suspendCancellableCoroutine
                    }

                // Set up ImageReader for JPEG capture
                val jpegSizes = streamConfigMap.getOutputSizes(ImageFormat.JPEG)
                val captureSize = jpegSizes?.maxByOrNull { it.width * it.height }
                    ?: android.util.Size(1920, 1080)

                imageReader = ImageReader.newInstance(
                    captureSize.width, captureSize.height,
                    ImageFormat.JPEG, 2
                )

                val latch = CountDownLatch(1)
                var capturedBytes: ByteArray? = null

                imageReader?.setOnImageAvailableListener({ reader ->
                    try {
                        val image = reader.acquireLatestImage()
                        if (image != null) {
                            capturedBytes = imageToJpegBytes(image)
                            image.close()
                            latch.countDown()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "[capturePhoto] Image reader error: ${e.message}")
                        latch.countDown()
                    }
                }, backgroundHandler)

                // Open camera
                try {
                    cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            cameraDevice = camera
                            try {
                                startCaptureSession(camera, latch)
                            } catch (e: Exception) {
                                Log.e(TAG, "[capturePhoto] Session start error: ${e.message}")
                                latch.countDown()
                            }
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            camera.close()
                            cameraDevice = null
                            latch.countDown()
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            Log.e(TAG, "[capturePhoto] Camera error: $error")
                            camera.close()
                            cameraDevice = null
                            latch.countDown()
                        }
                    }, backgroundHandler)
                } catch (e: SecurityException) {
                    throw e  // Re-throw security exceptions for permission handling
                }

                // Wait for capture with timeout
                val captured = latch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (!captured) {
                    Log.w(TAG, "[capturePhoto] Capture timed out")
                }

                continuation.resume(capturedBytes)
            } catch (e: Exception) {
                Log.e(TAG, "[capturePhoto] Exception: ${e.message}")
                continuation.resume(null)
            }
        }
    }

    /**
     * Start a capture session and take a single photo.
     */
    @Suppress("DEPRECATION")
    private fun startCaptureSession(camera: CameraDevice, latch: CountDownLatch) {
        val surface = imageReader?.surface ?: run {
            latch.countDown()
            return
        }

        val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        captureRequestBuilder.addTarget(surface)
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

        camera.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        // Auto-focus first, then capture
                        captureRequestBuilder.set(
                            CaptureRequest.CONTROL_AF_TRIGGER,
                            CaptureRequest.CONTROL_AF_TRIGGER_START
                        )
                        session.capture(captureRequestBuilder.build(), null, backgroundHandler)

                        // Then capture
                        captureRequestBuilder.set(
                            CaptureRequest.CONTROL_AF_TRIGGER,
                            CaptureRequest.CONTROL_AF_TRIGGER_IDLE
                        )
                        session.capture(captureRequestBuilder.build(), null, backgroundHandler)
                    } catch (e: Exception) {
                        Log.e(TAG, "[startCaptureSession] Capture error: ${e.message}")
                        latch.countDown()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "[startCaptureSession] Configuration failed")
                    latch.countDown()
                }
            },
            backgroundHandler
        )
    }

    /**
     * Convert an Image (YUV or JPEG) to JPEG byte array.
     */
    private fun imageToJpegBytes(image: Image): ByteArray {
        return when (image.format) {
            ImageFormat.JPEG -> {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                bytes
            }
            ImageFormat.YUV_420_888 -> {
                // Convert YUV to JPEG
                val yBuffer = image.planes[0].buffer
                val uBuffer = image.planes[1].buffer
                val vBuffer = image.planes[2].buffer

                val ySize = yBuffer.remaining()
                val uSize = uBuffer.remaining()
                val vSize = vBuffer.remaining()

                val nv21 = ByteArray(ySize + uSize + vSize)
                yBuffer.get(nv21, 0, ySize)
                vBuffer.get(nv21, ySize, vSize)
                uBuffer.get(nv21, ySize + vSize, uSize)

                val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
                val outputStream = ByteArrayOutputStream()
                yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), JPEG_QUALITY, outputStream)
                outputStream.toByteArray()
            }
            else -> {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                bytes
            }
        }
    }

    /**
     * Resize image if it exceeds the maximum dimension.
     */
    private fun resizeImageIfNeeded(imageBytes: ByteArray): ByteArray {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)

        val width = options.outWidth
        val height = options.outHeight

        if (width <= MAX_IMAGE_DIMENSION && height <= MAX_IMAGE_DIMENSION) {
            return imageBytes  // No resize needed
        }

        // Calculate scale
        val scale = minOf(
            MAX_IMAGE_DIMENSION.toFloat() / width,
            MAX_IMAGE_DIMENSION.toFloat() / height
        )
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        // Decode and resize
        val decodeOptions = BitmapFactory.Options()
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOptions)
            ?: return imageBytes

        val resized = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        val outputStream = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)

        if (bitmap != resized) bitmap.recycle()
        resized.recycle()

        return outputStream.toByteArray()
    }

    /**
     * Send an image + prompt to Groq's Vision API (OpenAI-compatible format).
     * Uses OkHttp with proper timeouts, retry, and connection pooling.
     */
    private suspend fun sendToGroqVision(
        base64Image: String,
        prompt: String,
        apiKey: String
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                // Build OpenAI-compatible vision request: text + image_url
                val requestBody = JSONObject().apply {
                    put("model", GROQ_MODEL)
                    put("messages", JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", JSONArray().apply {
                                // Text prompt
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", "You are JARVIS, Tony Stark's AI assistant. You are looking through the device camera. $prompt. Describe what you see concisely and with British elegance. Address the user as Sir. If you see text, read it. If you see objects, identify them. If you see people, describe what they're doing (don't identify faces for privacy).")
                                })
                                // Image data as base64 data URI
                                put(JSONObject().apply {
                                    put("type", "image_url")
                                    put("image_url", JSONObject().apply {
                                        put("url", "data:image/jpeg;base64,$base64Image")
                                    })
                                })
                            })
                        }
                    ))
                    put("max_tokens", 1024)
                }.toString()

                val request = Request.Builder()
                    .url(GROQ_VISION_URL)
                    .addHeader("Authorization", "Bearer ${apiKey.trim()}")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                val response = visionHttpClient.newCall(request).execute()
                if (response.code == 200) {
                    val responseBody = response.body?.string() ?: ""
                    response.close()

                    // Parse OpenAI-compatible response: choices[0].message.content
                    val root = JSONObject(responseBody)
                    val choices = root.optJSONArray("choices")
                    val firstChoice = choices?.optJSONObject(0)
                    val message = firstChoice?.optJSONObject("message")
                    val text = message?.optString("content", "") ?: ""

                    if (text.isNotBlank()) text else "Sir, I captured the image but couldn't analyze it properly."
                } else {
                    val errorBody = response.body?.string() ?: ""
                    response.close()
                    Log.e(TAG, "[sendToGroqVision] HTTP ${response.code}: ${errorBody.take(300)}")
                    "Sir, I couldn't analyze the image. The vision API returned an error (HTTP ${response.code})."
                }
            } catch (e: Exception) {
                Log.e(TAG, "[sendToGroqVision] Error: ${e.message}")
                "Sir, I had trouble connecting to the vision service: ${e.message?.take(100)}"
            }
        }
    }

    // ─── Camera Helpers ────────────────────────────────────────────────

    private fun findBackCameraId(cameraManager: CameraManager): String? {
        return cameraManager.cameraIdList.firstOrNull { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            facing == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.firstOrNull()  // Fallback to any camera
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("VisionCamera").apply { start() }
        backgroundHandler = backgroundThread?.looper?.let { Handler(it) }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
        } catch (e: InterruptedException) {
            Log.w(TAG, "[stopBackgroundThread] Interrupted: ${e.message}")
        }
        backgroundThread = null
        backgroundHandler = null
    }

    /**
     * Release all camera resources.
     */
    fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            stopBackgroundThread()
            Log.d(TAG, "[closeCamera] Camera resources released")
        } catch (e: Exception) {
            Log.e(TAG, "[closeCamera] Error: ${e.message}")
        }
    }
}
