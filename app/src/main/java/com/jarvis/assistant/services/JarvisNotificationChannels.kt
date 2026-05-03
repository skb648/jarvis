package com.jarvis.assistant.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * JarvisNotificationChannels — Shared notification channels for JARVIS foreground services.
 *
 * Instead of each service creating its own notification channel (which creates
 * 4+ channels in system settings), we consolidate into 2 channels:
 *
 *   1. CHANNEL_JARVIS_SERVICES — Core assistant services (listening, speech, sensory)
 *   2. CHANNEL_JARVIS_OVERLAY  — Overlay services (cursor overlay)
 *
 * Both are IMPORTANCE_LOW (no sound/vibration) so the user isn't spammed.
 * Creating a channel that already exists is a no-op, so it's safe to call
 * ensureChannels() from multiple services.
 */
object JarvisNotificationChannels {

    /** Shared channel for core JARVIS services (listening, speech, sensory) */
    const val CHANNEL_JARVIS_SERVICES = "jarvis_services"

    /** Channel for overlay-related services (cursor) */
    const val CHANNEL_JARVIS_OVERLAY = "jarvis_overlay"

    /**
     * Ensure all shared notification channels exist.
     * Safe to call multiple times — Android ignores duplicate channel creation.
     *
     * @param context Any context (uses applicationContext to prevent leaks)
     */
    fun ensureChannels(context: Context) {
        val nm = context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Core services channel
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_JARVIS_SERVICES,
                "JARVIS Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "JARVIS assistant background services"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )

        // Overlay channel
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_JARVIS_OVERLAY,
                "JARVIS Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "JARVIS overlay and cursor services"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }
}
