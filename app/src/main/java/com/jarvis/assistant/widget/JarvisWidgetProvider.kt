package com.jarvis.assistant.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R

/**
 * JARVIS Home Screen Widget — Shows brain state and quick actions.
 *
 * Displays:
 *   - Brain state text (Idle, Listening, Thinking, Speaking, Error)
 *   - Last voice command (truncated to 30 chars)
 *   - 3 action buttons: Listen, Capture, Read Screen
 *   - Click on widget opens app
 */
class JarvisWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "JarvisWidget"
        const val ACTION_LISTEN = "com.jarvis.assistant.WIDGET_LISTEN"
        const val ACTION_CAPTURE = "com.jarvis.assistant.WIDGET_CAPTURE"
        const val ACTION_READ = "com.jarvis.assistant.WIDGET_READ"

        // Static state for widget updates
        var brainState: String = "Idle"
        var lastCommand: String = ""

        /**
         * Update the widget from anywhere in the app.
         */
        fun updateWidget(context: Context, state: String, command: String) {
            brainState = state
            lastCommand = command

            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetComponent = ComponentName(context, JarvisWidgetProvider::class.java)
            val widgetIds = appWidgetManager.getAppWidgetIds(widgetComponent)

            for (widgetId in widgetIds) {
                updateAppWidget(context, appWidgetManager, widgetId)
            }
        }

        private fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.jarvis_widget).apply {
                // Update brain state text
                setTextViewText(R.id.widget_brain_state, "JARVIS: $brainState")

                // Update last command
                val displayCommand = if (lastCommand.length > 30) {
                    lastCommand.take(30) + "..."
                } else {
                    lastCommand.ifEmpty { "No recent command" }
                }
                setTextViewText(R.id.widget_last_command, displayCommand)

                // Click on widget opens app
                val openIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val openPending = PendingIntent.getActivity(
                    context, 0, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                setOnClickPendingIntent(android.R.id.background, openPending)

                // Listen button
                val listenIntent = Intent(context, JarvisWidgetProvider::class.java).apply {
                    action = ACTION_LISTEN
                }
                setOnClickPendingIntent(
                    R.id.widget_btn_listen,
                    PendingIntent.getBroadcast(
                        context, 1, listenIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )

                // Capture button
                val captureIntent = Intent(context, JarvisWidgetProvider::class.java).apply {
                    action = ACTION_CAPTURE
                }
                setOnClickPendingIntent(
                    R.id.widget_btn_capture,
                    PendingIntent.getBroadcast(
                        context, 2, captureIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )

                // Read button
                val readIntent = Intent(context, JarvisWidgetProvider::class.java).apply {
                    action = ACTION_READ
                }
                setOnClickPendingIntent(
                    R.id.widget_btn_read,
                    PendingIntent.getBroadcast(
                        context, 3, readIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_LISTEN -> {
                Log.d(TAG, "Widget: Listen button pressed")
                // Forward to MainActivity
                val activityIntent = Intent(context, MainActivity::class.java).apply {
                    action = ACTION_LISTEN
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(activityIntent)
            }
            ACTION_CAPTURE -> {
                Log.d(TAG, "Widget: Capture button pressed")
                val activityIntent = Intent(context, MainActivity::class.java).apply {
                    action = ACTION_CAPTURE
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(activityIntent)
            }
            ACTION_READ -> {
                Log.d(TAG, "Widget: Read button pressed")
                val activityIntent = Intent(context, MainActivity::class.java).apply {
                    action = ACTION_READ
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(activityIntent)
            }
        }
    }
}
