package com.jarvis.assistant.actions

/**
 * Shared registry of well-known app name → package name mappings.
 *
 * This eliminates the duplicate `appAliases` maps that previously existed
 * in both [ActionHandler] and [CommandRouter]. Any component that needs
 * to resolve a user-friendly app name (e.g. "youtube") to its Android
 * package name (e.g. "com.google.android.youtube") should reference
 * [AppRegistry.appAliases] instead of maintaining its own copy.
 */
object AppRegistry {

    /**
     * Immutable map of lowercase app alias → Android package name.
     *
     * Covers Google, social, Samsung, messaging, and system apps.
     */
    val appAliases: Map<String, String> = mapOf(
        // Google
        "youtube"        to "com.google.android.youtube",
        "maps"           to "com.google.android.apps.maps",
        "gmail"          to "com.google.android.gm",
        "chrome"         to "com.android.chrome",
        "drive"          to "com.google.android.apps.docs",
        "photos"         to "com.google.android.apps.photos",
        "play store"     to "com.android.vending",
        "play music"     to "com.google.android.music",
        "google"         to "com.google.android.googlequicksearchbox",
        "google home"    to "com.google.android.apps.chromecast.app",
        "translate"      to "com.google.android.apps.translate",
        "calendar"       to "com.google.android.calendar",
        "clock"          to "com.android.deskclock",
        "calculator"     to "com.google.android.calculator",
        "weather"        to "com.google.android.apps.weather",
        // Social
        "whatsapp"       to "com.whatsapp",
        "instagram"      to "com.instagram.android",
        "twitter"        to "com.twitter.android",
        "x"              to "com.twitter.android",
        "facebook"       to "com.facebook.katana",
        "telegram"       to "org.telegram.messenger",
        "snapchat"       to "com.snapchat.android",
        "discord"        to "com.discord",
        "spotify"        to "com.spotify.music",
        "netflix"        to "com.netflix.mediaclient",
        // Samsung
        "samsung internet" to "com.sec.android.app.sbrowser",
        "samsung health"   to "com.sec.android.app.shealth",
        "galaxy store"     to "com.sec.android.app.samsungapps",
        // Messaging
        "messages"       to "com.google.android.apps.messaging",
        "sms"            to "com.google.android.apps.messaging",
        "phone"          to "com.google.android.dialer",
        "dialer"         to "com.google.android.dialer",
        // System
        "settings"       to "com.android.settings",
        "files"          to "com.google.android.apps.nbu.files",
        "camera"         to "com.google.android.GoogleCamera",
    )
}
