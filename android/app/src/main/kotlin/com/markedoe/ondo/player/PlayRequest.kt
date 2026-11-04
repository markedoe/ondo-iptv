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
    /** What the Subtitles panel's online search looks for: a film or an episode; null for a channel. */
    val subtitleSearch: SubtitleSearchHint? = null,
) {
    /** A channel played from its archive: its programme from the start, a past one, a live pause resumed. */
    val isReplay: Boolean get() = replayStart != null && channelId != null

    /** The buffer setting for this item. */
    val bufferSeconds: Int get() = PlaybackBuffer.setting(isLive, liveBuffer, vodBuffer)
}

/**
 * A film or an episode as the subtitle services know it: its title without the provider's tags, its year, the season and
 * episode, and TMDb's id when the provider gives it.
 */
@Serializable
data class SubtitleSearchHint(
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val tmdbId: Int? = null,
) {
    fun query(languages: List<String>) = com.markedoe.ondo.core.subtitles.SubtitleQuery(title, year, season, episode, languages, tmdbId)

    companion object {
        /** "|EN| Tom Clancy's Jack Ryan (2018)" -> "Tom Clancy's Jack Ryan", 2018. */
        fun of(title: String, year: Int?, season: Int? = null, episode: Int? = null, tmdbId: Int? = null): SubtitleSearchHint {
            val split = com.markedoe.ondo.core.parsing.TitleParser.splitYear(title)
            val name = com.markedoe.ondo.core.library.CatalogueMatcher.displayTitle(split.name).ifBlank { split.name }
            return SubtitleSearchHint(name.ifBlank { title }, year ?: split.year, season, episode, tmdbId)
        }
    }
}
