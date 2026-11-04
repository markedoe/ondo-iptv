package com.markedoe.ondo.ui

import com.markedoe.ondo.core.TextNormalizer
import com.markedoe.ondo.core.library.AwardsCatalogue
import com.markedoe.ondo.core.library.CategoryTags
import com.markedoe.ondo.core.library.EpisodeTitles
import com.markedoe.ondo.core.library.FilmVersions
import com.markedoe.ondo.core.library.PictureSupport
import com.markedoe.ondo.core.library.People
import com.markedoe.ondo.core.library.PeopleIndex
import com.markedoe.ondo.core.library.RowOrder
import com.markedoe.ondo.core.library.TitleMetadata
import com.markedoe.ondo.core.library.TmdbRating
import com.markedoe.ondo.core.library.TrendingMatch
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.Episode
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.core.model.MediaDetails
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.Season
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.core.model.SeriesDetails
import com.markedoe.ondo.data.Favorite
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.data.PosterSort
import com.markedoe.ondo.data.Showcase
import com.markedoe.ondo.data.SyncDevice
import com.markedoe.ondo.data.WatchEntry
import com.markedoe.ondo.data.shownDetails
import com.markedoe.ondo.data.shownEpisode
import com.markedoe.ondo.ui.components.HeroPage
import com.markedoe.ondo.ui.model.ChannelRow
import com.markedoe.ondo.ui.model.PersonRow
import com.markedoe.ondo.ui.model.PosterFacts
import com.markedoe.ondo.ui.model.ResumeItem
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.model.TitleHeader
import com.markedoe.ondo.ui.screens.BrowseKind
import com.markedoe.ondo.ui.screens.BrowseUiState
import com.markedoe.ondo.ui.screens.CategoryRow
import com.markedoe.ondo.ui.screens.CollectionChoice
import com.markedoe.ondo.ui.screens.CollectionRow
import com.markedoe.ondo.ui.screens.CollectionUiState
import com.markedoe.ondo.ui.screens.GuideRow
import com.markedoe.ondo.ui.screens.GuideUiState
import com.markedoe.ondo.ui.screens.HomeUiState
import com.markedoe.ondo.ui.screens.LiveUiState
import com.markedoe.ondo.ui.screens.MovieDetailUiState
import com.markedoe.ondo.ui.screens.MyListUiState
import com.markedoe.ondo.ui.screens.PersonUiState
import com.markedoe.ondo.ui.screens.PlaylistSummary
import com.markedoe.ondo.ui.screens.RowOrderEntry
import com.markedoe.ondo.ui.screens.RowOrderUiState
import com.markedoe.ondo.ui.screens.SearchFilters
import com.markedoe.ondo.ui.screens.SearchUiState
import com.markedoe.ondo.ui.screens.SeriesDetailUiState
import com.markedoe.ondo.ui.screens.SettingsUiState
import com.markedoe.ondo.ui.screens.VersionChip
import com.markedoe.ondo.ui.screens.VodUiState
import com.markedoe.ondo.ui.screens.WatchProgress
import java.util.Locale

/** Localised words the mappers need (resolved by the caller, so the mappers stay pure). */
data class Labels(
    val movie: String = "Movie",
    val series: String = "Series",
    val locale: Locale = Locale.getDefault(),
    /** The viewer's language and country codes, for the "show only your languages?" suggestion. */
    val viewerCodes: Set<String> = CategoryFilter.viewerCodes(listOf(locale)),
)

/** Builds the screens' states from the app state. Pure functions, unit tested. */
object UiMappers {
    private const val ROW_LENGTH = 20

    /**
     * Categories hidden by the user, on their own or with their language tag, and adult-looking ones unless the
     * user shows them in Settings; a Kids profile never sees those (docs/PROFILES.md).
     */
    fun hiddenCategoryIds(state: OndoState): Set<String> = with(state.content) {
        val showAdult = state.settings.showAdultCategories && !state.activeProfile.isKids
        (movieCategories + seriesCategories + liveCategories)
            .filter { (!showAdult && it.looksAdult) || CategoryFilter.isHidden(state, it) }
            .mapTo(HashSet()) { it.id }
    }

    /** Live TV's channels: hidden categories, the provider's headers and channels of a hidden tag left out. */
    fun visibleChannels(state: OndoState, hidden: Set<String> = hiddenCategoryIds(state)): List<Channel> {
        val left = CategoryFilter.hiddenChannelIds(state)
        return state.content.channels.filter { it.categoryId !in hidden && it.id !in left }
    }

    /**
     * [kept]: what TMDb said and the device kept, by poster key ([OndoRepository.keptPosterFacts]); its votes, and those of
     * the answers of this session, rank the carousel's best recent titles.
     */
    fun showcase(state: OndoState, kept: Map<String, TitleMetadata> = emptyMap(), grouped: FilmVersions = films(state)): Showcase {
        val votes = HashMap<String, TmdbRating>()
        kept.forEach { (key, title) -> title.rating?.let { votes[key] = it } }
        votes.putAll(state.tmdbRatings)
        val byName = CategoryFilter.hiddenTitles(state)
        return Showcase(
            state.content, state.library, state.activePlaylistId.orEmpty(), hiddenCategoryIds(state), state.settings.hideWatchedMovies, state.trending, votes, grouped,
            excludedMovieIds = byName.movies, excludedSeriesIds = byName.series,
        )
    }

    /**
     * The movies left out where the viewer browses: the finished ones, when Settings hides them, with the other versions
     * of their films (a film is watched once one of its versions is).
     */
    fun hiddenMovieIds(state: OndoState): Set<String> =
        if (state.settings.hideWatchedMovies) films(state).expand(state.library.finishedMovies(state.activePlaylistId.orEmpty())) else emptySet()

    private val noVersions = FilmVersions.none()

    @Volatile private var filmVersions: Pair<PlaylistContent, FilmVersions>? = null

    /** What the screen shows (HDR, Dolby Vision, 4K), for the version a film plays first; set by the activity. */
    @Volatile var pictureSupport: PictureSupport = PictureSupport()

    /**
     * The versions of the catalogue's films (`FilmVersions`): grouped once per catalogue, in the background (the view
     * model starts it when a catalogue arrives); each stream alone when Settings' "Group versions" is off.
     */
    fun films(state: OndoState): FilmVersions = if (state.settings.groupVersions) films(state.content) else noVersions

    /** [films] when already grouped, else each stream alone: for the main thread, which never groups a catalogue. */
    fun filmsIfReady(state: OndoState): FilmVersions {
        if (!state.settings.groupVersions) return noVersions
        val known = filmVersions ?: return noVersions
        return if (known.first === state.content) known.second else noVersions
    }

    private val filmsLock = Any()

    /**
     * Built once per catalogue: the screens asking at once when a catalogue arrives wait for the one build (a TV's memory
     * holds one grouping of a large catalogue, not one per screen).
     */
    fun films(content: PlaylistContent): FilmVersions {
        filmVersions?.let { (known, versions) -> if (known === content) return versions }
        synchronized(filmsLock) {
            filmVersions?.let { (known, versions) -> if (known === content) return versions }
            return FilmVersions.of(content.movies).also { filmVersions = content to it }
        }
    }

    /**
     * The episode a series' Play plays, and its season. On the main thread (a tap): never waits for a catalogue's grouping
     * being built ([filmsIfReady]); a series' episodes do not depend on it.
     */
    fun nextEpisode(state: OndoState, series: Series, details: SeriesDetails): Pair<Episode, Season>? =
        showcase(state, grouped = filmsIfReady(state)).nextEpisode(series, details)

    /** The versions of [movie]'s film the viewer can see ([movie] itself, and the others not in a hidden category), in catalogue order. */
    fun visibleVersions(state: OndoState, movie: Movie, grouped: FilmVersions = films(state)): List<Movie> {
        val versions = grouped.versions(movie)
        if (versions.size < 2) return versions
        val hidden = hiddenCategoryIds(state)
        val byName = CategoryFilter.hiddenTitles(state).movies
        // A stream a panel lists twice (the same id) is one version.
        return versions.filter { it.id == movie.id || (it.categoryId !in hidden && it.id !in byName) }.distinctBy { it.id }
    }

