package com.markedoe.ondo.data

import com.markedoe.ondo.core.model.Episode
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.Season
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.core.model.SeriesDetails
import com.markedoe.ondo.ui.model.ShowcaseItem

/**
 * What the home screen and detail pages feature, as pure functions of the catalogue and the library
 * (the same rules as the iPhone app, see `AppModel+Showcase.swift`).
 */
class Showcase(
    private val content: PlaylistContent,
    private val library: LibraryState,
    private val playlistId: String,
    private val excludedCategoryIds: Set<String> = emptySet(),
) {
    private val movies by lazy { content.movies.filter { it.categoryId !in excludedCategoryIds } }
    private val series by lazy { content.series.filter { it.categoryId !in excludedCategoryIds } }
    private val moviesById by lazy { movies.associateBy { it.id } }
    private val seriesById by lazy { series.associateBy { it.id } }

    val moviesAreDated: Boolean get() = movies.any { it.addedAt != null }
    val seriesAreDated: Boolean get() = series.any { it.addedAt != null }

    /** Newest first when the provider dates them, otherwise in playlist order. */
    fun featuredMovies(limit: Int = 20): List<Movie> =
        if (moviesAreDated) movies.filter { it.addedAt != null }.sortedByDescending { it.addedAt }.take(limit) else movies.take(limit)

    fun featuredSeries(limit: Int = 20): List<Series> =
        if (seriesAreDated) series.filter { it.addedAt != null }.sortedByDescending { it.addedAt }.take(limit) else series.take(limit)

    /** Movies and series of My List, most recently added first. */
    fun myList(): List<ShowcaseItem> =
        library.favorites
            .filter { it.playlistId == playlistId && (it.kind == ItemKind.MOVIE || it.kind == ItemKind.SERIES) }
            .sortedByDescending { it.addedAt }
            .mapNotNull { item(it.kind, it.itemId) }

    fun continueWatching(): List<WatchEntry> = library.continueWatching(playlistId)

    fun item(kind: ItemKind, itemId: String): ShowcaseItem? = when (kind) {
        ItemKind.MOVIE -> moviesById[itemId]?.let { ShowcaseItem.MovieItem(it) }
        ItemKind.SERIES -> seriesById[itemId]?.let { ShowcaseItem.SeriesItem(it) }
        ItemKind.CHANNEL -> null
    }

    /** Up to [limit] titles for the carousel: what is being watched first, then featured titles, no duplicates. */
    fun heroItems(limit: Int = 5): List<ShowcaseItem> {
        val result = LinkedHashMap<String, ShowcaseItem>()
        fun add(item: ShowcaseItem?) {
            if (item != null && result.size < limit) result.putIfAbsent(item.key, item)
        }
        continueWatching().take(2).forEach { add(item(it.kind, it.itemId)) }
        val featuredMovies = featuredMovies(limit)
        val featuredSeries = featuredSeries(limit)
        for (index in 0 until maxOf(featuredMovies.size, featuredSeries.size)) {
            featuredMovies.getOrNull(index)?.let { add(ShowcaseItem.MovieItem(it)) }
            featuredSeries.getOrNull(index)?.let { add(ShowcaseItem.SeriesItem(it)) }
        }
        return result.values.toList()
    }

    /** Other titles of the same category (none from a hidden or locked one). */
    fun similarMovies(movie: Movie, limit: Int = 15): List<Movie> =
        if (movie.categoryId in excludedCategoryIds) emptyList()
        else movies.asSequence().filter { it.categoryId == movie.categoryId && it.id != movie.id }.take(limit).toList()

    fun similarSeries(item: Series, limit: Int = 15): List<Series> =
        if (item.categoryId in excludedCategoryIds) emptyList()
        else series.asSequence().filter { it.categoryId == item.categoryId && it.id != item.id }.take(limit).toList()

    fun progress(series: Series, episode: Episode): WatchEntry? =
        library.progress(WatchEntry(playlistId, ItemKind.SERIES, series.id, episode.id, title = "", lastWatchedAt = 0).key)

    fun progress(movie: Movie): WatchEntry? =
        library.progress(WatchEntry(playlistId, ItemKind.MOVIE, movie.id, null, title = "", lastWatchedAt = 0).key)

    /** Episode to watch next: the one in progress, else the one after the last finished, else the first. */
    fun nextEpisode(series: Series, details: SeriesDetails): Pair<Episode, Season>? {
        var last: Triple<Episode, Season, WatchEntry>? = null
        for (season in details.seasons) {
            for (episode in season.episodes) {
                val entry = progress(series, episode) ?: continue
                if (last == null || entry.lastWatchedAt > last.third.lastWatchedAt) last = Triple(episode, season, entry)
            }
        }
        if (last == null) {
            val first = details.seasons.firstOrNull() ?: return null
            return first.episodes.firstOrNull()?.let { it to first }
        }
        val (episode, season, entry) = last
        if (entry.isFinished) {
            val index = season.episodes.indexOf(episode)
            season.episodes.getOrNull(index + 1)?.let { return it to season }
            val seasonIndex = details.seasons.indexOf(season)
            details.seasons.getOrNull(seasonIndex + 1)?.let { next -> next.episodes.firstOrNull()?.let { return it to next } }
        }
        return episode to season
    }
}
