package com.markedoe.ondo.data

import com.markedoe.ondo.core.catchup.CatchupUrlBuilder
import com.markedoe.ondo.core.epg.EpgIndex
import com.markedoe.ondo.core.library.AwardsCatalogue
import com.markedoe.ondo.core.library.CatalogueMatcher
import com.markedoe.ondo.core.library.ErrorAttribution
import com.markedoe.ondo.core.library.PlaceholderNames
import com.markedoe.ondo.core.library.PlaybackFactory
import com.markedoe.ondo.core.library.PlaylistLoader
import com.markedoe.ondo.core.library.TmdbClient
import com.markedoe.ondo.core.library.TrendingMatch
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.EpgData
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
import com.markedoe.ondo.core.playback.ProblemReport
import com.markedoe.ondo.core.sync.SyncKey
import com.markedoe.ondo.core.sync.SyncMerge
import com.markedoe.ondo.core.sync.SyncSnapshot
import com.markedoe.ondo.core.xtream.XtreamClient
import com.markedoe.ondo.player.LivePause
import com.markedoe.ondo.player.PlayRequest
import com.markedoe.ondo.ui.UiMappers
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    /**
     * The active playlist's saved catalogue is being read (at start, after switching playlists): the splash screen
     * stays a little longer and Home does not call the playlist empty meanwhile.
     */
    val isRestoring: Boolean = false,
    val error: LoadError? = null,
    val movieDetails: Map<String, MediaDetails> = emptyMap(),
    val seriesDetails: Map<String, SeriesDetails> = emptyMap(),
    /** The awards bundled with the app (read once, see `awardsSource`). */
    val awards: AwardsCatalogue = AwardsCatalogue.EMPTY,
    /** The last error that stopped the app, shown in Settings → About. */
    val lastCrash: LastCrash? = null,
    /**
     * The app's words for the names the core gives in English where the provider gives none ("Uncategorized",
     * "Channel 12"…): [content] has them; the episodes of [seriesDetails] are kept as they came (they are saved).
     */
    val placeholderNames: PlaceholderNames = PlaceholderNames.ENGLISH,
    /** TMDb's trending titles found in the catalogue (with the viewer's token), in TMDb's order. */
    val trending: List<TrendingMatch> = emptyList(),
    /** TMDb's backdrop for a title whose provider gave none that loads, by the title's key (`m-<id>`, `s-<id>`). */
    val tmdbBackdrops: Map<String, String> = emptyMap(),
    /** Provider backdrops that did not load this session (dead address, refused, not an image): TMDb's show instead. */
    val deadBackdrops: Set<String> = emptySet(),
    /** TMDb refused the saved token when it was used (it was checked elsewhere, or revoked since): Settings says so. */
    val tmdbRefused: Boolean = false,
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
    /** Where newer awards are published and the last download kept; null: the bundled ones only. */
    private val awardsDownload: AwardsDownload? = null,
    /** Delivers programme reminders (WorkManager in the app). */
    private val reminderScheduler: ReminderScheduler = NoReminderScheduler,
    /** Where large answers (guides, Xtream lists) are downloaded while they are read (the app's cache); emptied at start. */
    private val downloadFolder: File? = null,
    /** Memory the guide's programmes may take (see `PlaylistLoader`); null: no limit. */
    private val guideBudgetBytes: Long? = null,
    /** The app's words for the names the core gives in English (see [OndoState.placeholderNames]). */
    placeholderNames: PlaceholderNames = PlaceholderNames.ENGLISH,
    /** Where playlists are parsed (tests that run each step themselves give a thread of their own). */
    private val parsing: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(OndoState(placeholderNames = placeholderNames))
    val state: StateFlow<OndoState> = _state.asStateFlow()
    private var refreshJob: Job? = null
    private var openJob: Job? = null
    private var guideJob: Job? = null
    private var bootstrapping = false

    /**
     * The playlist whose details are in [OndoState], when each came from the provider (those are kept on disk), the
     * reading of its document and the pending save. Changed on the main thread only.
     */
    private var detailsPlaylistId: String? = null
    private val movieFetchedAt = mutableMapOf<String, Long>()
    private val seriesFetchedAt = mutableMapOf<String, Long>()
    private var storedDetailsJob: Job? = null
    private var detailsSaveJob: Job? = null

    /**
     * Library changes made before the saved data is loaded: when the system recreates only the player after
     * the process was killed, its progress arrives first. They are applied on top of the saved library
     * once it is loaded; writing them alone would replace the favourites and history with an empty library.
     */
    private val pendingLibraryChanges = mutableListOf<(LibraryState) -> LibraryState>()

    /** Settings changed before the saved ones were read (the player opened again alone): applied to them once read. */
    private val pendingSettingsChanges = mutableListOf<(AppSettings) -> AppSettings>()

    /** Saves run one at a time, in order: the last change is the one on disk. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val writer = io.limitedParallelism(1)

    /** The catalogue as loaded, with the core's English names (as saved), and its copy in [OndoState.content]. */
    private var loadedContent = PlaylistContent.EMPTY
    private var shownContent = PlaylistContent.EMPTY

    /** [content] as the screens show it, in the words of [OndoState.placeholderNames]. Main thread. */
    private fun shown(content: PlaylistContent): PlaylistContent {
        val shown = _state.value.placeholderNames.rename(content)
        loadedContent = content
        shownContent = shown
        return shown
    }

    /** The catalogue left the screen: nothing of it is kept (a large one is most of a TV's memory). Main thread. */
    private fun forgetContent() {
        loadedContent = PlaylistContent.EMPTY
        shownContent = PlaylistContent.EMPTY
        UiMappers.forgetCatalogue()
    }

    /**
     * The app's words for the names the core gives in English, again after the app's language changes (the activity is
     * recreated, not the repository): the catalogue on screen is shown in them. Main thread.
     */
    fun setPlaceholderNames(names: PlaceholderNames) {
        if (names == _state.value.placeholderNames) return
        val before = shownContent
        val renamed = names.rename(loadedContent)
        shownContent = renamed
        // The episodes of a playlist's own series came with its catalogue, in the former words: they are made again.
        val provided = seriesFetchedAt.keys.toSet()
        _state.update { state ->
            val details = state.seriesDetails.filterKeys { it in provided }
            // A catalogue replaced meanwhile (another playlist opened) is not brought back.
            if (state.content === before) state.copy(placeholderNames = names, content = renamed, seriesDetails = details)
            else state.copy(placeholderNames = names, seriesDetails = details)
        }
    }

    fun bootstrap() {
        if (_state.value.isReady || bootstrapping) return
        bootstrapping = true
        scope.launch {
            val savedSettings = withContext(io) { store.loadSettings() }
            val playlists = withContext(io) { store.loadPlaylists() }
            val saved = withContext(io) { store.loadLibrary() }
            val lastCrash = withContext(io) {
                // Downloads left half-read by a stop, and copies of files chosen for a playlist that was not saved.
                downloadFolder?.listFiles()?.forEach { it.delete() }
                store.forgetUnusedImports(playlists)
                store.loadLastCrash()
            }
            val pending = pendingLibraryChanges.toList()
            pendingLibraryChanges.clear()
            val library = pending.fold(saved) { library, change -> change(library) }
            val pendingSettings = pendingSettingsChanges.toList()
            pendingSettingsChanges.clear()
            val settings = pendingSettings.fold(savedSettings) { settings, change -> change(settings) }
            // With a playlist, its saved catalogue is read next: restoring from the same update (no "empty" moment).
            _state.update {
                it.copy(settings = settings, playlists = playlists, library = library, lastCrash = lastCrash, isReady = true, isRestoring = playlists.isNotEmpty())
            }
            if (pending.isNotEmpty()) persist { saveLibrary(library) }
            if (pendingSettings.isNotEmpty()) persist { saveSettings(settings) }
            _state.value.activePlaylist?.let { open(it) }
            awardsSource?.let { source ->
                val awards = withContext(io) { runCatching { source()?.let(AwardsCatalogue::parse) }.getOrNull() }
                if (awards != null) _state.update { it.copy(awards = awards) }
            }
            awardsDownload?.let { updateAwards(it) }
        }
    }

    /** The last download when it is newer than the bundled awards; then, once a week unless turned off, a newer file. */
    private suspend fun updateAwards(download: AwardsDownload) {
        val saved = withContext(io) { runCatching { download.file.takeIf { it.exists() }?.readText()?.let(AwardsCatalogue::parse) }.getOrNull() }
        if (saved != null && saved.isNewerThan(_state.value.awards)) _state.update { it.copy(awards = saved) }
        val settings = _state.value.settings
        if (!settings.updateAwards || now() - settings.awardsCheckedAt < AwardsDownload.INTERVAL) return
        updateSettings { it.copy(awardsCheckedAt = now()) }
        val text = runCatching { withContext(io) { http.get(download.url) } }.getOrNull()
            ?.takeIf { it.size <= AwardsDownload.MAX_SIZE }?.decodeToString() ?: return
        val catalogue = runCatching { AwardsCatalogue.parse(text) }.getOrNull() ?: return
        if (catalogue.films.size < AwardsDownload.MIN_FILMS || !catalogue.isNewerThan(_state.value.awards)) return
        withContext(io) { runCatching { download.file.parentFile?.mkdirs(); download.file.writeText(text) } }
        _state.update { it.copy(awards = catalogue) }
    }

    fun setUpdateAwards(update: Boolean) = updateSettings { it.copy(updateAwards = update) }

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
            updatedAt = now(),
        )
        _state.update { it.copy(isLoading = true, error = null) }
        return try {
            add(saved)
        } catch (cancelled: CancellationException) {
            // Left before it loaded (Back on Welcome closes the screen and its scope): the buttons come back.
            _state.update { it.copy(isLoading = false) }
            throw cancelled
        }
    }

    private suspend fun add(saved: SavedPlaylist): Result<Unit> =
        loadCatching { withContext(io) { loader().load(saved.toCorePlaylist()) } }
            .map { content ->
                withContext(writer) { runCatching { store.saveContent(saved.id, content) } }
                startDetails(saved.id)
                val shown = shown(content)
                // The playlists and settings as they are once saved: a playlist synced or added meanwhile stays.
                _state.update {
                    it.copy(
                        playlists = it.playlists + saved, settings = it.settings.copy(activePlaylistId = saved.id), content = shown,
                        isLoading = false, isRestoring = false, guide = null, movieDetails = emptyMap(), seriesDetails = emptyMap(),
                    )
                }
                val state = _state.value
                persist {
                    savePlaylists(state.playlists)
                    saveSettings(state.settings)
                }
                loadGuide(saved, content)
            }
            .onFailure { error -> _state.update { it.copy(isLoading = false, error = LoadError.of(error)) } }

    fun activate(id: String) {
        val playlist = _state.value.playlists.firstOrNull { it.id == id } ?: return
        updateSettings { it.copy(activePlaylistId = id) }
        // The previous playlist's downloads stop: they could only land on the wrong playlist.
        openJob?.cancel()
        refreshJob?.cancel()
        guideJob?.cancel()
        startDetails(playlist.id)
        forgetContent()
        _state.update { it.copy(content = PlaylistContent.EMPTY, guide = null, movieDetails = emptyMap(), seriesDetails = emptyMap(), error = null, isLoading = false, isRestoring = true) }
        open(playlist)
    }

    /** The catalogue downloaded again, and its guide too (the viewer asked: what is kept is not enough). */
    fun refresh() {
        _state.value.activePlaylist?.let { load(it, newGuide = true) }
    }

    fun delete(id: String) {
        val removed = _state.value.playlists.firstOrNull { it.id == id } ?: return
        val playlists = _state.value.playlists.filterNot { it.id == id }
        _state.value.library.reminders.filter { it.playlistId == id }.forEach(reminderScheduler::cancel)
        val library = _state.value.library.removingPlaylist(id, now())
        val settings = _state.value.settings.let { if (it.activePlaylistId == id) it.copy(activePlaylistId = playlists.firstOrNull()?.id) else it }
        persist {
            savePlaylists(playlists, removed)
            saveLibrary(library)
            saveSettings(settings)
            deleteContent(id)
            deleteDetails(id)
            deleteGuide(id)
        }
        val wasActive = _state.value.activePlaylistId == id
        _state.update { it.copy(playlists = playlists, library = library, settings = settings) }
        if (wasActive) {
            openJob?.cancel()
            refreshJob?.cancel()
            guideJob?.cancel()
            forgetContent()
            _state.update { it.copy(content = PlaylistContent.EMPTY, guide = null, isLoading = false, isRestoring = it.playlists.isNotEmpty()) }
            _state.value.activePlaylist?.let { open(it) }
        }
    }

    fun setAppearance(appearance: com.markedoe.ondo.ui.theme.Appearance) = updateSettings { it.copy(appearance = appearance) }

    fun setShowAdultCategories(show: Boolean) = updateSettings { it.copy(showAdultCategories = show) }

    // MARK: Sync on the local network (docs/SYNC.md)

    fun setSyncOnLocalNetwork(on: Boolean) = updateSettings { it.copy(syncOnLocalNetwork = on) }

    private val _syncDevices = MutableStateFlow<List<SyncDevice>>(emptyList())

    /** The devices sync found or heard from while it runs (LocalSync reports them). */
    val syncDevices: StateFlow<List<SyncDevice>> = _syncDevices.asStateFlow()

    fun reportSyncDevices(devices: List<SyncDevice>) {
        _syncDevices.value = devices
    }

    /** This installation's sync ID, made the first time it is asked for. */
    fun syncDeviceId(): String {
        _state.value.settings.syncDeviceId.ifEmpty { null }?.let { return it }
        val id = UUID.randomUUID().toString()
        updateSettings { it.copy(syncDeviceId = id) }
        return id
    }

    /** What this device sends to sync; null until its data is loaded. Main thread. */
    fun syncSnapshot(deviceName: String?): SyncSnapshot? {
        if (!_state.value.isReady) return null
        return SyncData.snapshot(_state.value, syncDeviceId(), deviceName, now())
    }

    /**
     * Merges [incoming] with this device's data as it is now, keeps the result and returns it (what a server answers,
     * what a client keeps). Main thread: a change made during the session is part of the merge, so none is lost.
     */
    fun mergeSync(incoming: SyncSnapshot, deviceName: String?): SyncSnapshot? {
        val local = syncSnapshot(deviceName) ?: return null
        val merged = SyncMerge.merge(local, incoming)
        applySync(merged)
        return merged
    }

    private fun applySync(merged: SyncSnapshot) {
        val state = _state.value
        val applied = SyncData.apply(state, merged)
        val keysChanged = applied.keys != state.settings.serviceKeys
        if (applied.playlists == state.playlists && applied.library == state.library && !keysChanged) return
        val removedIds = applied.removed.mapTo(HashSet()) { it.id }
        state.library.reminders.filter { it.playlistId in removedIds }.forEach(reminderScheduler::cancel)
        // The playlist in use stays unless it was deleted, or becomes the same one from another device that replaced it;
        // a device without one takes the first that arrived.
        val active = state.settings.activePlaylistId?.takeIf { id -> applied.playlists.any { it.id == id } }
            ?: state.activePlaylistId?.let { applied.replacements[it] }
            ?: applied.playlists.firstOrNull()?.id
        val settings = state.settings.copy(activePlaylistId = active, serviceKeys = applied.keys)
        val before = state.activePlaylist
        persist {
            if (applied.removed.isEmpty()) savePlaylists(applied.playlists) else applied.removed.forEach { savePlaylists(applied.playlists, it) }
            saveLibrary(applied.library)
            saveSettings(settings)
            applied.removed.forEach {
                deleteContent(it.id)
                deleteDetails(it.id)
                deleteGuide(it.id)
            }
        }
        _state.update { it.copy(playlists = applied.playlists, library = applied.library, settings = settings) }
        val after = _state.value.activePlaylist
        when {
            after == null -> {
                openJob?.cancel()
                refreshJob?.cancel()
                guideJob?.cancel()
                forgetContent()
                _state.update { it.copy(content = PlaylistContent.EMPTY, guide = null, isLoading = false, isRestoring = false) }
            }
            before?.id != after.id -> activate(after.id)
            // Another address or login for the playlist in use: its catalogue comes again from there.
            before.url != after.url || before.username != after.username || before.password != after.password -> load(after)
        }
        // A TMDb token saved, changed or removed on another device: used from now on, as if typed here.
        if (state.settings.tmdbToken != settings.tmdbToken) tmdbTokenChanged()
    }

    fun setHideWatchedMovies(hide: Boolean) = updateSettings { it.copy(hideWatchedMovies = hide) }

    fun setPosterSort(sort: PosterSort) = updateSettings { it.copy(posterSort = sort) }

    fun setLiveBuffer(seconds: Int) = updateSettings { it.copy(liveBuffer = seconds) }

    fun setVodBuffer(seconds: Int) = updateSettings { it.copy(vodBuffer = seconds) }

    /** The languages the viewer chose in the player, for the next streams. Nothing written when nothing changed. */
    fun setTrackLanguages(audio: String?, subtitles: String?, subtitlesOn: Boolean) {
        val settings = _state.value.settings
        if (settings.preferredAudioLanguage == audio && settings.preferredSubtitleLanguage == subtitles && settings.subtitlesOn == subtitlesOn) return
        updateSettings { it.copy(preferredAudioLanguage = audio, preferredSubtitleLanguage = subtitles, subtitlesOn = subtitlesOn) }
    }

    fun setVideoScale(scale: VideoScale) {
        if (_state.value.settings.videoScale != scale) updateSettings { it.copy(videoScale = scale) }
    }

    fun setSubtitleSize(size: SubtitleSize) {
        if (_state.value.settings.subtitleSize != size) updateSettings { it.copy(subtitleSize = size) }
    }

    fun setCategoryHidden(categoryId: String, hidden: Boolean) = setCategoriesHidden(listOf(categoryId), hidden)

    /** Hide all / Show all: one change, one save of the library. */
    fun setCategoriesHidden(categoryIds: Collection<String>, hidden: Boolean) {
        val playlistId = _state.value.activePlaylistId ?: return
        if (categoryIds.isEmpty()) return
        updateLibrary { it.settingCategoriesHidden(playlistId, categoryIds, hidden) }
    }

    /** A choice about tags answers the "show only your languages?" suggestion too. */
    fun setTagsHidden(tags: Collection<String>, hidden: Boolean) {
        updateLibrary { it.settingTagsHidden(tags, hidden) }
        dismissTagSuggestion()
    }

    /** Hides or shows the categories of a style or word ("manga"), in every playlist. */
    fun setWordHidden(word: String, hidden: Boolean) = updateLibrary { it.settingWordHidden(word, hidden) }

    fun dismissTagSuggestion() {
        val playlistId = _state.value.activePlaylistId ?: return
        updateSettings { it.copy(dismissedTagSuggestions = it.dismissedTagSuggestions + playlistId) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun updateSettings(change: (AppSettings) -> AppSettings) {
        // Before the saved settings are read, saving would put the defaults over them.
        if (!_state.value.isReady) {
            pendingSettingsChanges += change
            bootstrap()
            return
        }
        val settings = change(_state.value.settings)
        _state.update { it.copy(settings = settings) }
        persist { saveSettings(settings) }
    }

    /**
     * Cached catalogue at once, then a refresh when it is a day old or missing. [OndoState.isRestoring] is on while the
     * cached catalogue is read; without one, the download takes over ([OndoState.isLoading]).
     */
    private fun open(playlist: SavedPlaylist) {
        openJob?.cancel()
        _state.update { it.copy(isRestoring = true) }
        loadStoredDetails(playlist)
        openJob = scope.launch {
            val cached = withContext(io) { store.loadContent(playlist.id) }
            // Another playlist was opened while this one was read: its catalogue must not replace that one (and that
            // one's opening ends the restoring).
            if (_state.value.activePlaylistId != playlist.id) return@launch
            if (cached != null) {
                val shown = shown(cached)
                _state.update { it.copy(content = shown, isRestoring = false) }
                loadGuide(playlist, cached)
                loadTrending()
            }
            if (cached == null || now() - cached.fetchedAt > REFRESH_AFTER_MS) load(playlist)
        }
    }

    private fun load(playlist: SavedPlaylist, newGuide: Boolean = false) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            _state.update { it.copy(isLoading = true, isRestoring = false, error = null) }
            loadCatching { withContext(io) { loader().load(playlist.toCorePlaylist()) } }
                .onSuccess { content ->
                    withContext(writer) { runCatching { store.saveContent(playlist.id, content) } }
                    if (_state.value.activePlaylistId == playlist.id) {
                        val shown = shown(content)
                        _state.update { it.copy(content = shown, isLoading = false) }
                        loadGuide(playlist, content, download = newGuide)
                        loadTrending()
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

    /**
     * The guides of the playlist (the user's, then the provider's), indexed off the main thread. The one kept on the
     * device is used while it is recent ([GUIDE_FRESH_MS]) and for the same guide: a launch does not download it again.
     * Otherwise, or when [download] (the viewer refreshed), it is downloaded; the kept one stays the fallback when that
     * fails. Only one guide is in memory at a time (a large one is a good part of a TV's memory).
     */
    private fun loadGuide(playlist: SavedPlaylist, content: PlaylistContent, download: Boolean = false) {
        val core = playlist.toCorePlaylist()
        if (core.epgUrls.isEmpty() && content.providerEpgUrls.isEmpty()) return
        val sources = guideSources(playlist, content)
        // A newer catalogue or another playlist replaces the guide being loaded.
        guideJob?.cancel()
        guideJob = scope.launch {
            suspend fun kept(): StoredGuide? = withContext(io) { store.loadGuide(playlist.id) }?.takeIf { it.sources == sources }
            suspend fun show(data: EpgData) {
                val guide = withContext(Dispatchers.Default) { EpgIndex(data) }
                if (_state.value.activePlaylistId == playlist.id) _state.update { it.copy(guide = guide) }
            }
            if (!download) {
                val stored = kept()
                if (stored != null && now() - stored.data.fetchedAt in 0 until GUIDE_FRESH_MS) {
                    show(stored.data)
                    return@launch
                }
            }
            // Downloading, matching to the catalogue's channels, merging and indexing: all of it off the main thread.
            val data = loadCatching { withContext(Dispatchers.Default) { loader().loadEpg(core, content).data } }.getOrNull()
            if (data != null && data.programs.isNotEmpty()) {
                show(data)
                persist { saveGuide(playlist.id, StoredGuide(sources, data)) }
            } else {
                // Out of reach, or nothing came: the kept guide, however old, rather than none.
                (kept()?.data ?: data)?.let { show(it) }
            }
        }
    }

    /** Which guide a playlist has: its address, the provider's, and the channels it is matched to. */
    private fun guideSources(playlist: SavedPlaylist, content: PlaylistContent): String {
        var channels = 17
        for (channel in content.channels) channels = 31 * channels + (channel.epgId ?: channel.name).hashCode()
        return listOf(playlist.epgUrl.orEmpty(), content.providerEpgUrls.joinToString(" "), "${content.channels.size}:$channels").joinToString("\n")
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
    } catch (error: OutOfMemoryError) {
        // A catalogue or guide too large for this device: an error on screen, not a stop of the app.
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

    /** Takes a "Continue watching" card away: the movie, or the series' episodes in progress. */
    fun removeFromContinueWatching(key: String) {
        val entry = _state.value.library.progress(key) ?: return
        updateLibrary { it.removingFromContinueWatching(entry, now()) }
    }

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

    fun deleteCollection(id: String) = updateLibrary { it.deletingCollection(id, now()) }

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

    /**
     * The kept details while they are recent ([DetailsArchive.MOVIE_MAX_AGE_MS]), else the provider's; the kept ones
     * when the provider cannot be reached.
     */
    suspend fun details(movie: Movie): MediaDetails? {
        storedDetailsJob?.join()
        val kept = _state.value.movieDetails[movie.id]
        if (kept != null && DetailsArchive.isFresh(movieFetchedAt[movie.id], DetailsArchive.MOVIE_MAX_AGE_MS, now())) return kept
        val playlist = _state.value.activePlaylist ?: return kept
        val streamId = movie.xtreamStreamId ?: return kept
        val credentials = playlist.credentials() ?: return kept
        val details = runCatching { withContext(io) { XtreamClient(credentials, http).movieDetails(streamId) } }.getOrNull() ?: return kept
        // Another playlist opened meanwhile: these details are not its own.
        if (_state.value.activePlaylistId != playlist.id) return details
        movieFetchedAt[movie.id] = now()
        _state.update { it.copy(movieDetails = it.movieDetails + (movie.id to details)) }
        scheduleDetailsSave()
        return details
    }

    /**
     * The kept episodes while they are recent ([DetailsArchive.SERIES_MAX_AGE_MS]: a series gains episodes), else the
     * provider's; the kept ones when the provider cannot be reached.
     */
    suspend fun details(series: Series): SeriesDetails {
        storedDetailsJob?.join()
        val kept = _state.value.seriesDetails[series.id]
        val playlist = _state.value.activePlaylist
        val credentials = playlist?.credentials()
        val seriesId = series.xtreamSeriesId
        if (playlist == null || credentials == null || seriesId == null) {
            if (kept != null) return kept
            // Without a provider's server, the episodes come with the playlist: nothing to fetch or to keep on disk.
            val seasons = series.episodes.groupBy { it.season }.toSortedMap().map { (number, episodes) ->
                Season(number, "Season $number", episodes.sortedBy { it.number })
            }
            val details = SeriesDetails(
                info = MediaDetails(title = series.name, plot = series.plot, genre = series.genre, rating = series.rating, posterUrl = series.coverUrl),
                seasons = seasons,
            )
            _state.update { it.copy(seriesDetails = it.seriesDetails + (series.id to details)) }
            return details
        }
        if (kept != null && DetailsArchive.isFresh(seriesFetchedAt[series.id], DetailsArchive.SERIES_MAX_AGE_MS, now())) return kept
        val details = try {
            withContext(io) { XtreamClient(credentials, http).seriesDetails(seriesId) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            kept ?: throw error
        }
        if (_state.value.activePlaylistId != playlist.id) return details
        seriesFetchedAt[series.id] = now()
        _state.update { it.copy(seriesDetails = it.seriesDetails + (series.id to details)) }
        scheduleDetailsSave()
        return details
    }

    // MARK: Details kept between launches

    /** The details in [OndoState] now belong to [playlistId]: a pending save goes to the previous playlist first. */
    private fun startDetails(playlistId: String) {
        saveDetailsNow()
        storedDetailsJob?.cancel()
        storedDetailsJob = null
        detailsPlaylistId = playlistId
        movieFetchedAt.clear()
        seriesFetchedAt.clear()
    }

    /**
     * Reads the playlist's document (`details-<id>.json`) when it opens: Home's backdrops, the pages and their episode
     * thumbnails show at once. [details] waits for it, so Home's prefetch does not ask the provider for what is on disk.
     */
    private fun loadStoredDetails(playlist: SavedPlaylist) {
        if (detailsPlaylistId != playlist.id) {
            startDetails(playlist.id)
            _state.update { it.copy(movieDetails = emptyMap(), seriesDetails = emptyMap()) }
        }
        storedDetailsJob?.cancel()
        storedDetailsJob = scope.launch {
            // On the writer, after the last save: reopening the same playlist reads the details just saved.
            val archive = withContext(writer) { runCatching { store.loadDetails(playlist.id) }.getOrNull() } ?: return@launch
            if (detailsPlaylistId != playlist.id) return@launch
            // A title fetched while the document was read has the newer details.
            val current = _state.value
            archive.movies.forEach { (id, entry) -> if (id !in current.movieDetails) movieFetchedAt[id] = entry.fetchedAt }
            archive.series.forEach { (id, entry) -> if (id !in current.seriesDetails) seriesFetchedAt[id] = entry.fetchedAt }
            _state.update { state ->
                state.copy(
                    movieDetails = archive.movies.mapValues { it.value.value } + state.movieDetails,
                    seriesDetails = archive.series.mapValues { it.value.value } + state.seriesDetails,
                )
            }
        }
    }

    /** Saves a moment after the last fetch: titles fetched together make one write. */
    private fun scheduleDetailsSave() {
        detailsSaveJob?.cancel()
        detailsSaveJob = scope.launch {
            delay(DETAILS_SAVE_DELAY_MS)
            saveDetailsNow()
        }
    }

    /** Writes a pending save at once (another playlist opens). */
    private fun saveDetailsNow() {
        val pending = detailsSaveJob ?: return
        detailsSaveJob = null
        pending.cancel()
        val playlistId = detailsPlaylistId ?: return
        val state = _state.value
        // The playlist was deleted meanwhile: its details go with it.
        if (state.playlists.none { it.id == playlistId }) return
        val archive = DetailsArchive(
            movies = movieFetchedAt.mapNotNull { (id, at) -> state.movieDetails[id]?.let { id to DetailsArchive.Entry(it, at) } }.toMap(),
            series = seriesFetchedAt.mapNotNull { (id, at) -> state.seriesDetails[id]?.let { id to DetailsArchive.Entry(it, at) } }.toMap(),
        )
        persist { saveDetails(playlistId, archive.trimmed()) }
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
        // The episodes of the provider's details are kept in the core's words (see OndoState.placeholderNames).
        val shown = _state.value.placeholderNames.rename(episode)
        val item = PlaybackFactory(playlist.toCorePlaylist()).item(shown, series)
        val entry = WatchEntry(playlist.id, ItemKind.SERIES, series.id, shown.id, series.name, "${shown.code} · ${shown.title}", shown.posterUrl ?: series.coverUrl, lastWatchedAt = now())
        return start(item, entry, fromStart = false)
    }

    /**
     * The episode after the one [request] plays, with its series, for the player's Up next: in the series' seasons
     * (its page, once opened) or its playlist's list; null after the last one, or for a movie or a channel.
     */
    fun nextEpisode(request: PlayRequest): Pair<Series, Episode>? {
        val parts = request.progressKey?.split('|') ?: return null
        if (parts.size != 4 || parts[1] != ItemKind.SERIES.name || parts[3].isEmpty()) return null
        val state = _state.value
        if (parts[0] != state.activePlaylistId) return null
        val series = state.content.series.firstOrNull { it.id == parts[2] } ?: return null
        val episodes = state.seriesDetails[series.id]?.seasons?.sortedBy { it.number }?.flatMap { season -> season.episodes.sortedBy { it.number } }
            ?: series.episodes.sortedWith(compareBy({ it.season }, { it.number }))
        val index = episodes.indexOfFirst { it.id == parts[3] }
        return episodes.getOrNull(index + 1)?.takeIf { index >= 0 }?.let { series to it }
    }

    fun playRequest(channel: Channel): PlayRequest? {
        val playlist = _state.value.activePlaylist ?: return null
        val item = PlaybackFactory(playlist.toCorePlaylist()).item(channel)
        val entry = WatchEntry(playlist.id, ItemKind.CHANNEL, channel.id, null, channel.name, null, channel.logoUrl, lastWatchedAt = now())
        return start(item, entry, fromStart = true).copy(channelId = channel.id)
    }

    /**
     * [channel] from [start] to [end] from the provider's archive (catch-up): null when the channel has no archive or
     * [start] is outside it. It keeps the channel (the channel keys, the Channels list and Back to live work from it)
     * and goes in no history: it is no film to resume.
     */
    private fun replayRequest(channel: Channel, start: Long, end: Long, title: String?, artworkUrl: String?, now: Long): PlayRequest? {
        val state = _state.value
        val playlist = state.activePlaylist ?: return null
        // The panel's time zone: an Xtream archive is asked for in the panel's own time.
        val item = PlaybackFactory(playlist.toCorePlaylist(), state.content.account).catchupItem(channel, start, end, title, artworkUrl, now) ?: return null
        return PlayRequest(
            title = item.title,
            subtitle = item.subtitle,
            urls = item.urls,
            headers = item.headers?.toMap().orEmpty(),
            isLive = false,
            artworkUrl = item.artworkUrl,
            kind = ProblemReport.kind(item),
            liveBuffer = state.settings.liveBuffer,
            vodBuffer = state.settings.vodBuffer,
            channelId = channel.id,
            replayStart = start,
        )
    }

    /** A programme of the guide on [channel], from its start: on now (Restart) or past (Play Replay). */
    fun replayRequest(channel: Channel, programme: EpgProgram, now: Long = now()): PlayRequest? =
        replayRequest(channel, programme.start, programme.end, programme.title, programme.iconUrl, now).takeIf { canReplay(channel, programme, now) }

    /** Whether [programme] of [channel] can be played from its start: it has begun, and is in the channel's archive. */
    fun canReplay(channel: Channel, programme: EpgProgram, now: Long = now()): Boolean = CatchupUrlBuilder.isAvailable(channel, programme.start, now)

    /** The programme on now on [channelId], from its start (Restart); null when the guide does not know it. */
    fun restartRequest(channelId: String, now: Long = now()): PlayRequest? {
        val (channel, programme) = airing(channelId, now) ?: return null
        return replayRequest(channel, programme, now)
    }

    /** Whether the programme on now on [channelId] can start over ([restartRequest]). */
    fun canRestart(channelId: String, now: Long = now()): Boolean = airing(channelId, now)?.let { (channel, programme) -> canReplay(channel, programme, now) } == true

    /**
     * A live channel paused at [pausedAt], from there ([LivePause]): the archive up to [LivePause.WINDOW_MS] later.
     * Null without an archive.
     */
    fun pausedLiveRequest(channelId: String, pausedAt: Long, now: Long = now()): PlayRequest? {
        val channel = _state.value.content.channels.firstOrNull { it.id == channelId } ?: return null
        return replayRequest(channel, pausedAt, pausedAt + LivePause.WINDOW_MS, null, null, now)
    }

    /** The channel of [channelId] with its programme on now, when it has an archive and the guide knows the programme. */
    private fun airing(channelId: String, now: Long): Pair<Channel, EpgProgram>? {
        val state = _state.value
        val channel = state.content.channels.firstOrNull { it.id == channelId }?.takeIf { it.hasCatchup } ?: return null
        val programme = state.guide?.current(channel, now) ?: return null
        return channel to programme
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
            kind = ProblemReport.kind(item),
            liveBuffer = _state.value.settings.liveBuffer,
            vodBuffer = _state.value.settings.vodBuffer,
        )
    }

    private fun loader() = PlaylistLoader(http, parsingDispatcher = parsing, clock = now, downloadFolder = downloadFolder, guideBudgetBytes = guideBudgetBytes)

    private fun SavedPlaylist.credentials(): XtreamCredentials? =
        if (isXtream) XtreamCredentials(XtreamCredentials.normalizeServer(url) ?: url, username.orEmpty(), password.orEmpty()) else null

    private fun SavedPlaylist.toCorePlaylist(): Playlist = Playlist(
        id = id,
        name = name,
        source = credentials()?.let { PlaylistSource.Xtream(it) } ?: PlaylistSource.Remote(url),
        epgUrls = listOfNotNull(epgUrl),
    )

    // MARK: TMDb (the viewer's own token, checked with TMDb when saved; sync carries it, docs/SYNC.md)

    /** When the trending titles were last asked for, and for which playlist. */
    private var trendingLoadedFor: Pair<String, Long>? = null
    private val backdropsSearched = HashSet<String>()

    private fun tmdbClient(): TmdbClient? =
        _state.value.settings.tmdbToken?.takeUnless { _state.value.tmdbRefused }?.let { TmdbClient(it, http, Locale.getDefault().toLanguageTag()) }

    /** Asks TMDb whether it accepts [value], and saves it only then (refused or out of reach: nothing is saved). */
    suspend fun checkAndSaveTmdbToken(value: String): TmdbCheck {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return TmdbCheck.REFUSED
        val accepted = try {
            withContext(io) { TmdbClient(trimmed, http).isAccepted() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return TmdbCheck.UNREACHABLE
        }
        if (!accepted) return TmdbCheck.REFUSED
        updateSettings { settings -> settings.copy(serviceKeys = settings.serviceKeys.filterNot { it.service == SyncKey.TMDB } + SyncKey(SyncKey.TMDB, trimmed, now())) }
        tmdbTokenChanged()
        return TmdbCheck.ACCEPTED
    }

    /** Removes the TMDb token here, and on the other devices with the next sync. */
    fun removeTmdbToken() {
        if (_state.value.settings.serviceKeys.none { it.service == SyncKey.TMDB }) return
        updateSettings { settings -> settings.copy(serviceKeys = settings.serviceKeys.filterNot { it.service == SyncKey.TMDB }) }
        updateLibrary { it.deleting("key:${SyncKey.TMDB}", now()) }
        tmdbTokenChanged()
    }

    private fun tmdbTokenChanged() {
        trendingLoadedFor = null
        backdropsSearched.clear()
        _state.update { it.copy(tmdbRefused = false, trending = emptyList(), tmdbBackdrops = emptyMap()) }
        loadTrending()
    }

    /** TMDb answered 401: the token no longer works. Settings says so, and nothing more is asked with it. */
    private fun noteTmdbFailure(error: Throwable) {
        if ((error as? HttpException.BadStatus)?.code == 401) _state.update { it.copy(tmdbRefused = true, trending = emptyList()) }
    }

    private suspend fun <T> tmdb(block: suspend TmdbClient.() -> T): T? {
        val client = tmdbClient() ?: return null
        return try {
            withContext(io) { client.block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            noteTmdbFailure(e)
            null
        }
    }

    /** TMDb's trending movies and series that the playlist has, asked again after [TRENDING_FRESH_MS]. */
    fun loadTrending(force: Boolean = false) {
        val state = _state.value
        val playlist = state.activePlaylistId ?: return
        if (state.settings.tmdbToken == null || state.tmdbRefused || state.content.movies.isEmpty() && state.content.series.isEmpty()) return
        val loaded = trendingLoadedFor
        if (!force && loaded != null && loaded.first == playlist && now() - loaded.second < TRENDING_FRESH_MS) return
        trendingLoadedFor = playlist to now()
        scope.launch {
            val items = tmdb { trending() }
            if (items == null) {
                trendingLoadedFor = null
                return@launch
            }
            val content = _state.value.content
            val matches = withContext(Dispatchers.Default) { CatalogueMatcher.match(items, content.movies, content.series) }
            if (_state.value.activePlaylistId == playlist) _state.update { it.copy(trending = matches) }
        }
    }

    /**
     * With the viewer's token, looks once a session for TMDb's backdrop of a title whose provider gave none that
     * loads: by the TMDb id the provider gave, else by title and year. Only those are sent.
     */
    fun findTmdbBackdrop(key: String, series: Boolean, tmdbId: String?, title: String, year: Int?) {
        if (_state.value.settings.tmdbToken == null || key in _state.value.tmdbBackdrops || !backdropsSearched.add(key)) return
        scope.launch {
            val found = tmdb { backdropUrl(series, tmdbId, title, year) } ?: return@launch
            _state.update { it.copy(tmdbBackdrops = it.tmdbBackdrops + (key to found)) }
        }
    }

    /** A backdrop that did not load: not asked for again this session, and the title gets TMDb's instead when it can. */
    fun backdropFailed(url: String) {
        if (url !in _state.value.deadBackdrops) _state.update { it.copy(deadBackdrops = it.deadBackdrops + url) }
    }

    /** With the viewer's token, the names of the people TMDb finds for a search (only the words typed are sent). */
    suspend fun tmdbPeople(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.length < 3) return emptyList()
        return tmdb { people(trimmed) }?.map { it.name }?.filter { it.isNotBlank() }?.distinct()?.take(8).orEmpty()
    }

    /** With the viewer's token, the person's movies and series on TMDb that the playlist has; null without a token. */
    suspend fun tmdbWorks(name: String): List<TrendingMatch>? {
        if (_state.value.settings.tmdbToken == null) return null
        val credits = tmdb { person(name)?.let { credits(it.id) } } ?: return null
        val content = _state.value.content
        return withContext(Dispatchers.Default) { CatalogueMatcher.match(credits, content.movies, content.series) }
    }

    companion object {
        const val REFRESH_AFTER_MS = 24 * 60 * 60 * 1000L

        /** A guide kept on the device is used for this long before it is downloaded again (as on Apple devices). */
        const val GUIDE_FRESH_MS = 6 * 60 * 60 * 1000L

        /** TMDb's trending titles are asked for again after this (as on Apple devices). */
        const val TRENDING_FRESH_MS = 6 * 60 * 60 * 1000L
        private const val DETAILS_SAVE_DELAY_MS = 2_000L

        fun hostName(url: String): String = runCatching { java.net.URI(url.trim()).host }.getOrNull()?.removePrefix("www.") ?: "Playlist"
    }
}
