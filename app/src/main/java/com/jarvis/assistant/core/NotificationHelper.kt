package com.jarvis.assistant.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R

object NotificationHelper {

    const val CHANNEL_SERVICE = "jarvis_service"
    const val CHANNEL_COMMANDS = "jarvis_commands"
    const val CHANNEL_ALARMS = "jarvis_alarms"

    const val ACTION_TALK = "com.jarvis.assistant.ACTION_TALK"
    const val ACTION_STOP = "com.jarvis.assistant.ACTION_STOP"
    const val ACTION_STOP_LISTENING = "com.jarvis.assistant.ACTION_STOP_LISTENING"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, context.getString(R.string.channel_service_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = "Always-on assistant indicator"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_COMMANDS, context.getString(R.string.channel_commands_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Command results and mood alerts"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARMS, context.getString(R.string.channel_alarms_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alarms, timers and reminders"
            }
        )
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun serviceNotification(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle(context.getString(R.string.service_notification_title))
            .setContentText(text)
            .setContentIntent(openAppIntent(context))
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, context.getString(R.string.action_talk), talkPendingIntent(context))
            .addAction(0, context.getString(R.string.action_stop), stopPendingIntent(context))
            .build()

    fun talkPendingIntent(context: Context): PendingIntent =
        PendingIntent.getService(
            context, 11,
            Intent(context, com.jarvis.assistant.service.JarvisService::class.java).setAction(ACTION_TALK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun stopPendingIntent(context: Context): PendingIntent =
        PendingIntent.getService(
            context, 12,
            Intent(context, com.jarvis.assistant.service.JarvisService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun commandNotification(context: Context, title: String, body: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_COMMANDS)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()

    fun alarmNotification(context: Context, title: String, body: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_ALARMS)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
}
