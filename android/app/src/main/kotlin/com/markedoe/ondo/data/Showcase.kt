package com.markedoe.ondo.data

import com.markedoe.ondo.core.library.CatalogueMatcher
import com.markedoe.ondo.core.library.FilmVersions
import com.markedoe.ondo.core.library.RecentReleases
import com.markedoe.ondo.core.library.TitleMetadata
import com.markedoe.ondo.core.library.TmdbRating
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
    /** What TMDb's voters said of the catalogue's titles, by [ShowcaseItem.key]: the carousel ranks by it. */
    private val votes: Map<String, TmdbRating> = emptyMap(),
    /** The versions of the catalogue's films: each film once in every list, finished when one of its versions is. */
    private val films: FilmVersions = FilmVersions.none(),
    /** Movies and series left out whatever their category: their own name carries a hidden language ("|PT| …"). */
    private val excludedMovieIds: Set<String> = emptySet(),
    private val excludedSeriesIds: Set<String> = emptySet(),
    /** What TMDb said of the catalogue's titles and the device kept, by [ShowcaseItem.key]: the carousel's release dates and backdrops. */
    private val kept: Map<String, TitleMetadata> = emptyMap(),
    /** The provider's release date of a title whose details are known (a movie's info, a series' latest episode). */
    private val providerRelease: (ShowcaseItem) -> String? = { null },
    /** Whether a title has a backdrop to show besides its poster (the provider's, TMDb's of this session). */
    private val hasBackdrop: (ShowcaseItem) -> Boolean = { false },
) {
    private fun shown(movie: Movie) = movie.categoryId !in excludedCategoryIds && movie.id !in excludedMovieIds
    private fun shown(series: Series) = series.categoryId !in excludedCategoryIds && series.id !in excludedSeriesIds
    private val movies by lazy { content.movies.filter(::shown) }
    private val watchedMovies by lazy { if (hideWatchedMovies) films.expand(library.finishedMovies(playlistId)) else emptySet() }
    private val browsableMovies by lazy {
        if (!hideWatchedMovies) movies else watchedMovies.let { watched -> movies.filter { it.id !in watched } }
    }
    private val series by lazy { content.series.filter(::shown) }
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
        val found = browsableMovies.filter { it.year?.let { year -> year in window } == true }.map { ShowcaseItem.MovieItem(it) }.sortedWith(newer)
        return films.distinctItems(found).take(limit)
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
        val watched = watchedMovies
        val found = trendingMatches.mapNotNull { match ->
            when (match) {
                is TrendingMatch.MovieMatch -> match.movie.takeIf { shown(it) && it.id !in watched }?.let { ShowcaseItem.MovieItem(it) }
                is TrendingMatch.SeriesMatch -> match.series.takeIf { shown(it) }?.let { ShowcaseItem.SeriesItem(it) }
            }
        }.distinctBy { it.key }
        return films.distinctItems(found)
    }

    /** Movies and series of My List, most recently added first; a film once, whichever of its versions are kept. */
    fun myList(): List<ShowcaseItem> =
        library.favorites
            .filter { it.playlistId == playlistId && (it.kind == ItemKind.MOVIE || it.kind == ItemKind.SERIES) }
            .sortedByDescending { it.addedAt }
            .mapNotNull { item(it.kind, it.itemId) }
            .let(films::distinctItems)

    /**
     * Continue watching: movies and episodes in progress, and a series whose last episode watched was finished goes on
     * with the next one ([nextEpisode], from its seasons in [details]: an entry at the start, or the next one's own
     * when it was started); a series watched to its last episode leaves. Without its seasons yet, the finished entry
     * stands for the series (its Play plays the next episode).
     */
    fun continueWatching(details: (String) -> SeriesDetails? = { null }): List<WatchEntry> =
        library.continueWatching(playlistId).mapNotNull { entry ->
            if (entry.kind != ItemKind.SERIES || !entry.isFinished) return@mapNotNull entry
            val series = seriesById[entry.itemId] ?: return@mapNotNull entry
            val seasons = details(series.id) ?: return@mapNotNull entry
            val (episode, _) = nextEpisode(series, seasons) ?: return@mapNotNull null
            if (episode.id == entry.episodeId) return@mapNotNull null
            progress(series, episode)?.takeIf { it.isResumable } ?: WatchEntry(
                playlistId, ItemKind.SERIES, series.id, episode.id, entry.title, "${episode.code} · ${episode.title}",
                episode.posterUrl ?: series.coverUrl, lastWatchedAt = entry.lastWatchedAt,
            )
        }

    fun item(kind: ItemKind, itemId: String): ShowcaseItem? = when (kind) {
        ItemKind.MOVIE -> moviesById[itemId]?.let { ShowcaseItem.MovieItem(it) }
        ItemKind.SERIES -> seriesById[itemId]?.let { ShowcaseItem.SeriesItem(it) }
        ItemKind.CHANNEL, ItemKind.PERSON -> null
    }

    /** The day of [now] (epoch ms) where the viewer is, as [RecentReleases] counts days. */
    private fun today(now: Long): Long = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    /** The day the carousel counts a title's release from ([RecentReleases.releaseDay]): TMDb's, else the provider's full date. */
    fun releaseDay(item: ShowcaseItem): Long? = RecentReleases.releaseDay(kept[item.key]?.releaseDate, providerRelease(item))

    private fun hasArtwork(item: ShowcaseItem): Boolean =
        !item.posterUrl.isNullOrBlank() || !kept[item.key]?.backdropUrl.isNullOrBlank() || hasBackdrop(item)

    private val categoryOf: (ShowcaseItem) -> String = { item ->
        when (item) {
            is ShowcaseItem.MovieItem -> item.movie.categoryId
            is ShowcaseItem.SeriesItem -> item.series.categoryId
        }
    }

    /**
     * The movies and series whose release TMDb is asked about for the carousel ([RecentReleases.mayBeRecent]: released
     * this year or last year, or changed by the provider in the last [RecentReleases.WIDEST] days, as a series with a new
     * season), the latest changed first; the caller asks those it needs ([RecentReleases.needsAsking]), a bounded number.
     */
    fun releasesToAsk(now: Long): List<ShowcaseItem> {
        val today = today(now)
        val found = browsableMovies.filter { RecentReleases.mayBeRecent(it.year, it.addedAt, today) }.map { ShowcaseItem.MovieItem(it) } +
            series.filter { RecentReleases.mayBeRecent(it.year, it.addedAt, today) }.map { ShowcaseItem.SeriesItem(it) }
        // sortedByDescending is stable: titles without a date keep the playlist's order, after the others. A title listed
        // several times (once per new episode, under other categories) is asked once, by its latest entry.
        return films.distinctItems(found).sortedByDescending { it.addedAt ?: Long.MIN_VALUE }.distinctBy(::identity)
    }

    /**
     * The titles worth preparing ahead (Settings, "Prepare new titles"): the carousel's, the titles whose release the
     * carousel needs ([releasesToAsk]), the new movies and series and the trending ones, each once, up to [limit].
     */
    fun aheadTitles(now: Long, limit: Int): List<ShowcaseItem> =
        (heroItems(now) + releasesToAsk(now).take(limit) + newReleases(now, limit) + trending())
            .distinctBy { it.key }
            .take(limit)

    /** What a title is for the carousel, whatever the provider's entry ([RecentReleases.identity]). */
    private fun identity(item: ShowcaseItem): String = RecentReleases.identity(item.title, item is ShowcaseItem.SeriesItem, item.year)

    /**
     * Up to [limit] titles for the carousel, by release date and not by the day the provider added them
     * ([RecentReleases.pick]): the movies released in the last 90 days and the series with an episode in the last 30,
     * best first by their rating for new titles plus freshness, those not rated enough after them by date, movies and
     * series in turn; with no recent release, none (the carousel hides). Only titles
     * with artwork, never one of a hidden category, hidden by name or watched when Settings hides those; each film, and each series, once.
     */
    fun heroItems(now: Long, limit: Int = HERO_TITLES): List<ShowcaseItem> {
        val today = today(now)
        val dated = HashMap<String, Long>()
        val shown = (browsableMovies.map { ShowcaseItem.MovieItem(it) } + series.map { ShowcaseItem.SeriesItem(it) }).filter { item ->
            val day = releaseDay(item) ?: return@filter false
            dated[item.key] = day
            today - day in 0 until RecentReleases.WIDEST
        }
        val byKey = HashMap<String, ShowcaseItem>()
        // One card per title ([RecentReleases.identity]): a series listed once per new episode or under other categories.
        val candidates = films.distinctItems(shown)
            .map { item ->
                byKey[item.key] = item
                val rating = votes[item.key] ?: kept[item.key]?.rating
                RecentReleases.Candidate(item.key, item.title, dated[item.key], rating?.average, rating?.votes ?: 0, categoryOf(item), hasArtwork(item), identity(item), item is ShowcaseItem.SeriesItem)
            }
        return RecentReleases.pick(candidates, today, limit, excludedCategoryIds).mapNotNull { byKey[it.id] }
    }

    /**
     * "More like this" for a movie: other movies of its category (the provider's grouping, often one language or one
     * genre; none when that category is excluded, such as a hidden adult one), never the movie itself or another copy
     * of it (another language or quality, see [CatalogueMatcher.isSameFilm]), each film once, finished ones left out
     * when Settings hides them. Best first: the most genres, director and actors shared with it (known from the pages
     * opened, [details]), then released in the same years, then the best rated, then in the playlist's order.
     */
    fun similarMovies(movie: Movie, limit: Int = 15, related: List<Movie> = emptyList(), details: (String) -> MediaDetails? = { null }): List<Movie> {
        if (!shown(movie)) return emptyList()
        // TMDb's recommendations the playlist has come first (from any category: TMDb chose them), then the closest of
        // the category (another one is often another language).
        val shown = browsableIds
        // Never another version of the film itself.
        val own = films.groupOf(movie)
        fun versionOfItself(other: Movie) = own >= 0 && films.isGrouped && films.groupOf(other) == own
        val pinned = films.distinct(related.filter { it.id in shown && it.id != movie.id && !versionOfItself(it) })
        val candidates = films.distinct(browsableMovies.filter { it.id != movie.id && it.categoryId == movie.categoryId && !versionOfItself(it) })
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
        if (!shown(item)) return emptyList()
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
        val candidates = series.filter { it.id != item.id && it.id !in pinnedIds && it.categoryId == item.categoryId }
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
