package com.jarvis.assistant.actions

/**
 * Shared registry of well-known app name → package name mappings.
 *
 * This eliminates the duplicate `appAliases` maps that previously existed
 * in both [ActionHandler] and [CommandRouter]. Any component that needs
 * to resolve a user-friendly app name (e.g. "youtube") to its Android
 * package name (e.g. "com.google.android.youtube") should reference
 * [AppRegistry.appAliases] instead of maintaining its own copy.
 *
 * Also provides [fuzzyMatchApp] for Levenshtein-based fallback matching
 * when an exact alias lookup fails.
 */
object AppRegistry {

    /**
     * Immutable map of lowercase app alias → Android package name.
     *
     * Covers Google, social, Samsung, messaging, system, gaming,
     * Indian payment, Indian streaming, Indian music, delivery,
     * e-commerce, and productivity apps.
     */
    val appAliases: Map<String, String> = mapOf(
        // ── Google ──────────────────────────────────────────────
        "youtube"          to "com.google.android.youtube",
        "maps"             to "com.google.android.apps.maps",
        "gmail"            to "com.google.android.gm",
        "chrome"           to "com.android.chrome",
        "drive"            to "com.google.android.apps.docs",
        "photos"           to "com.google.android.apps.photos",
        "play store"       to "com.android.vending",
        "play music"       to "com.google.android.apps.youtube.music",
        "youtube music"    to "com.google.android.apps.youtube.music",
        "yt music"         to "com.google.android.apps.youtube.music",
        "google"           to "com.google.android.googlequicksearchbox",
        "google home"      to "com.google.android.apps.chromecast.app",
        "translate"        to "com.google.android.apps.translate",
        "calendar"         to "com.google.android.calendar",
        "clock"            to "com.android.deskclock",
        "calculator"       to "com.google.android.calculator",
        "weather"          to "com.google.android.apps.weather",

        // ── Social ──────────────────────────────────────────────
        "whatsapp"         to "com.whatsapp",
        "instagram"        to "com.instagram.android",
        "twitter"          to "com.twitter.android",
        "x"                to "com.twitter.android",
        "facebook"         to "com.facebook.katana",
        "telegram"         to "org.telegram.messenger",
        "snapchat"         to "com.snapchat.android",
        "discord"          to "com.discord",
        "spotify"          to "com.spotify.music",
        "netflix"          to "com.netflix.mediaclient",

        // ── Samsung ─────────────────────────────────────────────
        "samsung internet" to "com.sec.android.app.sbrowser",
        "samsung health"   to "com.sec.android.app.shealth",
        "galaxy store"     to "com.sec.android.app.samsungapps",

        // ── Messaging ───────────────────────────────────────────
        "messages"         to "com.google.android.apps.messaging",
        "sms"              to "com.google.android.apps.messaging",
        "phone"            to "com.google.android.dialer",
        "dialer"           to "com.google.android.dialer",

        // ── System ──────────────────────────────────────────────
        "settings"         to "com.android.settings",
        "files"            to "com.google.android.apps.nbu.files",
        "camera"           to "com.google.android.GoogleCamera",

        // ── Gaming ──────────────────────────────────────────────
        "free fire"        to "com.dts.freefireth",
        "free fire max"    to "com.dts.freefiremax",
        "bgmi"             to "com.pubg.imobile",
        "pubg"             to "com.pubg.imobile",
        "pubg mobile"      to "com.pubg.imobile",
        "cod mobile"       to "com.activision.callofduty.shooter",
        "call of duty"     to "com.activision.callofduty.shooter",
        "minecraft"        to "com.mojang.minecraftpe",
        "roblox"           to "com.roblox.client",
        "clash of clans"   to "com.supercell.magic",
        "brawl stars"      to "com.supercell.brawlstars",
        "clash royale"     to "com.supercell.magic",
        "ludo king"        to "com.ludo.king",
        "candy crush"      to "com.king.candycrushsaga",
        "subway surfers"   to "com.kiloo.subwaysurf",
        "among us"         to "com.innersloth.spacemafia",
        "temple run"       to "com.imangi.templerun2",
        "asphalt"          to "com.gameloft.android.ANMP.GloftA8HM",
        "gta"              to "com.rockstargames.mobile",
        "fifa"             to "com.ea.gp.fifamobile",

        // ── Indian Payment Apps ─────────────────────────────────
        "phonepe"          to "com.phonepe.app",
        "paytm"            to "net.one97.paytm",
        "cred"             to "com.cred.app",
        "groww"            to "com.nextbillion.groww",
        "upi"              to "com.google.android.apps.nbu.paisa.user",
        "google pay"       to "com.google.android.apps.nbu.paisa.user",
        "gpay"             to "com.google.android.apps.nbu.paisa.user",
        "bharatpe"         to "com.bharatpe.app",
        "mobikwik"         to "com.mobikwik_new",
        "amazon pay"       to "in.amazon.mShop.android.shopping",

        // ── Indian Streaming ────────────────────────────────────
        "hotstar"          to "in.startv.hotstar",
        "disney hotstar"   to "in.startv.hotstar",
        "jiocinema"        to "com.jio.media.vidplayer",
        "zee5"             to "com.graymatrix.did",
        "mx player"        to "com.mxtech.videoplayer.ad",
        "sonyliv"          to "com.sonyliv",
        "voot"             to "com.viacom18.voot",
        "aha"              to "com.aha.videoplayer",
        "eros now"         to "com.erosnow",

        // ── Indian Music ────────────────────────────────────────
        "gaana"            to "com.gaana",
        "jiosaavn"         to "com.saavn.android",
        "saavn"            to "com.saavn.android",
        "wynk"             to "com.bsbportal.music",
        "hungama"          to "com.hungama.movies",

        // ── Delivery ────────────────────────────────────────────
        "swiggy"           to "in.swiggy.android",
        "zomato"           to "com.application.zomato",
        "blinkit"          to "com.grofers.customerapp",
        "zepto"            to "com.zeptoconsumerapp",
        "dunzo"            to "com.dunzo.user",
        "bigbasket"        to "com.bigbasket.mobileapp",
        "dominos"          to "com.dominos",
        "pizza hut"        to "com.yum.pizza",

        // ── E-commerce ──────────────────────────────────────────
        "amazon india"     to "in.amazon.mShop.android.shopping",
        "flipkart"         to "com.flipkart.android",
        "myntra"           to "com.myntra.android",
        "meesho"           to "com.meesho.app",
        "ajio"             to "com.ril.ajio",
        "nykaa"            to "com.nykaa.android",
        "snapdeal"         to "com.snapdeal.main",

        // ── Productivity ────────────────────────────────────────
        "zoom"             to "us.zoom.videomeetings",
        "microsoft teams"  to "com.microsoft.teams",
        "teams"            to "com.microsoft.teams",
        "slack"            to "com.Slack",
        "notion"           to "notion.id",
        "onedrive"         to "com.microsoft.skydrive",
        "dropbox"          to "com.dropbox.android",
        "linkedin"         to "com.linkedin.android",
        "reddit"           to "com.reddit.frontpage",
    )

