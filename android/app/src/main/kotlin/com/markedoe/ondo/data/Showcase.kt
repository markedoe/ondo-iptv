package com.markedoe.ondo.data

import com.markedoe.ondo.core.library.CatalogueMatcher
import com.markedoe.ondo.core.library.TrendingMatch
import com.markedoe.ondo.core.model.Episode
import com.markedoe.ondo.core.model.MediaDetails
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
 * (the same rules as the iPhone app, see `AppModel+Showcase.swift`, except that More like this puts the closest
 * titles first and leaves out copies of the title: see [similarMovies]).
 */
class Showcase(
    private val content: PlaylistContent,
    private val library: LibraryState,
    private val playlistId: String,
    private val excludedCategoryIds: Set<String> = emptySet(),
    /** Finished movies stay out of the new titles, the carousel and similar titles (they stay in My List). */
    private val hideWatchedMovies: Boolean = false,
    /** Titles of the catalogue that are trending on TMDb (with the viewer's token), in TMDb's order. */
    private val trendingMatches: List<TrendingMatch> = emptyList(),
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

    /** The trending titles to show: not in an excluded category, and not finished when Settings hides those. */
    fun trending(): List<ShowcaseItem> {
        val watched = if (hideWatchedMovies) library.finishedMovies(playlistId) else emptySet()
        return trendingMatches.mapNotNull { match ->
            when (match) {
                is TrendingMatch.MovieMatch -> match.movie.takeIf { it.categoryId !in excludedCategoryIds && it.id !in watched }?.let { ShowcaseItem.MovieItem(it) }
                is TrendingMatch.SeriesMatch -> match.series.takeIf { it.categoryId !in excludedCategoryIds }?.let { ShowcaseItem.SeriesItem(it) }
            }
        }.distinctBy { it.key }
    }

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
        ItemKind.CHANNEL, ItemKind.PERSON -> null
    }

    /**
     * The best rated movies and series the provider added in the last [RECENT_DAYS] days (its own date, which an M3U
     * list does not give), each film once whatever its copies (language, quality).
     */
    fun bestRecent(now: Long, limit: Int = HERO_TITLES): List<ShowcaseItem> {
        val since = now / 1_000 - RECENT_DAYS * 86_400L
        val recent = browsableMovies.map { ShowcaseItem.MovieItem(it) } + series.map { ShowcaseItem.SeriesItem(it) }
        return recent
            .filter { (it.addedAt ?: Long.MIN_VALUE) >= since && (it.rating ?: 0.0) > 0.0 }
            .sortedWith(compareByDescending<ShowcaseItem> { it.rating ?: 0.0 }.thenByDescending { it.addedAt ?: 0L }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            .distinctBy { "${it.title.trim().lowercase()}|${it.year}" }
            .take(limit)
    }

    /**
     * The titles worth preparing ahead (Settings, "Prepare new titles"): the carousel's, the best rated of the week,
     * the new movies and series and the trending ones, each once, up to [limit].
     */
    fun aheadTitles(now: Long, limit: Int): List<ShowcaseItem> =
        (heroItems(now) + bestRecent(now, limit) + newReleases(now, limit) + trending())
            .distinctBy { it.key }
            .take(limit)

    /** Up to [limit] titles for the carousel: the best rated of the week ([bestRecent]), then trending titles, then new releases, no duplicates. */
    fun heroItems(now: Long, limit: Int = HERO_TITLES): List<ShowcaseItem> {
        val result = LinkedHashMap<String, ShowcaseItem>()
        fun add(item: ShowcaseItem?) {
            if (item != null && result.size < limit) result.putIfAbsent(item.key, item)
        }
        bestRecent(now, limit).forEach { add(it) }
        trending().forEach { add(it) }
        newReleases(now, limit * 2).forEach { add(it) }
        return result.values.toList()
    }

    /**
     * "More like this" for a movie: other movies of its category (the provider's grouping, often one language or one
     * genre; none when that category is excluded, such as a hidden adult one), never the movie itself or another copy
     * of it (another language or quality, see [CatalogueMatcher.isSameFilm]), each film once, finished ones left out
     * when Settings hides them. Best first: the most genres, director and actors shared with it (known from the pages
     * opened, [details]), then released in the same years, then the best rated, then in the playlist's order.
     */
    fun similarMovies(movie: Movie, limit: Int = 15, related: List<Movie> = emptyList(), details: (String) -> MediaDetails? = { null }): List<Movie> {
        if (movie.categoryId in excludedCategoryIds) return emptyList()
        // TMDb's recommendations the playlist has come first, then the closest of the category and of the pages
        // opened elsewhere (a movie whose page names genres and people can be compared from any category).
        val shown = browsableIds
        val pinned = related.filter { it.id in shown && it.id != movie.id }
        val candidates = browsableMovies.filter { it.id != movie.id && (it.categoryId == movie.categoryId || details(it.id) != null) }
        val pinnedIds = pinned.mapTo(HashSet()) { it.id }
        val ranked = Similarity.rank(
            Similarity.Title(movie.name, movie.year, details(movie.id)),
            candidates.filter { it.id !in pinnedIds }.map { Similarity.Title(it.name, it.year, details(it.id), it.rating) to it },
            limit,
        ) { it.id }
        return pinnedFirst(movie.name, movie.year, pinned, ranked, limit, { it.id }) { it.name to it.year }
    }

    private val browsableIds by lazy { browsableMovies.mapTo(HashSet()) { it.id } }

    /**
     * The [pinned] titles first, in their order, then the [ranked] ones: never the title itself ([name], [year]) or
     * another copy of a title already listed (another language or quality).
     */
    private fun <T> pinnedFirst(name: String, year: Int?, pinned: List<T>, ranked: List<T>, limit: Int, id: (T) -> String, title: (T) -> Pair<String, Int?>): List<T> {
        if (pinned.isEmpty()) return ranked.take(limit)
        class Kept(val item: T, val key: String, val name: String, val year: Int?)
        fun sameFilm(a: Kept, b: Kept) = a.key.isNotEmpty() && a.key == b.key && CatalogueMatcher.isSameFilm(a.name, a.year, b.name, b.year)
        val own = Kept(pinned.first(), CatalogueMatcher.filmKey(name), name, year)
        val ids = HashSet<String>()
        val kept = ArrayList<Kept>()
        for (item in pinned + ranked) {
            if (kept.size >= limit) break
            if (!ids.add(id(item))) continue
            val (itemName, itemYear) = title(item)
            val candidate = Kept(item, CatalogueMatcher.filmKey(itemName), itemName, itemYear)
            if (sameFilm(own, candidate) || kept.any { sameFilm(it, candidate) }) continue
            kept += candidate
        }
        return kept.map { it.item }
    }

    /** "More like this" for a series, by the rules of [similarMovies]; a series list names its genre and cast itself. */
    fun similarSeries(item: Series, limit: Int = 15, related: List<Series> = emptyList(), details: (String) -> MediaDetails? = { null }): List<Series> {
        if (item.categoryId in excludedCategoryIds) return emptyList()
        fun title(series: Series): Similarity.Title {
            val info = details(series.id)
            return Similarity.Title(
                series.name, series.year,
                MediaDetails(
                    title = series.name,
                    genre = info?.genre ?: series.genre,
                    cast = info?.cast ?: series.cast,
                    director = info?.director ?: series.director,
                ),
                series.rating ?: info?.rating,
            )
        }
        val pinned = related.filter { it.id in seriesById && it.id != item.id }
        val pinnedIds = pinned.mapTo(HashSet()) { it.id }
        val candidates = series.filter { it.id != item.id && it.id !in pinnedIds && (it.categoryId == item.categoryId || details(it.id) != null) }
        val ranked = Similarity.rank(title(item), candidates.map { title(it) to it }, limit) { it.id }
        return pinnedFirst(item.name, item.year, pinned, ranked, limit, { it.id }) { it.name to it.year }
    }

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
            // An episode listed twice (the same address) is not the one after it.
            val index = season.episodes.indexOf(episode)
            season.episodes.drop(index + 1).firstOrNull { it.streamUrl != episode.streamUrl }?.let { return it to season }
            val seasonIndex = details.seasons.indexOf(season)
            details.seasons.getOrNull(seasonIndex + 1)?.let { next -> next.episodes.firstOrNull()?.let { return it to next } }
        }
        return episode to season
    }
}

/** How many titles the carousel shows at most. */
const val HERO_TITLES = 15

/** How far back the carousel looks for the best rated additions. */
const val RECENT_DAYS = 7L
