package com.markedoe.ondo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.markedoe.ondo.BuildConfig
import com.markedoe.ondo.core.TextNormalizer
import com.markedoe.ondo.core.library.TrendingMatch
import com.markedoe.ondo.core.model.EpgProgram
import com.markedoe.ondo.core.model.Episode
import com.markedoe.ondo.core.model.MediaDetails
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LoadError
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.data.PosterSort
import com.markedoe.ondo.data.Reminder
import com.markedoe.ondo.data.SyncDevice
import com.markedoe.ondo.player.PlayRequest
import com.markedoe.ondo.ui.model.PersonRow
import com.markedoe.ondo.ui.model.ChannelRow
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.GuideUiState
import com.markedoe.ondo.ui.screens.HomeUiState
import com.markedoe.ondo.ui.screens.LiveUiState
import com.markedoe.ondo.ui.screens.MyListUiState
import com.markedoe.ondo.ui.screens.PlaylistForm
import com.markedoe.ondo.ui.screens.SearchFilters
import com.markedoe.ondo.ui.screens.SearchUiState
import com.markedoe.ondo.ui.screens.VodUiState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Live TV filter: one category, the favourites, or everything. */
data class LiveFilter(val categoryId: String? = null, val favoritesOnly: Boolean = false)

/**
 * Screen states derived from [OndoRepository.state], computed off the main thread, and the user's
 * actions. Playback requests come out of [playRequests] (the activity starts the player).
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class OndoViewModel(
    private val repository: OndoRepository,
    labels: Labels,
    private val clock: () -> Long = System::currentTimeMillis,
    private val computation: CoroutineDispatcher = Dispatchers.Default,
    /** The time, every minute: "now on" and progress of live programmes. */
    private val minutes: Flow<Long> = everyMinute(clock),
) : ViewModel() {
    val state: StateFlow<OndoState> = repository.state

    private val sharing = SharingStarted.WhileSubscribed(5_000)

    private val labels = MutableStateFlow(labels)

    /** The words and locale of the app's language, again after the language changes (the view model stays). */
    fun setLabels(labels: Labels) {
        this.labels.value = labels
    }

    val home: StateFlow<HomeUiState?> = combine(state, minutes, this.labels) { state, now, labels ->
        UiMappers.home(state, labels, now).copy(beta = BuildConfig.BETA)
    }
        .flowOn(computation)
        .stateIn(viewModelScope, sharing, null)

    val movies: StateFlow<VodUiState?> = state.map { UiMappers.vod(it, series = false, title = "") }
        .flowOn(computation)
        .stateIn(viewModelScope, sharing, null)

    val series: StateFlow<VodUiState?> = state.map { UiMappers.vod(it, series = true, title = "") }
        .flowOn(computation)
        .stateIn(viewModelScope, sharing, null)

    val myList: StateFlow<MyListUiState?> = combine(state, minutes) { state, now -> UiMappers.myList(state, now) }
        .flowOn(computation)
        .stateIn(viewModelScope, sharing, null)

    private val _liveFilter = MutableStateFlow(LiveFilter())
    val liveFilter: StateFlow<LiveFilter> = _liveFilter.asStateFlow()

    init {
        // Another playlist opens on all its channels: a category chosen on the previous one is not carried over.
        viewModelScope.launch {
            state.map { it.activePlaylistId }.distinctUntilChanged().drop(1).collect { _liveFilter.value = LiveFilter() }
        }
    }

    val live: StateFlow<LiveUiState?> = combine(state, _liveFilter, minutes) { state, filter, now ->
        UiMappers.live(state, filter.categoryId, filter.favoritesOnly, now)
    }
        .flowOn(computation)
        .stateIn(viewModelScope, sharing, null)

    /** The TV guide of the channels chosen on Live TV. */
    val guide: StateFlow<GuideUiState?> = combine(state, _liveFilter, minutes) { state, filter, now ->
        UiMappers.guide(state, filter.categoryId, filter.favoritesOnly, now)
    }
        .flowOn(computation)
        .stateIn(viewModelScope, sharing, null)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val searchIndex = state
        .map { Triple(it.content, UiMappers.hiddenCategoryIds(it), it.library.hiddenTags) }
        .distinctUntilChanged()
        .map { (content, hidden, tags) -> UiMappers.SearchIndex.of(content, hidden, tags) }

    /** What a search reads besides its index: a change of the library or the settings does not run it again. */
    private val searchState = state.distinctUntilChanged { old, new ->
        old.content === new.content && old.movieDetails === new.movieDetails && old.seriesDetails === new.seriesDetails &&
            old.awards === new.awards && old.guide === new.guide
    }

    private class SearchInput(val index: UiMappers.SearchIndex, val query: String, val filters: SearchFilters, val state: OndoState)

    private val _searchFilters = MutableStateFlow(SearchFilters())
    val searchFilters: StateFlow<SearchFilters> = _searchFilters.asStateFlow()

    /**
     * A newer query stops the search under way (it is not finished for nothing). The titles and channels found come
     * first, the playlist's people (slower to find) after them: the results show as they come.
     */
    private val playlistSearch: Flow<SearchUiState> = combine(searchIndex, _query.debounce(200), _searchFilters, searchState) { index, query, filters, state ->
        SearchInput(index, query, filters, state)
    }
        .transformLatest { input ->
            val job = currentCoroutineContext().job
            val titles = UiMappers.search(input.index, input.query, input.state, clock(), input.filters, checkCancelled = { job.ensureActive() }, withPeople = false)
            if (input.filters.isActive || input.query.isBlank()) {
                emit(titles)
            } else {
                emit(titles.copy(searching = true))
                val people = UiMappers.people(input.index, input.state, input.query)
                emit(titles.copy(people = people, photos = UiMappers.faces(input.state, people)))
            }
        }
        .flowOn(computation)

    /**
     * With the viewer's TMDb token, the people TMDb knows by the words typed, once typing pauses (not for a filtered
     * search); null while TMDb is asked.
     */
    private val tmdbPeople: Flow<Pair<String, List<PersonRow>?>> = combine(_query.debounce(TMDB_PEOPLE_DEBOUNCE_MS), _searchFilters) { query, filters -> query to filters }
        .transformLatest { (query, filters) ->
            if (filters.isActive || query.trim().length < TMDB_PEOPLE_MIN_LENGTH) {
                emit(query to emptyList())
            } else {
                emit(query to null)
                emit(query to repository.tmdbPeopleWithPhotos(query))
            }
        }
        .onStart { emit("" to emptyList()) }

    /** The results as they come: Search says it is still searching until TMDb's people are in too. */
    val search: StateFlow<SearchUiState> = combine(playlistSearch, tmdbPeople) { search, (query, online) ->
        val asking = !search.filters.isActive && search.query.trim().length >= TMDB_PEOPLE_MIN_LENGTH && (query != search.query || online == null)
        val found = online.orEmpty()
        val photos = found.mapNotNull { person -> person.photoUrl?.let { TextNormalizer.normalize(person.name) to it } }.toMap()
        UiMappers.withTmdbPeople(search, query, found.map { it.name }, photos).copy(searching = search.searching || asking)
    }
        .stateIn(viewModelScope, sharing, SearchUiState())

    private val _preparing = MutableStateFlow<String?>(null)
    private var preparingJob: Job? = null

    /** The Home title or card whose Play is getting ready (a series' episodes loading): it shows progress meanwhile. */
    val preparing: StateFlow<String?> = _preparing.asStateFlow()

    private val _seriesErrors = MutableStateFlow<Map<String, LoadError>>(emptyMap())
    val seriesErrors: StateFlow<Map<String, LoadError>> = _seriesErrors.asStateFlow()

    private val _playRequests = Channel<PlayRequest>(Channel.BUFFERED)
    val playRequests: Flow<PlayRequest> = _playRequests.receiveAsFlow()

    init {
        repository.bootstrap()
    }

    // Catalogue

    fun setQuery(query: String) {
        _query.value = query
    }

    fun setSearchFilters(filters: SearchFilters) {
        _searchFilters.value = filters
    }

    /** Search opened anew (not come back to from a result): no words, no filters left from the last time. */
    fun newSearch() {
        _query.value = ""
        _searchFilters.value = SearchFilters()
    }

    fun setLiveFilter(filter: LiveFilter) {
        _liveFilter.value = filter
    }

    fun loadMovie(id: String) {
        viewModelScope.launch {
            val movie = withContext(computation) { UiMappers.movie(state.value, id) } ?: return@launch
            repository.details(movie)
            wantTmdb(ShowcaseItem.MovieItem(movie))
        }
    }

    fun loadSeries(id: String) {
        viewModelScope.launch {
            val series = withContext(computation) { UiMappers.series(state.value, id) } ?: return@launch
            runCatching { repository.details(series) }
                .onSuccess { _seriesErrors.value -= id }
                .onFailure { error -> _seriesErrors.value += (id to LoadError.of(error)) }
            wantTmdb(ShowcaseItem.SeriesItem(series))
        }
    }

    /**
     * What TMDb says about a title whose page opens (backdrop, trailer, rating, cast photos): kept on the device, else
     * asked with the viewer's token, by the TMDb id its provider gave, else by its title and year. [ahead]: a title
     * about to be seen (Home's carousel), asked at a pace behind the pages opened.
     */
    fun wantTmdb(item: ShowcaseItem, ahead: Boolean = false) {
        val state = state.value
        val details = details(state, item)
        val title = details?.title?.takeIf { it.isNotBlank() } ?: item.title
        val year = item.year ?: details?.releaseDate?.take(4)?.toIntOrNull()
        repository.findTmdbMetadata(item.key, item is ShowcaseItem.SeriesItem, details?.tmdbId, title, year, ahead)
    }

    /** A title Home shows: what TMDb says about it is read or asked ahead (its backdrop when the provider's fails). */
    fun wantBackdrop(item: ShowcaseItem) = wantTmdb(item, ahead = true)

    private fun details(state: OndoState, item: ShowcaseItem): MediaDetails? = when (item) {
        is ShowcaseItem.MovieItem -> state.movieDetails[item.movie.id]
        is ShowcaseItem.SeriesItem -> state.seriesDetails[item.series.id]?.info
    }

    /** With the viewer's TMDb token, the person's titles on TMDb that the playlist has; null without one, or if TMDb fails. */
    suspend fun tmdbWorks(name: String): List<TrendingMatch>? = repository.tmdbWorks(name)

    /** The playlist's titles that the titles kept on the device credit the person with, token or not. */
    suspend fun localWorks(name: String): List<TrendingMatch> = repository.localWorks(name)

    /** The backdrop at [url] did not load: the title shows TMDb's instead when the viewer has a token. */
    fun backdropFailed(item: ShowcaseItem, url: String) {
        repository.backdropFailed(url)
        wantBackdrop(item)
    }

    // Library

    fun toggleMyList(item: ShowcaseItem) = when (item) {
        is ShowcaseItem.MovieItem -> repository.toggleFavorite(ItemKind.MOVIE, item.movie.id, item.title, item.posterUrl)
        is ShowcaseItem.SeriesItem -> repository.toggleFavorite(ItemKind.SERIES, item.series.id, item.title, item.posterUrl)
    }

    // Reminders

    fun toggleReminder(channel: com.markedoe.ondo.core.model.Channel, program: EpgProgram) = repository.toggleReminder(channel, program)

    fun removeReminder(reminder: Reminder) = repository.removeReminder(reminder)

    /** Upcoming reminders of the current playlist, soonest first. */
    fun upcomingReminders(): List<Reminder> = state.value.let { it.library.upcomingReminders(it.activePlaylistId.orEmpty(), clock()) }

    // Collections

    fun toggleInCollection(id: String, item: ShowcaseItem) = when (item) {
        is ShowcaseItem.MovieItem -> repository.toggleInCollection(id, ItemKind.MOVIE, item.movie.id, item.title, item.posterUrl)
        is ShowcaseItem.SeriesItem -> repository.toggleInCollection(id, ItemKind.SERIES, item.series.id, item.title, item.posterUrl)
    }

    /** Makes a collection, with the item in it when there is one. */
    fun newCollection(name: String, item: ShowcaseItem? = null) {
        val id = repository.createCollection(name)
        if (item != null) toggleInCollection(id, item)
    }

    /** A genre's titles of the playlist beyond the pages opened (kept on the device, and TMDb's most popular). */
    suspend fun genreTitles(genre: String): List<TrendingMatch> = repository.genreTitles(genre)

    /** TMDb's photo of the person kept on the device, if any. */
    suspend fun personPhoto(name: String): String? = repository.personPhoto(name)

    /** An actor or a director in My List, or out of it. */
    fun togglePerson(name: String, photoUrl: String?) = repository.togglePerson(name, photoUrl)

    fun keepPersonPhoto(name: String, photoUrl: String) = repository.keepPersonPhoto(name, photoUrl)

    suspend fun fillPersonPhotos(names: List<String>) = repository.fillPersonPhotos(names)

    fun togglePersonInCollection(id: String, name: String, photoUrl: String?) = repository.togglePersonInCollection(id, name, photoUrl)

    /** Makes a collection holding the person. */
    fun newCollectionWithPerson(collection: String, name: String, photoUrl: String?) =
        repository.togglePersonInCollection(repository.createCollection(collection), name, photoUrl)

    fun renameCollection(id: String, name: String) = repository.renameCollection(id, name)

    fun deleteCollection(id: String) = repository.deleteCollection(id)

    fun toggleFavorite(row: ChannelRow) =
        repository.toggleFavorite(ItemKind.CHANNEL, row.channel.id, row.channel.name, row.channel.logoUrl)

    // Playback

    fun playMovie(id: String, fromStart: Boolean = false) {
        state.value.content.movies.firstOrNull { it.id == id }?.let { emit(repository.playRequest(it, fromStart)) }
    }

    fun playEpisode(seriesId: String, episode: Episode) {
        state.value.content.series.firstOrNull { it.id == seriesId }?.let { emit(repository.playRequest(it, episode)) }
    }

    fun playChannel(row: ChannelRow) = emit(repository.playRequest(row.channel))

    /** A programme of the guide from its start, from the provider's archive. */
    fun playReplay(channel: com.markedoe.ondo.core.model.Channel, programme: EpgProgram) = emit(repository.replayRequest(channel, programme))

    /** Movie: plays it. Series: the next episode (details loaded first when needed, see [preparing]). */
    fun play(item: ShowcaseItem) {
        when (item) {
            is ShowcaseItem.MovieItem -> playMovie(item.movie.id)
            is ShowcaseItem.SeriesItem -> prepare(item.key) { nextEpisode(item.series)?.let { emit(repository.playRequest(item.series, it)) } }
        }
    }

    /** Long press on a "Continue watching" card: the card goes. */
    fun removeFromContinueWatching(key: String) = repository.removeFromContinueWatching(key)

    /** A "Continue watching" card: the movie, episode or channel it stands for. */
    fun resume(key: String) {
        val current = state.value
        val entry = current.library.progress(key) ?: return
        when (entry.kind) {
            ItemKind.MOVIE -> playMovie(entry.itemId)
            ItemKind.CHANNEL -> current.content.channels.firstOrNull { it.id == entry.itemId }?.let { emit(repository.playRequest(it)) }
            ItemKind.SERIES -> {
                val series = current.content.series.firstOrNull { it.id == entry.itemId } ?: return
                prepare(key) {
                    val details = runCatching { repository.details(series) }.getOrNull() ?: return@prepare
                    details.seasons.flatMap { it.episodes }.firstOrNull { it.id == entry.episodeId }
                        ?.let { emit(repository.playRequest(series, it)) }
                }
            }
            ItemKind.PERSON -> Unit
        }
    }

    /** The page of a "Continue watching" card: the movie, or the series of the episode; none for a channel. */
    fun resumedTitle(key: String): ShowcaseItem? {
        val current = state.value
        val entry = current.library.progress(key) ?: return null
        return when (entry.kind) {
            ItemKind.MOVIE -> current.content.movies.firstOrNull { it.id == entry.itemId }?.let { ShowcaseItem.MovieItem(it) }
            ItemKind.SERIES -> current.content.series.firstOrNull { it.id == entry.itemId }?.let { ShowcaseItem.SeriesItem(it) }
            ItemKind.CHANNEL, ItemKind.PERSON -> null
        }
    }

    /**
     * Gets a Play ready with [preparing] set to [key] meanwhile. Another tap on the same Play waits for this one (one
     * player, not two); a tap on another one replaces it.
     */
    private fun prepare(key: String, block: suspend () -> Unit) {
        if (_preparing.value == key) return
        preparingJob?.cancel()
        _preparing.value = key
        preparingJob = viewModelScope.launch {
            try {
                block()
            } finally {
                _preparing.compareAndSet(key, null)
            }
        }
    }

    /**
     * Details of the provider's series on Home's carousel and in Continue watching, fetched ahead so that their Play
     * starts at once: [PREFETCH_LIMIT] at most, one at a time. Stops when the caller is cancelled (Home left).
     */
    suspend fun prefetchSeries(home: HomeUiState) {
        val wanted = withContext(computation) {
            val current = state.value
            val resumed = home.continueWatching.mapNotNull { current.library.progress(it.key) }
                .filter { it.kind == ItemKind.SERIES }
                .mapNotNull { UiMappers.series(current, it.itemId) }
            (home.hero.mapNotNull { (it.item as? ShowcaseItem.SeriesItem)?.series } + resumed)
                .distinctBy { it.id }
                // Without a provider's server, a series' episodes come with the playlist: nothing to fetch.
                .filter { it.xtreamSeriesId != null }
                .take(PREFETCH_LIMIT)
        }
        for (series in wanted) {
            if (series.id in state.value.seriesDetails) continue
            try {
                repository.details(series)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Play asks again, and says why if it still fails.
            } catch (_: OutOfMemoryError) {
                // A page too large for this device (the HTTP client hands the error over): not a stop of the app.
            }
        }
    }

    private suspend fun nextEpisode(series: Series): Episode? {
        val details = runCatching { repository.details(series) }.getOrNull() ?: return null
        return UiMappers.showcase(state.value).nextEpisode(series, details)?.first
    }

    private fun emit(request: PlayRequest?) {
        if (request != null) _playRequests.trySend(request)
    }

    // Playlists and settings

    fun addPlaylist(form: PlaylistForm, onAdded: () -> Unit) {
        viewModelScope.launch {
            val result = when (form) {
                is PlaylistForm.Xtream -> repository.addPlaylist(form.name, form.server, form.username, form.password)
                is PlaylistForm.M3u -> repository.addPlaylist(form.name, form.url, epgUrl = form.epgUrl)
            }
            if (result.isSuccess) onAdded()
        }
    }

    fun addDemo(name: String) {
        viewModelScope.launch { repository.addPlaylist(name, DEMO_PLAYLIST_URL) }
    }

    fun activate(id: String) = repository.activate(id)
    fun refresh() = repository.refresh()
    fun delete(id: String) = repository.delete(id)
    fun setShowAdultCategories(show: Boolean) = repository.setShowAdultCategories(show)
    fun setHideWatchedMovies(hide: Boolean) = repository.setHideWatchedMovies(hide)

    fun setSyncOnLocalNetwork(on: Boolean) = repository.setSyncOnLocalNetwork(on)

    /** The devices sync on the local network found or heard from. */
    val syncDevices: StateFlow<List<SyncDevice>> = repository.syncDevices
    fun setPosterSort(sort: PosterSort) = repository.setPosterSort(sort)
    fun setLiveBuffer(seconds: Int) = repository.setLiveBuffer(seconds)
    fun setVodBuffer(seconds: Int) = repository.setVodBuffer(seconds)

    fun setAutoRefreshHours(hours: Int) = repository.setAutoRefreshHours(hours)
    fun setCategoryHidden(categoryId: String, hidden: Boolean) = repository.setCategoryHidden(categoryId, hidden)
    fun setCategoriesHidden(categoryIds: Collection<String>, hidden: Boolean) = repository.setCategoriesHidden(categoryIds, hidden)
    fun setTagsHidden(tags: Collection<String>, hidden: Boolean) = repository.setTagsHidden(tags, hidden)
    fun setWordHidden(word: String, hidden: Boolean) = repository.setWordHidden(word, hidden)
    fun setUpdateAwards(update: Boolean) = repository.setUpdateAwards(update)

    fun setPrepareAhead(on: Boolean) = repository.setPrepareAhead(on)

    /**
     * Settings' "Prepare new titles", while Ondo is on screen (the caller stops it otherwise): what TMDb says about
     * the carousel's, the best rated, the new and the trending titles (cast and directors with their photos, backdrop,
     * rating) is fetched ahead at TMDb's pace and kept on the device, so their pages open at once. Needs the viewer's
     * TMDb token; titles already kept and recent are not asked again. Starts over with a new catalogue.
     */
    suspend fun prepareAhead() {
        state
            .map { PrepareInput(it.settings.prepareAhead && it.settings.tmdbToken != null && !it.tmdbRefused, it.content) }
            .distinctUntilChanged { old, new -> old.on == new.on && old.content === new.content }
            .collectLatest { input ->
                if (!input.on) return@collectLatest
                // Not while the app is still starting or the catalogue is being shown.
                delay(PREPARE_START_DELAY_MS)
                val now = clock()
                val titles = withContext(computation) { UiMappers.showcase(state.value).aheadTitles(now, PREPARE_LIMIT) }
                for (item in titles) {
                    val details = details(state.value, item)
                    val title = details?.title?.takeIf { it.isNotBlank() } ?: item.title
                    val year = item.year ?: details?.releaseDate?.take(4)?.toIntOrNull()
                    repository.prepareTmdbMetadata(item.key, item is ShowcaseItem.SeriesItem, details?.tmdbId, title, year)
                }
            }
    }

    private class PrepareInput(val on: Boolean, val content: com.markedoe.ondo.core.model.PlaylistContent)
    suspend fun checkAndSaveTmdbToken(token: String) = repository.checkAndSaveTmdbToken(token)
    fun removeTmdbToken() = repository.removeTmdbToken()
    fun acceptSuggestion(suggestion: TagSuggestion) = repository.setTagsHidden(suggestion.hidden, hidden = true)
    fun dismissTagSuggestion() = repository.dismissTagSuggestion()
    fun clearError() = repository.clearError()

    companion object {
        fun everyMinute(clock: () -> Long): Flow<Long> = flow {
            while (true) {
                emit(clock())
                delay(60_000)
            }
        }

        /** Series whose details Home fetches ahead, at most. */
        const val PREFETCH_LIMIT = 4

        /** Titles prepared ahead at most, and the wait before starting (see [prepareAhead]). */
        const val PREPARE_LIMIT = 300
        const val PREPARE_START_DELAY_MS = 10_000L

        /** TMDb is asked for people once typing pauses this long. */
        const val TMDB_PEOPLE_DEBOUNCE_MS = 400L

        /** TMDb is asked for people from three letters on ([OndoRepository.tmdbPeople]). */
        const val TMDB_PEOPLE_MIN_LENGTH = 3

        /** Public channels listed by the iptv-org project, the same demo as on iPhone. */
        const val DEMO_PLAYLIST_URL = "https://iptv-org.github.io/iptv/countries/fr.m3u"
    }
}
