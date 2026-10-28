package com.markedoe.ondo.ui

import com.markedoe.ondo.core.TextNormalizer
import com.markedoe.ondo.core.library.AwardsCatalogue
import com.markedoe.ondo.core.library.People
import com.markedoe.ondo.core.library.PeopleIndex
import com.markedoe.ondo.core.library.TmdbRating
import com.markedoe.ondo.core.library.TrendingMatch
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.core.model.MediaDetails
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.data.PosterSort
import com.markedoe.ondo.data.Showcase
import com.markedoe.ondo.data.SyncDevice
import com.markedoe.ondo.data.WatchEntry
import com.markedoe.ondo.ui.components.HeroPage
import com.markedoe.ondo.ui.model.ChannelRow
import com.markedoe.ondo.ui.model.ResumeItem
import com.markedoe.ondo.ui.model.ShowcaseItem
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
import com.markedoe.ondo.ui.screens.SearchFilters
import com.markedoe.ondo.ui.screens.SearchUiState
import com.markedoe.ondo.ui.screens.SeriesDetailUiState
import com.markedoe.ondo.ui.screens.SettingsUiState
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
     * user shows them in Settings.
     */
    fun hiddenCategoryIds(state: OndoState): Set<String> = with(state.content) {
        (movieCategories + seriesCategories + liveCategories)
            .filter { (!state.settings.showAdultCategories && it.looksAdult) || CategoryFilter.isHidden(state, it) }
            .mapTo(HashSet()) { it.id }
    }

    fun showcase(state: OndoState): Showcase =
        Showcase(state.content, state.library, state.activePlaylistId.orEmpty(), hiddenCategoryIds(state), state.settings.hideWatchedMovies, state.trending)

    /** The movies left out where the viewer browses: the finished ones, when Settings hides them. */
    fun hiddenMovieIds(state: OndoState): Set<String> =
        if (state.settings.hideWatchedMovies) state.library.finishedMovies(state.activePlaylistId.orEmpty()) else emptySet()

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

    fun resume(entry: WatchEntry, state: OndoState): ResumeItem {
        val backdrop = when (entry.kind) {
            ItemKind.MOVIE -> backdrop(state, ItemKind.MOVIE, entry.itemId)
            ItemKind.SERIES -> backdrop(state, ItemKind.SERIES, entry.itemId)
            ItemKind.CHANNEL -> null
        }
        return ResumeItem(
            key = entry.key,
            title = entry.title,
            subtitle = entry.subtitle,
            imageUrl = backdrop ?: entry.imageUrl,
            progress = entry.progress.toFloat(),
            minutesLeft = entry.minutesLeft,
            isSeries = entry.kind == ItemKind.SERIES,
            hasPage = entry.kind != ItemKind.CHANNEL,
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
            own == null || one(provider!!) == one(votes.average) -> tmdb
            else -> "$own · $tmdb"
        }
    }

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

    fun home(state: OndoState, labels: Labels, now: Long): HomeUiState {
        val showcase = showcase(state)
        val playlistId = state.activePlaylistId.orEmpty()
        val hidden = hiddenCategoryIds(state)
        val hero = showcase.heroItems(now).map { item ->
            val resumable = (item as? ShowcaseItem.MovieItem)?.let { showcase.progress(it.movie)?.isResumable } == true
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
        val channelsById = state.content.channels.filter { it.categoryId !in hidden }.associateBy { it.id }
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
        )
    }

    fun vod(state: OndoState, series: Boolean, title: String): VodUiState {
        val showcase = showcase(state)
        val hidden = hiddenCategoryIds(state)
        // A panel can list a category twice (same id): one chip and one row each.
        val categories = (if (series) state.content.seriesCategories else state.content.movieCategories).filter { it.id !in hidden }.distinctBy { it.id }
        val watched = hiddenMovieIds(state)
        val byCategory: Map<String, List<ShowcaseItem>> =
            if (series) state.content.series.groupBy({ it.categoryId }) { ShowcaseItem.SeriesItem(it) }
            else state.content.movies.filter { it.id !in watched }.groupBy({ it.categoryId }) { ShowcaseItem.MovieItem(it) }
        val rows = categories.mapNotNull { category -> byCategory[category.id]?.take(ROW_LENGTH)?.let { CategoryRow(category, it) } }
        val kind = if (series) ItemKind.SERIES else ItemKind.MOVIE
        return VodUiState(
            title = title,
            categories = rows.map { it.category },
            continueWatching = showcase.continueWatching().filter { it.kind == kind }.map { resume(it, state) },
            myList = showcase.myList().filter { (it is ShowcaseItem.SeriesItem) == series },
            rows = rows,
            awardWinners = if (series) emptyList() else MovieAwards.winners(state, hidden, ROW_LENGTH, watched).map { ShowcaseItem.MovieItem(it) },
        )
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
        val items: List<ShowcaseItem> = when {
            myList -> showcase(state).myList().filter { anyType || (it is ShowcaseItem.SeriesItem) == series }
            series -> state.content.series.filter { it.categoryId !in hidden && (categoryId == null || it.categoryId == categoryId) }.map { ShowcaseItem.SeriesItem(it) }
            else -> {
                val watched = hiddenMovieIds(state)
                state.content.movies.filter { it.categoryId !in hidden && it.id !in watched && (categoryId == null || it.categoryId == categoryId) }.map { ShowcaseItem.MovieItem(it) }
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
        return state.content.channels.filter { it.categoryId !in hidden }
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
        val visible = state.content.channels.filter { it.categoryId !in hidden }
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
        val channelsById = state.content.channels.filter { it.categoryId !in hidden }.associateBy { it.id }
        return MyListUiState(
            movies = grid(state, series = false, categoryId = null, myList = true, filter = ""),
            series = grid(state, series = true, categoryId = null, myList = true, filter = ""),
            channels = state.library.favorites(playlistId, ItemKind.CHANNEL).mapNotNull { channelsById[it.itemId] }.map { channelRow(it, state, now) },
            collections = collectionRows(state, hidden),
        )
    }

    /** The collections, each with the number of its items the collection page shows. */
    private fun collectionRows(state: OndoState, hidden: Set<String>): List<CollectionRow> {
        val playlistId = state.activePlaylistId.orEmpty()
        val shown by lazy {
            mapOf(
                ItemKind.MOVIE to state.content.movies.filter { it.categoryId !in hidden }.mapTo(HashSet()) { it.id },
                ItemKind.SERIES to state.content.series.filter { it.categoryId !in hidden }.mapTo(HashSet()) { it.id },
                ItemKind.CHANNEL to state.content.channels.filter { it.categoryId !in hidden }.mapTo(HashSet()) { it.id },
            )
        }
        return state.library.collections.map { list ->
            CollectionRow(list.id, list.name, list.items.count { it.playlistId == playlistId && it.itemId in shown.getValue(it.kind) })
        }
    }

    /** A collection's items the playlist has, in the order they were added; null once it is deleted. */
    fun collection(state: OndoState, id: String, now: Long): CollectionUiState? {
        val list = state.library.collections.firstOrNull { it.id == id } ?: return null
        val playlistId = state.activePlaylistId.orEmpty()
        val hidden = hiddenCategoryIds(state)
        fun order(kind: ItemKind): Map<String, Int> =
            list.items.filter { it.playlistId == playlistId && it.kind == kind }.withIndex().associate { it.value.itemId to it.index }
        val movies = order(ItemKind.MOVIE)
        val series = order(ItemKind.SERIES)
        val channels = order(ItemKind.CHANNEL)
        return CollectionUiState(
            id = list.id,
            name = list.name,
            movies = state.content.movies.filter { it.id in movies && it.categoryId !in hidden }.sortedBy { movies[it.id] }.map { ShowcaseItem.MovieItem(it) },
            series = state.content.series.filter { it.id in series && it.categoryId !in hidden }.sortedBy { series[it.id] }.map { ShowcaseItem.SeriesItem(it) },
            channels = state.content.channels.filter { it.id in channels && it.categoryId !in hidden }.sortedBy { channels[it.id] }.map { channelRow(it, state, now) },
        )
    }

    /** The collections, ticked when they hold this item of the current playlist. */
    fun collectionChoices(state: OndoState, kind: ItemKind, itemId: String): List<CollectionChoice> {
        val playlistId = state.activePlaylistId.orEmpty()
        return state.library.collections.map { CollectionChoice(it.id, it.name, it.contains(playlistId, kind, itemId)) }
    }

    fun channelRow(channel: Channel, state: OndoState, now: Long): ChannelRow {
        val guide = state.guide ?: return ChannelRow(channel, null, null, null)
        val current = guide.current(channel, now)
        // The programme on now comes first in `upcoming`; in a gap, the next one does.
        val next = guide.upcoming(channel, now, 2).firstOrNull { it != current }
        return ChannelRow(channel, current?.title, current?.progress(now)?.toFloat(), next?.title)
    }

    fun isFavorite(state: OndoState, item: ShowcaseItem): Boolean {
        val playlistId = state.activePlaylistId.orEmpty()
        return when (item) {
            is ShowcaseItem.MovieItem -> state.library.isFavorite(playlistId, ItemKind.MOVIE, item.movie.id)
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

    fun movieDetail(state: OndoState, movie: Movie, locale: Locale = Locale.getDefault()): MovieDetailUiState {
        val showcase = showcase(state)
        val item = ShowcaseItem.MovieItem(movie)
        return MovieDetailUiState(
            item = item,
            details = state.movieDetails[movie.id],
            backdropUrl = backdrop(state, item),
            trailer = youTubeId(state.movieDetails[movie.id]?.trailerYouTubeId) ?: state.tmdbTrailers[item.key],
            faces = state.tmdbFaces[item.key].orEmpty(),
            votes = tmdbRating(state, item),
            progress = progress(showcase.progress(movie)),
            inMyList = isFavorite(state, item),
            similar = showcase.similarMovies(movie).map { ShowcaseItem.MovieItem(it) },
            awards = MovieAwards.rows(state, movie, locale),
            collections = collectionChoices(state, ItemKind.MOVIE, movie.id),
        )
    }

    fun seriesDetail(state: OndoState, series: Series, selectedSeason: Int?, error: String?): SeriesDetailUiState {
        val showcase = showcase(state)
        val item = ShowcaseItem.SeriesItem(series)
        // The provider's episodes as they came (they are saved): an episode it does not name reads in the app's words.
        val details = state.seriesDetails[series.id]?.let { state.placeholderNames.rename(it) }
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
            trailer = youTubeId(details?.info?.trailerYouTubeId) ?: state.tmdbTrailers[item.key],
            faces = state.tmdbFaces[item.key].orEmpty(),
            votes = tmdbRating(state, item),
            seasons = seasons,
            selectedSeason = selectedSeason ?: next?.second?.number,
            next = next?.first,
            nextProgress = next?.first?.let { episodeProgress[it.id] },
            episodeProgress = episodeProgress,
            inMyList = isFavorite(state, item),
            similar = showcase.similarSeries(series).map { ShowcaseItem.SeriesItem(it) },
            error = error,
            collections = collectionChoices(state, ItemKind.SERIES, series.id),
        )
    }

    fun settings(state: OndoState, version: String, syncDevices: List<SyncDevice> = emptyList(), kindLabel: (Boolean) -> String): SettingsUiState = SettingsUiState(
        playlists = state.playlists.map { playlist ->
            val host = runCatching { java.net.URI(playlist.url.trim()).host }.getOrNull()
            PlaylistSummary(playlist.id, playlist.name, listOfNotNull(kindLabel(playlist.isXtream), host).joinToString(" · "), playlist.id == state.activePlaylistId, playlist.isXtream)
        },
        showAdultCategories = state.settings.showAdultCategories,
        updateAwards = state.settings.updateAwards,
        liveBuffer = state.settings.liveBuffer,
        vodBuffer = state.settings.vodBuffer,
        version = version,
        isRefreshing = state.isLoading,
        hideWatchedMovies = state.settings.hideWatchedMovies,
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

    /** The titles of the playlist sharing a value of a movie's or a series' page, hidden categories left out. */
    fun browse(state: OndoState, kind: BrowseKind, value: String, locale: Locale = Locale.getDefault()): BrowseUiState {
        val hidden = hiddenCategoryIds(state)
        val watched = hiddenMovieIds(state)
        val movies = state.content.movies.filter { it.categoryId !in hidden && it.id !in watched }
        val series = state.content.series.filter { it.categoryId !in hidden }
        val awards = MovieAwards.of(state)
        fun items(list: List<Movie>) = list.map { ShowcaseItem.MovieItem(it) }
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
                // A movie's genre is known once its page was opened; a series' comes with the catalogue.
                BrowseUiState(
                    kind, value,
                    movies = sorted(items(movies.filter { has(state.movieDetails[it.id]?.genre) }), PosterSort.NAME),
                    series = sorted(series.filter { has(it.genre) || has(state.seriesDetails[it.id]?.info?.genre) }.map { ShowcaseItem.SeriesItem(it) }, PosterSort.NAME),
                )
            }
        }
    }

    /**
     * The movies and series whose opened page names this person (cast or director), and the series whose list entry
     * does (series lists name their cast; movie lists do not), newest first.
     */
    fun person(state: OndoState, name: String, online: List<TrendingMatch>? = null): PersonUiState {
        val hidden = hiddenCategoryIds(state)
        fun names(details: MediaDetails) = People.mentions(details.cast, name) || People.mentions(details.director, name)
        val movieIds = state.movieDetails.filterValues(::names).keys
        val seriesIds = state.seriesDetails.filterValues { names(it.info) }.keys
        val watched = hiddenMovieIds(state)
        // With the viewer's TMDb token, the titles TMDb credits the person with that the playlist has.
        val onlineMovies = online.orEmpty().mapNotNullTo(HashSet()) { (it as? TrendingMatch.MovieMatch)?.movie?.id }
        val onlineSeries = online.orEmpty().mapNotNullTo(HashSet()) { (it as? TrendingMatch.SeriesMatch)?.series?.id }
        val movies = state.content.movies
            .filter { (it.id in movieIds || it.id in onlineMovies) && it.categoryId !in hidden && it.id !in watched }
            .distinctBy { it.id }
            .map { ShowcaseItem.MovieItem(it) }
        val series = state.content.series.filter { show ->
            show.categoryId !in hidden &&
                (show.id in seriesIds || show.id in onlineSeries || People.mentions(show.cast, name) || People.mentions(show.director, name))
        }.distinctBy { it.id }.map { ShowcaseItem.SeriesItem(it) }
        return PersonUiState(
            name,
            sorted(movies, PosterSort.RELEASE),
            sorted(series, PosterSort.RELEASE),
            searchedOnline = online != null,
            hasTmdbToken = state.settings.tmdbToken != null,
        )
    }

    /** Normalised titles, built once per catalogue (see [of]). */
    class SearchIndex(content: PlaylistContent, hidden: Set<String>) {
        val movies = content.movies.filter { it.categoryId !in hidden }.map { TextNormalizer.normalize(it.name).split(' ') to it }
        val series = content.series.filter { it.categoryId !in hidden }.map { TextNormalizer.normalize(it.name).split(' ') to it }
        val channels = content.channels.filter { it.categoryId !in hidden }.map { TextNormalizer.normalize(it.name).split(' ') to it }

        /** Release years of the movies and series, newest first (the filter's choices). */
        val years: List<Int> = (movies.mapNotNull { it.second.year } + series.mapNotNull { it.second.year }).distinct().sortedDescending()
        val movieIds: Set<String> = movies.mapTo(HashSet()) { it.second.id }

        /** Actors and directors of the series lists (movie lists give no cast). */
        val people = PeopleIndex(content.series.filter { it.categoryId !in hidden }.flatMap { listOf(it.cast, it.director) })

        companion object {
            @Volatile private var cached: Triple<PlaylistContent, Set<String>, SearchIndex>? = null

            /** The index of this catalogue and these hidden categories: coming back to Search does not build it again. */
            fun of(content: PlaylistContent, hidden: Set<String>): SearchIndex {
                cached?.let { (known, knownHidden, index) -> if (known === content && knownHidden == hidden) return index }
                return SearchIndex(content, hidden).also { cached = Triple(content, hidden, it) }
            }

            internal fun forget() {
                cached = null
            }
        }
    }

    /** Actors and directors whose name matches a search: from the pages already opened, then the series lists. */
    fun people(index: SearchIndex, state: OndoState, query: String): List<String> {
        val opened = PeopleIndex(
            state.movieDetails.values.flatMap { listOf(it.cast, it.director) } +
                state.seriesDetails.values.flatMap { listOf(it.info.cast, it.info.director) },
        )
        val seen = HashSet<String>()
        return (opened.names(query) + index.people.names(query)).filter { seen.add(TextNormalizer.normalize(it)) }.take(20)
    }

    /** Most movies and series listed by a filtered search (it lists the whole playlist when the query is empty). */
    const val FILTERED_LIMIT = 1_000

    /**
     * Every word of the query must start a word of the title ("dark kni" finds "The Dark Knight"). With filters,
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
    ): SearchUiState {
        val words = TextNormalizer.normalize(query).split(' ').filter { it.isNotEmpty() }
        val empty = SearchUiState(query = query, filters = filters, years = index.years, awardGroups = awardGroups(index, state))
        if (words.isEmpty() && !filters.isActive) return empty
        fun <T> List<Pair<List<String>, T>>.find(): Sequence<T> = asSequence()
            .filter { (titleWords, _) ->
                checkCancelled()
                words.all { word -> titleWords.any { it.startsWith(word) } }
            }
            .map { it.second }
        if (!filters.isActive) {
            return empty.copy(
                people = people(index, state, query),
                movies = index.movies.find().take(limit).map { ShowcaseItem.MovieItem(it) }.toList(),
                series = index.series.find().take(limit).map { ShowcaseItem.SeriesItem(it) }.toList(),
                channels = index.channels.find().take(limit).map { channelRow(it, state, now) }.toList(),
            )
        }
        val years = filters.years
        fun released(year: Int?) = years == null || (year != null && year in years)
        val awards = MovieAwards.of(state)
        fun awarded(movie: Movie) = filters.awardGroup == null || awards[movie.id].orEmpty().any { entry ->
            (!filters.winnersOnly || entry.won) && state.awards.award(entry.awardId)?.group == filters.awardGroup
        }
        val movies = index.movies.find().filter { released(it.year) && awarded(it) }.map { ShowcaseItem.MovieItem(it) }.toList()
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
    fun withTmdbPeople(search: SearchUiState, query: String, online: List<String>): SearchUiState {
        if (online.isEmpty() || search.query != query || search.filters.isActive) return search
        val seen = search.people.mapTo(HashSet()) { TextNormalizer.normalize(it) }
        val added = online.filter { it.isNotBlank() && seen.add(TextNormalizer.normalize(it)) }
        return if (added.isEmpty()) search else search.copy(people = search.people + added)
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
