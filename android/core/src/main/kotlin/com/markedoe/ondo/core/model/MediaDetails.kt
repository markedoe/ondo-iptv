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
)

/** Series metadata with its seasons and episodes. */
@Serializable
data class SeriesDetails(
    val info: MediaDetails,
    val seasons: List<Season>,
)