    /**
     * The version of [movie]'s film its card plays and its page selects first (`FilmVersions.defaultVersion`): the one in
     * progress, else the one chosen last on this device, else the best for the screen, else the first.
     */
    fun defaultVersion(state: OndoState, movie: Movie, versions: List<Movie> = visibleVersions(state, movie)): Movie {
        if (versions.size < 2) return movie
        val playlistId = state.activePlaylistId.orEmpty()
        val inProgress = versions.mapNotNull { version ->
            state.library.progress(WatchEntry(playlistId, ItemKind.MOVIE, version.id, null, title = "", lastWatchedAt = 0).key)
                ?.takeIf { it.isResumable }?.let { version to it.lastWatchedAt }
        }.maxByOrNull { it.second }?.first
        return FilmVersions.defaultVersion(
            versions, inProgress?.id, state.settings.chosenVersions, pictureSupport, FilmVersions.categoryNames(state.content.movieCategories),
        ) ?: movie
    }

    /** The badges of a film's versions, each with its stream (for its page); empty when the film has one. */
    fun versionChips(state: OndoState, versions: List<Movie>): List<VersionChip> {
        if (versions.size < 2) return emptyList()
        val badges = FilmVersions.badges(versions, FilmVersions.categoryNames(state.content.movieCategories))
        return versions.zip(badges) { movie, badge -> VersionChip(movie.id, badge.label, badge.ordinal) }
    }

    /** Movies and series by id; the first of an id the catalogue lists twice, as a search through the list would find. */
    class CatalogueIndex(content: PlaylistContent) {
        val movies: Map<String, Movie> = HashMap<String, Movie>(content.movies.size).also { map -> content.movies.forEach { map.getOrPut(it.id) { it } } }
        val series: Map<String, Series> = HashMap<String, Series>(content.series.size).also { map -> content.series.forEach { map.getOrPut(it.id) { it } } }
    }

    @Volatile private var catalogueIndex: Pair<PlaylistContent, CatalogueIndex>? = null

    /**
     * Drops what the screens built from a catalogue that left them (switched or deleted playlist): these objects live as
     * long as the app, and a large catalogue and its indexes are most of a TV's memory. Main thread.
     */
    fun forgetCatalogue() {
        catalogueIndex = null
        filmVersions = null
        SearchIndex.forget()
        CategoryFilter.forget()
        MovieAwards.forget()
    }

    /** Built once per catalogue, in the background (it goes through the whole catalogue). */
    fun index(content: PlaylistContent): CatalogueIndex {
        catalogueIndex?.let { (known, index) -> if (known === content) return index }
        return CatalogueIndex(content).also { catalogueIndex = content to it }
    }

    fun movie(state: OndoState, id: String): Movie? = index(state.content).movies[id]

    fun series(state: OndoState, id: String): Series? = index(state.content).series[id]

    /**
     * The line of an episode in Continue watching, `"S03E02 · <title>"`, as its series' page shows it: from the series'
     * episodes when they are known ([shownEpisode]: TMDb's name too), else the line kept, cleaned the same way.
     */
    private fun episodeLine(state: OndoState, entry: WatchEntry): String? {
        val series = series(state, entry.itemId)
        val episode = entry.episodeId?.let { id -> state.seriesDetails[entry.itemId]?.seasons?.firstNotNullOfOrNull { season -> season.episodes.firstOrNull { it.id == id } } }
        if (series != null && episode != null) return state.shownEpisode(series, episode).let { "${it.code} · ${it.title}" }
        return entry.subtitle?.let { EpisodeTitles.cleanSubtitle(it, series?.name ?: entry.title, state.placeholderNames) }
    }

    fun resume(entry: WatchEntry, state: OndoState): ResumeItem {
        val backdrop = when (entry.kind) {
            ItemKind.MOVIE -> backdrop(state, ItemKind.MOVIE, entry.itemId)
            ItemKind.SERIES -> backdrop(state, ItemKind.SERIES, entry.itemId)
            ItemKind.CHANNEL, ItemKind.PERSON -> null
        }
        // Continue Watching stays per stream: a film of several versions says which one is in progress.
        val version = if (entry.kind == ItemKind.MOVIE && entry.subtitle.isNullOrBlank()) {
            movie(state, entry.itemId)?.let { movie -> versionChips(state, visibleVersions(state, movie)).firstOrNull { it.id == movie.id }?.label }
        } else {
            null
        }
        // An episode's line as its series' page shows it, a line kept before included ("S03E02 · Episode 2").
        val subtitle = if (entry.kind == ItemKind.SERIES) episodeLine(state, entry) else entry.subtitle
        return ResumeItem(
            key = entry.key,
            title = entry.title,
            subtitle = version ?: subtitle,
            imageUrl = backdrop ?: entry.imageUrl,
            progress = entry.progress.toFloat(),
            minutesLeft = entry.minutesLeft,
            isSeries = entry.kind == ItemKind.SERIES,
            hasPage = entry.kind != ItemKind.CHANNEL,
            showcase = when (entry.kind) {
                ItemKind.MOVIE -> movie(state, entry.itemId)?.let { ShowcaseItem.MovieItem(it) }
                ItemKind.SERIES -> series(state, entry.itemId)?.let { ShowcaseItem.SeriesItem(it) }
                ItemKind.CHANNEL, ItemKind.PERSON -> null
            },
        )
    }

    /**
     * A title's wide picture (as on Apple devices): the provider's backdrop unless it did not load this session, else
     * the one found on TMDb, else the trending one.
     */
    fun backdrop(state: OndoState, item: ShowcaseItem): String? = when (item) {
        is ShowcaseItem.MovieItem -> backdrop(state, ItemKind.MOVIE, item.movie.id)
        is ShowcaseItem.SeriesItem -> backdrop(state, ItemKind.SERIES, item.series.id)
    }

    private fun backdrop(state: OndoState, kind: ItemKind, id: String): String? {
        val provider = if (kind == ItemKind.MOVIE) state.movieDetails[id]?.backdropUrl else state.seriesDetails[id]?.info?.backdropUrl
        provider?.takeIf { it.isNotBlank() && it !in state.deadBackdrops }?.let { return it }
        state.tmdbBackdrops[(if (kind == ItemKind.MOVIE) "m-" else "s-") + id]?.takeIf { it !in state.deadBackdrops }?.let { return it }
        val trending = state.trending.firstOrNull { match ->
            when (match) {
                is TrendingMatch.MovieMatch -> kind == ItemKind.MOVIE && match.movie.id == id
                is TrendingMatch.SeriesMatch -> kind == ItemKind.SERIES && match.series.id == id
            }
        }
        return trending?.trending?.backdropUrl?.takeIf { it !in state.deadBackdrops }
    }

    /**
     * TMDb's rating of a title with its number of votes, when a TMDb answer gave it (trending, backdrop, cast); null
     * otherwise: no count is guessed.
     */
    fun tmdbRating(state: OndoState, item: ShowcaseItem): TmdbRating? = state.tmdbRatings[item.key] ?: state.trending.firstOrNull { match ->
        when (match) {
            is TrendingMatch.MovieMatch -> item is ShowcaseItem.MovieItem && match.movie.id == item.movie.id
            is TrendingMatch.SeriesMatch -> item is ShowcaseItem.SeriesItem && match.series.id == item.series.id
        }
    }?.trending?.rating

