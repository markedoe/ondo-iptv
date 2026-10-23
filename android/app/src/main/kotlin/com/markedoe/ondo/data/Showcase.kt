package com.markedoe.ondo.data

import com.markedoe.ondo.core.model.Episode
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.Season
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.core.model.SeriesDetails
import com.markedoe.ondo.ui.model.ShowcaseItem
import java.time.Instant
import java.time.ZoneId

/**
 * What the home screen and detail pages feature, as pure functions of the catalogue and the library
 * (the same rules as the iPhone app, see `AppModel+Showcase.swift`).
 */
class Showcase(
    private val content: PlaylistContent,
    private val library: LibraryState,
    private val playlistId: String,
    private val excludedCategoryIds: Set<String> = emptySet(),
    /** Finished movies stay out of the new titles, the carousel and similar titles (they stay in My List). */
    private val hideWatchedMovies: Boolean = false,
) {
    private val movies by lazy { content.movies.filter { it.categoryId !in excludedCategoryIds } }
    private val browsableMovies by lazy {
        if (!hideWatchedMovies) movies else library.finishedMovies(playlistId).let { watched -> movies.filter { it.id !in watched } }
    }
    private val series by lazy { content.series.filter { it.categoryId !in excludedCategoryIds } }
    private val moviesById by lazy { movies.associateBy { it.id } }
    private val seriesById by lazy { series.associateBy { it.id } }

    val hasVod: Boolean get() = movies.isNotEmpty() || series.isNotEmpty()

    /**
     * New movies: released this year or last year, by release year and not by the date the provider added
     * them (an old film added yesterday is not new). Newest first, then the latest additions, then the
     * best rated. Titles without a known year never appear.
     */
    fun newMovies(now: Long, limit: Int = 20): List<ShowcaseItem> {
        val window = releaseWindow(now)
        return browsableMovies.filter { it.year?.let { year -> year in window } == true }.map { ShowcaseItem.MovieItem(it) }.sortedWith(newer).take(limit)
    }

    /** New series, by the same rule as [newMovies]. */
    fun newSeries(now: Long, limit: Int = 20): List<ShowcaseItem> {
        val window = releaseWindow(now)
        return series.filter { it.year?.let { year -> year in window } == true }.map { ShowcaseItem.SeriesItem(it) }.sortedWith(newer).take(limit)
    }

    /** New movies and series alternating (the carousel). */
    fun newReleases(now: Long, limit: Int = 20): List<ShowcaseItem> {
        val newMovies = newMovies(now, limit)
        val newSeries = newSeries(now, limit)
        val result = ArrayList<ShowcaseItem>()
        for (index in 0 until maxOf(newMovies.size, newSeries.size)) {
            if (result.size >= limit) break
            newMovies.getOrNull(index)?.let { result.add(it) }
            if (result.size < limit) newSeries.getOrNull(index)?.let { result.add(it) }
        }
        return result
    }

    /** This year and last year. */
    private fun releaseWindow(now: Long): IntRange {
        val thisYear = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).year
        return (thisYear - 1)..thisYear
    }

    private val newer = compareByDescending<ShowcaseItem> { it.year ?: 0 }
        .thenByDescending { it.addedAt ?: Long.MIN_VALUE }
        .thenByDescending { it.rating ?: 0.0 }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }

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

    /** Up to [limit] titles for the carousel: what is being watched first, then new releases, no duplicates. */
    fun heroItems(now: Long, limit: Int = 5): List<ShowcaseItem> {
        val result = LinkedHashMap<String, ShowcaseItem>()
        fun add(item: ShowcaseItem?) {
            if (item != null && result.size < limit) result.putIfAbsent(item.key, item)
        }
        continueWatching().take(2).forEach { add(item(it.kind, it.itemId)) }
        newReleases(now, limit * 2).forEach { add(it) }
        return result.values.toList()
    }

    /** Other titles of the same category (none when that category is excluded, such as a hidden adult one). */
    fun similarMovies(movie: Movie, limit: Int = 15): List<Movie> =
        if (movie.categoryId in excludedCategoryIds) emptyList()
        else browsableMovies.asSequence().filter { it.categoryId == movie.categoryId && it.id != movie.id }.take(limit).toList()

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
