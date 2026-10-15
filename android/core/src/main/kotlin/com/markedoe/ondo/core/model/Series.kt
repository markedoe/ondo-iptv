package com.markedoe.ondo.core.model

import java.util.Locale
import kotlinx.serialization.Serializable

/** One episode of a [Series]. */
@Serializable
data class Episode(
    val id: String,
    val title: String,
    val season: Int,
    val number: Int,
    val streamUrl: String,
    val posterUrl: String? = null,
    val plot: String? = null,
    val durationSeconds: Int? = null,
    val releaseDate: String? = null,
    val rating: Double? = null,
    val headers: HttpHeaders? = null,
) {
    /** `"S01E05"`. */
    val code: String
        get() = String.format(Locale.ROOT, "S%02dE%02d", season, number)
}

/** A series. Episodes are known up-front for M3U playlists; Xtream series load them on demand. */
@Serializable
data class Series(
    val id: String,
    val name: String,
    val coverUrl: String? = null,
    val categoryId: String,
    val plot: String? = null,
    val rating: Double? = null,
    val year: Int? = null,
    val genre: String? = null,
    /** Xtream series lists name the cast and the director (movie lists do not): searchable without opening pages. */
    val cast: String? = null,
    val director: String? = null,
    /** Last modification reported by the provider, in epoch **seconds** (same unit as [Movie.addedAt]). */
    val addedAt: Long? = null,
    val xtreamSeriesId: Int? = null,
    val episodes: List<Episode> = emptyList(),
)

/** A season of a series, as returned by the Xtream details call. */
@Serializable
data class Season(
    val number: Int,
    val name: String,
    val episodes: List<Episode>,
    val coverUrl: String? = null,
    val overview: String? = null,
) {
    val id: Int
        get() = number
}