    /**
     * The ratings side by side: the provider's ("★ 7.1") and TMDb's with its number of votes ("TMDb 7.3 (12,345)"), as
     * "★ 7.1 · TMDb 7.3 (12,345)". The provider's alone without TMDb's votes; TMDb's alone when the provider gives none
     * or the same.
     */
    fun ratingFact(provider: Double?, votes: TmdbRating?, locale: Locale): String? {
        fun one(value: Double) = String.format(locale, "%.1f", value)
        val own = provider?.takeIf { it > 0 }?.let { "★ " + one(it) }
        val tmdb = votes?.let { "TMDb " + one(it.average) + " (" + java.text.NumberFormat.getIntegerInstance(locale).format(it.votes) + ")" }
        return when {
            tmdb == null -> own
            own == null || one(provider) == one(votes.average) -> tmdb
            else -> "$own · $tmdb"
        }
    }

    /**
     * What each poster of a category's page shows ([PosterFacts]): TMDb's rating (from the pages opened, its trending
     * titles, or [kept] on the device), else the provider's; TMDb's genres, else the provider's; two at most.
     */
    fun posterFacts(state: OndoState, items: List<ShowcaseItem>, kept: Map<String, TitleMetadata>, locale: Locale): Map<String, PosterFacts> {
        val trending = HashMap<String, TmdbRating>()
        for (match in state.trending) match.trending.rating?.let { trending.putIfAbsent(match.id, it) }
        val result = HashMap<String, PosterFacts>(items.size * 2)
        for (item in items) {
            val details = when (item) {
                is ShowcaseItem.MovieItem -> state.movieDetails[item.movie.id]
                is ShowcaseItem.SeriesItem -> state.seriesDetails[item.series.id]?.info
            }
            val stored = kept[item.key]
            val rating = (state.tmdbRatings[item.key] ?: trending[item.key] ?: stored?.rating)?.average
                ?: (details?.rating ?: item.rating)?.takeIf { it > 0 }
            val genres = stored?.genres?.map { it.name }?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
                ?: genres(details?.genre ?: (item as? ShowcaseItem.SeriesItem)?.series?.genre)
            result[item.key] = PosterFacts(
                rating = rating?.let { String.format(locale, "%.1f", it.coerceIn(0.0, 10.0)) },
                halfStars = rating?.let { halfStars(it) } ?: 0,
                genres = genres.distinct().take(2),
            )
        }
        return result
    }

    /**
     * What the TV's header shows of [item], the poster with the focus, from what the device has: TMDb's rating (from
     * the pages opened, its trending titles, or [kept] on the device), else the provider's; TMDb's genres, else the
     * provider's; the plot and the backdrop of the details kept, else the playlist's own.
     */
    fun titleHeader(state: OndoState, item: ShowcaseItem, kept: TitleMetadata?, locale: Locale): TitleHeader {
        val details = when (item) {
            is ShowcaseItem.MovieItem -> state.movieDetails[item.movie.id]
            is ShowcaseItem.SeriesItem -> state.seriesDetails[item.series.id]?.info
        }
        val format = { value: Double -> String.format(locale, "%.1f", value.coerceIn(0.0, 10.0)) }
        val tmdb = (tmdbRating(state, item) ?: kept?.rating)?.average
        val provider = (details?.rating ?: item.rating)?.takeIf { it > 0 }
        val genres = kept?.genres?.map { it.name }?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            ?: genres(details?.genre ?: (item as? ShowcaseItem.SeriesItem)?.series?.genre)
        val (plot, category) = when (item) {
            is ShowcaseItem.MovieItem -> item.movie.plot to categoryName(state, false, item.movie.categoryId)
            is ShowcaseItem.SeriesItem -> item.series.plot to categoryName(state, true, item.series.categoryId)
        }
        return TitleHeader(
            item = item,
            genres = genres.distinct().take(3),
            tmdbRating = tmdb?.let(format),
            providerRating = if (tmdb == null) provider?.let(format) else null,
            plot = (details?.plot?.takeIf { it.isNotBlank() } ?: plot)?.trim()?.takeIf { it.isNotEmpty() },
            backdropUrl = backdrop(state, item),
            category = category?.takeIf { it.isNotBlank() },
            logoUrl = state.tmdbLogos[item.key],
        )
    }

    /** A rating out of 10 as half stars out of five stars: 7.4 is three and a half stars. */
    fun halfStars(rating: Double): Int = kotlin.math.floor(rating.coerceIn(0.0, 10.0) + 0.5).toInt()

    fun progress(entry: WatchEntry?): WatchProgress? =
        entry?.takeIf { it.duration > 0 }?.let { WatchProgress(it.progress.toFloat(), it.minutesLeft, it.isFinished) }

    fun meta(item: ShowcaseItem, state: OndoState, labels: Labels): String {
        val details = when (item) {
            is ShowcaseItem.MovieItem -> state.movieDetails[item.movie.id]
            is ShowcaseItem.SeriesItem -> state.seriesDetails[item.series.id]?.info
        }
        val genre = (details?.genre ?: (item as? ShowcaseItem.SeriesItem)?.series?.genre)
            ?.split(",", "/")?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        return listOfNotNull(
            if (item is ShowcaseItem.MovieItem) labels.movie else labels.series,
            item.year?.toString(),
            genre,
            ratingFact(details?.rating ?: item.rating, tmdbRating(state, item), labels.locale),
        ).joinToString(" · ")
    }

    fun home(state: OndoState, labels: Labels, now: Long, kept: Map<String, TitleMetadata> = emptyMap()): HomeUiState {
        val showcase = showcase(state, kept)
        val playlistId = state.activePlaylistId.orEmpty()
        val hidden = hiddenCategoryIds(state)
        val hero = showcase.heroItems(now).map { item ->
            val resumable = (item as? ShowcaseItem.MovieItem)?.let { showcase.progress(defaultVersion(state, it.movie))?.isResumable } == true
            val details = when (item) {
                is ShowcaseItem.MovieItem -> state.movieDetails[item.movie.id]
                is ShowcaseItem.SeriesItem -> state.seriesDetails[item.series.id]?.info
            }
            val plot = when (item) {
                is ShowcaseItem.MovieItem -> item.movie.plot
                is ShowcaseItem.SeriesItem -> item.series.plot
            }
            HeroPage(item, meta(item, state, labels), isFavorite(state, item), resumable, backdrop(state, item), details?.plot ?: plot)
        }
        val channelsById = visibleChannels(state, hidden).associateBy { it.id }
        val favoriteChannels = state.library.favorites(playlistId, ItemKind.CHANNEL).mapNotNull { channelsById[it.itemId] }
        val recentChannels = state.library.recentChannels(playlistId).mapNotNull { channelsById[it.itemId] }
        val channels = favoriteChannels.ifEmpty { recentChannels }.ifEmpty { channelsById.values.take(ROW_LENGTH) }
        return HomeUiState(
            playlistName = state.activePlaylist?.name.orEmpty(),
            updatedAt = state.content.fetchedAt.takeIf { it > 0 && !state.content.isEmpty },
            now = now,
            hero = hero,
            continueWatching = showcase.continueWatching().map { resume(it, state) },
            myList = showcase.myList(),
            newMovies = showcase.newMovies(now),
            newSeries = showcase.newSeries(now),
            trending = showcase.trending(),
            hasVod = showcase.hasVod,
            channels = channels.map { channelRow(it, state, now) },
            channelsAreFavorites = favoriteChannels.isNotEmpty(),
            isLoading = state.isLoading,
            isRestoring = state.isRestoring,
            tagSuggestion = CategoryFilter.suggestion(state, labels.viewerCodes, labels.locale),
            profile = state.activeProfile.takeIf { state.profiles.isNotEmpty() },
        )
    }

