package com.jarvis.assistant.shortcuts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.assistant.MainActivity

/**
 * JARVIS Shortcut Receiver — Handles app shortcut intents.
 *
 * 3 static shortcuts:
 *   1. Voice Command — Starts listening immediately
 *   2. Capture Screen — Takes a screenshot
 *   3. Read Screen — Reads on-screen text aloud
 */
class JarvisShortcutReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "JarvisShortcuts"
        const val ACTION_VOICE_COMMAND = "com.jarvis.assistant.VOICE_COMMAND"
        const val ACTION_CAPTURE_SCREEN = "com.jarvis.assistant.CAPTURE_SCREEN"
        const val ACTION_READ_SCREEN = "com.jarvis.assistant.READ_SCREEN"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_VOICE_COMMAND -> {
                Log.d(TAG, "Shortcut: Voice Command")
                launchMainActivity(context, ACTION_VOICE_COMMAND)
            }
            ACTION_CAPTURE_SCREEN -> {
                Log.d(TAG, "Shortcut: Capture Screen")
                launchMainActivity(context, ACTION_CAPTURE_SCREEN)
            }
            ACTION_READ_SCREEN -> {
                Log.d(TAG, "Shortcut: Read Screen")
                launchMainActivity(context, ACTION_READ_SCREEN)
            }
        }
    }

    private fun launchMainActivity(context: Context, action: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            this.action = action
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("shortcut_action", action)
        }
        context.startActivity(intent)
    }
}
