package com.markedoe.ondo.data

import com.markedoe.ondo.core.model.MediaDetails
import com.markedoe.ondo.core.model.SeriesDetails
import kotlinx.serialization.Serializable

/**
 * The movie and series pages of a playlist as the provider last returned them, kept between launches (one document
 * per playlist, next to its catalogue). Pages and Home's backdrops show them at once; a copy that is too old is
 * fetched again, and still shown when the provider cannot be reached.
 */
@Serializable
data class DetailsArchive(
    val movies: Map<String, Entry<MediaDetails>> = emptyMap(),
    val series: Map<String, Entry<SeriesDetails>> = emptyMap(),
) {
    @Serializable
    data class Entry<T>(val value: T, val fetchedAt: Long)

    /** At most [movieLimit] movies and [seriesLimit] series: the most recently fetched. */
    fun trimmed(movieLimit: Int = MOVIE_LIMIT, seriesLimit: Int = SERIES_LIMIT) =
        DetailsArchive(newest(movies, movieLimit), newest(series, seriesLimit))

    companion object {
        /** A movie's page hardly changes; a series gains episodes. */
        const val MOVIE_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L
        const val SERIES_MAX_AGE_MS = 12 * 60 * 60 * 1000L

        /** The most recently fetched are kept: a series with all its episodes takes far more room than a movie. */
        const val MOVIE_LIMIT = 1_000
        const val SERIES_LIMIT = 300

        /** Fetched less than [maxAge] ago. A date in the future (the clock was changed) is not trusted. */
        fun isFresh(fetchedAt: Long?, maxAge: Long, now: Long): Boolean {
            if (fetchedAt == null) return false
            val age = now - fetchedAt
            return age in 0 until maxAge
        }

        private fun <T> newest(entries: Map<String, Entry<T>>, limit: Int): Map<String, Entry<T>> =
            if (entries.size <= limit) {
                entries
            } else {
                entries.entries.sortedByDescending { it.value.fetchedAt }.take(limit.coerceAtLeast(0)).associate { it.key to it.value }
            }
    }
}
