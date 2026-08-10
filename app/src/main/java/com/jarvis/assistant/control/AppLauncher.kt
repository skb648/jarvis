package com.jarvis.assistant.control

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings

/**
 * App launching + "play <song>" search intents.
 */
class AppLauncher(private val context: Context) {

    private val packageMap = mapOf(
        "whatsapp" to "com.whatsapp",
        "youtube" to "com.google.android.youtube",
        "ytmusic" to "com.google.android.apps.youtube.music",
        "instagram" to "com.instagram.android",
        "telegram" to "org.telegram.messenger",
        "chrome" to "com.android.chrome",
        "maps" to "com.google.android.apps.maps",
        "phone" to "com.google.android.dialer",
        "calculator" to "com.google.android.calculator",
        "gallery" to "com.google.android.apps.photos",
        "music" to "com.spotify.music",
        "spotify" to "com.spotify.music",
        "playstore" to "com.android.vending",
        "files" to "com.google.android.apps.nbu.files",
        "clock" to "com.google.android.deskclock",
        "calendar" to "com.google.android.calendar",
        "gmail" to "com.google.android.gm",
        "twitter" to "com.twitter.android",
        "facebook" to "com.facebook.katana",
        "netflix" to "com.netflix.mediaclient",
        "prime" to "com.amazon.avod.thirdpartyclient"
    )

    fun open(app: String): String {
        if (app == "camera") {
            runCatching {
                context.startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
            }.onFailure { return "Camera open nahi hui." }
            return ""
        }
        if (app == "settings") {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_SETTINGS))
            }.onFailure { return "Settings open nahi hui." }
            return ""
        }
        val pkg = packageMap[app] ?: return "App nahi mila."
        val pm = context.packageManager
        val launch = pm.getLaunchIntentForPackage(pkg)
        return if (launch != null) {
            runCatching { context.startActivity(launch) }
            ""
        } else {
            "Wo app install nahi hai."
        }
    }

    fun playSomething(query: String): String {
        val url = "https://music.youtube.com/search?q=" + Uri.encode(query)
        val ytMusic = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .apply { setPackage("com.google.android.apps.youtube.music") }
        if (context.packageManager.resolveActivity(ytMusic, 0) != null) {
            context.startActivity(ytMusic)
            return ""
        }
        val anyBrowser = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (context.packageManager.resolveActivity(anyBrowser, 0) != null) {
            context.startActivity(anyBrowser)
            return ""
        }
        return "Music search ke liye app nahi mila."
    }
}
