package dev.firetvonwatch.domain.model

data class TvApp(val packageName: String, val label: String)

/** Label (in the TV's language) and icon PNG read from the TV. [icon] is null when the app has none. */
class AppDetails(val packageName: String, val label: String, val icon: ByteArray?)

/**
 * Friendly labels for popular streaming apps. Fire TV ships some of them preinstalled as
 * system apps, so they're listed explicitly; other system apps are hidden from the Apps page.
 * Unknown packages fall back to a name derived from the package.
 */
object KnownApps {
    private val labels = mapOf(
        "com.netflix.ninja" to "Netflix",
        "com.amazon.firebat" to "Prime Video",
        "com.amazon.avod" to "Prime Video",
        "com.amazon.firetv.youtube" to "YouTube",
        "com.amazon.firetv.youtube.tv" to "YouTube TV",
        "com.google.android.youtube.tv" to "YouTube",
        "com.disney.disneyplus" to "Disney+",
        "com.hbo.hbonow" to "Max",
        "com.wbd.stream" to "Max",
        "com.apple.atve.amazon.appletv" to "Apple TV",
        "com.spotify.tv.android" to "Spotify",
        "tv.twitch.android.viewer" to "Twitch",
        "tv.twitch.android.app" to "Twitch",
        "com.plexapp.android" to "Plex",
        "org.xbmc.kodi" to "Kodi",
        "org.jellyfin.androidtv" to "Jellyfin",
    )

    fun labelFor(packageName: String): String =
        labels[packageName] ?: packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }

    fun isKnown(packageName: String): Boolean = packageName in labels

    fun toApp(packageName: String): TvApp = TvApp(packageName, labelFor(packageName))
}
