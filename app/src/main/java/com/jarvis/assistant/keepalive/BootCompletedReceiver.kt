package com.jarvis.assistant.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.jarvis.assistant.data.SettingsRepository

/**
 * Boot Completed Receiver — Auto-starts JARVIS on device boot.
 *
 * Listens for:
 *   - BOOT_COMPLETED — Standard Android boot
 *   - QUICKBOOT_POWERON — HTC/Some devices quick boot
 *
 * On boot, starts:
 *   1. JarvisForegroundService (always-listening mic) if wake word was enabled
 *   2. JarvisKeepAliveService (persistent monitoring)
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "JarvisBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                Log.i(TAG, "Boot completed — starting JARVIS services")

                // Start Keep-Alive service
                try {
                    val keepAliveIntent = Intent(context, JarvisKeepAliveService::class.java).apply {
                        action = JarvisKeepAliveService.ACTION_START
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(keepAliveIntent)
                    } else {
                        context.startService(keepAliveIntent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start keep-alive service on boot", e)
                }

                // Start Foreground Service if wake word was previously enabled
                try {
                    val settingsRepo = SettingsRepository(context)
                    if (settingsRepo.isWakeWordEnabledBlocking()) {
                        Log.i(TAG, "Wake word was enabled — starting always-listening foreground service")
                        val fgIntent = Intent(context, com.jarvis.assistant.services.JarvisForegroundService::class.java).apply {
                            action = com.jarvis.assistant.services.JarvisForegroundService.ACTION_START
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(fgIntent)
                        } else {
                            context.startService(fgIntent)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start foreground service on boot", e)
                }
            }
        }
    }
}
