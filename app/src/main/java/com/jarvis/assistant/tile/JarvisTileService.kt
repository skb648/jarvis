package com.jarvis.assistant.tile

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.jarvis.assistant.core.NotificationHelper
import com.jarvis.assistant.service.JarvisService

/**
 * Quick Settings tile — notification shade me hi JARVIS mic.
 * Ek tap = turant baat shuru.
 */
class JarvisTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.state = Tile.STATE_ACTIVE
        qsTile?.updateTile()
    }

    override fun onClick() {
        super.onClick()
        try {
            startForegroundService(
                Intent(this, JarvisService::class.java)
                    .setAction(NotificationHelper.ACTION_TALK)
            )
        } catch (e: Exception) {
            startService(
                Intent(this, JarvisService::class.java)
                    .setAction(NotificationHelper.ACTION_TALK)
            )
        }
    }
}
