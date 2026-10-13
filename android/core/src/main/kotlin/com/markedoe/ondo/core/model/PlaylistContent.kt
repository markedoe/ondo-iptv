package com.markedoe.ondo.core.model

import kotlinx.serialization.Serializable

/** Everything a playlist contains, as cached on disk. */
@Serializable
data class PlaylistContent(
    val channels: List<Channel> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val series: List<Series> = emptyList(),
    val liveCategories: List<MediaCategory> = emptyList(),
    val movieCategories: List<MediaCategory> = emptyList(),
    val seriesCategories: List<MediaCategory> = emptyList(),
    /** Guide URLs advertised by the source (M3U `url-tvg`, Xtream `xmltv.php`). */
    val providerEpgUrls: List<String> = emptyList(),
    val account: AccountInfo? = null,
    /** When the content was downloaded, epoch millis (0 = unknown). */
    val fetchedAt: Long = 0L,
) {
    val isEmpty: Boolean
        get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()

    fun categories(type: ContentType): List<MediaCategory> = when (type) {
        ContentType.LIVE -> liveCategories
        ContentType.MOVIE -> movieCategories
        ContentType.SERIES -> seriesCategories
    }

    fun count(type: ContentType): Int = when (type) {
        ContentType.LIVE -> channels.size
        ContentType.MOVIE -> movies.size
        ContentType.SERIES -> series.size
    }

    /** Channels renamed / renumbered by the user. A renamed channel keeps its original name for guide matching. */
    fun applying(edits: Map<String, ChannelEdit>): PlaylistContent {
        if (edits.isEmpty()) return this
        return copy(
            channels = channels.map { channel ->
                val edit = edits[channel.id] ?: return@map channel
                var updated = channel
                val name = edit.name
                if (!name.isNullOrEmpty() && name != channel.name) {
                    updated = updated.copy(epgName = channel.epgName ?: channel.name, name = name)
                }
                if (edit.number != null) updated = updated.copy(number = edit.number)
                updated
            },
        )
    }

    companion object {
        val EMPTY = PlaylistContent()
    }
}

/** Xtream account status returned by `player_api.php`. */
@Serializable
data class AccountInfo(
    val username: String,
    val status: String,
    val isTrial: Boolean = false,
    /** Subscription end, epoch millis. */
    val expiresAt: Long? = null,
    /** Account creation, epoch millis. */
    val createdAt: Long? = null,
    val activeConnections: Int? = null,
    val maxConnections: Int? = null,
    val allowedOutputFormats: List<String> = emptyList(),
    val message: String? = null,
    /** IANA time zone of the panel (`Europe/Paris`), used for catch-up URLs. */
    val serverTimeZone: String? = null,
    val serverUrl: String? = null,
) {
    val isActive: Boolean
        get() = status.lowercase() == "active"
}
