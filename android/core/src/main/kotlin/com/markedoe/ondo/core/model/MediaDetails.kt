package com.markedoe.ondo.core.model

import kotlinx.serialization.Serializable

/** Rich metadata shown on the movie / series detail screens. */
@Serializable
data class MediaDetails(
    val title: String,
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val durationSeconds: Int? = null,
    val rating: Double? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val trailerYouTubeId: String? = null,
    val originalTitle: String? = null,
    val tmdbId: String? = null,
    /** What the provider's `get_vod_info` says of the stream's picture and sound (a movie's only); null when nothing. */
    val streamSpecs: StreamSpecs? = null,
    /**
     * What the provider's `get_vod_info` answer held (a movie's only): the names of the fields of its `info` and whether
     * it had a `video` and an `audio` block, for the title data panel. Null for details read before it was kept, or not
     * from a provider's `get_vod_info`.
     */
    val providerInfo: ProviderInfo? = null,
)

/**
 * What a provider's `get_vod_info` answer held: the names of the fields of its `info` (never their values: a value can
 * hold an address), at most [MAX_KEYS], and whether its `video` and `audio` blocks (ffprobe's) were there.
 */
@Serializable
data class ProviderInfo(
    val keys: List<String> = emptyList(),
    val video: Boolean = false,
    val audio: Boolean = false,
) {
    companion object {
        const val MAX_KEYS = 40
        private val KEY = Regex("[A-Za-z0-9_-]{1,32}")

        /** The names among [names] the panel may show: plain field names only, sorted, at most [MAX_KEYS]. */
        fun keysOf(names: Iterable<String>): List<String> = names.filter { KEY.matches(it) }.distinct().sorted().take(MAX_KEYS)
    }
}

/** Series metadata with its seasons and episodes. */
@Serializable
data class SeriesDetails(
    val info: MediaDetails,
    val seasons: List<Season>,
)
