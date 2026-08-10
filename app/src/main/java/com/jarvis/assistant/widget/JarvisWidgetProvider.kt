package com.jarvis.assistant.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.jarvis.assistant.R
import com.jarvis.assistant.core.NotificationHelper
import com.jarvis.assistant.service.JarvisService

/**
 * Home screen widget — tap karo, JARVIS sunne lagega.
 */
class JarvisWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val views = RemoteViews(context.packageName, R.layout.jarvis_widget)

        val talkPi = PendingIntent.getService(
            context,
            77,
            Intent(context, JarvisService::class.java)
                .setAction(NotificationHelper.ACTION_TALK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_icon, talkPi)
        views.setOnClickPendingIntent(R.id.widget_title, talkPi)
        views.setOnClickPendingIntent(R.id.widget_subtitle, talkPi)

        appWidgetManager.updateAppWidget(appWidgetIds, views)
    }
}
