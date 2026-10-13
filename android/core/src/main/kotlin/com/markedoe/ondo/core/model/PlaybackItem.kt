package com.markedoe.ondo.core.model

import kotlinx.serialization.Serializable

/**
 * Everything the player needs to start a stream.
 *
 * [urls] are the candidates in the order the player should try them (stream first, then fallbacks such
 * as the `.m3u8` variant of a `.ts` stream), without duplicates. [headers] is `null` when no header is needed.
 */
@Serializable
data class PlaybackItem(
    val title: String,
    val subtitle: String? = null,
    val urls: List<String>,
    val headers: HttpHeaders? = null,
    val reference: MediaReference,
    val isLive: Boolean,
    val artworkUrl: String? = null,
    /** Catch-up playback of a past programme of a live channel. */
    val isCatchup: Boolean = false,
    /** Seconds to resume from. */
    val startPosition: Double? = null,
) {
    init {
        require(urls.isNotEmpty()) { "a playback item needs at least one URL" }
    }

    val id: String
        get() = reference.playbackKey

    val streamUrl: String
        get() = urls.first()

    val fallbackUrls: List<String>
        get() = urls.drop(1)
}
