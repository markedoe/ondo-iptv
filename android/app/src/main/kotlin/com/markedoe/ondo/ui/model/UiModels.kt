package com.markedoe.ondo.ui.model

import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.Series

/** A movie or a series as shown on posters, in rows and in the carousel. */
sealed interface ShowcaseItem {
    /** Unique across movies and series. */
    val key: String
    val title: String
    val posterUrl: String?
    val year: Int?
    val rating: Double?
    /** When the provider added it, in epoch seconds. */
    val addedAt: Long?

    data class MovieItem(val movie: Movie) : ShowcaseItem {
        override val key get() = "m-" + movie.id
        override val title get() = movie.name
        override val posterUrl get() = movie.posterUrl
        override val year get() = movie.year
        override val rating get() = movie.rating
        override val addedAt get() = movie.addedAt
    }

    data class SeriesItem(val series: Series) : ShowcaseItem {
        override val key get() = "s-" + series.id
        override val title get() = series.name
        override val posterUrl get() = series.coverUrl
        override val year get() = series.year
        override val rating get() = series.rating
        override val addedAt get() = series.addedAt
    }
}

/** A "continue watching" card. */
data class ResumeItem(
    val key: String,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val progress: Float,
    val minutesLeft: Int,
    /** An episode: Home shows it in the series in progress, apart from the movies. */
    val isSeries: Boolean = false,
    /** A movie or an episode: its page (the series' for an episode) opens from the card's long press. */
    val hasPage: Boolean = false,
)

/** An actor or a director kept in My List or a collection: their name, and their photo (TMDb's) when known. */
data class PersonRow(val name: String, val photoUrl: String? = null)

/** A channel with what is on now. */
data class ChannelRow(
    val channel: Channel,
    val nowTitle: String?,
    val nowProgress: Float?,
    val nextTitle: String?,
)