    /**
     * Attempts a fuzzy match against the known app aliases using
     * Levenshtein edit distance.
     *
     * Used by [CommandRouter] as a fallback when an exact alias
     * lookup fails.
     *
     * Algorithm:
     * 1. Normalize input (remove spaces & punctuation, lowercase).
     * 2. For each alias, compute the Levenshtein distance between
     *    the normalized input and the normalized alias.
     * 3. Return the **original** (un-normalized) alias whose distance
     *    is the minimum, provided the distance is within threshold:
     *      - distance ≤ 3 for aliases longer than 5 characters
     *      - distance ≤ 1 for aliases of 5 characters or shorter
     * 4. Return null if no alias meets the threshold.
     *
     * @param input Raw user input (e.g. "youtub", "instagrm").
     * @return The best-matching alias key, or null if no good match.
     */
    fun fuzzyMatchApp(input: String): String? {
        val normalized = normalize(input)
        if (normalized.isEmpty()) return null

        var bestAlias: String? = null
        var bestDistance = Int.MAX_VALUE

        for (alias in appAliases.keys) {
            val normalizedAlias = normalize(alias)
            val distance = levenshtein(normalized, normalizedAlias)

            if (distance < bestDistance) {
                bestDistance = distance
                bestAlias = alias
            }
        }

        // Apply threshold based on the length of the best-matching alias
        val threshold = if ((bestAlias?.length ?: 0) > 5) 3 else 1
        return if (bestDistance <= threshold) bestAlias else null
    }

    /**
     * Normalizes a string for fuzzy comparison:
     * removes all whitespace and punctuation, and lowercases.
     */
    private fun normalize(s: String): String {
        return s.lowercase().filter { it.isLetterOrDigit() }
    }

    /**
     * Computes the Levenshtein edit distance between two strings.
     *
     * Uses the classic dynamic-programming approach with O(a·b) time
     * and O(min(a,b)) space (two-row optimisation).
     */
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        // Ensure `prev` is the shorter row for space optimisation
        val (shorter, longer) = if (a.length <= b.length) a to b else b to a

        var prev = IntArray(shorter.length + 1) { it }
        var curr = IntArray(shorter.length + 1)

        for (j in 1..longer.length) {
            curr[0] = j
            for (i in 1..shorter.length) {
                val cost = if (shorter[i - 1] == longer[j - 1]) 0 else 1
                curr[i] = minOf(
                    prev[i] + 1,       // deletion
                    curr[i - 1] + 1,   // insertion
                    prev[i - 1] + cost // substitution
                )
            }
            // Swap rows
            val tmp = prev
            prev = curr
            curr = tmp
        }

        return prev[shorter.length]
    }
}
