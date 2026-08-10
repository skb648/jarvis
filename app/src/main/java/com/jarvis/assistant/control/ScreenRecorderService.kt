package com.jarvis.assistant.control

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.hardware.display.DisplayManager
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.core.NotificationHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen recorder — "screen record karo".
 * Requires a one-time MediaProjection consent (auto-opened by MainActivity).
 */
class ScreenRecorderService : Service() {

    companion object {
        private const val CHANNEL = "jarvis_recording"
        private const val NOTIF_ID = 42
        const val ACTION_START = "com.jarvis.assistant.REC_START"
        const val ACTION_STOP = "com.jarvis.assistant.REC_STOP"
        private const val EXTRA_CODE = "result_code"
        private const val EXTRA_DATA = "result_data"

        private var active = false

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenRecorderService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenRecorderService::class.java).setAction(ACTION_STOP))
        }

        fun isActive(): Boolean = active
    }

    private var mediaProjection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var outputFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecording()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (data != null) {
                    startRecording(code, data)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording(resultCode: Int, data: Intent) {
        if (active) return
        active = true
        startForeground(NOTIF_ID, buildNotification())

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpm.getMediaProjection(resultCode, data) ?: run {
            active = false
            stopSelf()
            return
        }

        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "JARVIS"
        ).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "JARVIS_$stamp.mp4")
        outputFile = file

        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        val density = resources.displayMetrics.densityDpi

        val rec = MediaRecorder()
        rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        rec.setVideoSize(width, height)
        rec.setVideoFrameRate(30)
        rec.setVideoEncodingBitRate(6_000_000)
        rec.setAudioEncodingBitRate(128_000)
        rec.setAudioSamplingRate(44_100)
        rec.setOutputFile(file.absolutePath)

        recorder = rec
        try {
            rec.prepare()
            virtualDisplay = projection.createVirtualDisplay(
                "JARVIS_REC",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface, null, null
            )
            rec.start()
            mediaProjection = projection
        } catch (e: Exception) {
            runCatching { rec.release() }
            runCatching { projection.stop() }
            active = false
            stopSelf()
        }
    }

    private fun stopRecording() {
        if (!active) return
        active = false
        try {
            recorder?.stop()
        } catch (_: Exception) {}
        runCatching { recorder?.release() }
        runCatching { virtualDisplay?.release() }
        runCatching { mediaProjection?.stop() }
        recorder = null
        virtualDisplay = null
        mediaProjection = null
    }

    private fun buildNotification(): Notification {
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenRecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("JARVIS Recording")
            .setContentText("Screen record ho raha hai — tap karke band karo")
            .setOngoing(true)
            .setContentIntent(stopPi)
            .build()
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }
}
