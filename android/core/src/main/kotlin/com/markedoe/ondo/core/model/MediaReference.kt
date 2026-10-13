package com.markedoe.ondo.core.model

import kotlinx.serialization.Serializable

/** Stable pointer to a channel, movie, series or episode inside a playlist. */
@Serializable
data class MediaReference(
    val playlistId: String,
    val type: ContentType,
    /** Channel / movie / series id. For episodes this is the series id. */
    val itemId: String,
    /** Set for series episodes. */
    val episodeId: String? = null,
) {
    /** Key that identifies the exact playable thing (episode level for series). */
    val playbackKey: String
        get() = listOf(playlistId, type.rawValue, itemId, episodeId.orEmpty()).joinToString("|")

    /** Key that identifies the browsable item (series level for episodes). */
    val itemKey: String
        get() = listOf(playlistId, type.rawValue, itemId).joinToString("|")
}
