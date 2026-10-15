package com.markedoe.ondo.data

import com.markedoe.ondo.core.epg.EpgIndex
import com.markedoe.ondo.core.library.AwardsCatalogue
import com.markedoe.ondo.core.library.ErrorAttribution
import com.markedoe.ondo.core.library.PlaybackFactory
import com.markedoe.ondo.core.library.PlaylistLoader
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.EpgProgram
import com.markedoe.ondo.core.model.Episode
import com.markedoe.ondo.core.model.MediaDetails
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.PlaybackItem
import com.markedoe.ondo.core.model.Playlist
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.PlaylistSource
import com.markedoe.ondo.core.model.Season
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.core.model.SeriesDetails
import com.markedoe.ondo.core.model.XtreamCredentials
import com.markedoe.ondo.core.net.HttpClient
import com.markedoe.ondo.core.net.HttpException
import com.markedoe.ondo.core.xtream.XtreamClient
import com.markedoe.ondo.player.PlayRequest
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the screens show, in one immutable value. */
data class OndoState(
    val isReady: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val playlists: List<SavedPlaylist> = emptyList(),
    val content: PlaylistContent = PlaylistContent.EMPTY,
    val library: LibraryState = LibraryState(),
    val guide: EpgIndex? = null,
    val isLoading: Boolean = false,
    val error: LoadError? = null,
    val movieDetails: Map<String, MediaDetails> = emptyMap(),
    val seriesDetails: Map<String, SeriesDetails> = emptyMap(),
    /** The awards bundled with the app (read once, see `awardsSource`). */
    val awards: AwardsCatalogue = AwardsCatalogue.EMPTY,
) {
    val activePlaylist: SavedPlaylist? get() = playlists.firstOrNull { it.id == settings.activePlaylistId } ?: playlists.firstOrNull()
    val activePlaylistId: String? get() = activePlaylist?.id
}

/** Why a playlist could not be loaded: shown in the viewer's language (see `loadErrorText`), not as the exception's text. */
data class LoadError(val kind: ErrorAttribution, val status: Int? = null) {
    companion object {
        fun of(error: Throwable) = LoadError(ErrorAttribution.classify(error), (error as? HttpException.BadStatus)?.code)
    }
}

/**
 * The app's state and its changes: playlists, catalogue, guide, library and details. Loading and parsing
 * go through the shared core; everything is persisted in [AppStore].
 */
