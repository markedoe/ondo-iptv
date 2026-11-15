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
)

/** Series metadata with its seasons and episodes. */
@Serializable
data class SeriesDetails(
    val info: MediaDetails,
    val seasons: List<Season>,
)
