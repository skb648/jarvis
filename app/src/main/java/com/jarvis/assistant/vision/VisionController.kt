package com.jarvis.assistant.vision

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import java.io.File
import kotlin.concurrent.thread

/**
 * Vision mode — front camera photo capture.
 * With a Gemini key, JARVIS can then DESCRIBE what it sees.
 */
class VisionController(private val context: Context) {

    /** Captures a JPEG from the front camera (640x480) into cache. */
    fun capturePhoto(front: Boolean): File? {
        val manager = context.getSystemService(CameraManager::class.java)
        val cameraId = try {
            val ids = manager.cameraIdList
            val wanted = if (front) CameraCharacteristics.LENS_FACING_FRONT
            else CameraCharacteristics.LENS_FACING_BACK
            ids.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == wanted
            } ?: ids.firstOrNull()
        } catch (e: Exception) {
            return null
        } ?: return null

        val file = File(context.cacheDir, "jarvis_vision.jpg")
        val done = java.util.concurrent.CountDownLatch(1)
        val result = arrayOf<File?>(null)

        val handlerThread = HandlerThread("jarvis-cam").apply { start() }
        val handler = Handler(handlerThread.looper)

        val imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 1)
        imageReader.setOnImageAvailableListener({ reader ->
            thread {
                val image = reader.acquireLatestImage()
                if (image != null) {
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        file.writeBytes(bytes)
                        result[0] = file
                    } catch (e: Exception) {
                        result[0] = null
                    } finally {
                        image.close()
                    }
                }
                done.countDown()
            }
        }, handler)

        try {
            if (context.checkSelfPermission(android.Manifest.permission.CAMERA) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                done.countDown()
                return null
            }
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(imageReader.surface)
                            set(CaptureRequest.JPEG_QUALITY, 85.toByte())
                        }
                        camera.createCaptureSession(
                            listOf(imageReader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try {
                                        session.capture(request.build(), null, handler)
                                    } catch (e: Exception) {
                                        done.countDown()
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    done.countDown()
                                }
                            },
                            handler
                        )
                    } catch (e: Exception) {
                        done.countDown()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    done.countDown()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    done.countDown()
                }
            }, handler)

            if (!done.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                result[0] = null
            }
        } catch (e: Exception) {
            result[0] = null
        } finally {
            handlerThread.quitSafely()
        }
        return result[0]
    }
}