    fun vod(state: OndoState, series: Boolean, title: String, now: Long = System.currentTimeMillis()): VodUiState {
        val showcase = showcase(state)
        val hidden = hiddenCategoryIds(state)
        // A panel can list a category twice (same id): one chip and one row each.
        val categories = (if (series) state.content.seriesCategories else state.content.movieCategories).filter { it.id !in hidden }.distinctBy { it.id }
        val watched = hiddenMovieIds(state)
        // A title whose own name carries a hidden language stays out of its category's row, and out of All.
        val byName = CategoryFilter.hiddenTitles(state)
        val all: List<ShowcaseItem>
        val allCount: Int
        val byCategory: Map<String, List<ShowcaseItem>>
        if (series) {
            val shown = state.content.series.filter { it.categoryId !in hidden && it.id !in byName.series }
            byCategory = shown.groupBy({ it.categoryId }) { ShowcaseItem.SeriesItem(it) }
            allCount = shown.size
            all = latest(shown, ROW_LENGTH) { it.addedAt }.map { ShowcaseItem.SeriesItem(it) }
        } else {
            val films = films(state)
            val shown = state.content.movies.filter { it.categoryId !in hidden && it.id !in watched && it.id !in byName.movies }
            // Each film once in a category's row, in every category one of its versions is in.
            byCategory = shown.groupBy { it.categoryId }.mapValues { (_, list) -> films.distinct(list).map { ShowcaseItem.MovieItem(it) } }
            // All: each film once, as the grid of the whole catalogue lists them (a list of the same movies, not copies).
            val distinct = films.distinct(shown)
            allCount = distinct.size
            all = latest(distinct, ROW_LENGTH) { it.addedAt }.map { ShowcaseItem.MovieItem(it) }
        }
        val rows = categories.mapNotNull { category -> byCategory[category.id]?.take(ROW_LENGTH)?.let { CategoryRow(category, it) } }
        val kind = if (series) ItemKind.SERIES else ItemKind.MOVIE
        val saved = state.library.rowOrder(series)
        // The viewer's order (Settings → Row order); a row they hid never shows, nor one a hidden category or language left out.
        val order = RowOrder.arrange(tabRowKeys(categories, series), saved?.order.orEmpty(), saved?.hidden.orEmpty())
            .filter { !it.hidden }.map { it.key }
        return VodUiState(
            title = title,
            categories = rows.map { it.category },
            continueWatching = showcase.continueWatching().filter { it.kind == kind }.map { resume(it, state) },
            myList = showcase.myList().filter { (it is ShowcaseItem.SeriesItem) == series },
            rows = rows,
            awardWinners = if (series) emptyList() else MovieAwards.winners(state, hidden, ROW_LENGTH, watched + byName.movies, films(state)).map { ShowcaseItem.MovieItem(it) },
            all = all,
            allCount = allCount,
            newReleases = if (series) showcase.newSeries(now, ROW_LENGTH) else showcase.newMovies(now, ROW_LENGTH),
            trending = showcase.trending().filter { (it is ShowcaseItem.SeriesItem) == series },
            order = order,
            series = series,
        )
    }

    /** The keys of the rows of the Movies or Series tab ([RowOrder]): the built-in rows, then [categories] in their order. */
    fun tabRowKeys(categories: List<MediaCategory>, series: Boolean): List<String> =
        RowOrder.builtIns(series) + categories.map { RowOrder.categoryKey(it.id, it.name) }

    /**
     * Settings → Row order: the rows of each tab in the viewer's order, hidden or not. The categories hidden in Settings
     * (or by a hidden language, or adult) are not listed: they never show, whatever the order.
     */
    fun rowOrder(state: OndoState): RowOrderUiState {
        val hidden = hiddenCategoryIds(state)
        fun tab(series: Boolean): List<RowOrderEntry> {
            val categories = (if (series) state.content.seriesCategories else state.content.movieCategories).filter { it.id !in hidden }.distinctBy { it.id }
            val names = categories.associate { RowOrder.categoryKey(it.id, it.name) to it.name }
            val saved = state.library.rowOrder(series)
            return RowOrder.arrange(tabRowKeys(categories, series), saved?.order.orEmpty(), saved?.hidden.orEmpty())
                .map { RowOrderEntry(it.key, names[it.key], it.hidden) }
        }
        return RowOrderUiState(movies = tab(series = false), series = tab(series = true))
    }

    /**
     * The [count] latest additions of [items] (by the provider's date, [addedAt]; those without one after, in the
     * playlist's order), without sorting the whole catalogue: a TV's memory holds no second copy of it.
     */
    internal fun <T> latest(items: List<T>, count: Int, addedAt: (T) -> Long?): List<T> {
        if (count <= 0) return emptyList()
        // Kept sorted, the latest first; an item later in the playlist goes after those of the same date.
        val kept = ArrayList<T>(count + 1)
        val dates = ArrayList<Long>(count + 1)
        for (item in items) {
            val date = addedAt(item) ?: Long.MIN_VALUE
            if (kept.size == count && date <= dates[count - 1]) continue
            var at = kept.size
            while (at > 0 && dates[at - 1] < date) at--
            kept.add(at, item)
            dates.add(at, date)
            if (kept.size > count) {
                kept.removeAt(count)
                dates.removeAt(count)
            }
        }
        return kept
    }

    /** Items of a poster grid, filtered by [filter] (accent and case insensitive), in the order [sort]. */
    fun grid(
        state: OndoState,
        series: Boolean,
        categoryId: String?,
        myList: Boolean,
        filter: String,
        anyType: Boolean = false,
        sort: PosterSort = PosterSort.PLAYLIST,
    ): List<ShowcaseItem> {
        val hidden = hiddenCategoryIds(state)
        val byName = CategoryFilter.hiddenTitles(state)
        val items: List<ShowcaseItem> = when {
            myList -> showcase(state).myList().filter { anyType || (it is ShowcaseItem.SeriesItem) == series }
            series -> state.content.series.filter { it.categoryId !in hidden && it.id !in byName.series && (categoryId == null || it.categoryId == categoryId) }.map { ShowcaseItem.SeriesItem(it) }
            else -> {
                val watched = hiddenMovieIds(state)
                films(state).distinct(state.content.movies.filter { it.categoryId !in hidden && it.id !in watched && it.id !in byName.movies && (categoryId == null || it.categoryId == categoryId) })
                    .map { ShowcaseItem.MovieItem(it) }
            }
        }
        val query = TextNormalizer.normalize(filter)
        return sorted(if (query.isEmpty()) items else items.filter { TextNormalizer.normalize(it.title).contains(query) }, sort)
    }

