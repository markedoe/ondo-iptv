package com.markedoe.ondo.ui

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tracing.trace
import com.markedoe.ondo.BuildConfig
import com.markedoe.ondo.core.TextNormalizer
import com.markedoe.ondo.core.library.FilmVersions
import com.markedoe.ondo.core.library.MetadataKey
import com.markedoe.ondo.core.library.RatingFill
import com.markedoe.ondo.core.library.TitleMetadata
import com.markedoe.ondo.core.library.TrendingMatch
import com.markedoe.ondo.core.model.EpgProgram
import com.markedoe.ondo.core.model.Episode
import com.markedoe.ondo.core.model.MediaDetails
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.Profile
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.core.model.SeriesDetails
import com.markedoe.ondo.data.HomeSnapshot
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LoadError
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.data.PosterSort
import com.markedoe.ondo.data.Reminder
import com.markedoe.ondo.data.SyncDevice
import com.markedoe.ondo.data.WatchEntry
import com.markedoe.ondo.player.PlayRequest
import com.markedoe.ondo.ui.model.PersonRow
import com.markedoe.ondo.ui.model.ChannelRow
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.model.TitleHeader
import com.markedoe.ondo.ui.screens.GuideUiState
import com.markedoe.ondo.ui.screens.HomeUiState
import com.markedoe.ondo.ui.screens.LiveUiState
import com.markedoe.ondo.ui.screens.MyListUiState
import com.markedoe.ondo.ui.screens.PlaylistForm
import com.markedoe.ondo.ui.screens.SearchFilters
import com.markedoe.ondo.ui.screens.SearchUiState
import com.markedoe.ondo.ui.screens.VodUiState
import com.markedoe.ondo.ui.screens.RowOrderUiState
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Live TV filter: one category, the favourites, or everything. No filter at all (null in [OndoViewModel.liveFilter]):
 * the viewer has chosen nothing yet, and Live TV opens on My List, or on all channels while My List has none.
 */
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

    /** Changes when the carousel's fill brought release dates ([fillCarouselReleases]): Home reads what was kept again. */
    private val carouselVersion = MutableStateFlow(0)

    /**
     * What TMDb said of a catalogue's titles and the device kept ([facts]: votes and release dates included), and the
     * catalogue it was read for ([content]; null: not read yet). Home tells "not read for this catalogue yet" from "none".
     */
    private class Kept(val content: PlaylistContent?, val facts: Map<String, TitleMetadata>)

    /** What TMDb said of the catalogue's titles and the device kept, again as more is kept; empty until read. */
    private val keptFacts: Flow<Kept> =
        combine(state.distinctUntilChanged { old, new -> old.content === new.content && old.tmdbRatings === new.tmdbRatings }, carouselVersion) { state, _ -> state }
            .mapLatest { Kept(it.content, repository.keptPosterFacts()) }
            .onStart { emit(Kept(null, emptyMap())) }

    /** How many times Home's state and the Movies tab's were started and worked out, and how long that took in all (ColdStartTimingTest). */
    @VisibleForTesting internal val homeStarted = AtomicInteger()
    @VisibleForTesting internal val homeComputations = AtomicInteger()
    @VisibleForTesting internal val homeNanos = AtomicLong()
    @VisibleForTesting internal val moviesComputations = AtomicInteger()
    @VisibleForTesting internal val moviesNanos = AtomicLong()

    /** How many times Home was the one kept from the last start ([HomeSnapshots]) rather than worked out. */
    @VisibleForTesting internal val savedHomesShown = AtomicInteger()

    /**
     * When Home was first worked out whole (its catalogue, what the device kept of it, its carousel), by
     * [System.nanoTime]; 0: not yet. Equal to Home kept, it is no new state for the screen: only this tells it came.
     */
    @VisibleForTesting internal val workedOutWhole = AtomicLong(0L)

    /** Home kept from the last time it was drawn ([HomeSnapshots]), read once as the view model starts; [read] once known. */
    private class SavedHome(val read: Boolean, val snapshot: HomeSnapshot?)

    private val savedHome = MutableStateFlow(SavedHome(read = false, snapshot = null))

    /** Home worked out anew, or the saved one no longer fits: the saved one is not shown again. */
    @Volatile private var savedHomeDone = false

    /** The last Home kept for the next start: not written again unchanged. */
    @Volatile private var keptHome: HomeSnapshot? = null

    init {
        // The saved Home of the playlist in use, read off the main thread (a small file, apart from the catalogue's read).
        viewModelScope.launch {
            val ready = state.first { it.isReady }
            val snapshot = ready.activePlaylistId?.let { repository.homeSnapshot(it) }
            keptHome = snapshot
            savedHome.value = SavedHome(read = true, snapshot = snapshot)
        }
        // A playlist or a profile deleted or replaced may take its kept Home away: the next one is kept, even unchanged.
        viewModelScope.launch {
            state.map { current -> current.playlists.map { it.id } to current.profiles.map { it.id } }
                .distinctUntilChanged()
                .drop(1)
                .collect { keptHome = null }
        }
    }

    private class HomeInput(val state: OndoState, val now: Long, val labels: Labels, val kept: Kept)

    /**
     * At a cold start, Home as it was last drawn ([HomeSnapshots]) while the saved catalogue is read and until Home is
     * worked out with it and with what the device kept of it (its carousel); then Home worked out anew.
     */
    val home: StateFlow<HomeUiState?> = combine(state, minutes, this.labels, keptFacts) { state, now, labels, kept -> HomeInput(state, now, labels, kept) }
        // Nothing before the saved playlists and settings are read: no screen shows Home then (OndoApp).
        .map { input -> if (!input.state.isReady) null else savedHomeShown(input) ?: workedOutHome(input) }
        .flowOn(computation)
        .stateIn(viewModelScope, sharing, null)

    /**
     * Home of the last state without a catalogue (the saved one being read, the first download), and what it was worked
     * out from: without titles, neither what the device kept of TMDb nor the awards change it, which come meanwhile.
     */
    @Volatile private var withoutCatalogue: Pair<HomeInput, HomeUiState>? = null

    /**
     * The saved Home while it fits: the same playlist, profile and everything else Home is made of ([HomeSnapshots.matches]),
     * the saved catalogue being read, or read (the same download) while what the device kept of it is not read yet. Null
     * once it no longer fits, for good.
     */
    private suspend fun savedHomeShown(input: HomeInput): HomeUiState? {
        if (savedHomeDone) return null
        val state = input.state
        if (!state.isReady) return null
        // The saved Home is read as the playlists are (one small file): Home without its carousel is not shown meanwhile.
        val snapshot = savedHome.first { it.read }.snapshot
        val fits = snapshot != null && HomeSnapshots.matches(snapshot, state, input.labels, input.now) && if (state.content.isEmpty) {
            state.isRestoring
        } else {
            input.kept.content !== state.content && state.content.fetchedAt == snapshot.fetchedAt
        }
        if (!fits || snapshot == null) {
            savedHomeDone = true
            return null
        }
        savedHomesShown.incrementAndGet()
        return HomeSnapshots.home(snapshot, state, input.now)
    }

    private fun workedOutHome(input: HomeInput): HomeUiState {
        val state = input.state
        if (state.content.isEmpty) {
            withoutCatalogue?.let { (before, home) ->
                if (before.now == input.now && before.labels == input.labels && before.state.copy(awards = state.awards) == state) return home
            }
        }
        homeStarted.incrementAndGet()
        val start = System.nanoTime()
        val home = UiMappers.home(state, input.labels, input.now, input.kept.facts)
        homeNanos.addAndGet(System.nanoTime() - start)
        homeComputations.incrementAndGet()
        withoutCatalogue = if (state.content.isEmpty) input to home else null
        // With its catalogue and what the device kept of it: Home whole, kept for the next start, and the tabs worked out.
        if (!state.isRestoring && !state.content.isEmpty && input.kept.content === state.content) {
            if (home.hero.isNotEmpty()) {
                workedOutWhole.compareAndSet(0L, System.nanoTime())
                keepHome(home, state, input.labels)
            }
            homeWorkedOut.value = TabsFor(state.content, state.activeProfile.id)
        }
        return home
    }

    private fun keepHome(home: HomeUiState, state: OndoState, labels: Labels) {
        val snapshot = HomeSnapshots.of(home, state, labels) ?: return
        if (snapshot == keptHome) return
        keptHome = snapshot
        repository.keepHomeSnapshot(snapshot)
    }

    /** Whether Home can be drawn with its carousel now (the saved one while the catalogue is read): the splash screen goes. */
    val homeShown: Boolean get() = home.value?.hero?.isNotEmpty() == true

    /** A catalogue and a profile, which the Movies and Series tabs are worked out for (compared by identity). */
    private class TabsFor(val content: PlaylistContent, val profileId: String) {
        fun sameAs(other: TabsFor?): Boolean = other != null && other.content === content && other.profileId == profileId
    }

    /** Set when Home was worked out whole (its catalogue and what was kept of it): the tabs are worked out after it. */
    private val homeWorkedOut = MutableStateFlow<TabsFor?>(null)
    private val moviesWorkedOut = MutableStateFlow<TabsFor?>(null)
    private val seriesWorkedOut = MutableStateFlow<TabsFor?>(null)

    /**
     * The playlist in use for a TV's top bar: when its catalogue was last fetched, whether it is being fetched again or
     * that failed, again every minute. Only the catalogue's fetch, which the time is of: not what TMDb fills in.
     */
    val playlistStatus: StateFlow<com.markedoe.ondo.ui.tv.TvPlaylistStatus?> = combine(
        state.map { state ->
            state.activePlaylist?.let { playlist ->
                com.markedoe.ondo.ui.tv.TvPlaylistStatus(
                    name = playlist.name,
                    updatedAt = state.content.fetchedAt.takeIf { it > 0 && !state.content.isEmpty },
                    now = 0L,
                    refreshing = state.isLoading,
                    failed = !state.isLoading && state.error != null,
                )
            }
        }.distinctUntilChanged(),
        minutes,
    ) { status, now -> status?.copy(now = now) }
        .stateIn(viewModelScope, sharing, null)

    /** The device's date, again when it changes: a selection's row is a new pick every day. */
    private val days: Flow<String> = minutes.map { SelectionRows.day(it) }.distinctUntilChanged()

    val movies: StateFlow<VodUiState?> = combine(state, keptFacts, days) { state, kept, _ ->
        val start = System.nanoTime()
        UiMappers.vod(state, series = false, title = "", now = clock(), kept = kept.facts).also {
            moviesNanos.addAndGet(System.nanoTime() - start)
            moviesComputations.incrementAndGet()
            if (kept.content === state.content) moviesWorkedOut.value = TabsFor(state.content, state.activeProfile.id)
        }
    }
        .flowOn(computation)
        .stateIn(viewModelScope, sharing, null)

    val series: StateFlow<VodUiState?> = combine(state, keptFacts, days) { state, kept, _ ->
        UiMappers.vod(state, series = true, title = "", now = clock(), kept = kept.facts).also {
            if (kept.content === state.content) seriesWorkedOut.value = TabsFor(state.content, state.activeProfile.id)
        }
    }
        .flowOn(computation)
        .stateIn(viewModelScope, sharing, null)

    init {
        // Once Home was worked out whole (its catalogue and what the device kept of it), the Movies and Series tabs are
        // worked out in the background, one after the other, once per catalogue and profile: their first opening shows
        // them at once (a tab keeps its last state while no one watches it), then as the state is when it opens. Watched
        // only until worked out: no work for them while they are not open.
        viewModelScope.launch {
            homeWorkedOut.filterNotNull()
                .distinctUntilChanged { old, new -> new.sameAs(old) }
                .collectLatest { target ->
                    workOut(movies, moviesWorkedOut, target)
                    workOut(series, seriesWorkedOut, target)
                }
        }
    }

    /**
     * Watches [tab] until it was worked out for [target]: started if no one watches it, left as it was after. Not longer
     * than [target] is what the app shows: another catalogue (a download ended, Home not on screen) or another profile
     * ends it, and a minute at most, so that the tab is never watched for nothing.
     */
    private suspend fun workOut(tab: StateFlow<VodUiState?>, workedOut: StateFlow<TabsFor?>, target: TabsFor) = coroutineScope {
        val watching = launch { tab.collect {} }
        tabsWatched.incrementAndGet()
        try {
            withTimeoutOrNull(WORK_OUT_LIMIT_MS) {
                combine(workedOut, state) { done, current ->
                    target.sameAs(done) || current.content !== target.content || current.activeProfile.id != target.profileId
                }.first { it }
            }
        } finally {
            tabsWatched.decrementAndGet()
            watching.cancel()
        }
    }

    /** How many tabs are watched to be worked out ahead now ([workOut]). */
    @VisibleForTesting internal val tabsWatched = AtomicInteger()

    /** Settings → Row order: the rows of the Home, Movies and Series tabs in the viewer's order. */
    val rowOrder: StateFlow<RowOrderUiState?> = state.map { UiMappers.rowOrder(it) }
        .flowOn(computation)
        .stateIn(viewModelScope, sharing, null)

    val myList: StateFlow<MyListUiState?> = combine(state, minutes) { state, now -> UiMappers.myList(state, now) }
        .flowOn(computation)
        .stateIn(viewModelScope, sharing, null)

    private val _liveFilter = MutableStateFlow<LiveFilter?>(null)
    val liveFilter: StateFlow<LiveFilter?> = _liveFilter.asStateFlow()

    init {
        // As soon as a catalogue (or a newer awards file) arrives, in the background, once: a film's versions are grouped
        // (UiMappers.films, which Home needs first), then its movies and series are matched with the awards (MovieAwards),
        // which the Movies and Series tabs need: their first opening would otherwise wait for that whole match.
        viewModelScope.launch(computation) {
            state.map { Triple(it.content, it.settings.groupVersions, it.awards) }
                .distinctUntilChanged { a, b -> a.first === b.first && a.second == b.second && a.third === b.third }
                .collect { (content, group, awards) ->
                    trace("Ondo.prepareCatalogue") {
                        if (group && content.movies.isNotEmpty()) UiMappers.films(content)
                        MovieAwards.prepare(content, awards)
                    }
                }
        }
    }

    init {
        // Another playlist, or another profile, opens Live TV anew (My List, else all channels): the choice made on the
        // previous one is not carried over. Within one, the choice stays while the viewer goes from tab to tab.
        viewModelScope.launch {
            state.map { it.activePlaylistId }.distinctUntilChanged().drop(1).collect { _liveFilter.value = null }
        }
        viewModelScope.launch {
            state.map { it.activeProfile.id }.distinctUntilChanged().drop(1).collect { _liveFilter.value = null }
        }
    }

    val live: StateFlow<LiveUiState?> = combine(state, _liveFilter, minutes) { state, filter, now ->
        UiMappers.live(state, filter?.categoryId, filter?.favoritesOnly, now)
    }
        .flowOn(computation)
        .stateIn(viewModelScope, sharing, null)

    /** The TV guide of the channels chosen on Live TV. */
    val guide: StateFlow<GuideUiState?> = combine(state, _liveFilter, minutes) { state, filter, now ->
        UiMappers.guide(state, filter?.categoryId, filter?.favoritesOnly, now)
    }
        .flowOn(computation)
        .stateIn(viewModelScope, sharing, null)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private data class SearchKey(val content: PlaylistContent, val hidden: Set<String>, val tags: Set<String>, val groupVersions: Boolean)

    private val searchIndex = state
        .map { SearchKey(it.content, UiMappers.hiddenCategoryIds(it), it.library.hiddenTags, it.settings.groupVersions) }
        .distinctUntilChanged()
        .map { key -> UiMappers.SearchIndex.of(key.content, key.hidden, key.tags, if (key.groupVersions) UiMappers.films(key.content) else FilmVersions.none()) }

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
            seriesDetails(series)
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

    /**
     * TMDb's stills and names of the episodes of [season] of the series [id] whose page shows it, for those its provider
     * leaves without them: kept on the device, else asked once its TMDb id is known ([wantTmdb]).
     */
    fun wantSeason(id: String, season: Int) = repository.findTmdbSeason("s-$id", season)

    /** A title Home shows: what TMDb says about it is read or asked ahead (its backdrop when the provider's fails). */
    fun wantBackdrop(item: ShowcaseItem) = wantTmdb(item, ahead = true)

    private fun details(state: OndoState, item: ShowcaseItem): MediaDetails? = when (item) {
        is ShowcaseItem.MovieItem -> state.movieDetails[item.movie.id]
        is ShowcaseItem.SeriesItem -> state.seriesDetails[item.series.id]?.info
    }

    /**
     * What a TV's header shows of [item], the poster with the focus, again as more of it is known (its details, TMDb's
     * answer). Worked out off the main thread; what TMDb said and the device kept (rating, genres) joins it once read.
     */
    fun titleHeader(item: ShowcaseItem, locale: java.util.Locale): Flow<TitleHeader> {
        val kept = state.distinctUntilChanged { old, new -> old.content === new.content && old.tmdbRatings === new.tmdbRatings }
            .mapLatest<OndoState, TitleMetadata?> { repository.keptPosterFacts()[item.key] }
            .onStart { emit(null) }
        return combine(state, kept) { state, stored -> UiMappers.titleHeader(state, item, stored, locale) }
            .distinctUntilChanged()
            .flowOn(computation)
    }

    /**
     * The remote stays on [item] (a TV's header shows it): what the header lacks is fetched, in the caller's coroutine,
     * so one title at a time, cancelled when the remote moves on. What the device keeps comes first: TMDb's answer kept
     * on the device, then TMDb with the viewer's token, then the provider's details of a movie that still has no plot
     * (a series' list gives its plot; its details would bring every episode). Nothing is asked when [online] is false,
     * and nothing kept is asked again.
     */
    suspend fun loadHeader(item: ShowcaseItem, online: Boolean) {
        if (!online) return
        val current = state.value
        val details = details(current, item)
        val title = details?.title?.takeIf { it.isNotBlank() } ?: item.title
        val year = item.year ?: details?.releaseDate?.take(4)?.toIntOrNull()
        repository.awaitTmdbMetadata(item.key, item is ShowcaseItem.SeriesItem, details?.tmdbId, title, year)
        val movie = (item as? ShowcaseItem.MovieItem)?.movie ?: return
        if (movie.id in state.value.movieDetails || !movie.plot.isNullOrBlank() || movie.xtreamStreamId == null) return
        repository.details(movie)
    }

    /** With the viewer's TMDb token, the person's titles on TMDb that the playlist has; null without one, or if TMDb fails. */
    suspend fun tmdbWorks(name: String): List<TrendingMatch>? = repository.tmdbWorks(name)

    /** What the device kept of TMDb's ratings and genres, by poster key, for the posters of a category's page. */
    suspend fun keptPosterFacts(): Map<String, TitleMetadata> = repository.keptPosterFacts()

    /** The playlist's titles that the titles kept on the device credit the person with, token or not. */
    suspend fun localWorks(name: String): List<TrendingMatch> = repository.localWorks(name)

    /** The backdrop at [url] did not load: the title shows TMDb's instead when the viewer has a token. */
    fun backdropFailed(item: ShowcaseItem, url: String) {
        repository.backdropFailed(url)
        wantBackdrop(item)
    }

    // Library

    /**
     * A film is in My List when one of its versions is: out of it, every version kept goes; into it, [version] (the one a
     * page selects) or else the film's default version (`UiMappers.defaultVersion`). Each stream is kept as before.
     */
    fun toggleMyList(item: ShowcaseItem, version: Movie? = null) {
        when (item) {
            is ShowcaseItem.MovieItem -> {
                val current = state.value
                val playlistId = current.activePlaylistId.orEmpty()
                // The versions grouped in the background already (never grouped here, on the main thread).
                val versions = UiMappers.visibleVersions(current, item.movie, UiMappers.filmsIfReady(current))
                val kept = versions.filter { current.library.isFavorite(playlistId, ItemKind.MOVIE, it.id) }
                val chosen = version ?: UiMappers.defaultVersion(current, item.movie, versions)
                if (kept.isEmpty()) {
                    repository.toggleFavorite(ItemKind.MOVIE, chosen.id, chosen.name, chosen.posterUrl ?: item.posterUrl)
                } else {
                    kept.forEach { repository.toggleFavorite(ItemKind.MOVIE, it.id, it.name, it.posterUrl) }
                }
            }
            is ShowcaseItem.SeriesItem -> repository.toggleFavorite(ItemKind.SERIES, item.series.id, item.title, item.posterUrl)
        }
    }

    /** A version picked on a film's page: remembered on this device, the film's card and page start from it. */
    fun chooseVersion(movieId: String) = repository.rememberVersion(movieId)

    /** "Mark as watched" / "Mark as unwatched" on a film's page: [version] marked, or every version unmarked. */
    fun setFilmWatched(movie: Movie, version: Movie, watched: Boolean) {
        val current = state.value
        viewModelScope.launch {
            // Every version, as the badge counts them ([UiMappers.watchedKeys]): grouped off the main thread if not yet.
            val versions = withContext(computation) { UiMappers.films(current).versions(movie) }
            repository.setFilmWatched(watched, version, versions, current.movieDetails[version.id]?.durationSeconds ?: current.movieDetails[movie.id]?.durationSeconds)
        }
    }

    /** A season of the series ([number]) marked watched or unwatched. */
    fun setSeasonWatched(seriesId: String, number: Int, watched: Boolean) {
        val season = state.value.seriesDetails[seriesId]?.seasons?.firstOrNull { it.number == number } ?: return
        setEpisodesWatched(seriesId, season.episodes, watched)
    }

    /** One episode, or a season, marked watched or unwatched. */
    fun setEpisodesWatched(seriesId: String, episodes: List<Episode>, watched: Boolean) {
        val series = state.value.content.series.firstOrNull { it.id == seriesId } ?: return
        repository.setEpisodesWatched(watched, series, episodes)
    }

    /** The whole series: every episode of its seasons as known. */
    fun setSeriesWatched(seriesId: String, watched: Boolean) {
        val details = state.value.seriesDetails[seriesId] ?: return
        setEpisodesWatched(seriesId, details.seasons.flatMap { it.episodes }, watched)
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

    /** Shares a collection with every profile, or takes a shared one back into the open profile. */
    fun setCollectionShared(id: String, shared: Boolean) = repository.setCollectionShared(id, shared)

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

    /** The channel as the catalogue has it now: one no longer in it (Home kept from the last start) plays nothing. */
    fun playChannel(row: ChannelRow) {
        if (afterRestoring { playChannel(row) }) return
        state.value.content.channels.firstOrNull { it.id == row.channel.id }?.let { emit(repository.playRequest(it)) }
    }

    /** What was asked while the saved catalogue is read ([afterRestoring]). */
    private var afterRestore: Job? = null

    /**
     * Home left before the catalogue was read (another page, Ondo in the background): what was asked on it then is
     * dropped, the player never opens over what the viewer went to.
     */
    fun cancelHeldPlay() {
        afterRestore?.cancel()
        afterRestore = null
    }

    /**
     * While the saved catalogue is read at a start, Home kept from the last start on screen (its titles not in the
     * catalogue yet): [action] once it is read, on the same playlist, the last one asked only. False otherwise (another
     * playlist being opened plays nothing of the one before, as always).
     */
    private fun afterRestoring(action: () -> Unit): Boolean {
        val current = state.value
        if (!current.isRestoring || savedHomeDone || savedHome.value.snapshot == null) return false
        val playlist = current.activePlaylistId
        afterRestore?.cancel()
        afterRestore = viewModelScope.launch {
            val restored = state.first { !it.isRestoring }
            if (restored.activePlaylistId == playlist) action()
        }
        return true
    }

    /** A programme of the guide from its start, from the provider's archive. */
    fun playReplay(channel: com.markedoe.ondo.core.model.Channel, programme: EpgProgram) = emit(repository.replayRequest(channel, programme))

    /** Movie: plays it. Series: the next episode (details loaded first when needed, see [preparing]). */
    fun play(item: ShowcaseItem) {
        if (afterRestoring { play(item) }) return
        when (item) {
            // A film's card plays its default version (the one in progress, chosen last, or best for the screen), the film
            // as the catalogue has it now: one no longer in it (Home kept from the last start) plays nothing.
            is ShowcaseItem.MovieItem -> {
                val current = state.value
                val movie = current.content.movies.firstOrNull { it.id == item.movie.id } ?: return
                val versions = UiMappers.visibleVersions(current, movie, UiMappers.filmsIfReady(current))
                playMovie(UiMappers.defaultVersion(current, movie, versions).id)
            }
            // The series as the catalogue has it now: one no longer in it (Home kept from the last start) plays nothing.
            is ShowcaseItem.SeriesItem -> {
                val series = state.value.content.series.firstOrNull { it.id == item.series.id } ?: return
                prepare(item.key) { nextEpisode(series)?.let { emit(repository.playRequest(series, it)) } }
            }
        }
    }

    /** Long press on a "Continue watching" card: the card goes. */
    fun removeFromContinueWatching(key: String) = repository.removeFromContinueWatching(key)

    /**
     * A "Continue watching" card: the movie, episode or channel it stands for. A series' next episode has no entry yet
     * (its key says which); a finished one stands for its series while its seasons are not known: the next one plays.
     */
    fun resume(key: String) {
        if (afterRestoring { resume(key) }) return
        val current = state.value
        val entry = current.library.progress(key) ?: WatchEntry.ofKey(key)?.takeIf { it.kind == ItemKind.SERIES } ?: return
        when (entry.kind) {
            ItemKind.MOVIE -> playMovie(entry.itemId)
            ItemKind.CHANNEL -> current.content.channels.firstOrNull { it.id == entry.itemId }?.let { emit(repository.playRequest(it)) }
            ItemKind.SERIES -> {
                val series = current.content.series.firstOrNull { it.id == entry.itemId } ?: return
                if (entry.isFinished) return prepare(key) { nextEpisode(series)?.let { emit(repository.playRequest(series, it)) } }
                prepare(key) {
                    val details = seriesDetails(series).getOrNull() ?: return@prepare
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
        val entry = current.library.progress(key) ?: WatchEntry.ofKey(key) ?: return null
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
        // The series as the catalogue has them, once read (Home kept from the last start shows them before).
        val current = state.first { !it.isRestoring }
        val wanted = withContext(computation) {
            val resumed = home.continueWatching.mapNotNull { current.library.progress(it.key) ?: WatchEntry.ofKey(it.key) }
                .filter { it.kind == ItemKind.SERIES }
                .mapNotNull { UiMappers.series(current, it.itemId) }
            (home.hero.mapNotNull { (it.item as? ShowcaseItem.SeriesItem)?.series?.let { series -> UiMappers.series(current, series.id) } } + resumed)
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
        val details = seriesDetails(series).getOrNull() ?: return null
        return UiMappers.nextEpisode(state.value, series, details)?.first
    }

    /**
     * A series' episodes ([OndoRepository.details]), or why they could not be had. A cancellation stays one (`runCatching`
     * would take it as a failure: an error shown for a page closed, the work after it done anyway).
     */
    private suspend fun seriesDetails(series: Series): Result<SeriesDetails> = try {
        Result.success(repository.details(series))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    } catch (error: OutOfMemoryError) {
        // A page too large for this device (the HTTP client hands the error over): not a stop of the app.
        Result.failure(error)
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

    fun activate(id: String) = repository.activate(id)
    fun refresh() = repository.refresh()
    fun delete(id: String) = repository.delete(id)
    fun setShowAdultCategories(show: Boolean) = repository.setShowAdultCategories(show)

    // Profiles (docs/PROFILES.md)

    /** The profile in use, alone: the top bar's avatar follows it without the rest of the state. */
    val activeProfile: StateFlow<Profile> = repository.state.map { it.activeProfile }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, repository.state.value.activeProfile)


    fun switchProfile(id: String) = repository.switchProfile(id)
    fun checkPin(profile: Profile, pin: String) = repository.checkPin(profile, pin)
    fun unlockKidsSettings(pin: String) = repository.unlockKidsSettings(pin)
    fun addProfile(name: String, colorIndex: Int, symbol: String, isKids: Boolean, pin: String?) = repository.addProfile(name, colorIndex, symbol, isKids, pin)
    fun updateProfile(id: String, name: String, colorIndex: Int, symbol: String, isKids: Boolean, pin: String?, removePin: Boolean) =
        repository.updateProfile(id, name, colorIndex, symbol, isKids, pin, removePin)
    fun deleteProfile(id: String) = repository.deleteProfile(id)
    fun setLaunchProfile(id: String?) = repository.setLaunchProfile(id)
    fun setHideWatchedMovies(hide: Boolean) = repository.setHideWatchedMovies(hide)

    fun setGroupVersions(group: Boolean) = repository.setGroupVersions(group)

    fun setRowOrder(tab: String, arranged: List<String>, hidden: Collection<String>) = repository.setRowOrder(tab, arranged, hidden)

    fun resetRowOrder(tab: String) = repository.resetRowOrder(tab)

    /** Saves a selection (Settings → Row order → New selection); [id] null makes a new one. */
    fun saveSelection(id: String?, name: String, tags: List<String>, all: Boolean, tabs: List<String>) = repository.saveSelection(id, name, tags, all, tabs)

    fun deleteSelection(id: String) = repository.deleteSelection(id)

    /** The tags a selection can be made of, from the playlist's titles and what the device kept of them. */
    suspend fun selectionChoices(): SelectionChoices {
        val kept = repository.keptPosterFacts()
        return withContext(computation) { SelectionRows.choices(state.value, kept) }
    }

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
                    repository.prepareTmdbMetadata(item.key, item is ShowcaseItem.SeriesItem, details?.tmdbId, title, year, changedAt = item.addedAt)
                }
            }
    }

    private class PrepareInput(val on: Boolean, val content: PlaylistContent)

    /**
     * Home's carousel goes by release date ([com.markedoe.ondo.core.library.RecentReleases]), which TMDb gives: while Ondo
     * is on screen (the caller stops it otherwise), with the viewer's TMDb token, the titles that may have been released
     * lately ([com.markedoe.ondo.data.Showcase.releasesToAsk]) whose date the device does not know, or whose provider
     * changed them since TMDb was asked (a series' new episode), are asked in the background: [RatingFill.LIMIT] at most per
     * catalogue, two at a time at TMDb's pace (shared with the titles prepared ahead), kept on the device, and Home reads
     * them again twice ([RatingFill.run]), not at each answer. Nothing without a token; starts over with a new catalogue.
     */
    suspend fun fillCarouselReleases() {
        state
            .map { PrepareInput(it.settings.tmdbToken != null && !it.tmdbRefused, it.content) }
            .distinctUntilChanged { old, new -> old.on == new.on && old.content === new.content }
            .collectLatest { input ->
                if (!input.on) return@collectLatest
                // Home draws first.
                delay(CAROUSEL_FILL_DELAY_MS)
                val current = state.value
                val now = clock()
                val asked = withContext(computation) {
                    UiMappers.showcase(current).releasesToAsk(now).take(CAROUSEL_CANDIDATES).map { item ->
                        val details = details(current, item)
                        val title = details?.title?.takeIf { it.isNotBlank() } ?: item.title
                        val year = item.year ?: details?.releaseDate?.take(4)?.toIntOrNull()
                        item to RatingAsk(item is ShowcaseItem.SeriesItem, details?.tmdbId, title, year)
                    }
                }
                val unknown = repository.withoutReleaseDate(asked.associate { (item, a) -> item.key to (MetadataKey.all(a.series, a.tmdbId, a.title, a.year) to item.addedAt) })
                val ask = RatingFill.toAsk(asked, isKnown = { it.first.key !in unknown })
                RatingFill.run(
                    ask,
                    fetch = { (item, a) -> repository.prepareTmdbRelease(item.key, a.series, a.tmdbId, a.title, a.year, item.addedAt) },
                    resort = { carouselVersion.value += 1 },
                )
            }
    }

    private val _ratingSortVersion = MutableStateFlow(0)

    /** Changes when a grid's fill brought TMDb ratings ([fillRatingSort]): the pages sorted by rating read them again. */
    val ratingSortVersion: StateFlow<Int> = _ratingSortVersion.asStateFlow()

    /**
     * A page sorted by rating shows [items] (in its order), in the caller's coroutine (cancelled when the page goes):
     * with the viewer's TMDb token, up to [RatingFill.LIMIT] of those TMDb never answered for are asked in the
     * background, two at a time at TMDb's pace (as the titles prepared ahead), kept on the device, and the page sorts
     * again twice ([RatingFill.run]), not at each answer. Nothing without a token.
     */
    suspend fun fillRatingSort(items: List<ShowcaseItem>) {
        val current = state.value
        if (current.settings.tmdbToken == null || current.tmdbRefused || items.isEmpty()) return
        val asked = withContext(computation) {
            items.associate { item ->
                val details = details(current, item)
                val title = details?.title?.takeIf { it.isNotBlank() } ?: item.title
                val year = item.year ?: details?.releaseDate?.take(4)?.toIntOrNull()
                item.key to RatingAsk(item is ShowcaseItem.SeriesItem, details?.tmdbId, title, year)
            }
        }
        val unanswered = repository.withoutTmdbAnswer(asked.mapValues { (_, a) -> MetadataKey.all(a.series, a.tmdbId, a.title, a.year) })
        val ask = RatingFill.toAsk(items, isKnown = { it.key !in unanswered })
        RatingFill.run(
            ask,
            fetch = { item -> asked.getValue(item.key).let { repository.prepareTmdbRating(item.key, it.series, it.tmdbId, it.title, it.year) } },
            resort = { _ratingSortVersion.value += 1 },
        )
    }

    private class RatingAsk(val series: Boolean, val tmdbId: String?, val title: String, val year: Int?)
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

        /** The longest a tab is watched to be worked out ahead ([workOut]). */
        private const val WORK_OUT_LIMIT_MS = 60_000L

        /** The carousel's candidates read at most, and the wait before asking about them (see [fillCarouselReleases]). */
        const val CAROUSEL_CANDIDATES = 2_000
        const val CAROUSEL_FILL_DELAY_MS = 2_000L

        /** Titles prepared ahead at most, and the wait before starting (see [prepareAhead]). */
        const val PREPARE_LIMIT = 300
        const val PREPARE_START_DELAY_MS = 10_000L

        /** TMDb is asked for people once typing pauses this long. */
        const val TMDB_PEOPLE_DEBOUNCE_MS = 400L

        /** TMDb is asked for people from three letters on ([OndoRepository.tmdbPeople]). */
        const val TMDB_PEOPLE_MIN_LENGTH = 3
    }
}
