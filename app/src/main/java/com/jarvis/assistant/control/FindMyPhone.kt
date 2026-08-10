package com.jarvis.assistant.control

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.concurrent.thread

/**
 * Find-my-phone — "phone kahan hai?"
 * Full-volume beeping + vibration + flashlight strobe for 30 seconds,
 * then everything restores automatically.
 */
object FindMyPhone {

    @Volatile
    private var running = false

    fun start(context: Context) {
        if (running) return
        running = true

        val ctx = context.applicationContext
        val audioManager = ctx.getSystemService(AudioManager::class.java)
        val vibrator = ctx.getSystemService(Vibrator::class.java)
        val cameraManager = ctx.getSystemService(CameraManager::class.java)
        val savedVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)

        val beeper = ToneGenerator(AudioManager.STREAM_MUSIC, 100)

        val worker = thread(name = "jarvis-find") {
            val startTime = System.currentTimeMillis()
            var flashOn = false
            while (running && System.currentTimeMillis() - startTime < 30_000) {
                beeper.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1100)
                runCatching {
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        vibrator.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(400)
                    }
                }
                // flashlight strobe
                runCatching {
                    val id = cameraManager.cameraIdList.firstOrNull { cid ->
                        cameraManager.getCameraCharacteristics(cid)
                            .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    }
                    if (id != null) {
                        cameraManager.setTorchMode(id, flashOn)
                        flashOn = !flashOn
                    }
                }
                Thread.sleep(1500)
            }
            runCatching { beeper.release() }
            runCatching {
                cameraManager.cameraIdList.forEach { id ->
                    runCatching { cameraManager.setTorchMode(id, false) }
                }
            }
            runCatching {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
            }
            running = false
        }
        worker.isDaemon = true
    }

    fun stop() {
        running = false
    }
}