    /**
     * Grid order. Release date: newest year first, titles without a year last, the latest addition first within a
     * year, then by name.
     */
    fun sorted(items: List<ShowcaseItem>, sort: PosterSort): List<ShowcaseItem> {
        // One collator per sort, not per comparison (getInstance() is costly, and a sort compares n log n times).
        val collator = java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }
        val byName = Comparator<ShowcaseItem> { a, b -> collator.compare(a.title, b.title) }
        return when (sort) {
            PosterSort.PLAYLIST -> items
            PosterSort.NAME -> items.sortedWith(byName)
            PosterSort.RECENT -> items.sortedByDescending { it.addedAt ?: Long.MIN_VALUE }
            PosterSort.RELEASE -> items.sortedWith(
                compareByDescending<ShowcaseItem> { it.year ?: Int.MIN_VALUE }
                    .thenByDescending { it.addedAt ?: Long.MIN_VALUE }
                    .then(byName),
            )
            PosterSort.RATING -> items.sortedByDescending { it.rating ?: 0.0 }
        }
    }


    fun categoryName(state: OndoState, series: Boolean, categoryId: String?): String? =
        (if (series) state.content.seriesCategories else state.content.movieCategories).firstOrNull { it.id == categoryId }?.name

    fun live(state: OndoState, categoryId: String?, favoritesOnly: Boolean, now: Long): LiveUiState {
        val selection = liveChannels(state, categoryId, favoritesOnly)
        return LiveUiState(
            categories = selection.categories,
            selectedCategoryId = selection.categoryId,
            showingFavorites = favoritesOnly,
            channels = selection.channels.map { channelRow(it, state, now) },
            favoriteIds = selection.favoriteIds,
        )
    }

    /** The channels the player's channel keys go through: all of Live TV, hidden categories left out. */
    fun zappingChannels(state: OndoState): List<Channel> {
        val hidden = hiddenCategoryIds(state)
        return visibleChannels(state, hidden)
    }

    /**
     * The channels of a Live TV choice (My List, a category or all), the categories that have some, the favourites, and
     * the category shown ([categoryId] when it is still among them).
     */
    private class ChannelSelection(val channels: List<Channel>, val categories: List<MediaCategory>, val favoriteIds: Set<String>, val categoryId: String?)

    private fun liveChannels(state: OndoState, categoryId: String?, favoritesOnly: Boolean): ChannelSelection {
        val playlistId = state.activePlaylistId.orEmpty()
        val hidden = hiddenCategoryIds(state)
        val favoriteIds = state.library.favorites(playlistId, ItemKind.CHANNEL).mapTo(HashSet()) { it.itemId }
        val visible = visibleChannels(state, hidden)
        val used = visible.mapTo(HashSet()) { it.categoryId }
        val categories = state.content.liveCategories.filter { it.id in used }.distinctBy { it.id }
        // A category hidden since, or gone with a refresh: all channels again, rather than an empty list with no chip on.
        val shown = categoryId?.takeIf { id -> categories.any { it.id == id } }
        val channels = visible.filter {
            when {
                favoritesOnly -> it.id in favoriteIds
                shown != null -> it.categoryId == shown
                else -> true
            }
        }
        return ChannelSelection(channels, categories, favoriteIds, shown)
    }

    /** Rows of the TV guide at most: each row lays out its programmes. Beyond, a note asks to choose a category. */
    const val GUIDE_ROW_LIMIT = 300

    /** Two hours back and twelve ahead, from the half hour. */
    fun guideWindow(now: Long): LongRange {
        val halfHour = 30 * 60_000L
        val start = (now - 2 * 3_600_000L) / halfHour * halfHour
        return start..(start + 14 * 3_600_000L)
    }

    /** The TV guide of the Live TV choice: its channels (at most [GUIDE_ROW_LIMIT]) and their programmes. */
    fun guide(state: OndoState, categoryId: String?, favoritesOnly: Boolean, now: Long): GuideUiState {
        val selection = liveChannels(state, categoryId, favoritesOnly)
        val window = guideWindow(now)
        val guide = state.guide
        val playlistId = state.activePlaylistId.orEmpty()
        return GuideUiState(
            rows = selection.channels.take(GUIDE_ROW_LIMIT).map { GuideRow(it, guide?.programs(it, window.first, window.last).orEmpty()) },
            total = selection.channels.size,
            start = window.first,
            end = window.last,
            now = now,
            hasGuide = guide != null,
            isLoading = state.isLoading,
            categories = selection.categories,
            selectedCategoryId = selection.categoryId,
            showingFavorites = favoritesOnly,
            reminders = state.library.upcomingReminders(playlistId, now).mapTo(HashSet()) { "${it.channelId}|${it.start}" },
        )
    }

    /** The My List tab: the movies and series kept (latest addition first), and the channels. */
    fun myList(state: OndoState, now: Long): MyListUiState {
        val hidden = hiddenCategoryIds(state)
        val playlistId = state.activePlaylistId.orEmpty()
        val channelsById = visibleChannels(state, hidden).associateBy { it.id }
        return MyListUiState(
            movies = grid(state, series = false, categoryId = null, myList = true, filter = ""),
            series = grid(state, series = true, categoryId = null, myList = true, filter = ""),
            channels = state.library.favorites(playlistId, ItemKind.CHANNEL).mapNotNull { channelsById[it.itemId] }.map { channelRow(it, state, now) },
            collections = collectionRows(state, hidden),
            people = people(state.library.favorites),
        )
    }

    /** The people kept (actors and directors), latest addition first. */
    private fun people(items: List<Favorite>): List<PersonRow> =
        items.filter { it.kind == ItemKind.PERSON }.sortedByDescending { it.addedAt }.map { PersonRow(it.title, it.imageUrl) }

    /** The collections, each with the number of its items the collection page shows. */
    private fun collectionRows(state: OndoState, hidden: Set<String>): List<CollectionRow> {
        val playlistId = state.activePlaylistId.orEmpty()
        val byName = CategoryFilter.hiddenTitles(state)
        val shown by lazy {
            mapOf(
                ItemKind.MOVIE to state.content.movies.filter { it.categoryId !in hidden && it.id !in byName.movies }.mapTo(HashSet()) { it.id },
                ItemKind.SERIES to state.content.series.filter { it.categoryId !in hidden && it.id !in byName.series }.mapTo(HashSet()) { it.id },
                ItemKind.CHANNEL to visibleChannels(state, hidden).mapTo(HashSet()) { it.id },
            )
        }
        return state.library.collections.map { list ->
            CollectionRow(list.id, list.name, list.items.count { it.kind == ItemKind.PERSON || (it.playlistId == playlistId && it.itemId in shown.getValue(it.kind)) })
        }
    }

    /** A collection's items the playlist has, in the order they were added; null once it is deleted. */
    fun collection(state: OndoState, id: String, now: Long): CollectionUiState? {
        val list = state.library.collections.firstOrNull { it.id == id } ?: return null
        val playlistId = state.activePlaylistId.orEmpty()
        val hidden = hiddenCategoryIds(state)
        val byName = CategoryFilter.hiddenTitles(state)
        fun order(kind: ItemKind): Map<String, Int> =
            list.items.filter { it.playlistId == playlistId && it.kind == kind }.withIndex().associate { it.value.itemId to it.index }
        val movies = order(ItemKind.MOVIE)
        val series = order(ItemKind.SERIES)
        val channels = order(ItemKind.CHANNEL)
        return CollectionUiState(
            id = list.id,
            name = list.name,
            movies = state.content.movies.filter { it.id in movies && it.categoryId !in hidden && it.id !in byName.movies }.sortedBy { movies[it.id] }.map { ShowcaseItem.MovieItem(it) },
            series = state.content.series.filter { it.id in series && it.categoryId !in hidden && it.id !in byName.series }.sortedBy { series[it.id] }.map { ShowcaseItem.SeriesItem(it) },
            channels = visibleChannels(state, hidden).filter { it.id in channels }.sortedBy { channels[it.id] }.map { channelRow(it, state, now) },
            people = list.items.filter { it.kind == ItemKind.PERSON }.map { PersonRow(it.title, it.imageUrl) },
        )
    }

    /** The collections, ticked when they hold this item of the current playlist. */
    fun collectionChoices(state: OndoState, kind: ItemKind, itemId: String): List<CollectionChoice> {
        val playlistId = if (kind == ItemKind.PERSON) Favorite.ANY_PLAYLIST else state.activePlaylistId.orEmpty()
        return state.library.collections.map { CollectionChoice(it.id, it.name, it.contains(playlistId, kind, itemId)) }
    }

    fun channelRow(channel: Channel, state: OndoState, now: Long): ChannelRow {
        val guide = state.guide ?: return ChannelRow(channel, null, null, null)
        val current = guide.current(channel, now)
        // The programme on now comes first in `upcoming`; in a gap, the next one does.
        val next = guide.upcoming(channel, now, 2).firstOrNull { it != current }
        return ChannelRow(channel, current?.title, current?.progress(now)?.toFloat(), next?.title)
    }

    /** The keys ([ShowcaseItem.key]) of the movies and series in My List, for the current playlist. */
    /** A film is in My List when one of its versions is: every version's key then. Main thread ([filmsIfReady]). */
    fun myListKeys(state: OndoState): Set<String> {
        val playlistId = state.activePlaylistId.orEmpty()
        val movies = HashSet<String>()
        val keys = state.library.favorites.mapNotNullTo(HashSet()) { favorite ->
            when {
                favorite.playlistId != playlistId -> null
                favorite.kind == ItemKind.MOVIE -> {
                    movies.add(favorite.itemId)
                    "m-" + favorite.itemId
                }
                favorite.kind == ItemKind.SERIES -> "s-" + favorite.itemId
                else -> null
            }
        }
        filmsIfReady(state).expand(movies).forEach { keys.add("m-$it") }
        return keys
    }

    /** Whether the item is in My List: a film when one of its versions is. Main thread too ([filmsIfReady]). */
    fun isFavorite(state: OndoState, item: ShowcaseItem): Boolean {
        val playlistId = state.activePlaylistId.orEmpty()
        return when (item) {
            is ShowcaseItem.MovieItem -> visibleVersions(state, item.movie, filmsIfReady(state)).any { state.library.isFavorite(playlistId, ItemKind.MOVIE, it.id) }
            is ShowcaseItem.SeriesItem -> state.library.isFavorite(playlistId, ItemKind.SERIES, item.series.id)
        }
    }

    /**
     * The YouTube id of a trailer as the provider gives it: the id alone, or a YouTube address (watch, youtu.be,
     * embed, shorts). Null for anything else: no button rather than a broken one.
     */
    fun youTubeId(trailer: String?): String? {
        val text = trailer?.trim().orEmpty()
        if (YOUTUBE_ID.matches(text)) return text
        return YOUTUBE_ADDRESS.find(text)?.groupValues?.get(1)
    }

    private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")
    private val YOUTUBE_ADDRESS = Regex(
        """^(?:https?://)?(?:(?:www|m)\.)?(?:youtube(?:-nocookie)?\.com/(?:watch\?(?:[^#]*&)?v=|embed/|shorts/|v/)|youtu\.be/)([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])""",
    )

    /**
     * A movie's page: its details, and its film's versions as badges ([MovieDetailUiState.versions]) with [selected] (one
     * the viewer picked on the page), else the default version ([defaultVersion]), the one Play plays and whose progress
     * shows.
     */
    fun movieDetail(state: OndoState, movie: Movie, locale: Locale = Locale.getDefault(), selected: String? = null): MovieDetailUiState {
        val showcase = showcase(state)
        val item = ShowcaseItem.MovieItem(movie)
        val versions = visibleVersions(state, movie)
        val version = selected?.let { id -> versions.firstOrNull { it.id == id } } ?: defaultVersion(state, movie, versions)
        return MovieDetailUiState(
            item = item,
            details = state.movieDetails[movie.id],
            backdropUrl = backdrop(state, item),
            logoUrl = state.tmdbLogos[item.key],
            trailer = youTubeId(state.movieDetails[movie.id]?.trailerYouTubeId) ?: state.tmdbTrailers[item.key],
            faces = state.tmdbFaces[item.key].orEmpty(),
            votes = tmdbRating(state, item),
            progress = progress(showcase.progress(version)),
            inMyList = isFavorite(state, item),
            versions = versionChips(state, versions),
            version = version,
            similar = showcase.similarMovies(movie, related = state.tmdbRelated[item.key].orEmpty().mapNotNull { (it as? TrendingMatch.MovieMatch)?.movie }) { state.movieDetails[it] }
                .map { ShowcaseItem.MovieItem(it) },
            awards = MovieAwards.rows(state, movie, locale),
            collections = collectionChoices(state, ItemKind.MOVIE, movie.id),
        )
    }

    fun seriesDetail(state: OndoState, series: Series, selectedSeason: Int?, error: String?): SeriesDetailUiState {
        val showcase = showcase(state)
        val item = ShowcaseItem.SeriesItem(series)
        // The provider's episodes as they came (they are saved), shown with their own titles, TMDb's stills where the
        // provider has none, and an episode named by nothing in the app's words.
        val details = state.seriesDetails[series.id]?.let { state.shownDetails(series, it) }
        val next = details?.let { showcase.nextEpisode(series, it) }
        val episodeProgress = details?.seasons.orEmpty().flatMap { it.episodes }
            .mapNotNull { episode -> progress(showcase.progress(series, episode))?.let { episode.id to it } }
            .toMap()
        // A panel can list an episode twice in a season (kept so in the details on disk): each row once.
        val seasons = details?.seasons.orEmpty().map { season -> season.copy(episodes = season.episodes.distinctBy { it.id }) }
        return SeriesDetailUiState(
            item = item,
            details = details?.info,
            backdropUrl = backdrop(state, item),
            logoUrl = state.tmdbLogos[item.key],
            trailer = youTubeId(details?.info?.trailerYouTubeId) ?: state.tmdbTrailers[item.key],
            faces = state.tmdbFaces[item.key].orEmpty(),
            votes = tmdbRating(state, item),
            seasons = seasons,
            selectedSeason = selectedSeason ?: next?.second?.number,
            next = next?.first,
            nextProgress = next?.first?.let { episodeProgress[it.id] },
            episodeProgress = episodeProgress,
            inMyList = isFavorite(state, item),
            similar = showcase.similarSeries(series, related = state.tmdbRelated[item.key].orEmpty().mapNotNull { (it as? TrendingMatch.SeriesMatch)?.series }) { state.seriesDetails[it]?.info }
                .map { ShowcaseItem.SeriesItem(it) },
            error = error,
            collections = collectionChoices(state, ItemKind.SERIES, series.id),
        )
    }

    fun settings(state: OndoState, version: String, syncDevices: List<SyncDevice> = emptyList(), kindLabel: (Boolean) -> String): SettingsUiState = SettingsUiState(
        canChangeRowOrder = !state.activeProfile.isKids,
        playlists = state.playlists.map { playlist ->
            val host = runCatching { java.net.URI(playlist.url.trim()).host }.getOrNull()
            val active = playlist.id == state.activePlaylistId
            PlaylistSummary(
                playlist.id, playlist.name, listOfNotNull(kindLabel(playlist.isXtream), host).joinToString(" · "), active, playlist.isXtream,
                updatedAt = state.content.fetchedAt.takeIf { active && it > 0 && !state.content.isEmpty },
            )
        },
        showAdultCategories = state.settings.showAdultCategories,
        updateAwards = state.settings.updateAwards,
        prepareAhead = state.settings.prepareAhead,
        liveBuffer = state.settings.liveBuffer,
        vodBuffer = state.settings.vodBuffer,
        autoRefreshHours = state.settings.autoRefreshHours,
        version = version,
        isRefreshing = state.isLoading,
        hideWatchedMovies = state.settings.hideWatchedMovies,
        groupVersions = state.settings.groupVersions,
        syncOnLocalNetwork = state.settings.syncOnLocalNetwork,
        syncDevices = if (state.settings.syncOnLocalNetwork) syncDevices else emptyList(),
        lastCrash = state.lastCrash,
        hasTmdbToken = state.settings.tmdbToken != null,
        tmdbRefused = state.tmdbRefused,
    )

    /**
     * Longer than any genre: a field without separators in a long run of text. A genre opens its own page, and the
     * genre goes with the page into what Android keeps of the screens (a binder transaction, 1 MB at most).
     */
    const val MAX_GENRE_LENGTH = 200

    /** The genres of a genre field ("Drama, Thriller", "Action / Adventure"), each once ("Action & Adventure, Adventure"). */
    fun genres(field: String?): List<String> =
        field.orEmpty().split(',', '/', '|', '&').map { it.trim() }.filter { it.length in 1..MAX_GENRE_LENGTH }.distinct()

    /**
     * The titles of the playlist sharing a value of a movie's or a series' page, hidden categories left out, in
     * [sort]'s order; [PosterSort.PLAYLIST] keeps the page's own (winners first for an award, the newest for a year or a
     * festival, by name for a genre). [found]: the genre's titles found beyond the pages opened
     * ([com.markedoe.ondo.data.OndoRepository.genreTitles]), sorted with the others.
     */
    fun browse(
        state: OndoState,
        kind: BrowseKind,
        value: String,
        locale: Locale = Locale.getDefault(),
        found: List<TrendingMatch> = emptyList(),
        sort: PosterSort = PosterSort.PLAYLIST,
    ): BrowseUiState {
        val page = browsePage(state, kind, value, locale, found)
        if (sort == PosterSort.PLAYLIST) return page
        return page.copy(movies = sorted(page.movies, sort), series = sorted(page.series, sort))
    }

    private fun browsePage(state: OndoState, kind: BrowseKind, value: String, locale: Locale, found: List<TrendingMatch>): BrowseUiState {
        val hidden = hiddenCategoryIds(state)
        val watched = hiddenMovieIds(state)
        val byName = CategoryFilter.hiddenTitles(state)
        val movies = state.content.movies.filter { it.categoryId !in hidden && it.id !in watched && it.id !in byName.movies }
        val series = state.content.series.filter { it.categoryId !in hidden && it.id !in byName.series }
        val awards = MovieAwards.of(state)
        val films = films(state)
        // Each film once, the first of its versions in the page's order standing for it.
        fun items(list: List<Movie>) = films.distinct(list).map { ShowcaseItem.MovieItem(it) }
        return when (kind) {
            BrowseKind.AWARD -> {
                val award = state.awards.award(value)
                val language = if (locale.language == "zh") "zh-Hans" else locale.language
                // Winners first, then the most recent.
                val found = movies.mapNotNull { movie -> awards[movie.id]?.firstOrNull { it.awardId == value }?.let { movie to it } }
                    .sortedWith(compareByDescending<Pair<Movie, AwardsCatalogue.Entry>> { it.second.won }.thenByDescending { it.second.year }.thenBy { it.first.name })
                BrowseUiState(kind, value, award?.name(language) ?: value, movies = items(found.map { it.first }), festival = award?.group)
            }
            BrowseKind.FESTIVAL -> {
                val found = movies.filter { movie -> awards[movie.id].orEmpty().any { state.awards.award(it.awardId)?.group == value } }
                BrowseUiState(kind, value, movies = sorted(items(found), PosterSort.RELEASE))
            }
            BrowseKind.YEAR -> {
                val year = value.toIntOrNull()
                BrowseUiState(
                    kind, value,
                    movies = sorted(items(movies.filter { it.year == year }), PosterSort.RELEASE),
                    series = sorted(series.filter { it.year == year }.map { ShowcaseItem.SeriesItem(it) }, PosterSort.RELEASE),
                )
            }
            BrowseKind.GENRE -> {
                val wanted = TextNormalizer.normalize(value)
                fun has(field: String?) = genres(field).any { TextNormalizer.normalize(it) == wanted }
                // A movie's genre is known once its page was opened; a series' comes with the catalogue. TMDb's genres
                // (kept on the device, or its most popular titles of the genre) add those of the pages never opened.
                val foundMovies = found.mapNotNullTo(HashSet()) { (it as? TrendingMatch.MovieMatch)?.movie?.id }
                val foundSeries = found.mapNotNullTo(HashSet()) { (it as? TrendingMatch.SeriesMatch)?.series?.id }
                BrowseUiState(
                    kind, value,
                    movies = sorted(items(movies.filter { it.id in foundMovies || has(state.movieDetails[it.id]?.genre) }), PosterSort.NAME),
                    series = sorted(
                        series.filter { it.id in foundSeries || has(it.genre) || has(state.seriesDetails[it.id]?.info?.genre) }.map { ShowcaseItem.SeriesItem(it) },
                        PosterSort.NAME,
                    ),
                )
            }
        }
    }

    /**
     * The movies and series whose opened page names this person (cast or director), and the series whose list entry
     * does (series lists name their cast; movie lists do not), newest first.
     */
    fun person(state: OndoState, name: String, online: List<TrendingMatch>? = null, kept: List<TrendingMatch> = emptyList()): PersonUiState {
        val hidden = hiddenCategoryIds(state)
        val byName = CategoryFilter.hiddenTitles(state)
        fun names(details: MediaDetails) = People.mentions(details.cast, name) || People.mentions(details.director, name)
        val movieIds = state.movieDetails.filterValues(::names).keys
        val seriesIds = state.seriesDetails.filterValues { names(it.info) }.keys
        val watched = hiddenMovieIds(state)
        // The titles TMDb credits the person with that the playlist has: asked with the viewer's token, and those
        // kept on the device.
        val credited = online.orEmpty() + kept
        val onlineMovies = credited.mapNotNullTo(HashSet()) { (it as? TrendingMatch.MovieMatch)?.movie?.id }
        val onlineSeries = credited.mapNotNullTo(HashSet()) { (it as? TrendingMatch.SeriesMatch)?.series?.id }
        val movies = state.content.movies
            .filter { (it.id in movieIds || it.id in onlineMovies) && it.categoryId !in hidden && it.id !in watched && it.id !in byName.movies }
            .distinctBy { it.id }
            .let(films(state)::distinct)
            .map { ShowcaseItem.MovieItem(it) }
        val series = state.content.series.filter { show ->
            show.categoryId !in hidden && show.id !in byName.series &&
                (show.id in seriesIds || show.id in onlineSeries || People.mentions(show.cast, name) || People.mentions(show.director, name))
        }.distinctBy { it.id }.map { ShowcaseItem.SeriesItem(it) }
        val keptPerson = state.library.favorites.firstOrNull { it.kind == ItemKind.PERSON && it.itemId == name.trim() }
        val face = TextNormalizer.normalize(name)
        return PersonUiState(
            name,
            sorted(movies, PosterSort.RELEASE),
            sorted(series, PosterSort.RELEASE),
            searchedOnline = online != null,
            hasTmdbToken = state.settings.tmdbToken != null,
            photoUrl = keptPerson?.imageUrl ?: state.tmdbFaces.values.firstNotNullOfOrNull { it[face] },
            inMyList = keptPerson != null,
            collections = collectionChoices(state, ItemKind.PERSON, name.trim()),
        )
    }

    /** Normalised titles, built once per catalogue (see [of]). */
    /**
     * What Search looks through: not the hidden categories, nor a title or channel whose own name carries a hidden
     * language tag ("|DE| …" in a category of no language).
     */
    class SearchIndex(
        content: PlaylistContent,
        private val hidden: Set<String>,
        private val hiddenTags: Set<String> = emptySet(),
        /** The versions of the films: every version is searched by its own words, each film found once. */
        val films: FilmVersions = FilmVersions.none(),
    ) {
        private fun shown(categoryId: String, name: String) =
            categoryId !in hidden && !CategoryTags.isHiddenByName(name, hiddenTags)

        // Every version, found by its own title and year; a search keeps each film once ([search]).
        val movies = content.movies.filter { shown(it.categoryId, it.name) }.map { TextNormalizer.normalize(it.name) to it }
        val series = content.series.filter { shown(it.categoryId, it.name) }.map { TextNormalizer.normalize(it.name) to it }
        val channels = content.channels.filter { shown(it.categoryId, it.name) && !CategoryFilter.isHeader(it.name) }.map { TextNormalizer.normalize(it.name) to it }

        /** Release years of the movies and series, newest first (the filter's choices). */
        val years: List<Int> = (movies.mapNotNull { it.second.year } + series.mapNotNull { it.second.year }).distinct().sortedDescending()
        /** Every movie shown, each version of a film included (the people of the pages opened). */
        val movieIds: Set<String> = content.movies.filter { shown(it.categoryId, it.name) }.mapTo(HashSet()) { it.id }
        val seriesIds: Set<String> = series.mapTo(HashSet()) { it.second.id }

        /** Actors and directors of the series shown (movie lists give no cast). */
        val people = PeopleIndex(series.flatMap { (_, it) -> listOf(it.cast, it.director) })

        companion object {
            /** A catalogue (the same object, not an equal one) with its hidden categories and tags and its versions. */
            private class Key(val content: PlaylistContent, val hidden: Set<String>, val hiddenTags: Set<String>, val films: FilmVersions) {
                override fun equals(other: Any?) =
                    other is Key && other.content === content && other.hidden == hidden && other.hiddenTags == hiddenTags && other.films === films
                override fun hashCode() = ((System.identityHashCode(content) * 31 + hidden.hashCode()) * 31 + hiddenTags.hashCode()) * 31 + System.identityHashCode(films)
            }

            /**
             * The last few indexes, the most recently used kept: another search on another thread (another catalogue,
             * other hidden categories) does not throw away the one Search is about to use again.
             */
            private val cached = object : LinkedHashMap<Key, SearchIndex>(8, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, SearchIndex>?) = size > CACHED_INDEXES
            }

            /**
             * The index of this catalogue, these hidden categories and tags and these versions: coming back to Search does
             * not build it again.
             */
            /** The index Search uses for [state]: its hidden categories and tags, each film once when "Group versions" is on. */
            fun of(state: OndoState): SearchIndex = of(state.content, UiMappers.hiddenCategoryIds(state), state.library.hiddenTags, UiMappers.films(state))

            fun of(content: PlaylistContent, hidden: Set<String>, hiddenTags: Set<String> = emptySet(), films: FilmVersions = noFilms): SearchIndex {
                val key = Key(content, hidden, hiddenTags, films)
                synchronized(cached) { cached[key]?.let { return it } }
                val index = SearchIndex(content, hidden, hiddenTags, films)
                synchronized(cached) { return cached.getOrPut(key) { index } }
            }

            internal fun forget() {
                synchronized(cached) { cached.clear() }
            }

            /** No versions grouped, one object for all: the key compares versions by identity. */
            private val noFilms = FilmVersions.none()

            // Two: an index holds every title of a catalogue, and a big one weighs on a TV's memory.
            private const val CACHED_INDEXES = 2
        }
    }

    /**
     * Actors and directors whose name matches a search: from the pages already opened, then the series lists; only the
     * pages of titles still shown (one opened before its category was hidden names nobody).
     */
    fun people(index: SearchIndex, state: OndoState, query: String): List<String> {
        val opened = PeopleIndex(
            state.movieDetails.filterKeys { it in index.movieIds }.values.flatMap { listOf(it.cast, it.director) } +
                state.seriesDetails.filterKeys { it in index.seriesIds }.values.flatMap { listOf(it.info.cast, it.info.director) },
        )
        val seen = HashSet<String>()
        return (opened.names(query) + index.people.names(query)).filter { seen.add(TextNormalizer.normalize(it)) }.take(20)
    }

    /** Most movies and series listed by a filtered search (it lists the whole playlist when the query is empty). */
    const val FILTERED_LIMIT = 1_000

    /**
     * Every word of the query is found in the title ("dark kni" finds "The Dark Knight", "dust" finds "Stardust"). With filters,
     * only movies and series released in those years, or movies of that festival or ceremony, are listed, newest
     * first; an empty query then lists all of them. [checkCancelled] is called while going through the titles: it
     * throws to stop a search that a newer query replaced.
     */
    fun search(
        index: SearchIndex,
        query: String,
        state: OndoState,
        now: Long,
        filters: SearchFilters = SearchFilters(),
        limit: Int = 60,
        checkCancelled: () -> Unit = {},
        /** False: titles and channels only, the people left for a second pass (they take longer to find). */
        withPeople: Boolean = true,
    ): SearchUiState {
        val words = TextNormalizer.normalize(query).split(' ').filter { it.isNotEmpty() }
        val empty = SearchUiState(query = query, filters = filters, years = index.years, awardGroups = awardGroups(index, state))
        if (words.isEmpty() && !filters.isActive) return empty
        val phrase = words.joinToString(" ")
        // Every word found anywhere in the title ("dust" finds "Stardust"); the title itself first, then the titles that
        // start with the words typed, then those with a word that does, then the others: catalogue order within each.
        fun rank(title: String): Int = when {
            title == phrase -> 0
            title.startsWith(phrase) -> 1
            words.all { word -> title.startsWith(word) || title.contains(" $word") } -> 2
            else -> 3
        }
        // One pass, each rank kept up to [cap]: a short query on a big catalogue stops once enough titles named
        // exactly that are found, and a newer query stops it between titles.
        // [film]: the film an item is a version of, so that a rank holds each film once (the cap counts films).
        fun <T> List<Pair<String, T>>.find(cap: Int = Int.MAX_VALUE, film: (T) -> Int = { -1 }): Sequence<T> {
            val ranked = List(4) { ArrayList<T>() }
            val seen = List(4) { HashSet<Int>() }
            for ((title, item) in this) {
                checkCancelled()
                if (!words.all { title.contains(it) }) continue
                val level = rank(title)
                val bucket = ranked[level]
                val group = film(item)
                if (bucket.size < cap && (group < 0 || seen[level].add(group))) bucket += item
                if (ranked[0].size >= cap) break
            }
            return ranked.asSequence().flatten()
        }
        val films = index.films
        fun film(movie: Movie) = if (films.isGrouped) films.groupOf(movie) else -1
        if (!filters.isActive) {
            return empty.copy(
                people = if (withPeople) people(index, state, query) else emptyList(),
                // Each film once, the version found at the best rank standing for it.
                movies = films.distinct(index.movies.find(limit, ::film).toList()).take(limit).map { ShowcaseItem.MovieItem(it) },
                series = index.series.find(limit).take(limit).map { ShowcaseItem.SeriesItem(it) }.toList(),
                channels = index.channels.find(limit).take(limit).map { channelRow(it, state, now) }.toList(),
            )
        }
        val years = filters.years
        fun released(year: Int?) = years == null || (year != null && year in years)
        val awards = MovieAwards.of(state)
        fun awarded(movie: Movie) = filters.awardGroup == null || awards[movie.id].orEmpty().any { entry ->
            (!filters.winnersOnly || entry.won) && state.awards.award(entry.awardId)?.group == filters.awardGroup
        }
        // Each version by its own year; each film once.
        val movies = films.distinct(index.movies.find().filter { released(it.year) && awarded(it) }.toList()).map { ShowcaseItem.MovieItem(it) }
        // Awards are given to films: series are left out. Release years and awards are not about channels.
        val series = if (filters.awardGroup != null) emptyList() else index.series.find().filter { released(it.year) }.map { ShowcaseItem.SeriesItem(it) }.toList()
        return empty.copy(
            movies = sorted(movies, PosterSort.RELEASE).take(FILTERED_LIMIT),
            series = sorted(series, PosterSort.RELEASE).take(FILTERED_LIMIT),
        )
    }

    /**
     * The people of a search: the playlist's, then those TMDb knows by the words typed (with the viewer's token), each
     * name once. [online] is TMDb's answer for [query]; one for an older query, or a filtered search, adds nobody.
     */
    fun withTmdbPeople(search: SearchUiState, query: String, online: List<String>, photos: Map<String, String> = emptyMap()): SearchUiState {
        if (online.isEmpty() || search.query != query || search.filters.isActive) return search
        val seen = search.people.mapTo(HashSet()) { TextNormalizer.normalize(it) }
        val added = online.filter { it.isNotBlank() && seen.add(TextNormalizer.normalize(it)) }
        // TMDb's photos for the playlist's people too, when the pages opened gave none.
        val known = search.photos + photos.filterKeys { it !in search.photos }
        return if (added.isEmpty() && known == search.photos) search else search.copy(people = search.people + added, photos = known)
    }

    /** The photos of these people that the pages opened this session gave (TMDb's), by normalised name. */
    fun faces(state: OndoState, names: List<String>): Map<String, String> {
        if (names.isEmpty() || state.tmdbFaces.isEmpty()) return emptyMap()
        val wanted = names.mapTo(HashSet()) { TextNormalizer.normalize(it) }
        val found = HashMap<String, String>()
        for (faces in state.tmdbFaces.values) for ((name, url) in faces) if (name in wanted) found.putIfAbsent(name, url)
        return found
    }

    /** The festivals and ceremonies some visible movie took part in, in the order of the awards file. */
    private fun awardGroups(index: SearchIndex, state: OndoState): List<String> {
        val present = HashSet<String>()
        for ((id, entries) in MovieAwards.of(state)) {
            if (id !in index.movieIds) continue
            entries.mapNotNullTo(present) { state.awards.award(it.awardId)?.group }
        }
        return state.awards.groups.filter { it in present }
    }
}