class OndoRepository(
    private val store: AppStore,
    private val http: HttpClient,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    /** Files and network (tests pass their own dispatcher so that no write outlives a test). */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** The bundled `awards.json` (Palme d'Or, Oscars…, from Wikidata), or null without it. */
    private val awardsSource: (() -> String?)? = null,
    /** Delivers programme reminders (WorkManager in the app). */
    private val reminderScheduler: ReminderScheduler = NoReminderScheduler,
) {
    private val _state = MutableStateFlow(OndoState())
    val state: StateFlow<OndoState> = _state.asStateFlow()
    private var refreshJob: Job? = null
    private var openJob: Job? = null
    private var guideJob: Job? = null
    private var bootstrapping = false

    /**
     * Library changes made before the saved data is loaded: when the system recreates only the player after
     * the process was killed, its progress arrives first. They are applied on top of the saved library
     * once it is loaded; writing them alone would replace the favourites and history with an empty library.
     */
    private val pendingLibraryChanges = mutableListOf<(LibraryState) -> LibraryState>()

    /** Saves run one at a time, in order: the last change is the one on disk. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val writer = io.limitedParallelism(1)

    fun bootstrap() {
        if (_state.value.isReady || bootstrapping) return
        bootstrapping = true
        scope.launch {
            val settings = withContext(io) { store.loadSettings() }
            val playlists = withContext(io) { store.loadPlaylists() }
            val saved = withContext(io) { store.loadLibrary() }
            val pending = pendingLibraryChanges.toList()
            pendingLibraryChanges.clear()
            val library = pending.fold(saved) { library, change -> change(library) }
            _state.update { it.copy(settings = settings, playlists = playlists, library = library, isReady = true) }
            if (pending.isNotEmpty()) persist { saveLibrary(library) }
            _state.value.activePlaylist?.let { open(it) }
            awardsSource?.let { source ->
                val awards = withContext(io) { runCatching { source()?.let(AwardsCatalogue::parse) }.getOrNull() }
                if (awards != null) _state.update { it.copy(awards = awards) }
            }
        }
    }

    /** Saves in order, off the main thread. A failed save (disk full) keeps the app running. */
    private fun persist(save: AppStore.() -> Unit) {
        scope.launch(writer) { runCatching { store.save() } }
    }

    // MARK: Playlists

    /** Loads the playlist once to check it, then saves and activates it. */
    suspend fun addPlaylist(name: String, url: String, username: String? = null, password: String? = null, epgUrl: String? = null): Result<Unit> {
        val saved = SavedPlaylist(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { hostName(url) },
            url = url.trim(),
            username = username?.trim(),
            password = password,
            epgUrl = epgUrl?.trim()?.ifBlank { null },
            addedAt = now(),
        )
        _state.update { it.copy(isLoading = true, error = null) }
        return loadCatching { withContext(io) { loader().load(saved.toCorePlaylist()) } }
            .map { content ->
                val playlists = _state.value.playlists + saved
                val settings = _state.value.settings.copy(activePlaylistId = saved.id)
                withContext(writer) {
                    runCatching {
                        store.savePlaylists(playlists)
                        store.saveSettings(settings)
                        store.saveContent(saved.id, content)
                    }
                }
                _state.update { it.copy(playlists = playlists, settings = settings, content = content, isLoading = false, guide = null, movieDetails = emptyMap(), seriesDetails = emptyMap()) }
                loadGuide(saved, content)
            }
            .onFailure { error -> _state.update { it.copy(isLoading = false, error = LoadError.of(error)) } }
    }

    fun activate(id: String) {
        val playlist = _state.value.playlists.firstOrNull { it.id == id } ?: return
        updateSettings { it.copy(activePlaylistId = id) }
        // The previous playlist's downloads stop: they could only land on the wrong playlist.
        openJob?.cancel()
        refreshJob?.cancel()
        guideJob?.cancel()
        _state.update { it.copy(content = PlaylistContent.EMPTY, guide = null, movieDetails = emptyMap(), seriesDetails = emptyMap(), error = null, isLoading = false) }
        open(playlist)
    }

    fun refresh() {
        _state.value.activePlaylist?.let { load(it) }
    }

    fun delete(id: String) {
        val playlists = _state.value.playlists.filterNot { it.id == id }
        _state.value.library.reminders.filter { it.playlistId == id }.forEach(reminderScheduler::cancel)
        val library = _state.value.library.removingPlaylist(id)
        val settings = _state.value.settings.let { if (it.activePlaylistId == id) it.copy(activePlaylistId = playlists.firstOrNull()?.id) else it }
        persist {
            savePlaylists(playlists)
            saveLibrary(library)
            saveSettings(settings)
            deleteContent(id)
        }
        val wasActive = _state.value.activePlaylistId == id
        _state.update { it.copy(playlists = playlists, library = library, settings = settings) }
        if (wasActive) {
            openJob?.cancel()
            refreshJob?.cancel()
            guideJob?.cancel()
            _state.update { it.copy(content = PlaylistContent.EMPTY, guide = null, isLoading = false) }
            _state.value.activePlaylist?.let { open(it) }
        }
    }

    fun setAppearance(appearance: com.markedoe.ondo.ui.theme.Appearance) = updateSettings { it.copy(appearance = appearance) }

    fun setShowAdultCategories(show: Boolean) = updateSettings { it.copy(showAdultCategories = show) }

    fun setPosterSort(sort: PosterSort) = updateSettings { it.copy(posterSort = sort) }

    fun setCategoryHidden(categoryId: String, hidden: Boolean) {
        val playlistId = _state.value.activePlaylistId ?: return
        updateLibrary { it.settingCategoryHidden(playlistId, categoryId, hidden) }
    }

    /** A choice about tags answers the "show only your languages?" suggestion too. */
    fun setTagsHidden(tags: Collection<String>, hidden: Boolean) {
        updateLibrary { it.settingTagsHidden(tags, hidden) }
        dismissTagSuggestion()
    }

    fun dismissTagSuggestion() {
        val playlistId = _state.value.activePlaylistId ?: return
        updateSettings { it.copy(dismissedTagSuggestions = it.dismissedTagSuggestions + playlistId) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun updateSettings(change: (AppSettings) -> AppSettings) {
        val settings = change(_state.value.settings)
        _state.update { it.copy(settings = settings) }
        persist { saveSettings(settings) }
    }

    /** Cached catalogue at once, then a refresh when it is a day old or missing. */
    private fun open(playlist: SavedPlaylist) {
        openJob?.cancel()
        openJob = scope.launch {
            val cached = withContext(io) { store.loadContent(playlist.id) }
            // Another playlist was opened while this one was read: its catalogue must not replace that one.
            if (_state.value.activePlaylistId != playlist.id) return@launch
            if (cached != null) {
                _state.update { it.copy(content = cached) }
                loadGuide(playlist, cached)
            }
            if (cached == null || now() - cached.fetchedAt > REFRESH_AFTER_MS) load(playlist)
        }
    }

    private fun load(playlist: SavedPlaylist) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            loadCatching { withContext(io) { loader().load(playlist.toCorePlaylist()) } }
                .onSuccess { content ->
                    withContext(writer) { runCatching { store.saveContent(playlist.id, content) } }
                    if (_state.value.activePlaylistId == playlist.id) {
                        _state.update { it.copy(content = content, isLoading = false) }
                        loadGuide(playlist, content)
                    } else {
                        _state.update { it.copy(isLoading = false) }
                    }
                }
                .onFailure { error ->
                    // The error of a playlist that is no longer shown is not this one's.
                    _state.update { if (it.activePlaylistId == playlist.id) it.copy(isLoading = false, error = LoadError.of(error)) else it.copy(isLoading = false) }
                }
        }
    }

    /** The guides of the playlist (the user's, then the provider's), indexed off the main thread. */
    private fun loadGuide(playlist: SavedPlaylist, content: PlaylistContent) {
        val core = playlist.toCorePlaylist()
        if (core.epgUrls.isEmpty() && content.providerEpgUrls.isEmpty()) return
        // A newer catalogue or another playlist replaces the guide being loaded.
        guideJob?.cancel()
        guideJob = scope.launch {
            val guide = loadCatching {
                val result = loader().loadEpg(core, content)
                withContext(Dispatchers.Default) { EpgIndex(result.data) }
            }.getOrNull() ?: return@launch
            if (_state.value.activePlaylistId == playlist.id) _state.update { it.copy(guide = guide) }
        }
    }

    /**
     * Like `runCatching`, but a cancelled load (another playlist opened meanwhile) stays a cancellation: it
     * must not reach the screen as a loading error.
     */
    private suspend fun <T> loadCatching(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    // MARK: Library

    fun toggleFavorite(kind: ItemKind, itemId: String, title: String, imageUrl: String?) {
        val playlistId = _state.value.activePlaylistId ?: return
        updateLibrary { it.toggleFavorite(Favorite(playlistId, kind, itemId, title, imageUrl, now())) }
    }

    fun recordProgress(key: String, position: Double, duration: Double) {
        updateLibrary { it.updateProgress(key, position, duration, now()) }
    }

    fun removeFromHistory(entry: WatchEntry) = updateLibrary { it.remove(entry) }

    // MARK: Reminders

    /** Sets a reminder for a programme of the current playlist, or takes it away. */
    fun toggleReminder(channel: Channel, program: EpgProgram) {
        val playlistId = _state.value.activePlaylistId ?: return
        val reminder = Reminder(playlistId, channel.id, channel.name, program.title, program.start, program.end)
        val wasSet = _state.value.library.reminders.any { it.key == reminder.key }
        updateLibrary { it.togglingReminder(reminder, now()) }
        if (wasSet) reminderScheduler.cancel(reminder) else reminderScheduler.schedule(reminder, now())
    }

    fun removeReminder(reminder: Reminder) {
        reminderScheduler.cancel(reminder)
        updateLibrary { library -> library.copy(reminders = library.reminders.filterNot { it.key == reminder.key }) }
    }

    // MARK: Collections

    /** Creates a collection and returns its id. */
    fun createCollection(name: String): String {
        val id = UUID.randomUUID().toString()
        updateLibrary { it.creatingCollection(id, name, now()) }
        return id
    }

    fun renameCollection(id: String, name: String) = updateLibrary { it.renamingCollection(id, name, now()) }

    fun deleteCollection(id: String) = updateLibrary { it.deletingCollection(id) }

    /** Adds an item of the current playlist to a collection, or takes it out. */
    fun toggleInCollection(id: String, kind: ItemKind, itemId: String, title: String, imageUrl: String?) {
        val playlistId = _state.value.activePlaylistId ?: return
        updateLibrary { it.togglingInCollection(id, Favorite(playlistId, kind, itemId, title, imageUrl, now()), now()) }
    }

    private fun updateLibrary(change: (LibraryState) -> LibraryState) {
        if (!_state.value.isReady) {
            pendingLibraryChanges += change
            bootstrap()
            return
        }
        _state.update { it.copy(library = change(it.library)) }
        val library = _state.value.library
        persist { saveLibrary(library) }
    }

    // MARK: Details

    suspend fun details(movie: Movie): MediaDetails? {
        _state.value.movieDetails[movie.id]?.let { return it }
        val playlist = _state.value.activePlaylist ?: return null
        val streamId = movie.xtreamStreamId ?: return null
        val credentials = playlist.credentials() ?: return null
        val details = runCatching { withContext(io) { XtreamClient(credentials, http).movieDetails(streamId) } }.getOrNull() ?: return null
        _state.update { it.copy(movieDetails = it.movieDetails + (movie.id to details)) }
        return details
    }

    suspend fun details(series: Series): SeriesDetails {
        _state.value.seriesDetails[series.id]?.let { return it }
        val playlist = _state.value.activePlaylist
        val credentials = playlist?.credentials()
        val seriesId = series.xtreamSeriesId
        val details = if (credentials != null && seriesId != null) {
            withContext(io) { XtreamClient(credentials, http).seriesDetails(seriesId) }
        } else {
            val seasons = series.episodes.groupBy { it.season }.toSortedMap().map { (number, episodes) ->
                Season(number, "Season $number", episodes.sortedBy { it.number })
            }
            SeriesDetails(
                info = MediaDetails(title = series.name, plot = series.plot, genre = series.genre, rating = series.rating, posterUrl = series.coverUrl),
                seasons = seasons,
            )
        }
        _state.update { it.copy(seriesDetails = it.seriesDetails + (series.id to details)) }
        return details
    }

    // MARK: Playback

    fun playRequest(movie: Movie, fromStart: Boolean = false): PlayRequest? {
        val playlist = _state.value.activePlaylist ?: return null
        val item = PlaybackFactory(playlist.toCorePlaylist()).item(movie)
        val entry = WatchEntry(playlist.id, ItemKind.MOVIE, movie.id, null, movie.name, movie.year?.toString(), movie.posterUrl, lastWatchedAt = now())
        return start(item, entry, fromStart)
    }

    fun playRequest(series: Series, episode: Episode): PlayRequest? {
        val playlist = _state.value.activePlaylist ?: return null
        val item = PlaybackFactory(playlist.toCorePlaylist()).item(episode, series)
        val entry = WatchEntry(playlist.id, ItemKind.SERIES, series.id, episode.id, series.name, "${episode.code} · ${episode.title}", episode.posterUrl ?: series.coverUrl, lastWatchedAt = now())
        return start(item, entry, fromStart = false)
    }

    fun playRequest(channel: Channel): PlayRequest? {
        val playlist = _state.value.activePlaylist ?: return null
        val item = PlaybackFactory(playlist.toCorePlaylist()).item(channel)
        val entry = WatchEntry(playlist.id, ItemKind.CHANNEL, channel.id, null, channel.name, null, channel.logoUrl, lastWatchedAt = now())
        return start(item, entry, fromStart = true)
    }

    private fun start(item: PlaybackItem, entry: WatchEntry, fromStart: Boolean): PlayRequest {
        updateLibrary { it.recordStart(entry) }
        val known = _state.value.library.progress(entry.key)
        val resumeAt = if (!fromStart && known?.isResumable == true) (known.position * 1000).toLong() else 0L
        return PlayRequest(
            title = item.title,
            subtitle = item.subtitle,
            urls = item.urls,
            headers = item.headers?.toMap().orEmpty(),
            isLive = item.isLive,
            artworkUrl = item.artworkUrl,
            startPositionMs = resumeAt,
            progressKey = entry.key,
        )
    }

    private fun loader() = PlaylistLoader(http, clock = now)

    private fun SavedPlaylist.credentials(): XtreamCredentials? =
        if (isXtream) XtreamCredentials(XtreamCredentials.normalizeServer(url) ?: url, username.orEmpty(), password.orEmpty()) else null

    private fun SavedPlaylist.toCorePlaylist(): Playlist = Playlist(
        id = id,
        name = name,
        source = credentials()?.let { PlaylistSource.Xtream(it) } ?: PlaylistSource.Remote(url),
        epgUrls = listOfNotNull(epgUrl),
    )

    companion object {
        const val REFRESH_AFTER_MS = 24 * 60 * 60 * 1000L

        fun hostName(url: String): String = runCatching { java.net.URI(url.trim()).host }.getOrNull()?.removePrefix("www.") ?: "Playlist"
    }
}
