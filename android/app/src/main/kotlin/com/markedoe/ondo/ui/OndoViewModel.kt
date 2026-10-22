package com.markedoe.ondo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.markedoe.ondo.BuildConfig
import com.markedoe.ondo.core.model.EpgProgram
import com.markedoe.ondo.core.model.Episode
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LoadError
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.data.PosterSort
import com.markedoe.ondo.data.Reminder
import com.markedoe.ondo.player.PlayRequest
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
import com.markedoe.ondo.ui.theme.Appearance
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
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
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
    private val labels: Labels,
    private val clock: () -> Long = System::currentTimeMillis,
    private val computation: CoroutineDispatcher = Dispatchers.Default,
    /** The time, every minute: "now on" and progress of live programmes. */
    private val minutes: Flow<Long> = everyMinute(clock),
) : ViewModel() {
    val state: StateFlow<OndoState> = repository.state

    private val sharing = SharingStarted.WhileSubscribed(5_000)

    /** The look chosen in Settings, alone: the activity's root does not recompose at every other change of the state. */
    val appearance: StateFlow<Appearance> = state.map { it.settings.appearance }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, state.value.settings.appearance)

    val home: StateFlow<HomeUiState?> = combine(state, minutes) { state, now ->
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
        .map { it.content to UiMappers.hiddenCategoryIds(it) }
        .distinctUntilChanged()
        .map { (content, hidden) -> UiMappers.SearchIndex.of(content, hidden) }

    /** What a search reads besides its index: a change of the library or the settings does not run it again. */
    private val searchState = state.distinctUntilChanged { old, new ->
        old.content === new.content && old.movieDetails === new.movieDetails && old.seriesDetails === new.seriesDetails &&
            old.awards === new.awards && old.guide === new.guide
    }

    private class SearchInput(val index: UiMappers.SearchIndex, val query: String, val filters: SearchFilters, val state: OndoState)

    private val _searchFilters = MutableStateFlow(SearchFilters())
    val searchFilters: StateFlow<SearchFilters> = _searchFilters.asStateFlow()

    /** A newer query stops the search under way (it is not finished for nothing). */
    val search: StateFlow<SearchUiState> = combine(searchIndex, _query.debounce(200), _searchFilters, searchState) { index, query, filters, state ->
        SearchInput(index, query, filters, state)
    }
        .mapLatest { input ->
            val job = currentCoroutineContext().job
            UiMappers.search(input.index, input.query, input.state, clock(), input.filters, checkCancelled = { job.ensureActive() })
        }
        .flowOn(computation)
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

    fun setLiveFilter(filter: LiveFilter) {
        _liveFilter.value = filter
    }

    fun loadMovie(id: String) {
        viewModelScope.launch {
            val movie = withContext(computation) { UiMappers.movie(state.value, id) } ?: return@launch
            repository.details(movie)
        }
    }

    fun loadSeries(id: String) {
        viewModelScope.launch {
            val series = withContext(computation) { UiMappers.series(state.value, id) } ?: return@launch
            runCatching { repository.details(series) }
                .onSuccess { _seriesErrors.value -= id }
                .onFailure { error -> _seriesErrors.value += (id to LoadError.of(error)) }
        }
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

    /** Movie: plays it. Series: the next episode (details loaded first when needed, see [preparing]). */
    fun play(item: ShowcaseItem) {
        when (item) {
            is ShowcaseItem.MovieItem -> playMovie(item.movie.id)
            is ShowcaseItem.SeriesItem -> prepare(item.key) { nextEpisode(item.series)?.let { emit(repository.playRequest(item.series, it)) } }
        }
    }

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
    fun setAppearance(appearance: Appearance) = repository.setAppearance(appearance)
    fun setShowAdultCategories(show: Boolean) = repository.setShowAdultCategories(show)
    fun setPosterSort(sort: PosterSort) = repository.setPosterSort(sort)
    fun setLiveBuffer(seconds: Int) = repository.setLiveBuffer(seconds)
    fun setVodBuffer(seconds: Int) = repository.setVodBuffer(seconds)
    fun setCategoryHidden(categoryId: String, hidden: Boolean) = repository.setCategoryHidden(categoryId, hidden)
    fun setCategoriesHidden(categoryIds: Collection<String>, hidden: Boolean) = repository.setCategoriesHidden(categoryIds, hidden)
    fun setTagsHidden(tags: Collection<String>, hidden: Boolean) = repository.setTagsHidden(tags, hidden)
    fun setWordHidden(word: String, hidden: Boolean) = repository.setWordHidden(word, hidden)
    fun setUpdateAwards(update: Boolean) = repository.setUpdateAwards(update)
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

        /** Public channels listed by the iptv-org project, the same demo as on iPhone. */
        const val DEMO_PLAYLIST_URL = "https://iptv-org.github.io/iptv/countries/fr.m3u"
    }
}
