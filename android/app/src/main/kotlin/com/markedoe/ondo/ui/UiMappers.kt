package com.markedoe.ondo.ui

import com.markedoe.ondo.core.TextNormalizer
import com.markedoe.ondo.core.library.People
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.MediaDetails
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.data.PosterSort
import com.markedoe.ondo.data.Showcase
import com.markedoe.ondo.data.WatchEntry
import com.markedoe.ondo.ui.components.HeroPage
import com.markedoe.ondo.ui.model.ChannelRow
import com.markedoe.ondo.ui.model.ResumeItem
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.CategoryRow
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
        Showcase(state.content, state.library, state.activePlaylistId.orEmpty(), hiddenCategoryIds(state))

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
            HeroPage(item, meta(item, state, labels), isFavorite(state, item), resumable)
        }
        val channelsById = state.content.channels.filter { it.categoryId !in hidden }.associateBy { it.id }
        val favoriteChannels = state.library.favorites(playlistId, ItemKind.CHANNEL).mapNotNull { channelsById[it.itemId] }
        val recentChannels = state.library.recentChannels(playlistId).mapNotNull { channelsById[it.itemId] }
        val channels = favoriteChannels.ifEmpty { recentChannels }.ifEmpty { channelsById.values.take(ROW_LENGTH) }
        return HomeUiState(
            playlistName = state.activePlaylist?.name.orEmpty(),
            hero = hero,
            continueWatching = showcase.continueWatching().map { resume(it, state) },
            myList = showcase.myList(),
            newMovies = showcase.newMovies(now),
            newSeries = showcase.newSeries(now),
            hasVod = showcase.hasVod,
            channels = channels.map { channelRow(it, state, now) },
            channelsAreFavorites = favoriteChannels.isNotEmpty(),
            isLoading = state.isLoading,
            tagSuggestion = CategoryFilter.suggestion(state, labels.viewerCodes, labels.locale),
        )
    }

    fun vod(state: OndoState, series: Boolean, title: String): VodUiState {
        val showcase = showcase(state)
        val hidden = hiddenCategoryIds(state)
        val categories = (if (series) state.content.seriesCategories else state.content.movieCategories).filter { it.id !in hidden }
        val byCategory: Map<String, List<ShowcaseItem>> =
            if (series) state.content.series.groupBy({ it.categoryId }) { ShowcaseItem.SeriesItem(it) }
            else state.content.movies.groupBy({ it.categoryId }) { ShowcaseItem.MovieItem(it) }
        val rows = categories.mapNotNull { category -> byCategory[category.id]?.take(ROW_LENGTH)?.let { CategoryRow(category, it) } }
        val kind = if (series) ItemKind.SERIES else ItemKind.MOVIE
        val dated = if (series) showcase.seriesAreDated else showcase.moviesAreDated
        return VodUiState(
            title = title,
            categories = rows.map { it.category },
            continueWatching = showcase.continueWatching().filter { it.kind == kind }.map { resume(it, state) },
            myList = showcase.myList().filter { (it is ShowcaseItem.SeriesItem) == series },
            recent = when {
                !dated -> emptyList()
                series -> showcase.featuredSeries().map { ShowcaseItem.SeriesItem(it) }
                else -> showcase.featuredMovies().map { ShowcaseItem.MovieItem(it) }
            },
            rows = rows,
            awardWinners = if (series) emptyList() else MovieAwards.winners(state, hidden, ROW_LENGTH).map { ShowcaseItem.MovieItem(it) },
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
            else -> state.content.movies.filter { it.categoryId !in hidden && (categoryId == null || it.categoryId == categoryId) }.map { ShowcaseItem.MovieItem(it) }
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
        return LiveUiState(
            categories = state.content.liveCategories.filter { it.id in used },
            selectedCategoryId = categoryId,
            showingFavorites = favoritesOnly,
            channels = channels.map { channelRow(it, state, now) },
            favoriteIds = favoriteIds,
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
        )
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
        )
    }

    fun settings(state: OndoState, version: String, kindLabel: (Boolean) -> String): SettingsUiState = SettingsUiState(
        playlists = state.playlists.map { playlist ->
            val host = runCatching { java.net.URI(playlist.url.trim()).host }.getOrNull()
            PlaylistSummary(playlist.id, playlist.name, listOfNotNull(kindLabel(playlist.isXtream), host).joinToString(" · "), playlist.id == state.activePlaylistId)
        },
        appearance = state.settings.appearance,
        showAdultCategories = state.settings.showAdultCategories,
        version = version,
        isRefreshing = state.isLoading,
    )

    /** The movies and series whose opened page names this person (cast or director), newest first. */
    fun person(state: OndoState, name: String): PersonUiState {
        val hidden = hiddenCategoryIds(state)
        fun names(details: MediaDetails) = People.mentions(details.cast, name) || People.mentions(details.director, name)
        val movieIds = state.movieDetails.filterValues(::names).keys
        val seriesIds = state.seriesDetails.filterValues { names(it.info) }.keys
        val movies = state.content.movies.filter { it.id in movieIds && it.categoryId !in hidden }.map { ShowcaseItem.MovieItem(it) }
        val series = state.content.series.filter { it.id in seriesIds && it.categoryId !in hidden }.map { ShowcaseItem.SeriesItem(it) }
        return PersonUiState(name, sorted(movies, PosterSort.RELEASE), sorted(series, PosterSort.RELEASE))
    }

    /** Normalised titles, built once per catalogue. */
    class SearchIndex(content: PlaylistContent, hidden: Set<String>) {
        val movies = content.movies.filter { it.categoryId !in hidden }.map { TextNormalizer.normalize(it.name).split(' ') to it }
        val series = content.series.filter { it.categoryId !in hidden }.map { TextNormalizer.normalize(it.name).split(' ') to it }
        val channels = content.channels.filter { it.categoryId !in hidden }.map { TextNormalizer.normalize(it.name).split(' ') to it }

        /** Release years of the movies and series, newest first (the filter's choices). */
        val years: List<Int> = (movies.mapNotNull { it.second.year } + series.mapNotNull { it.second.year }).distinct().sortedDescending()
        val movieIds: Set<String> = movies.mapTo(HashSet()) { it.second.id }
    }

    /** Most movies and series listed by a filtered search (it lists the whole playlist when the query is empty). */
    const val FILTERED_LIMIT = 1_000

    /**
     * Every word of the query must start a word of the title ("dark kni" finds "The Dark Knight"). With filters,
     * only movies and series released in those years, or movies of that festival or ceremony, are listed, newest
     * first; an empty query then lists all of them.
     */
    fun search(
        index: SearchIndex,
        query: String,
        state: OndoState,
        now: Long,
        filters: SearchFilters = SearchFilters(),
        limit: Int = 60,
    ): SearchUiState {
        val words = TextNormalizer.normalize(query).split(' ').filter { it.isNotEmpty() }
        val empty = SearchUiState(query = query, filters = filters, years = index.years, awardGroups = awardGroups(index, state))
        if (words.isEmpty() && !filters.isActive) return empty
        fun <T> List<Pair<List<String>, T>>.find(): Sequence<T> = asSequence()
            .filter { (titleWords, _) -> words.all { word -> titleWords.any { it.startsWith(word) } } }
            .map { it.second }
        if (!filters.isActive) {
            return empty.copy(
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
