package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.Episode
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.core.model.SeriesDetails
import java.util.Locale

/**
 * The names the core gives in English where the provider gives none: "Uncategorized" (an M3U entry without
 * `group-title`, an Xtream category without a name), "Other" and "Other 2" (categories the panel's items use but its
 * lists leave out), "Channel 12", "Movie 7" and "Series 3" (an Xtream item without a name, by its id) and "Episode 4".
 * They stay in English where they are kept (category ids, saved catalogues and details, sync), and the app shows them
 * in its language with these words: [uncategorized] and [other] as they are, the others patterns of one number
 * (`"Chaîne %1$d"`) formatted with [locale]. Same rules as the Swift `PlaceholderNames`.
 */
data class PlaceholderNames(
    val uncategorized: String,
    val other: String,
    val otherNumbered: String,
    val channel: String,
    val movie: String,
    val series: String,
    val episode: String,
    val locale: Locale = Locale.ROOT,
) {
    fun rename(category: MediaCategory): MediaCategory {
        val name = when {
            category.name == UNCATEGORIZED -> uncategorized
            category.name == OTHER -> other
            category.name.startsWith("$OTHER ") -> numberAfter("$OTHER ", category.name)?.takeIf { it >= 2 }?.let { format(otherNumbered, it) }
            else -> null
        }
        return if (name == null || name == category.name) category else category.copy(name = name)
    }

    fun rename(channel: Channel): Channel {
        val name = named(channel.name, CHANNEL, channel.xtreamStreamId, this.channel) ?: return channel
        return channel.copy(name = name)
    }

    fun rename(movie: Movie): Movie {
        val name = named(movie.name, MOVIE, movie.xtreamStreamId, this.movie) ?: return movie
        return movie.copy(name = name)
    }

    /** The series and the episodes that come with it (M3U). */
    fun rename(series: Series): Series {
        val name = named(series.name, SERIES, series.xtreamSeriesId, this.series)
        val episodes = series.episodes.renamed(::rename)
        if (name == null && episodes === series.episodes) return series
        return series.copy(name = name ?: series.name, episodes = episodes)
    }

    fun rename(episode: Episode): Episode {
        val title = named(episode.title, EPISODE, episode.number, this.episode) ?: return episode
        return episode.copy(title = title)
    }

    fun rename(details: SeriesDetails): SeriesDetails {
        val seasons = details.seasons.renamed { season ->
            val episodes = season.episodes.renamed(::rename)
            if (episodes === season.episodes) season else season.copy(episodes = episodes)
        }
        return if (seasons === details.seasons) details else details.copy(seasons = seasons)
    }

    /** The same instance when nothing in it is named by the core (the screens' caches go by identity). */
    fun rename(content: PlaylistContent): PlaylistContent {
        val live = content.liveCategories.renamed(::rename)
        val movieCategories = content.movieCategories.renamed(::rename)
        val seriesCategories = content.seriesCategories.renamed(::rename)
        val channels = content.channels.renamed(::rename)
        val movies = content.movies.renamed(::rename)
        val series = content.series.renamed(::rename)
        if (live === content.liveCategories && movieCategories === content.movieCategories &&
            seriesCategories === content.seriesCategories && channels === content.channels &&
            movies === content.movies && series === content.series
        ) {
            return content
        }
        return content.copy(
            liveCategories = live,
            movieCategories = movieCategories,
            seriesCategories = seriesCategories,
            channels = channels,
            movies = movies,
            series = series,
        )
    }

    /** The name in these words when [name] is the core's `"<word> <number>"`, else null. */
    private fun named(name: String, word: String, number: Int?, pattern: String): String? {
        if (number == null || !name.startsWith(word) || name != "$word $number") return null
        return format(pattern, number).takeIf { it != name }
    }

    private fun format(pattern: String, number: Int): String = String.format(locale, pattern, number)

    private fun numberAfter(prefix: String, name: String): Int? {
        val digits = name.substring(prefix.length)
        if (digits.isEmpty() || digits.length > 9 || !digits.all { it in '0'..'9' } || digits.startsWith('0')) return null
        return digits.toInt()
    }

    companion object {
        /** The core's own names (`M3uContentBuilder`, `XtreamDtos`, `XtreamClient`, `TitleParser`). */
        const val UNCATEGORIZED = "Uncategorized"
        const val OTHER = "Other"
        const val CHANNEL = "Channel"
        const val MOVIE = "Movie"
        const val SERIES = "Series"
        const val EPISODE = "Episode"

        /** The core's names as they are. */
        val ENGLISH = PlaceholderNames(UNCATEGORIZED, OTHER, "$OTHER %1\$d", "$CHANNEL %1\$d", "$MOVIE %1\$d", "$SERIES %1\$d", "$EPISODE %1\$d")

        /** The list itself when no item changes, else a copy with the changed ones. */
        private inline fun <T> List<T>.renamed(transform: (T) -> T): List<T> {
            var result: MutableList<T>? = null
            for (index in indices) {
                val item = get(index)
                val renamed = transform(item)
                if (renamed !== item) (result ?: toMutableList().also { result = it })[index] = renamed
            }
            return result ?: this
        }
    }
}
