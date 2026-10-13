package com.markedoe.ondo.player

import kotlinx.serialization.Serializable

/** Everything the player needs, passed to [PlayerActivity] as JSON (independent of the catalogue). */
@Serializable
data class PlayRequest(
    val title: String,
    val subtitle: String? = null,
    /** Main address first, then fallbacks (another format or another version of the channel). */
    val urls: List<String>,
    val headers: Map<String, String> = emptyMap(),
    val isLive: Boolean,
    val artworkUrl: String? = null,
    /** Where to resume, in milliseconds. */
    val startPositionMs: Long = 0,
    /** Identifies the history entry to update with the progress. */
    val progressKey: String? = null,
)
