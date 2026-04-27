package com.jarvis.assistant.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Boot Completed Receiver — Auto-starts JARVIS on device boot.
 *
 * Listens for:
 *   - BOOT_COMPLETED — Standard Android boot
 *   - QUICKBOOT_POWERON — HTC/Some devices quick boot
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "JarvisBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                Log.i(TAG, "Boot completed — starting JARVIS Keep-Alive service")
                try {
                    val serviceIntent = Intent(context, JarvisKeepAliveService::class.java).apply {
                        action = JarvisKeepAliveService.ACTION_START
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start keep-alive service on boot", e)
                }
            }
        }
    }
}
