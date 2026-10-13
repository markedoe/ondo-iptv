package com.markedoe.ondo.core.model

import java.util.UUID
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where a playlist comes from. */
@Serializable
sealed interface PlaylistSource {
    /** M3U / M3U8 / XSPF / JSON playlist downloaded from a URL. */
    @Serializable
    @SerialName("remote")
    data class Remote(val url: String) : PlaylistSource

    /** Xtream Codes API login. */
    @Serializable
    @SerialName("xtream")
    data class Xtream(val credentials: XtreamCredentials) : PlaylistSource

    /** A playlist file imported by the user (absolute path on the device). */
    @Serializable
    @SerialName("local")
    data class LocalFile(val path: String) : PlaylistSource

    val kindLabel: String
        get() = when (this) {
            is Remote -> "M3U URL"
            is LocalFile -> "File"
            is Xtream -> "Xtream Codes"
        }

    /** Password that should be stored in the keystore rather than in plain files. */
    val secret: String?
        get() = (this as? Xtream)?.credentials?.password

    /** The same source with its secret replaced (sources without a secret are returned unchanged). */
    fun withSecret(secret: String): PlaylistSource = when (this) {
        is Xtream -> copy(credentials = credentials.copy(password = secret))
        is Remote, is LocalFile -> this
    }

    val xtreamCredentials: XtreamCredentials?
        get() = (this as? Xtream)?.credentials
}

/** A user configured playlist (one IPTV subscription / source). */
@Serializable
data class Playlist(
    /** Random UUID string; required when decoding. */
    @Required val id: String = UUID.randomUUID().toString(),
    val name: String,
    val source: PlaylistSource,
    /** Additional XMLTV sources added by the user. */
    val epgUrls: List<String> = emptyList(),
    /** User-Agent sent with every request of this playlist (playlist, guide and streams). */
    val userAgent: String? = null,
    /** Re-download the playlist when it is older than this many hours (0 = manual only). */
    val autoRefreshHours: Int = 24,
    /** Referer sent with every request of this playlist. */
    val referrer: String? = null,
    /** Other headers sent with every request of this playlist. */
    val extraHeaders: Map<String, String> = emptyMap(),
    /** Use the guide advertised by the playlist itself (`url-tvg` header, Xtream `xmltv.php`). */
    val useProviderEpg: Boolean = true,
    /** Hours to shift guide times by (`tvg-shift`), for badly configured providers. */
    val epgTimeShiftHours: Double = 0.0,
    val xtreamOutput: XtreamOutputFormat = XtreamOutputFormat.M3U8,
    /** Xtream accounts: load the `get.php` M3U export instead of the JSON API ("force M3U"). */
    val xtreamForceM3u: Boolean = false,
    /** Epoch millis. */
    val createdAt: Long = System.currentTimeMillis(),
    /** Epoch millis of the last successful refresh. */
    val lastRefreshedAt: Long? = null,
    /** Guide channel chosen by the user per playlist channel (`channelId -> guide id`, `""` = no guide). */
    val epgChannelOverrides: Map<String, String> = emptyMap(),
    /** Names and numbers changed by the user, by channel id. */
    val channelEdits: Map<String, ChannelEdit> = emptyMap(),
) {
    /** Playlist-level headers ([userAgent], [referrer], [extraHeaders]) as one value. */
    val headers: HttpHeaders
        get() = HttpHeaders(userAgent = userAgent, referrer = referrer, extra = extraHeaders)

    fun needsRefresh(now: Long = System.currentTimeMillis()): Boolean {
        val last = lastRefreshedAt ?: return true
        if (autoRefreshHours <= 0) return false
        return now - last > autoRefreshHours * 3_600_000L
    }
}
