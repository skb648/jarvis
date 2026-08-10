package com.jarvis.assistant.control

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.view.KeyEvent

/**
 * Media control: play / pause / next / previous / stop.
 * Uses active media sessions first; falls back to media key events.
 * Tip: enable "Notification access" for JARVIS in Settings for full control
 * of every music app.
 */
class MediaController(context: Context) {

    private val ctx = context.applicationContext
    private val audioManager = context.getSystemService(AudioManager::class.java)

    fun control(action: String): String {
        val controls = activeSessions().mapNotNull { it.transportControls }
        if (controls.isNotEmpty()) {
            when (action) {
                "play" -> controls.forEach { runCatching { it.play() } }
                "pause" -> controls.forEach { runCatching { it.pause() } }
                "stop" -> controls.forEach { runCatching { it.pause() } }
                "next" -> controls.forEach { runCatching { it.skipToNext() } }
                "previous" -> controls.forEach { runCatching { it.skipToPrevious() } }
            }
            return ""
        }
        // Fallback: media key events
        val code = when (action) {
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        runCatching {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        return ""
    }

    private fun activeSessions(): List<MediaController> {
        val mgr = ctx.getSystemService(MediaSessionManager::class.java) ?: return emptyList()
        return try {
            val own = mgr.getActiveSessions(null)
            if (own.isNotEmpty()) own
            else {
                // Enabled notification listener unlocks other apps' sessions
                mgr.getActiveSessions(ComponentName(ctx, MediaNotificationListener::class.java))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
