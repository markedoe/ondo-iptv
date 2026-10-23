package com.markedoe.ondo.ui

import com.markedoe.ondo.core.TextNormalizer
import com.markedoe.ondo.core.library.AwardsCatalogue
import com.markedoe.ondo.core.library.People
import com.markedoe.ondo.core.library.PeopleIndex
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
        Showcase(state.content, state.library, state.activePlaylistId.orEmpty(), hiddenCategoryIds(state), state.settings.hideWatchedMovies)

    /** The movies left out where the viewer browses: the finished ones, when Settings hides them. */
    fun hiddenMovieIds(state: OndoState): Set<String> =
        if (state.settings.hideWatchedMovies) state.library.finishedMovies(state.activePlaylistId.orEmpty()) else emptySet()

    /** Movies and series by id; the first of an id the catalogue lists twice, as a search through the list would find. */
    class CatalogueIndex(content: PlaylistContent) {
        val movies: Map<String, Movie> = HashMap<String, Movie>(content.movies.size).also { map -> content.movies.forEach { map.getOrPut(it.id) { it } } }
        val series: Map<String, Series> = HashMap<String, Series>(content.series.size).also { map -> content.series.forEach { map.getOrPut(it.id) { it } } }
    }

    @Volatile private var catalogueIndex: Pair<PlaylistContent, CatalogueIndex>? = null

    /** Built once per catalogue, in the background (it goes through the whole catalogue). */
    fun index(content: PlaylistContent): CatalogueIndex {
        catalogueIndex?.let { (known, index) -> if (known === content) return index }
        return CatalogueIndex(content).also { catalogueIndex = content to it }
    }

    fun movie(state: OndoState, id: String): Movie? = index(state.content).movies[id]

    fun series(state: OndoState, id: String): Series? = index(state.content).series[id]

    fun resume(entry: WatchEntry, state: OndoState): ResumeItem {
        val backdrop = when (entry.kind) {
            ItemKind.MOVIE -> state.movieDetails[entry.itemId]?.backdropUrl
            ItemKind.SERIES -> state.seriesDetails[entry.itemId]?.info?.backdropUrl
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
        )
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
        val rating = (details?.rating ?: item.rating)?.takeIf { it > 0 }
        return listOfNotNull(
            if (item is ShowcaseItem.MovieItem) labels.movie else labels.series,
            item.year?.toString(),
            genre,
            rating?.let { "★ " + String.format(labels.locale, "%.1f", it) },
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
            HeroPage(item, meta(item, state, labels), isFavorite(state, item), resumable, details?.backdropUrl, details?.plot ?: plot)
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
        val categories = (if (series) state.content.seriesCategories else state.content.movieCategories).filter { it.id !in hidden }
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
            selectedCategoryId = categoryId,
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

    /** The channels of a Live TV choice (My List, a category or all), the categories that have some, the favourites. */
    private class ChannelSelection(val channels: List<Channel>, val categories: List<MediaCategory>, val favoriteIds: Set<String>)

    private fun liveChannels(state: OndoState, categoryId: String?, favoritesOnly: Boolean): ChannelSelection {
        val playlistId = state.activePlaylistId.orEmpty()
        val hidden = hiddenCategoryIds(state)
        val favoriteIds = state.library.favorites(playlistId, ItemKind.CHANNEL).mapTo(HashSet()) { it.itemId }
        val visible = state.content.channels.filter { it.categoryId !in hidden }
        val channels = visible.filter {
            when {
                favoritesOnly -> it.id in favoriteIds
                categoryId != null -> it.categoryId == categoryId
                else -> true
            }
        }
        val used = visible.mapTo(HashSet()) { it.categoryId }
        return ChannelSelection(channels, state.content.liveCategories.filter { it.id in used }, favoriteIds)
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
            selectedCategoryId = categoryId,
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
        val next = guide.upcoming(channel, now, 1).firstOrNull()
        return ChannelRow(channel, current?.title, current?.progress(now)?.toFloat(), next?.title)
    }

    fun isFavorite(state: OndoState, item: ShowcaseItem): Boolean {
        val playlistId = state.activePlaylistId.orEmpty()
        return when (item) {
            is ShowcaseItem.MovieItem -> state.library.isFavorite(playlistId, ItemKind.MOVIE, item.movie.id)
            is ShowcaseItem.SeriesItem -> state.library.isFavorite(playlistId, ItemKind.SERIES, item.series.id)
        }
    }

    fun movieDetail(state: OndoState, movie: Movie, locale: Locale = Locale.getDefault()): MovieDetailUiState {
        val showcase = showcase(state)
        val item = ShowcaseItem.MovieItem(movie)
        return MovieDetailUiState(
            item = item,
            details = state.movieDetails[movie.id],
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
        val details = state.seriesDetails[series.id]
        val next = details?.let { showcase.nextEpisode(series, it) }
        val episodeProgress = details?.seasons.orEmpty().flatMap { it.episodes }
            .mapNotNull { episode -> progress(showcase.progress(series, episode))?.let { episode.id to it } }
            .toMap()
        return SeriesDetailUiState(
            item = item,
            details = details?.info,
            seasons = details?.seasons.orEmpty(),
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
        appearance = state.settings.appearance,
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
    )

    /** The genres of a genre field ("Drama, Thriller", "Action / Adventure"), each once ("Action & Adventure, Adventure"). */
    fun genres(field: String?): List<String> = field.orEmpty().split(',', '/', '|', '&').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

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
    fun person(state: OndoState, name: String): PersonUiState {
        val hidden = hiddenCategoryIds(state)
        fun names(details: MediaDetails) = People.mentions(details.cast, name) || People.mentions(details.director, name)
        val movieIds = state.movieDetails.filterValues(::names).keys
        val seriesIds = state.seriesDetails.filterValues { names(it.info) }.keys
        val watched = hiddenMovieIds(state)
        val movies = state.content.movies.filter { it.id in movieIds && it.categoryId !in hidden && it.id !in watched }.map { ShowcaseItem.MovieItem(it) }
        val series = state.content.series.filter { show ->
            show.categoryId !in hidden && (show.id in seriesIds || People.mentions(show.cast, name) || People.mentions(show.director, name))
        }.map { ShowcaseItem.SeriesItem(it) }
        return PersonUiState(name, sorted(movies, PosterSort.RELEASE), sorted(series, PosterSort.RELEASE))
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
