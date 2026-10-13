package com.markedoe.ondo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.markedoe.ondo.core.model.Episode
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.player.PlayRequest
import com.markedoe.ondo.ui.model.ChannelRow
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.HomeUiState
import com.markedoe.ondo.ui.screens.LiveUiState
import com.markedoe.ondo.ui.screens.PlaylistForm
import com.markedoe.ondo.ui.screens.SearchUiState
import com.markedoe.ondo.ui.screens.VodUiState
import com.markedoe.ondo.ui.theme.Appearance
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel

/** Live TV filter: one category, the favourites, or everything. */
data class LiveFilter(val categoryId: String? = null, val favoritesOnly: Boolean = false)

/**
 * Screen states derived from [OndoRepository.state], computed off the main thread, and the user's
 * actions. Playback requests come out of [playRequests] (the activity starts the player).
 */
@OptIn(FlowPreview::class)
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

    val home: StateFlow<HomeUiState?> = combine(state, minutes) { state, now -> UiMappers.home(state, labels, now) }
        .flowOn(computation)
        .stateIn(viewModelScope, sharing, null)

    val movies: StateFlow<VodUiState?> = state.map { UiMappers.vod(it, series = false, title = "") }
        .flowOn(computation)
        .stateIn(viewModelScope, sharing, null)

    val series: StateFlow<VodUiState?> = state.map { UiMappers.vod(it, series = true, title = "") }
        .flowOn(computation)
        .stateIn(viewModelScope, sharing, null)

    private val _liveFilter = MutableStateFlow(LiveFilter())
    val liveFilter: StateFlow<LiveFilter> = _liveFilter.asStateFlow()

    val live: StateFlow<LiveUiState?> = combine(state, _liveFilter, minutes) { state, filter, now ->
        UiMappers.live(state, filter.categoryId, filter.favoritesOnly, now)
    }
        .flowOn(computation)
        .stateIn(viewModelScope, sharing, null)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val searchIndex = state
        .map { it.content to UiMappers.hiddenCategoryIds(it) }
        .distinctUntilChanged()
        .map { (content, hidden) -> UiMappers.SearchIndex(content, hidden) }
        .flowOn(computation)

    val search: StateFlow<SearchUiState> = combine(searchIndex, _query.debounce(200), state) { index, query, state ->
        UiMappers.search(index, query, state, clock())
    }
        .flowOn(computation)
        .stateIn(viewModelScope, sharing, SearchUiState())

    private val _seriesErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val seriesErrors: StateFlow<Map<String, String>> = _seriesErrors.asStateFlow()

    private val _playRequests = Channel<PlayRequest>(Channel.BUFFERED)
    val playRequests: Flow<PlayRequest> = _playRequests.receiveAsFlow()

    init {
        repository.bootstrap()
    }

    // Catalogue

    fun setQuery(query: String) {
        _query.value = query
    }

    fun setLiveFilter(filter: LiveFilter) {
        _liveFilter.value = filter
    }

    fun loadMovie(id: String) {
        val movie = state.value.content.movies.firstOrNull { it.id == id } ?: return
        viewModelScope.launch { repository.details(movie) }
    }

    fun loadSeries(id: String) {
        val series = state.value.content.series.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            runCatching { repository.details(series) }
                .onSuccess { _seriesErrors.value -= id }
                .onFailure { error -> _seriesErrors.value += (id to (error.message ?: error.toString())) }
        }
    }

    // Library

    fun toggleMyList(item: ShowcaseItem) = when (item) {
        is ShowcaseItem.MovieItem -> repository.toggleFavorite(ItemKind.MOVIE, item.movie.id, item.title, item.posterUrl)
        is ShowcaseItem.SeriesItem -> repository.toggleFavorite(ItemKind.SERIES, item.series.id, item.title, item.posterUrl)
    }

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

    /** Movie: plays it. Series: the next episode (details loaded first when needed). */
    fun play(item: ShowcaseItem) {
        when (item) {
            is ShowcaseItem.MovieItem -> playMovie(item.movie.id)
            is ShowcaseItem.SeriesItem -> viewModelScope.launch { nextEpisode(item.series)?.let { emit(repository.playRequest(item.series, it)) } }
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
                viewModelScope.launch {
                    val details = runCatching { repository.details(series) }.getOrNull() ?: return@launch
                    details.seasons.flatMap { it.episodes }.firstOrNull { it.id == entry.episodeId }
                        ?.let { emit(repository.playRequest(series, it)) }
                }
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
    fun clearError() = repository.clearError()

    companion object {
        fun everyMinute(clock: () -> Long): Flow<Long> = flow {
            while (true) {
                emit(clock())
                delay(60_000)
            }
        }

        /** Public channels listed by the iptv-org project, the same demo as on iPhone. */
        const val DEMO_PLAYLIST_URL = "https://iptv-org.github.io/iptv/countries/fr.m3u"
    }
}
