package com.markedoe.ondo.player

import com.markedoe.ondo.core.playback.PlaybackBuffer
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
    /** What is played, in the problem report's words ("film", "episode", "catch-up"…, see `ProblemReport.kind`). */
    val kind: String? = null,
    /**
     * The buffer settings in seconds, 0 for automatic: carried here because the player can be recreated alone,
     * after the process was killed, before the settings are read again.
     */
    val liveBuffer: Int = 0,
    val vodBuffer: Int = 0,
    /** The live channel played: the remote's channel keys change from it (zapping). */
    val channelId: String? = null,
    /**
     * A replay of [channelId] from the provider's archive (catch-up): the instant it starts at (epoch milliseconds).
     * The player plays it as a film, with Back to live.
     */
    val replayStart: Long? = null,
) {
    /** A channel played from its archive: its programme from the start, a past one, a live pause resumed. */
    val isReplay: Boolean get() = replayStart != null && channelId != null

    /** The buffer setting for this item. */
    val bufferSeconds: Int get() = PlaybackBuffer.setting(isLive, liveBuffer, vodBuffer)
}
