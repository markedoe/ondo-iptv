package com.markedoe.ondo.data

import com.markedoe.ondo.core.TextNormalizer
import com.markedoe.ondo.ui.model.PersonRow
import com.markedoe.ondo.core.catchup.CatchupUrlBuilder
import com.markedoe.ondo.core.epg.EpgIndex
import com.markedoe.ondo.core.library.AwardsCatalogue
import com.markedoe.ondo.core.library.CatalogueMatcher
import com.markedoe.ondo.core.library.CreditRecord
import com.markedoe.ondo.core.library.EpisodeTitles
import com.markedoe.ondo.core.library.Genres
import com.markedoe.ondo.core.library.InMemoryMetadataStore
import com.markedoe.ondo.core.library.MetadataFreshness
import com.markedoe.ondo.core.library.MetadataKey
import com.markedoe.ondo.core.library.MetadataStore
import com.markedoe.ondo.core.library.MetadataTitles
import com.markedoe.ondo.core.library.RelatedTitle
import com.markedoe.ondo.core.library.RowOrder
import com.markedoe.ondo.core.library.Selections
import com.markedoe.ondo.core.library.TitleMetadata
import com.markedoe.ondo.core.library.TmdbGenre
import com.markedoe.ondo.core.library.TmdbItem
import com.markedoe.ondo.core.library.ErrorAttribution
import com.markedoe.ondo.core.library.PlaceholderNames
import com.markedoe.ondo.core.library.PlaybackFactory
import com.markedoe.ondo.core.library.PlaylistLoader
import com.markedoe.ondo.core.library.TmdbClient
import com.markedoe.ondo.core.library.TmdbEpisode
import com.markedoe.ondo.core.library.TmdbRating
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
import com.markedoe.ondo.core.model.Profile
import com.markedoe.ondo.core.model.Season
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.core.model.SeriesDetails
import com.markedoe.ondo.core.model.XtreamCredentials
import com.markedoe.ondo.core.net.HttpClient
import com.markedoe.ondo.core.net.HttpException
import com.markedoe.ondo.core.playback.ProblemReport
import com.markedoe.ondo.core.sync.SentSnapshot
import com.markedoe.ondo.core.sync.SyncRows
import com.markedoe.ondo.core.sync.SyncSelection
import com.markedoe.ondo.core.sync.SyncClock
import com.markedoe.ondo.core.sync.SyncKey
import com.markedoe.ondo.core.sync.SyncMerge
import com.markedoe.ondo.core.sync.SyncProfile
import com.markedoe.ondo.core.sync.SyncProfiles
import com.markedoe.ondo.core.sync.SyncSharing
import com.markedoe.ondo.core.sync.SyncSnapshot
import com.markedoe.ondo.core.xtream.XtreamClient
import com.markedoe.ondo.player.LivePause
import com.markedoe.ondo.player.PlayRequest
import com.markedoe.ondo.player.SubtitleSearchHint
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    /** TMDb's photos of a title's cast and directors, by the title's key, then by the person's name ([TextNormalizer]). */
    val tmdbFaces: Map<String, Map<String, String>> = emptyMap(),
    /** The playlist's titles TMDb recommends with a title, by the title's key, in TMDb's order. */
    val tmdbRelated: Map<String, List<TrendingMatch>> = emptyMap(),
    /** TMDb's trailers (YouTube ids) of titles whose provider gave none, by the title's key. */
    val tmdbTrailers: Map<String, String> = emptyMap(),
    /** TMDb's logos of titles (their title as a picture), by the title's key. */
    val tmdbLogos: Map<String, String> = emptyMap(),
    /**
     * TMDb's rating of a title with its number of votes, by the title's key, from the answers TMDb gave for its
     * backdrop or its cast (the trending titles carry theirs).
     */
    val tmdbRatings: Map<String, TmdbRating> = emptyMap(),
    /**
     * TMDb's episodes of a series' seasons (their stills and names), by the series' key (`s-<id>`), then by season
     * number: the episodes the provider leaves without a picture or a title get TMDb's ([shownDetails]).
     */
    val tmdbSeasons: Map<String, Map<Int, List<TmdbEpisode>>> = emptyMap(),
    /** TMDb refused the saved token when it was used (it was checked elsewhere, or revoked since): Settings says so. */
    val tmdbRefused: Boolean = false,
    /** The viewer profiles (docs/PROFILES.md): the main one first. [library] is the active one's. */
    val profiles: List<Profile> = emptyList(),
    /** "Who's watching?" shows: several profiles and none ticked "Always open this profile". */
    val needsProfileSelection: Boolean = false,
    /** A parent's PIN was given in this Kids profile session: its settings are open until the profile changes. */
    val kidsUnlocked: Boolean = false,
    /**
     * The keys sync carries that are kept outside the settings ([SyncedKeyStore]: the subtitle services'), as the store
     * holds them; not saved with the state.
     */
    val storedKeys: List<SyncKey> = emptyList(),
    /**
     * The collections shared with every profile, and what was deleted among them (docs/PROFILES.md, "Shared lists"): the
     * same for every profile, apart from [library].
     */
    val household: LibraryState = LibraryState(),
) {
    /** The collections the open profile sees: its own and those shared with every profile, by name. */
    val collections: List<UserCollection>
        get() = (library.collections + household.collections).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, UserCollection::name).thenBy { it.id })

    /** Whether collection [id] is shared with every profile. */
    fun isShared(id: String): Boolean = household.collections.any { it.id == id }

    /** Whether the open profile may change the shared collections: any but a Kids profile, which only sees them. */
    val canEditShared: Boolean get() = SyncSharing.canEdit(activeProfile.isKids)

    val activePlaylist: SavedPlaylist? get() = playlists.firstOrNull { it.id == settings.activePlaylistId } ?: playlists.firstOrNull()
    val activePlaylistId: String? get() = activePlaylist?.id

    val activeProfile: Profile
        get() = profiles.firstOrNull { it.id == settings.activeProfileId } ?: profiles.firstOrNull { it.isMain } ?: profiles.firstOrNull()
            ?: Profile(id = Profile.DEFAULT_ID)

    /**
     * Whether what could weaken a Kids profile (Settings, the categories, adding a playlist, the profiles) asks a parent
     * first: a Kids profile, not unlocked, on a device where a profile that is not a Kids one has a PIN (otherwise
     * nobody could ever leave it).
     */
    val needsParentForSettings: Boolean
        get() = activeProfile.isKids && !kidsUnlocked && profiles.any { !it.isKids && it.isProtected }
}

/**
 * [details] of [series] as every screen shows them (its page, the player's Episodes row and Up next): each episode's own
 * title, TMDb's still and name where the provider gives none ([EpisodeTitles.merge], [OndoState.tmdbSeasons]), in the
 * app's words ([OndoState.placeholderNames]).
 */
fun OndoState.shownDetails(series: Series, details: SeriesDetails): SeriesDetails =
    placeholderNames.rename(EpisodeTitles.merge(details, series.name, tmdbSeasons["s-" + series.id].orEmpty()))

/** [episode] of [series] as every screen shows it ([shownDetails]). */
fun OndoState.shownEpisode(series: Series, episode: Episode): Episode {
    val tmdb = tmdbSeasons["s-" + series.id]?.get(episode.season)?.firstOrNull { it.number == episode.number }
    return placeholderNames.rename(EpisodeTitles.merge(episode, series.name, tmdb))
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
    /** What TMDb said about titles, kept between launches (a SQLite file in the app). */
    private val metadata: MetadataStore = InMemoryMetadataStore(),
    /** Where sync merges a snapshot received ([mergeSyncInBackground]), off the main thread. */
    private val syncMerging: CoroutineDispatcher = Dispatchers.Default,
    /** Where the guide is downloaded, read and indexed (tests pass their own dispatcher: no thread outlives a step). */
    private val guideWork: CoroutineDispatcher = Dispatchers.Default,
    /** Where the guide's download is written to a file and deleted (as [guideWork], for tests). */
    private val guideFiles: CoroutineDispatcher = Dispatchers.IO,
    /** The main profile's name when it is made (the first launch with profiles), in the viewer's language. */
    private val mainProfileName: String = "Me",
    /** The keys sync carries kept outside the settings (the subtitle services'); null: none. */
    private val keyStore: SyncedKeyStore? = null,
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

    /** The libraries of the profiles not open, by profile ID, as saved (docs/PROFILES.md). Main thread. */
    private var otherLibraries: Map<String, LibraryState> = emptyMap()

    /** Settings changed before the saved ones were read (the player opened again alone): applied to them once read. */
    private val pendingSettingsChanges = mutableListOf<(AppSettings) -> AppSettings>()

    /**
     * This device's clock for sync (docs/SYNC.md, "Clocks"): every change sync carries is dated by [stamp], later than
     * everything this device dated or received before, whatever its wall clock says.
     */
    private val syncClock = SyncClock()

    /** The time of a change sync carries, made now ([SyncClock.stamp]). */
    private fun stamp(): Long = syncClock.stamp(now())

    /** Now as sync dates it ([SyncClock.now]): to prune by and to note sessions, never earlier than a time it gave. */
    private fun syncNow(): Long = syncClock.now(now())

    init {
        // A key the user saves or removes in the store is dated by this device's clock, its removal travels with the main
        // profile's deletions (as the TMDb token's), and it syncs soon.
        keyStore?.let { store ->
            store.listener = object : SyncedKeyStore.Listener {
                override fun stamp(): Long = syncClock.stamp(now())
                override fun removed(key: String, at: Long) = updateMainLibrary { it.deleting(key, at) }
                override fun changed() = _state.update { it.copy(storedKeys = store.syncKeys()) }
            }
        }
    }

    /** Saves run one at a time, in order: the last change is the one on disk. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val writer = io.limitedParallelism(1)

    /** The catalogue as loaded, with the core's English names (as saved), and its copy in [OndoState.content]. */
    private var loadedContent = PlaylistContent.EMPTY
    private var shownContent = PlaylistContent.EMPTY

    /**
     * [content] as the screens show it, in the words of [OndoState.placeholderNames]. Called on the main thread; the
     * renaming walks the whole catalogue, so it runs off it (the remote keeps answering during a refresh).
     */
    private suspend fun shown(content: PlaylistContent): PlaylistContent {
        var names = _state.value.placeholderNames
        var shown = withContext(parsing) { names.rename(content) }
        // The app's language changed meanwhile ([setPlaceholderNames]): in its words.
        while (names != _state.value.placeholderNames) {
            names = _state.value.placeholderNames
            shown = withContext(parsing) { names.rename(content) }
        }
        loadedContent = content
        shownContent = shown
        forgetCatalogueFindings()
        return shown
    }

    /** The catalogue left the screen: nothing of it is kept (a large one is most of a TV's memory). Main thread. */
    private fun forgetContent() {
        loadedContent = PlaylistContent.EMPTY
        shownContent = PlaylistContent.EMPTY
        forgetCatalogueFindings()
        UiMappers.forgetCatalogue()
    }

    /**
     * What was found in a catalogue and kept for it (its titles' index, a genre's or a person's titles, its posters'
     * facts) goes with it: kept, each catalogue replaced (a refresh, another playlist) stayed in memory. Main thread.
     */
    private fun forgetCatalogueFindings() {
        metadataTitles = null
        posterFacts = null
        genreMatches.clear()
        personMatches.clear()
    }

    /**
     * The app's words for the names the core gives in English, again after the app's language changes (the activity is
     * recreated, not the repository): the catalogue on screen is shown in them. Called on the main thread; the renaming
     * walks the whole catalogue, so it runs off it, as in [shown].
     */
    fun setPlaceholderNames(names: PlaceholderNames) {
        if (names == _state.value.placeholderNames) return
        // At once: a catalogue shown meanwhile ([shown]) is in these words.
        _state.update { it.copy(placeholderNames = names) }
        renameJob?.cancel()
        val loaded = loadedContent
        val before = shownContent
        renameJob = scope.launch {
            val renamed = withContext(parsing) { names.rename(loaded) }
            if (_state.value.placeholderNames != names) return@launch
            // Another catalogue loaded meanwhile is already in these words ([shown]).
            val current = loadedContent === loaded
            if (current) {
                shownContent = renamed
                forgetCatalogueFindings()
            }
            // The episodes of a playlist's own series came with its catalogue, in the former words: they are made again.
            val provided = seriesFetchedAt.keys.toSet()
            _state.update { state ->
                val details = state.seriesDetails.filterKeys { it in provided }
                // A catalogue taken off the screen meanwhile (another playlist opened, one deleted) is not brought back.
                if (current && state.content === before) state.copy(content = renamed, seriesDetails = details) else state.copy(seriesDetails = details)
            }
        }
    }

    /** The renaming of the catalogue in the app's new words ([setPlaceholderNames]). Main thread. */
    private var renameJob: Job? = null

    fun bootstrap() {
        if (_state.value.isReady || bootstrapping) return
        bootstrapping = true
        scope.launch {
            val storedSettings = withContext(io) { store.loadSettings() }
            val playlists = withContext(io) { store.loadPlaylists() }
            // Before profiles, the library was the main profile's: it keeps its document, and the profiles document is
            // written once a profile is added (docs/PROFILES.md, "Migration").
            val storedProfiles = withContext(io) { store.loadProfiles() }
            val unstamped = storedProfiles.ifEmpty { listOf(Profile.main(mainProfileName)) }
            val launch = unstamped.firstOrNull { it.id == storedSettings.launchProfileId }
            val opened = launch ?: unstamped.firstOrNull { it.id == storedSettings.activeProfileId } ?: unstamped.firstOrNull { it.isMain } ?: unstamped.first()
            // "Always open this profile" opens that one; otherwise the one open last (none saved: the main one).
            val savedSettings = if (launch != null) storedSettings.copy(activeProfileId = launch.id) else storedSettings
            val stored = withContext(io) { store.loadLibrary(opened.libraryDocumentName) }
            // Every profile's library travels (docs/SYNC.md, "Profiles"): the ones not open are kept at hand.
            val storedOthers = unstamped.filter { it.id != opened.id }.associate { profile ->
                profile.id to withContext(io) { store.loadLibrary(profile.libraryDocumentName) }
            }
            // The order of the rows was the device's before it was each profile's: it becomes the main profile's, with its
            // time (docs/PROFILES.md, "Migration").
            val movedRows = storedSettings.rowOrders.takeIf { it.isNotEmpty() }
            fun withRows(library: LibraryState) = movedRows?.fold(library) { result, record ->
                val kept = result.rowOrders.firstOrNull { it.tab == record.tab }
                if (kept != null && kept.updatedAt >= record.updatedAt) result else result.keepingRowOrder(record)
            } ?: library
            val saved = if (opened.isMain) withRows(stored) else stored
            otherLibraries = storedOthers.mapValues { (id, library) -> if (id == Profile.DEFAULT_ID) withRows(library) else library }
            val lastCrash = withContext(io) {
                // Downloads left half-read by a stop, and copies of files chosen for a playlist that was not saved.
                downloadFolder?.listFiles()?.forEach { it.delete() }
                store.forgetUnusedImports(playlists)
                store.loadLastCrash()
            }
            // The sync clock starts after every time this device holds: after a restart with its clock back at 1970,
            // a change is still dated after the ones it made or received before.
            syncClock.advance(savedSettings.syncClock)
            val household = withContext(io) { store.loadLibrary(AppStore.HOUSEHOLD) }
            val dated = OndoState(settings = savedSettings, playlists = playlists, library = saved, profiles = unstamped, household = household)
            syncClock.advance(SyncData.latestTime(dated, SyncData.allLibraries(dated, otherLibraries)))
            // The store's keys too; those saved before sync carried them are dated now, once.
            keyStore?.let { keys ->
                syncClock.advance(keys.syncKeys().maxOfOrNull { it.updatedAt })
                keys.dateUndated(::stamp)
            }
            // Keys of the store an earlier version kept among those it only passed on (in the settings file): moved to
            // the store once (the later one kept), and gone from the settings.
            val relayedKeys = keyStore?.let { keys -> savedSettings.serviceKeys.filter { it.service in keys.services } }.orEmpty()
            if (relayedKeys.isNotEmpty()) keyStore?.applySynced(relayedKeys, emptyMap())
            // The categories, languages and styles hidden before they synced get a time now, after everything this device
            // holds: two devices' hidden sets add up at their first session (docs/PROFILES.md, "Migration").
            val undatedOthers = otherLibraries.filterValues { it.hasUndatedHidden() }
            val hiddenTime = if (saved.hasUndatedHidden() || undatedOthers.isNotEmpty()) stamp() else 0L
            val opening = if (saved.hasUndatedHidden()) saved.datingHidden(hiddenTime) else saved
            val datedOthers = undatedOthers.mapValues { it.value.datingHidden(hiddenTime) }
            otherLibraries = otherLibraries + datedOthers
            // A profile made before profiles synced has no time: it gets one now, so that it travels.
            val profiles = unstamped.map { if (!it.isMain && it.updatedAt == 0L) it.copy(updatedAt = stamp()) else it }
            val pending = pendingLibraryChanges.toList()
            pendingLibraryChanges.clear()
            val library = pending.fold(opening) { library, change -> change(library) }
            val pendingSettings = pendingSettingsChanges.toList()
            pendingSettingsChanges.clear()
            val base = savedSettings.let { if (relayedKeys.isEmpty()) it else it.copy(serviceKeys = it.serviceKeys - relayedKeys.toSet()) }
            val settings = pendingSettings.fold(if (movedRows != null) base.copy(rowOrders = emptyList()) else base) { settings, change -> change(settings) }
            // With a playlist, its saved catalogue is read next: restoring from the same update (no "empty" moment).
            _state.update {
                it.copy(
                    settings = settings, playlists = playlists, library = library, lastCrash = lastCrash, isReady = true, isRestoring = playlists.isNotEmpty(),
                    profiles = profiles, needsProfileSelection = profiles.size > 1 && launch == null, household = household,
                    storedKeys = keyStore?.syncKeys().orEmpty(),
                )
            }
            if (pending.isNotEmpty() || (movedRows != null && opened.isMain) || opening !== saved) opened.libraryDocumentName.let { document -> persist { saveLibrary(library, document) } }
            datedOthers.forEach { (id, dated) -> unstamped.firstOrNull { it.id == id }?.let { profile -> persist { saveLibrary(dated, profile.libraryDocumentName) } } }
            if (movedRows != null && !opened.isMain) otherLibraries[Profile.DEFAULT_ID]?.let { main -> persist { saveLibrary(main, AppStore.MAIN_LIBRARY) } }
            if (profiles != unstamped) persist { saveProfiles(profiles) }
            if (pendingSettings.isNotEmpty() || movedRows != null || relayedKeys.isNotEmpty()) persist { saveSettings(settings) }
            _state.value.activePlaylist?.let { open(it) }
            awardsSource?.let { source ->
                val awards = withContext(io) { runCatching { source()?.let(AwardsCatalogue::parse) }.getOrNull() }
                if (awards != null) _state.update { it.copy(awards = awards) }
            }
            awardsDownload?.let { updateAwards(it) }
            loadDeadImages()
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

    fun setPrepareAhead(on: Boolean) = updateSettings { it.copy(prepareAhead = on) }

    /** Saves in order, off the main thread; the job ends once saved. A failed save (disk full) keeps the app running. */
    private fun persist(save: AppStore.() -> Unit): Job = scope.launch(writer) { runCatching { store.save() } }

    /** Home as last drawn for [playlistId] ([HomeSnapshot]), read off the main thread; null without one. */
    suspend fun homeSnapshot(playlistId: String): HomeSnapshot? = withContext(io) { runCatching { store.loadHomeSnapshot(playlistId) }.getOrNull() }

    /** Keeps [snapshot] for the next start (written in the background, after the saves before it). */
    fun keepHomeSnapshot(snapshot: HomeSnapshot) {
        persist {
            // Not for a playlist or a profile deleted meanwhile (their deletion took their snapshots away).
            val current = _state.value
            val profileKept = current.profiles.isEmpty() || current.profiles.any { it.id == snapshot.profileId }
            if (profileKept && current.playlists.any { it.id == snapshot.playlistId }) saveHomeSnapshot(snapshot)
        }
    }

    // MARK: Playlists

    /** Loads the playlist once to check it, then saves and activates it. */
    suspend fun addPlaylist(name: String, url: String, username: String? = null, password: String? = null, epgUrl: String? = null): Result<Unit> {
        val time = stamp()
        val saved = SavedPlaylist(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { hostName(url) },
            url = url.trim(),
            username = username?.trim(),
            password = password,
            epgUrl = epgUrl?.trim()?.ifBlank { null },
            addedAt = time,
            updatedAt = time,
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
        // Every profile's reminders of the playlist go with it.
        (otherLibraries.values + _state.value.library).flatMap { it.reminders }.filter { it.playlistId == id }.forEach(reminderScheduler::cancel)
        val time = stamp()
        val library = _state.value.library.removingPlaylist(id, time)
        val settings = _state.value.settings.let { if (it.activePlaylistId == id) it.copy(activePlaylistId = playlists.firstOrNull()?.id) else it }
        val document = _state.value.activeProfile.libraryDocumentName
        // Every profile's entries of the playlist go with it.
        val others = otherLibraries.mapValues { (_, other) -> other.removingPlaylist(id, time) }
        otherLibraries = others
        // And those of the shared collections (whose own deletions are of collections and their items only).
        val household = _state.value.household.removingPlaylist(id, time).let { it.copy(syncDeleted = it.syncDeleted - SyncKeys.playlist(id)) }
        val documents = _state.value.profiles.filter { it.id in others }.associate { it.libraryDocumentName to others.getValue(it.id) }
        persist {
            savePlaylists(playlists, removed)
            saveLibrary(library, document)
            documents.forEach { (other, saved) -> saveLibrary(saved, other) }
            saveLibrary(household, AppStore.HOUSEHOLD)
            saveSettings(settings)
            deleteContent(id)
            deleteDetails(id)
            deleteGuide(id)
            deleteHomeSnapshot(id)
        }
        val wasActive = _state.value.activePlaylistId == id
        _state.update { it.copy(playlists = playlists, library = library, settings = settings, household = household) }
        if (wasActive) {
            openJob?.cancel()
            refreshJob?.cancel()
            guideJob?.cancel()
            forgetContent()
            _state.update { it.copy(content = PlaylistContent.EMPTY, guide = null, isLoading = false, isRestoring = it.playlists.isNotEmpty()) }
            _state.value.activePlaylist?.let { open(it) }
        }
    }


    fun setShowAdultCategories(show: Boolean) = updateSettings { it.copy(showAdultCategories = show) }

    // MARK: Profiles (docs/PROFILES.md)

    /** What a PIN given gets: the profile opens, the PIN was wrong, or too many wrong ones make the next try wait. */
    enum class PinCheck { OK, WRONG, WAIT }

    /**
     * Opens profile [id]: what was changed in the profile left is saved to its own library, then the other's is read
     * (after any save of it still pending). The PIN, if any, was checked before ([checkPin]). Main thread.
     */
    fun switchProfile(id: String) {
        val state = _state.value
        val profile = state.profiles.firstOrNull { it.id == id } ?: return
        if (!state.isReady) return
        if (profile.id == state.activeProfile.id) {
            _state.update { it.copy(needsProfileSelection = false) }
            return
        }
        // The profile left joins the others kept at hand (its pending saves go to its own document); the other's comes out.
        val library = otherLibraries[profile.id] ?: LibraryState()
        otherLibraries = otherLibraries - profile.id + (state.activeProfile.id to state.library)
        val settings = state.settings.copy(activeProfileId = profile.id)
        _state.update { it.copy(library = library, settings = settings, needsProfileSelection = false, kidsUnlocked = false) }
        persist { saveSettings(settings) }
    }

    /** Checks [pin] against [profile]'s, waiting after too many wrong ones in a row (kept across launches). */
    fun checkPin(profile: Profile, pin: String): PinCheck {
        val hash = profile.pinHash ?: return PinCheck.OK
        return checkPinHashes(listOf(hash), pin)
    }

    /** A parent's PIN (of a profile that is not a Kids one) opens the Kids profile's settings until the profile changes. */
    fun unlockKidsSettings(pin: String): PinCheck {
        val hashes = _state.value.profiles.filter { !it.isKids }.mapNotNull { it.pinHash }
        if (hashes.isEmpty()) return PinCheck.OK.also { _state.update { it.copy(kidsUnlocked = true) } }
        return checkPinHashes(hashes, pin).also { result -> if (result == PinCheck.OK) _state.update { it.copy(kidsUnlocked = true) } }
    }

    /** When the next PIN may be tried, if wrong ones made it wait; null: now. */
    fun pinRetryAt(): Long? = _state.value.settings.pinRetryAt.takeIf { it > now() }

    private fun checkPinHashes(hashes: List<String>, pin: String): PinCheck {
        if (pinRetryAt() != null) return PinCheck.WAIT
        if (Profile.hashPin(pin) in hashes) {
            if (_state.value.settings.pinFailures != 0 || _state.value.settings.pinRetryAt != 0L) updateSettings { it.copy(pinFailures = 0, pinRetryAt = 0) }
            return PinCheck.OK
        }
        val failures = _state.value.settings.pinFailures + 1
        val retryAt = Profile.pinDelayMs(failures)?.let { now() + it } ?: 0L
        updateSettings { it.copy(pinFailures = failures, pinRetryAt = retryAt) }
        return PinCheck.WRONG
    }

    /** A new profile, with its own empty library; [pin] already checked ([Profile.isValidPin]) or null. */
    fun addProfile(name: String, colorIndex: Int, symbol: String, isKids: Boolean, pin: String?): Profile? {
        // No more than sync carries: a 17th profile would never reach the other devices.
        if (!_state.value.isReady || name.isBlank() || _state.value.profiles.size >= Profile.MAX_PROFILES) return null
        val profile = Profile(name = name.trim(), colorIndex = colorIndex, symbol = symbol, isKids = isKids, pinHash = pin?.let(Profile::hashPin), updatedAt = stamp())
        otherLibraries = otherLibraries + (profile.id to LibraryState())
        keepProfiles(_state.value.profiles + profile)
        return profile
    }

    /** Changes a profile: [pin] sets a new PIN, [removePin] takes it away, neither keeps it. */
    fun updateProfile(id: String, name: String, colorIndex: Int, symbol: String, isKids: Boolean, pin: String?, removePin: Boolean) {
        val profiles = _state.value.profiles
        val profile = profiles.firstOrNull { it.id == id } ?: return
        if (name.isBlank()) return
        val pinHash = when {
            removePin -> null
            pin != null -> Profile.hashPin(pin)
            else -> profile.pinHash
        }
        val changed = profile.copy(name = name.trim(), colorIndex = colorIndex, symbol = symbol, isKids = isKids, pinHash = pinHash)
        if (changed == profile) return
        // A new PIN alone is no change for sync: it stays on this device.
        val dated = if (changed.copy(pinHash = profile.pinHash) == profile) changed else changed.copy(updatedAt = stamp())
        keepProfiles(profiles.map { if (it.id == id) dated else it })
    }

    /**
     * Deletes a profile and its library. The main profile is never deleted (its data predates profiles, and an older
     * device is matched with it), nor the last one. The profile in use gives way to the main one first.
     */
    fun deleteProfile(id: String) {
        val state = _state.value
        val profile = state.profiles.firstOrNull { it.id == id } ?: return
        if (profile.isMain || state.profiles.size <= 1) return
        if (state.settings.launchProfileId == id) updateSettings { it.copy(launchProfileId = null) }
        // Its reminders go with it.
        (if (state.activeProfile.id == id) state.library else otherLibraries[id])?.reminders?.forEach(reminderScheduler::cancel)
        if (state.activeProfile.id == id) switchProfile(Profile.DEFAULT_ID)
        // Its deletion travels, dated (docs/SYNC.md, "Profiles"): it is kept with the main profile's deletions.
        val time = stamp()
        updateMainLibrary { it.deleting(SyncProfile.KEY_PREFIX + id.lowercase(), time) }
        otherLibraries = otherLibraries - id
        val profiles = _state.value.profiles.filterNot { it.id == id }
        _state.update { it.copy(profiles = profiles) }
        // After any save of its library still pending: the file does not come back.
        persist {
            saveProfiles(profiles)
            deleteLibrary(profile.libraryDocumentName)
            // Home's snapshots may show its lists: made again from the profiles left.
            deleteHomeSnapshots()
        }
    }

    /**
     * The first time this device receives profiles (docs/PROFILES.md, "Sync"): a profile of its own made apart, before
     * profiles synced, is one with a received profile of the same name (whatever its case and accents) and the same Kids
     * switch. The smaller ID is kept on both devices, the other is deleted (dated), and its data goes into the one kept:
     * a received one into this device's profile, or this device's profile renamed to the received ID. Once only.
     * Returns [incoming] as it is merged then. Main thread.
     */
    private fun matchingProfilesOnce(incoming: SyncSnapshot): SyncSnapshot {
        val state = _state.value
        if (!state.isReady || state.settings.profilesMatched || incoming.profiles.isEmpty()) return incoming
        updateSettings { it.copy(profilesMatched = true) }
        val local = state.profiles.filter { !it.isMain }.map { SyncProfile(it.id.lowercase(), it.name, it.colorIndex, it.symbol, it.isKids, it.updatedAt) }
        val localIds = local.mapTo(HashSet()) { it.id }
        var taken = incoming
        for ((dropped, kept) in SyncProfiles.matches(local, incoming.profiles)) {
            val time = stamp()
            updateMainLibrary { it.deleting(SyncProfile.KEY_PREFIX + dropped, time) }
            if (kept in localIds) {
                taken = taken.copy(
                    profiles = taken.profiles.filterNot { it.id == dropped },
                    profileData = taken.profileData.map { if (it.profile == dropped) it.copy(profile = kept) else it },
                )
            } else {
                renameProfile(dropped, kept)
            }
        }
        return taken
    }

    /** This device's profile [from] (any case) under the ID [to], with its library, PIN and the settings naming it. */
    private fun renameProfile(from: String, to: String) {
        val state = _state.value
        val profile = state.profiles.firstOrNull { it.id.equals(from, ignoreCase = true) } ?: return
        val renamed = profile.copy(id = to)
        val wasActive = state.activeProfile.id == profile.id
        val library = if (wasActive) state.library else otherLibraries[profile.id] ?: LibraryState()
        if (!wasActive) otherLibraries = otherLibraries - profile.id + (to to library)
        if (playbackProfileId == profile.id) playbackProfileId = to
        val profiles = state.profiles.map { if (it.id == profile.id) renamed else it }
        val settings = state.settings.let { current ->
            current.copy(
                activeProfileId = if (current.activeProfileId == profile.id) to else current.activeProfileId,
                launchProfileId = if (current.launchProfileId == profile.id) to else current.launchProfileId,
            )
        }
        _state.update { it.copy(profiles = profiles, settings = settings) }
        // After any save of the former document still pending: its data is under the new one.
        persist {
            saveLibrary(library, renamed.libraryDocumentName)
            deleteLibrary(profile.libraryDocumentName)
            saveProfiles(profiles)
            saveSettings(settings)
        }
    }

    /** Changes the main profile's library, whichever profile is open. */
    private fun updateMainLibrary(change: (LibraryState) -> LibraryState) {
        if (_state.value.activeProfile.isMain) return updateLibrary(change)
        val main = change(otherLibraries[Profile.DEFAULT_ID] ?: LibraryState())
        otherLibraries = otherLibraries + (Profile.DEFAULT_ID to main)
        persist { saveLibrary(main, AppStore.MAIN_LIBRARY) }
    }

    /** Ticks or unticks "Always open this profile" for [id] (null: ask who is watching at launch). */
    fun setLaunchProfile(id: String?) = updateSettings { it.copy(launchProfileId = id?.takeIf { profileId -> _state.value.profiles.any { it.id == profileId } }) }

    private fun keepProfiles(profiles: List<Profile>) {
        _state.update { it.copy(profiles = profiles) }
        persist { saveProfiles(profiles) }
    }

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
        return SyncData.snapshot(_state.value, syncDeviceId(), deviceName, syncNow(), SyncData.allLibraries(_state.value, otherLibraries))
    }

    /**
     * What this device takes of [incoming] (docs/SYNC.md, "Clocks"): without what is dated more than a day after its
     * clock; the rest goes into its sync clock, so that a change made here next is dated after it. No time is moved.
     */
    private fun received(incoming: SyncSnapshot): SyncSnapshot = syncClock.receive(incoming, now())

    /**
     * Merges [incoming] with this device's data as it is now, keeps the result and returns it as the answer for its
     * sender. Main thread, all of it: for tests and screens that merge at once; sync merges with
     * [mergeSyncInBackground].
     */
    fun mergeSync(incoming: SyncSnapshot, deviceName: String?): SyncSnapshot? {
        if (!_state.value.isReady) return null
        val taken = matchingProfilesOnce(received(incoming))
        val local = syncSnapshot(deviceName) ?: return null
        val merged = SyncMerge.merge(local, taken)
        commitSync(SyncData.apply(_state.value, merged, syncNow(), SyncData.allLibraries(_state.value, otherLibraries)))
        return merged
    }

    /** This device's snapshot, built on [syncMerging] from the data as it is now (returned with it); null until loaded. */
    private suspend fun syncSnapshotInBackground(deviceName: String?): Pair<OndoState, SyncSnapshot>? =
        syncBaseInBackground(deviceName)?.let { (base, _, snapshot) -> base to snapshot }

    /** As [syncSnapshotInBackground], with every profile's library it was made from (docs/SYNC.md, "Profiles"). */
    private suspend fun syncBaseInBackground(deviceName: String?): Triple<OndoState, Map<String, LibraryState>, SyncSnapshot>? {
        if (!_state.value.isReady) return null
        val id = syncDeviceId()
        val base = _state.value
        val libraries = SyncData.allLibraries(base, otherLibraries)
        val time = syncNow()
        return Triple(base, libraries, withContext(syncMerging) { SyncData.snapshot(base, id, deviceName, time, libraries) })
    }

    /** The progress left out of digests: the item playing, saved every few seconds (docs/SYNC.md, "Transport"). */
    private fun digestLeavingOut(state: OndoState): Set<String> =
        playingKey?.let { state.library.progress(it) }?.let { setOf(SyncKeys.progress(it)) }.orEmpty()

    /** This device's digest for [state] ([SyncSnapshot.contentDigest]), worked out on [syncMerging]. */
    private suspend fun syncDigest(state: OndoState, deviceName: String?): String {
        val id = syncDeviceId()
        val leavingOut = digestLeavingOut(state)
        val time = syncNow()
        val libraries = SyncData.allLibraries(state, otherLibraries)
        return withContext(syncMerging) { SyncData.snapshot(state, id, deviceName, time, libraries).contentDigest(leavingOut) }
    }

    /** What [syncOffer] gives: the snapshot to send, its size in bytes, and what to keep as sent once the server has it. */
    class SyncOffer(val snapshot: SyncSnapshot, val size: Int, val sent: SentSnapshot?)

    /**
     * What a client sends a server ([SyncSnapshot.offer]) after it last sent it [sent] in full: everything is worked out
     * on [syncMerging], off the main thread. Main thread.
     */
    suspend fun syncOffer(deviceName: String?, sent: SentSnapshot?, answerDigest: String?, full: Boolean): SyncOffer? {
        val (state, own) = syncSnapshotInBackground(deviceName) ?: return null
        val leavingOut = digestLeavingOut(state)
        return withContext(syncMerging) {
            val offer = own.offer(sent, answerDigest, full, leavingOut)
            SyncOffer(offer.snapshot, if (offer.snapshot.isProbe) 0 else offer.snapshot.encoded().size, offer.sent)
        }
    }

    /**
     * Merges [incoming] on [syncMerging], off the main thread, and keeps the result only if nothing it read changed
     * meanwhile (the playlists, the library, the settings); else merges again from the data as it is then. After
     * [MERGE_TRIES] changes in a row it gives up (null): the next session merges. Returns the answer for the sender
     * and the data as kept. Main thread.
     */
    private suspend fun mergeInBackground(incoming: SyncSnapshot, deviceName: String?): Pair<SyncSnapshot, OndoState>? {
        if (!_state.value.isReady) return null
        val taken = matchingProfilesOnce(received(incoming))
        repeat(MERGE_TRIES) {
            val baseOthers = otherLibraries
            val (base, libraries, local) = syncBaseInBackground(deviceName) ?: return null
            val time = syncNow()
            val (merged, applied) = withContext(syncMerging) {
                val result = SyncMerge.merge(local, taken)
                result to SyncData.apply(base, result, time, libraries)
            }
            val current = _state.value
            if (current.playlists === base.playlists && current.library === base.library && current.settings === base.settings &&
                current.profiles === base.profiles && current.household === base.household && otherLibraries === baseOthers
            ) {
                commitSync(applied)
                return merged to _state.value
            }
        }
        return null
    }

    /** [mergeInBackground], for its answer: what a client keeps, what a server answers. Main thread. */
    suspend fun mergeSyncInBackground(incoming: SyncSnapshot, deviceName: String?): SyncSnapshot? =
        mergeInBackground(incoming, deviceName)?.first

    /**
     * What a server answers [incoming] (docs/SYNC.md, "Transport"): to a probe, `unchanged` when this device's data has
     * not changed since the client's last session ([SyncSnapshot.seen]), else its own snapshot; to a snapshot, the
     * merge ([mergeSyncInBackground]). Answers carry this device's digest, of the data the answer comes from. Main
     * thread; the work is done on [syncMerging].
     */
    suspend fun answerSync(incoming: SyncSnapshot, deviceName: String?): SyncSnapshot? {
        if (incoming.isProbe) {
            val (state, own) = syncSnapshotInBackground(deviceName) ?: return null
            val leavingOut = digestLeavingOut(state)
            val digest = withContext(syncMerging) { own.contentDigest(leavingOut) }
            return if (incoming.seen == digest) {
                SyncSnapshot(device = own.device, deviceName = own.deviceName, digest = digest, unchanged = true)
            } else {
                own.copy(digest = digest)
            }
        }
        val (answer, kept) = mergeInBackground(incoming, deviceName) ?: return null
        return answer.copy(digest = syncDigest(kept, deviceName))
    }

    /** Whether the player plays something (a channel too): sync then reloads no catalogue until it stops. */
    private var inPlayback = false

    /** What the player plays now ([WatchEntry.key]): sync leaves its entry alone until playback ends. Main thread. */
    private var playingKey: String? = null

    /** Whether something plays: sync then puts off its regular sessions that would send everything. */
    val isPlaying: Boolean get() = inPlayback

    /** Where other devices left the item playing here, received during playback: applied once it ends. */
    private val parkedProgress = HashMap<String, WatchEntry>()

    /**
     * The playlist whose catalogue is shown, when sync changed the playlist in use (or its address) during playback:
     * the catalogue follows once playback ends ([playbackEnded]).
     */
    private var contentBeforeSync: SavedPlaylist? = null
    private var contentFollowsSync = false

    /** The player started [key] (null: something not resumable, a channel). Main thread. */
    fun playbackStarted(key: String?) {
        if (inPlayback && key == playingKey) return
        applyParked()
        inPlayback = true
        playingKey = key
    }

    /**
     * The player stopped: what another device sent meanwhile for its item applies now, if it is the later, and a
     * catalogue sync changed meanwhile follows. Main thread.
     */
    fun playbackEnded() {
        if (!inPlayback) return
        applyParked()
        inPlayback = false
        playingKey = null
        if (contentFollowsSync) {
            contentFollowsSync = false
            followSyncedPlaylist(contentBeforeSync)
            contentBeforeSync = null
        }
    }

    private fun applyParked() {
        val key = playingKey ?: return
        val parked = parkedProgress.remove(key) ?: return
        // In the profile the item plays in (review S5), whichever is open now.
        updatePlaybackLibrary { current ->
            val stored = current.progress(key)
            val removedAt = current.syncDeleted[SyncKeys.progress(parked)]
            if ((stored != null && stored.lastWatchedAt >= parked.lastWatchedAt) || (removedAt != null && removedAt >= parked.lastWatchedAt)) current
            else current.copy(history = (current.history.filterNot { it.key == key } + parked).sortedByDescending { it.lastWatchedAt }.take(LibraryState.MAX_HISTORY))
        }
    }

    /**
     * The profile the item playing (or played last) was started in: its progress goes there, whichever profile is open
     * when it is saved (a switch, a deletion by sync while it plays). Null: the open one. Main thread.
     */
    private var playbackProfileId: String? = null

    /**
     * Changes the library of the profile playback belongs to ([playbackProfileId]): the open one's, another's kept at
     * hand, or nothing when that profile was deleted meanwhile (never the main one's in its place).
     */
    private fun updatePlaybackLibrary(change: (LibraryState) -> LibraryState) {
        val state = _state.value
        val id = playbackProfileId
        if (id == null || !state.isReady || id == state.activeProfile.id) return updateLibrary(change)
        val profile = state.profiles.firstOrNull { it.id == id } ?: return
        val library = change(otherLibraries[id] ?: LibraryState())
        otherLibraries = otherLibraries + (id to library)
        persist { saveLibrary(library, profile.libraryDocumentName) }
    }

    /**
     * [applied] with the entry of the item playing here as it is in [local]: the one received is parked until playback
     * ends, so that sync never moves what the player shows.
     */
    private fun keepingPlayback(local: LibraryState, applied: LibraryState): LibraryState {
        val key = playingKey ?: return applied
        val mine = local.progress(key)
        val received = applied.progress(key)
        if (received == mine) return applied
        if (received != null) parkedProgress[key] = received
        val history = applied.history.filterNot { it.key == key } + listOfNotNull(mine)
        return applied.copy(history = history.sortedByDescending { it.lastWatchedAt }.take(LibraryState.MAX_HISTORY))
    }

    /**
     * Remembers a session with [device] (to the day: the library is not written at each session), and forgets the
     * devices not seen for a year: deletions are kept until each device remembered has had them. A client calls it
     * once it has merged a server's full answer; a server once its answer to a full snapshot was delivered. Main thread.
     */
    fun noteSyncSession(device: String) {
        val time = syncNow()
        // Kept with the main profile's sync data, whichever profile is open.
        val peers = (if (_state.value.activeProfile.isMain) _state.value.library else otherLibraries[Profile.DEFAULT_ID] ?: LibraryState()).syncPeers
        if (time - (peers[device.lowercase()] ?: Long.MIN_VALUE / 2) < DAY_MS) return
        val forgotten = time - SyncSnapshot.PEERS_KEPT_DAYS * DAY_MS
        updateMainLibrary { library -> library.copy(syncPeers = (library.syncPeers + (device.lowercase() to time)).filterValues { it >= forgotten }) }
    }

    /** What [commitSync] last kept, as [SyncData.changes] sees it: not a change of this device's own. */
    @Volatile private var committedBySync: Any? = null

    /**
     * The changes to what sync carries made on this device (not those a merge brought): each one is worth a session
     * soon. A merge kept here travels on with the next regular session.
     */
    val localSyncChanges: Flow<Any> get() = state.map(SyncData::changes).distinctUntilChanged().drop(1).filter { it != committedBySync }

    /** Keeps what applying a merged snapshot gave ([SyncData.apply] on the data as it is now). Main thread. */
    private fun commitSync(applied: SyncData.Applied) {
        val state = _state.value
        // The open profile may have been deleted on another device: the main one opens.
        val activeRemoved = applied.removedProfiles.any { it.id == state.activeProfile.id }
        val activeId = if (activeRemoved) Profile.DEFAULT_ID else state.activeProfile.id
        val appliedActive = applied.library(activeId) ?: state.library
        // The item playing keeps its entry in the profile it plays in: what was received for it waits until playback ends.
        val playbackId = playbackProfileId ?: state.activeProfile.id
        val library = if (activeRemoved || playbackId != activeId) appliedActive else keepingPlayback(state.library, appliedActive)
        // A profile the merge left as it was (not in it) keeps its library; one deleted elsewhere goes.
        val removedIds = applied.removedProfiles.mapTo(HashSet()) { it.id }
        val others = (otherLibraries.filterKeys { it !in removedIds } + applied.libraries).filterKeys { it != activeId }.mapValues { (id, other) ->
            if (id == playbackId) keepingPlayback(otherLibraries[id] ?: other, other) else other
        }
        // The keys the store keeps go to it (and only there), the others to the settings.
        keyStore?.applySynced(applied.keys, applied.library.syncDeleted)
        val storedKeys = keyStore?.syncKeys() ?: state.storedKeys
        val serviceKeys = if (keyStore == null) applied.keys else applied.keys.filter { it.service !in keyStore.services }
        val keysChanged = serviceKeys != state.settings.serviceKeys || storedKeys != state.storedKeys
        val householdChanged = applied.household != state.household
        if (applied.playlists == state.playlists && library == state.library && !keysChanged && applied.profiles == state.profiles &&
            others.all { (id, other) -> otherLibraries[id] == other } && !activeRemoved && !householdChanged
        ) return
        val removedPlaylistIds = applied.removed.mapTo(HashSet()) { it.id }
        // Every profile's reminders of a removed playlist, and all those of a profile deleted elsewhere, go.
        (otherLibraries.values + state.library).flatMap { it.reminders }.filter { it.playlistId in removedPlaylistIds }.forEach(reminderScheduler::cancel)
        applied.removedProfiles.forEach { removed ->
            (if (removed.id == state.activeProfile.id) state.library else otherLibraries[removed.id])?.reminders?.forEach(reminderScheduler::cancel)
        }
        // The playlist in use stays unless it was deleted, or becomes the same one from another device that replaced it;
        // a device without one takes the first that arrived.
        val active = state.settings.activePlaylistId?.takeIf { id -> applied.playlists.any { it.id == id } }
            ?: state.activePlaylistId?.let { applied.replacements[it] }
            ?: applied.playlists.firstOrNull()?.id
        val settings = state.settings.copy(
            activePlaylistId = active, serviceKeys = serviceKeys, syncClock = syncClock.last,
            activeProfileId = if (activeRemoved) Profile.DEFAULT_ID else state.settings.activeProfileId,
            launchProfileId = state.settings.launchProfileId?.takeIf { id -> applied.profiles.any { it.id == id } },
        )
        val before = state.activePlaylist
        val profilesById = applied.profiles.associateBy { it.id }
        val document = (profilesById[activeId] ?: Profile(id = Profile.DEFAULT_ID)).libraryDocumentName
        // Only the libraries that changed are written.
        val changedOthers = others.filter { (id, other) -> otherLibraries[id] != other }.mapNotNull { (id, other) ->
            profilesById[id]?.let { it.libraryDocumentName to other }
        }
        val profilesChanged = applied.profiles != state.profiles
        otherLibraries = others
        val saved = persist {
            if (applied.removed.isEmpty()) savePlaylists(applied.playlists) else applied.removed.forEach { savePlaylists(applied.playlists, it) }
            saveLibrary(library, document)
            changedOthers.forEach { (name, other) -> saveLibrary(other, name) }
            if (profilesChanged) saveProfiles(applied.profiles)
            if (householdChanged) saveLibrary(applied.household, AppStore.HOUSEHOLD)
            applied.removedProfiles.forEach { deleteLibrary(it.libraryDocumentName) }
            saveSettings(settings)
            applied.removed.forEach {
                // The same account under another ID keeps its catalogue, guide and details: nothing is downloaded again.
                val into = applied.replacements[it.id]
                if (into != null) {
                    movePlaylistData(it.id, into)
                } else {
                    deleteContent(it.id)
                    deleteDetails(it.id)
                    deleteGuide(it.id)
                }
            }
        }
        _state.update { current ->
            current.copy(
                playlists = applied.playlists, library = library, settings = settings, profiles = applied.profiles,
                kidsUnlocked = current.kidsUnlocked && !activeRemoved, household = applied.household, storedKeys = storedKeys,
            ).also { committedBySync = SyncData.changes(it) }
        }
        fun follow() {
            if (inPlayback) {
                // Nothing is reloaded under the player: the catalogue follows once playback ends.
                if (!contentFollowsSync) contentBeforeSync = before
                contentFollowsSync = true
            } else {
                followSyncedPlaylist(before)
            }
        }
        if (applied.replacements.isEmpty()) {
            follow()
        } else {
            // The catalogue of a replaced playlist is moved to the one that replaced it: read from there once moved.
            scope.launch {
                saved.join()
                follow()
            }
        }
        // A TMDb token saved, changed or removed on another device: used from now on, as if typed here.
        if (state.settings.tmdbToken != settings.tmdbToken) tmdbTokenChanged()
    }

    /** The catalogue shown follows the playlist in use after a sync, [before] being the one it shows. Main thread. */
    private fun followSyncedPlaylist(before: SavedPlaylist?) {
        val after = _state.value.activePlaylist
        when {
            after == null -> {
                if (before == null) return
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
    }

    fun setHideWatchedMovies(hide: Boolean) = updateSettings { it.copy(hideWatchedMovies = hide) }

    fun setGroupVersions(group: Boolean) = updateSettings { it.copy(groupVersions = group) }

    /**
     * Saves the order of the rows of a tab ([tab] as sync names it: `movie`, `series`, `home`) as the viewer set it in
     * Settings → Row order: [arranged], the keys of the tab's rows now (`RowOrder`), [hidden] those hidden. The rows the
     * tab no longer has (a category gone from the playlist, a row of Home this device does not show) keep their place
     * and their choice (`RowOrder.saving`); the record is dated for sync.
     */
    fun setRowOrder(tab: String, arranged: List<String>, hidden: Collection<String>) {
        // A Kids profile cannot change it, as on Apple devices (`AppModel.canChangeRowOrder`).
        if (_state.value.activeProfile.isKids) return
        val previous = _state.value.library.rowOrder(tab)
        val record = SyncRows(
            tab = tab,
            order = RowOrder.saving(arranged, previous?.order.orEmpty()),
            hidden = RowOrder.savingHidden(arranged, hidden, previous?.hidden.orEmpty()),
            updatedAt = 0,
        ).normalized()
        if (previous != null && previous.order == record.order && previous.hidden == record.hidden) return
        val dated = record.copy(updatedAt = stamp())
        updateLibrary { it.keepingRowOrder(dated) }
    }

    /** The Movies ([series] false) or Series tab's: [setRowOrder] with its tab. */
    fun setRowOrder(series: Boolean, arranged: List<String>, hidden: Collection<String>) =
        setRowOrder(if (series) SyncRows.SERIES else SyncRows.MOVIES, arranged, hidden)

    /** Default order: the rows of the tab in their default order again, those hidden staying hidden. */
    fun resetRowOrder(tab: String) {
        if (_state.value.activeProfile.isKids) return
        val previous = _state.value.library.rowOrder(tab) ?: return
        if (previous.order.isEmpty()) return
        val dated = previous.copy(order = emptyList(), updatedAt = stamp())
        updateLibrary { it.keepingRowOrder(dated) }
    }

    /**
     * Saves a selection (Settings → Row order → New selection, docs/SYNC.md): [id] null makes a new one, whose row goes to
     * the top of each tab it shows in; an edited one that now shows in another tab goes to the top of that tab too. The
     * record is dated for sync. Returns its ID; null for a Kids profile, which cannot change its rows.
     */
    fun saveSelection(id: String?, name: String, tags: List<String>, all: Boolean, tabs: List<String>): String? {
        if (_state.value.activeProfile.isKids) return null
        val previous = id?.let { _state.value.library.selection(it) }
        val record = SyncSelection.normalized(
            listOf(
                SyncSelection(
                    id = previous?.id ?: java.util.UUID.randomUUID().toString().lowercase(),
                    name = name.trim(),
                    // Genre tags by TMDb's genre (Genres), whatever language the editor showed them in.
                    tags = Genres.canonicalTags(tags),
                    match = if (all) SyncSelection.ALL else SyncSelection.ANY,
                    tabs = tabs,
                    updatedAt = 0,
                ),
            ),
        ).singleOrNull() ?: return null
        if (previous != null && previous.copy(updatedAt = 0) == record) return record.id
        val now = stamp()
        val key = Selections.rowKey(record.id)
        updateLibrary { library ->
            record.tabs.filter { it !in previous?.tabs.orEmpty() }.fold(library.keepingSelection(record.copy(updatedAt = now))) { result, tab ->
                val rows = result.rowOrder(tab)
                if (rows != null && key in rows.order) result
                else result.keepingRowOrder(SyncRows(tab, listOf(key) + rows?.order.orEmpty(), rows?.hidden.orEmpty(), now).normalized())
            }
        }
        return record.id
    }

    /** Deletes a selection, its row taken out of each tab's order; remembered for sync. */
    fun deleteSelection(id: String) {
        if (_state.value.activeProfile.isKids) return
        if (_state.value.library.selection(id) == null) return
        val now = stamp()
        val key = Selections.rowKey(id)
        updateLibrary { library ->
            library.rowOrders.filter { key in it.order || key in it.hidden }.fold(library.deletingSelection(id, now)) { result, rows ->
                result.keepingRowOrder(rows.copy(order = rows.order - key, hidden = rows.hidden - key, updatedAt = now))
            }
        }
    }

    /** The version of a film the viewer chose on its page ([AppSettings.chosenVersions]), on this device only. */
    fun rememberVersion(movieId: String) = updateSettings { settings ->
        if (settings.chosenVersions.firstOrNull() == movieId) settings
        else settings.copy(chosenVersions = (listOf(movieId) + settings.chosenVersions.filter { it != movieId }).take(MAX_CHOSEN_VERSIONS))
    }

    fun setPosterSort(sort: PosterSort) = updateSettings { it.copy(posterSort = sort) }

    fun setLiveBuffer(seconds: Int) = updateSettings { it.copy(liveBuffer = seconds) }

    fun setVodBuffer(seconds: Int) = updateSettings { it.copy(vodBuffer = seconds) }

    fun setAutoRefreshHours(hours: Int) = updateSettings { it.copy(autoRefreshHours = hours) }

    /** Older than the viewer's choice of automatic refresh (never, with "only when asked"). */
    private fun isStale(fetchedAt: Long): Boolean {
        val hours = _state.value.settings.autoRefreshHours
        return hours > 0 && now() - fetchedAt > hours * HOUR_MS
    }

    /**
     * Ondo is back in front (a TV keeps it open for days): the playlist in use is downloaded again once it is older
     * than the viewer's choice. Nothing while it is being read or downloaded.
     */
    fun refreshIfStale() {
        val state = _state.value
        val playlist = state.activePlaylist ?: return
        if (state.isLoading || state.isRestoring || openJob?.isActive == true || refreshJob?.isActive == true) return
        if (state.content.isEmpty || !isStale(state.content.fetchedAt)) return
        load(playlist)
    }

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
        // A Kids profile changes what it hides only once a parent unlocked it (docs/PROFILES.md, "Kids"), as its screens ask.
        if (_state.value.needsParentForSettings) return
        val playlistId = _state.value.activePlaylistId ?: return
        if (categoryIds.isEmpty()) return
        val time = stamp()
        val after = stamp()
        updateLibrary { it.settingCategoriesHidden(playlistId, categoryIds, hidden, time, after) }
    }

    /** A choice about tags answers the "show only your languages?" suggestion too. */
    fun setTagsHidden(tags: Collection<String>, hidden: Boolean) {
        if (_state.value.needsParentForSettings) return
        val time = stamp()
        updateLibrary { it.settingTagsHidden(tags, hidden, time) }
        dismissTagSuggestion()
    }

    /** Hides or shows the categories of a style or word ("manga"), in every playlist. */
    fun setWordHidden(word: String, hidden: Boolean) {
        if (_state.value.needsParentForSettings) return
        val time = stamp()
        updateLibrary { it.settingWordHidden(word, hidden, time) }
    }

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
            if (cached == null || isStale(cached.fetchedAt)) load(playlist)
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
                val guide = withContext(guideWork) { EpgIndex(data) }
                if (_state.value.activePlaylistId == playlist.id) _state.update { it.copy(guide = guide) }
            }
            if (!download) {
                val stored = kept()
                if (stored != null && now() - stored.data.fetchedAt in 0 until GUIDE_FRESH_MS) {
                    show(stored.data)
                    return@launch
                }
            }
            // With little memory (a guide budget under 48 MB), the guide shown leaves before the new one is read: two
            // large guides at once, with the catalogue just refreshed, leave a TV's app no room (Android stops it).
            if (download && (guideBudgetBytes ?: Long.MAX_VALUE) < SMALL_GUIDE_BUDGET && _state.value.activePlaylistId == playlist.id) {
                _state.update { it.copy(guide = null) }
            }
            // Downloading, matching to the catalogue's channels, merging and indexing: all of it off the main thread.
            val data = loadCatching { withContext(guideWork) { loader(files = guideFiles).loadEpg(core, content).data } }.getOrNull()
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
        updateLibrary { it.toggleFavorite(Favorite(playlistId, kind, itemId, title, imageUrl, stamp())) }
    }

    /**
     * The playlist's movies and series of a genre beyond the pages opened: those whose genres the device kept (TMDb's,
     * pages opened or prepared ahead), and with the viewer's token the genre's most popular titles on TMDb. TMDb's
     * answer is kept a day.
     */
    suspend fun genreTitles(genre: String): List<TrendingMatch> {
        val wanted = TextNormalizer.normalize(genre)
        if (wanted.isEmpty()) return emptyList()
        val titles = metadataTitles() ?: return emptyList()
        // Found once per catalogue and session: back on the genre, it shows at once.
        val content = _state.value.content
        genreMatches[wanted]?.takeIf { it.first === content }?.let { return it.second }
        val kept = withContext(io) { metadata.titlesOfGenre(wanted, series = false) + metadata.titlesOfGenre(wanted, series = true) }
        val local = withContext(Dispatchers.Default) { titles.match(kept.map { it.key }) }
        val online = tmdbGenreItems(wanted, kept)
        val found = if (online.isEmpty()) emptyList() else withContext(Dispatchers.Default) { online.flatMap { titles.match(it.keys) } }
        // Kept only for the catalogue still shown: one replaced meanwhile is not held in memory.
        return (local + found).distinctBy { it.id }.also { if (_state.value.content === content) genreMatches[wanted] = content to it }
    }

    /** The catalogue's titles of each genre opened, for the catalogue they were found in. */
    private val genreMatches = java.util.concurrent.ConcurrentHashMap<String, Pair<PlaylistContent, List<TrendingMatch>>>()

    /**
     * What TMDb said of the catalogue's titles and the device kept (their rating, their genres), by the poster's key
     * ("m-1", "s-2"): a poster shows it without asking TMDb. Read once per catalogue, again once more was kept.
     */
    suspend fun keptPosterFacts(): Map<String, TitleMetadata> {
        val titles = metadataTitles() ?: return emptyMap()
        val content = _state.value.content
        val saved = metadataSaves.get()
        posterFacts?.let { (known, at, facts) -> if (known === content && at == saved) return facts }
        val named = withContext(io) { runCatching { metadata.namedTitles() }.getOrDefault(emptyList()) }
        val facts = withContext(Dispatchers.Default) {
            val result = HashMap<String, TitleMetadata>()
            for (title in named) {
                if (title.genres.isNullOrEmpty() && title.rating == null && title.releaseDate.isNullOrEmpty()) continue
                for (match in titles.match(listOf(title.key))) result.putIfAbsent(match.id, title)
            }
            result
        }
        if (_state.value.content === content) posterFacts = Triple(content, saved, facts)
        return facts
    }

    @Volatile private var posterFacts: Triple<PlaylistContent, Int, Map<String, TitleMetadata>>? = null

    /** How many times titles were kept this session: what [keptPosterFacts] read may be out of date. */
    private val metadataSaves = java.util.concurrent.atomic.AtomicInteger()

    /** TMDb's most popular movies and series of the genre (named in the viewer's language), a few pages of each. */
    private suspend fun tmdbGenreItems(genre: String, kept: List<TitleMetadata>): List<RelatedTitle> {
        val token = _state.value.settings.tmdbToken ?: return emptyList()
        val result = ArrayList<RelatedTitle>()
        for (series in listOf(false, true)) {
            // The genre's id: from a title kept, else TMDb's list of genres (kept for the session).
            val id = kept.firstNotNullOfOrNull { title -> title.genres?.firstOrNull { TextNormalizer.normalize(it.name) == genre && title.kind == MetadataKey.kind(series) }?.id }
                ?: (genreLists["$token\u0000$series"] ?: tmdb { genres(series) }?.also { genreLists["$token\u0000$series"] = it }).orEmpty()
                    .firstOrNull { TextNormalizer.normalize(it.name) == genre }?.id
                ?: continue
            // Kept on the device a week (in the store, beside the people's titles): the genre shows at once next time.
            val key = "genre:${MetadataKey.kind(series)}:$id"
            val askedAt = now()
            val stored = withContext(io) { metadata.personWorks(key) }
            val items = stored?.takeIf { MetadataFreshness.isFresh(it.first, PERSON_WORKS_FRESH_MS, askedAt) }?.second ?: buildList {
                for (page in 1..GENRE_PAGES) {
                    val found = tmdbSlots.withPermit { tmdb { discover(series, id, page) } } ?: break
                    for (item in found) {
                        for (title in listOfNotNull(item.title, item.originalTitle).distinct().filter { it.isNotBlank() }) add(RelatedTitle(series, item.id, title, item.year))
                    }
                    if (found.size < GENRE_PAGE_SIZE) break
                }
            }.also { if (it.isNotEmpty()) withContext(io) { metadata.savePersonWorks(key, askedAt, it) } }
                .ifEmpty { stored?.second.orEmpty() }
            result += items
        }
        return result
    }

    /** TMDb's genres by token and kind. */
    private val genreLists = java.util.concurrent.ConcurrentHashMap<String, List<TmdbGenre>>()

    /** TMDb's photo of the person, from the titles kept on the device that credit them; null when none has one. */
    suspend fun personPhoto(name: String): String? {
        val key = TextNormalizer.normalize(name)
        return withContext(io) { runCatching { metadata.people(name, PHOTO_CANDIDATES) }.getOrDefault(emptyList()) }
            .firstOrNull { TextNormalizer.normalize(it.name) == key && it.photoUrl != null }?.photoUrl
    }

    /** The photo found for a person kept without one: My List and the collections show it from now on. */
    fun keepPersonPhoto(name: String, photoUrl: String) {
        val library = _state.value.library
        if (library.withPersonPhoto(name.trim(), photoUrl) !== library) updateLibrary { it.withPersonPhoto(name.trim(), photoUrl) }
    }

    /** The people kept without a photo: the one found on the device (a title that credits them), when there is one. */
    suspend fun fillPersonPhotos(names: List<String>) {
        for (name in names) personPhoto(name)?.let { keepPersonPhoto(name, it) }
    }

    /** Keeps an actor or a director in My List (whatever the playlist), or takes them out. */
    fun togglePerson(name: String, photoUrl: String?) = updateLibrary { it.toggleFavorite(Favorite.person(name, photoUrl, stamp())) }

    /** Adds an actor or a director to a collection, or takes them out. */
    fun togglePersonInCollection(id: String, name: String, photoUrl: String?) =
        updateCollection(id) { library -> stamp().let { time -> library.togglingInCollection(id, Favorite.person(name, photoUrl, time), time) } }

    /** The library of the profile playback belongs to ([playbackProfileId]). */
    private fun playbackLibrary(): LibraryState {
        val id = playbackProfileId
        return if (id == null || id == _state.value.activeProfile.id) _state.value.library else otherLibraries[id] ?: LibraryState()
    }

    /** [creditsStart]: where the end credits start, when the player found them in the file's chapters. */
    fun recordProgress(key: String, position: Double, duration: Double, creditsStart: Double? = null) {
        updatePlaybackLibrary { it.updateProgress(key, position, duration, stamp(), creditsStart) }
    }

    /** Takes a "Continue watching" card away: the movie, or the series' episodes in progress. */
    fun removeFromContinueWatching(key: String) {
        val entry = _state.value.library.progress(key) ?: return
        updateLibrary { it.removingFromContinueWatching(entry, stamp()) }
    }

    /**
     * "Mark as watched" on a film's page: [version] (the one its page shows), the same state as watching it to the end;
     * unwatched, every one of [versions] (a film is watched once one of its versions is). The open profile's library:
     * sync carries it like any position.
     */
    fun setFilmWatched(watched: Boolean, version: Movie, versions: List<Movie>, durationSeconds: Int? = null) {
        val playlistId = _state.value.activePlaylistId ?: return
        val time = stamp()
        if (watched) {
            val entry = WatchEntry(playlistId, ItemKind.MOVIE, version.id, null, version.name, imageUrl = version.posterUrl, duration = (durationSeconds ?: 0).toDouble(), lastWatchedAt = time)
            updateLibrary { it.markingWatched(entry, time) }
        } else {
            val keys = (versions + version).map { WatchEntry(playlistId, ItemKind.MOVIE, it.id, null, title = "", lastWatchedAt = 0).key }.distinct()
            updateLibrary { library -> keys.fold(library) { changed, key -> changed.markingUnwatched(key, time) } }
        }
    }

    /**
     * Marks [episodes] of [series] watched or unwatched, in their order ([LibraryState.markingEpisodes]): watched, the
     * last one is the latest, so Continue watching goes on after it as after playback; unwatched, Play starts again at
     * the first; never behind a later episode the viewer is watching. At most [LibraryState.BULK_MARK_LIMIT] at once.
     */
    fun setEpisodesWatched(watched: Boolean, series: Series, episodes: List<Episode>) {
        val playlistId = _state.value.activePlaylistId ?: return
        val distinct = episodes.distinctBy { it.id }
        if (!LibraryState.canMarkAtOnce(distinct.size)) return
        val entries = distinct.map { episode ->
            WatchEntry(
                playlistId, ItemKind.SERIES, series.id, episode.id, series.name, "${episode.code} · ${episode.title}",
                episode.posterUrl ?: series.coverUrl, duration = (episode.durationSeconds ?: 0).toDouble(), lastWatchedAt = 0,
            )
        }
        // Every episode in season order: its seasons as known, else the playlist's own list.
        val order = _state.value.seriesDetails[series.id]?.seasons?.flatMap { season -> season.episodes.map { it.id } }
            ?: series.episodes.sortedWith(compareBy({ it.season }, { it.number })).map { it.id }
        val stamps = entries.map { stamp() }
        updateLibrary { it.markingEpisodes(entries, watched, order, stamps) }
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
        updateLibrary { it.creatingCollection(id, name, stamp()) }
        return id
    }

    fun renameCollection(id: String, name: String) = updateCollection(id) { it.renamingCollection(id, name, stamp()) }

    /** Deletes a collection: a shared one for every profile (the screen asked first). */
    fun deleteCollection(id: String) {
        val state = _state.value
        if (!state.isShared(id)) return updateLibrary { it.deletingCollection(id, stamp()) }
        if (!state.canEditShared) return
        val time = stamp()
        updateHousehold { it.deletingCollection(id, time) }
        // For every profile, behind an older device too (it passes on the main profile's deletions).
        updateMainLibrary { it.deleting(SyncSharing.deletedForAllKey(id), time) }
    }

    /** Adds an item of the current playlist to a collection, or takes it out. */
    fun toggleInCollection(id: String, kind: ItemKind, itemId: String, title: String, imageUrl: String?) {
        val playlistId = _state.value.activePlaylistId ?: return
        updateCollection(id) { library -> stamp().let { time -> library.togglingInCollection(id, Favorite(playlistId, kind, itemId, title, imageUrl, time), time) } }
    }

    /**
     * Shares collection [id] of the open profile with every profile, or takes a shared one back into the open profile
     * (docs/PROFILES.md, "Shared lists"): it moves whole, under the same ID. Not in a Kids profile.
     */
    fun setCollectionShared(id: String, shared: Boolean) {
        val state = _state.value
        if (!state.isReady || !state.canEditShared || shared == state.isShared(id)) return
        val removedAt = stamp()
        val placedAt = stamp()
        // The move out of the space it leaves goes with the main profile's deletions (the snapshot's own), which an older
        // device keeps and passes on without applying: no list is lost behind one (docs/SYNC.md, "Shared lists").
        val profileSpace = if (state.activeProfile.isMain) SyncProfile.MAIN else state.activeProfile.id.lowercase()
        if (shared) {
            val (library, household) = SharedCollections.move(state.library, state.household, id, placedAt) ?: return
            updateLibrary { library }
            updateHousehold { household }
            updateMainLibrary { it.deleting(SyncSharing.movedKey(profileSpace, id), removedAt) }
        } else {
            val (household, library) = SharedCollections.move(state.household, state.library, id, placedAt) ?: return
            updateHousehold { household }
            updateLibrary { library }
            updateMainLibrary { it.deleting(SyncSharing.movedKey(SyncSharing.HOUSEHOLD, id), removedAt) }
        }
    }

    /** [change] made to the library collection [id] is in: the household's when it is shared (never in a Kids profile). */
    private fun updateCollection(id: String, change: (LibraryState) -> LibraryState) {
        val state = _state.value
        if (!state.isShared(id)) return updateLibrary(change)
        if (state.canEditShared) updateHousehold(change)
    }

    /** Changes the collections shared with every profile, and saves them. */
    private fun updateHousehold(change: (LibraryState) -> LibraryState) {
        if (!_state.value.isReady) return
        _state.update { it.copy(household = change(it.household)) }
        val household = _state.value.household
        persist { saveLibrary(household, AppStore.HOUSEHOLD) }
    }

    private fun updateLibrary(change: (LibraryState) -> LibraryState) {
        if (!_state.value.isReady) {
            pendingLibraryChanges += change
            bootstrap()
            return
        }
        _state.update { it.copy(library = change(it.library)) }
        val library = _state.value.library
        // The document is the profile's the change was made in, even if another profile opens before it is written.
        val document = _state.value.activeProfile.libraryDocumentName
        persist { saveLibrary(library, document) }
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
        // A cancelled call stays one: the caller (a page closed, the remote moved on) does not go on as if it had failed.
        val details = loadCatching { withContext(io) { XtreamClient(credentials, http).movieDetails(streamId) } }.getOrNull() ?: return kept
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
        val entry = WatchEntry(playlist.id, ItemKind.MOVIE, movie.id, null, movie.name, movie.year?.toString(), movie.posterUrl, lastWatchedAt = 0)
        val tmdbId = movie.tmdbId ?: _state.value.movieDetails[movie.id]?.tmdbId?.toIntOrNull()
        return start(item, entry, fromStart).copy(subtitleSearch = SubtitleSearchHint.of(movie.name, movie.year, tmdbId = tmdbId))
    }

    fun playRequest(series: Series, episode: Episode): PlayRequest? {
        val playlist = _state.value.activePlaylist ?: return null
        // The episodes of the provider's details are kept as they came: the history keeps the title shown.
        val shown = _state.value.shownEpisode(series, episode)
        val item = PlaybackFactory(playlist.toCorePlaylist()).item(shown, series)
        val entry = WatchEntry(playlist.id, ItemKind.SERIES, series.id, shown.id, series.name, "${shown.code} · ${shown.title}", shown.posterUrl ?: series.coverUrl, lastWatchedAt = 0)
        val hint = SubtitleSearchHint.of(series.name, series.year, episode.season, episode.number, _state.value.seriesDetails[series.id]?.info?.tmdbId?.toIntOrNull())
        return start(item, entry, fromStart = false).copy(subtitleSearch = hint)
    }

    /**
     * The episode after the one [request] plays, with its series, for the player's Up next: in the series' seasons
     * (its page, once opened) or its playlist's list; null after the last one, or for a movie or a channel.
     */
    fun nextEpisode(request: PlayRequest): Pair<Series, Episode>? {
        val playing = playingSeries(request) ?: return null
        val episodes = playing.seasons.flatMap { it.episodes }
        val index = episodes.indexOfFirst { it.id == playing.episodeId }
        return episodes.getOrNull(index + 1)?.takeIf { index >= 0 }?.let { playing.series to it }
    }

    /**
     * The series [request] plays an episode of, with its seasons in order (its page's, once opened, else its
     * playlist's list), each episode once: for the player's Up next and its Episodes row. Null for a movie or a channel.
     */
    fun playingSeries(request: PlayRequest): PlayingSeries? {
        val parts = request.progressKey?.split('|') ?: return null
        if (parts.size != 4 || parts[1] != ItemKind.SERIES.name || parts[3].isEmpty()) return null
        val state = _state.value
        if (parts[0] != state.activePlaylistId) return null
        val series = state.content.series.firstOrNull { it.id == parts[2] } ?: return null
        // As the series' page shows them: each episode's own title, TMDb's still where the provider has none.
        val seasons = state.seriesDetails[series.id]?.let { state.shownDetails(series, it) }?.seasons
            ?.map { season -> season.copy(episodes = season.episodes.sortedBy { it.number }.distinctBy { it.id }) }
            ?: series.episodes.groupBy { it.season }.map { (number, episodes) ->
                Season(number, name = "", episodes = episodes.sortedBy { it.number }.distinctBy { it.id }.map { state.shownEpisode(series, it) })
            }
        return PlayingSeries(series, parts[3], seasons.sortedBy { it.number }.filter { it.episodes.isNotEmpty() })
    }

    /** Where the viewer left the episode [episodeId] of [series]: null before it was played. */
    fun episodeProgress(series: Series, episodeId: String): WatchEntry? {
        val state = _state.value
        val playlistId = state.activePlaylistId ?: return null
        return state.library.progress(WatchEntry(playlistId, ItemKind.SERIES, series.id, episodeId, title = "", lastWatchedAt = 0).key)
    }

    fun playRequest(channel: Channel): PlayRequest? {
        val playlist = _state.value.activePlaylist ?: return null
        val item = PlaybackFactory(playlist.toCorePlaylist()).item(channel)
        val entry = WatchEntry(playlist.id, ItemKind.CHANNEL, channel.id, null, channel.name, null, channel.logoUrl, lastWatchedAt = 0)
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

    /** [entry] starts now: dated by [stamp] when it is kept (after the sync clock is set up, at a launch). */
    private fun start(item: PlaybackItem, entry: WatchEntry, fromStart: Boolean): PlayRequest {
        // A title started from the screens belongs to the profile open; the next one started in the player (an episode,
        // a channel) to the profile the playback began in.
        if (!inPlayback || playbackProfileId == null) playbackProfileId = _state.value.activeProfile.id.takeIf { _state.value.isReady }
        updatePlaybackLibrary { it.recordStart(entry.copy(lastWatchedAt = stamp())) }
        val known = playbackLibrary().progress(entry.key)
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

    private fun loader(files: CoroutineDispatcher = Dispatchers.IO) =
        PlaylistLoader(http, parsingDispatcher = parsing, ioDispatcher = files, clock = now, downloadFolder = downloadFolder, guideBudgetBytes = guideBudgetBytes)

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

    /** Titles (`m-<id>`, `s-<id>`) looked for this session: the store read, TMDb asked when needed. Main thread. */
    private val metadataAsked = HashSet<String>()

    /** Requests ahead of the viewer at the same time; they also keep a pace (TMDb's limits are far above). */
    private val tmdbSlots = Semaphore(MAX_PREFETCHING)

    /** The catalogue's titles by their words, for the people the store knows ([MetadataTitles]). */
    @Volatile private var metadataTitles: Pair<PlaylistContent, MetadataTitles>? = null

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
        updateSettings { settings -> settings.copy(serviceKeys = settings.serviceKeys.filterNot { it.service == SyncKey.TMDB } + SyncKey(SyncKey.TMDB, trimmed, stamp())) }
        tmdbTokenChanged()
        return TmdbCheck.ACCEPTED
    }

    /** Removes the TMDb token here, and on the other devices with the next sync. */
    fun removeTmdbToken() {
        if (_state.value.settings.serviceKeys.none { it.service == SyncKey.TMDB }) return
        updateSettings { settings -> settings.copy(serviceKeys = settings.serviceKeys.filterNot { it.service == SyncKey.TMDB }) }
        // A key is every profile's: its removal travels with the main profile's deletions, the snapshot's own.
        updateMainLibrary { it.deleting("key:${SyncKey.TMDB}", stamp()) }
        tmdbTokenChanged()
    }

    private fun tmdbTokenChanged() {
        trendingLoadedFor = null
        metadataAsked.clear()
        metadataPrepared.clear()
        headerFetches.clear()
        genreMatches.clear()
        personMatches.clear()
        seasonsAsked.clear()
        _state.update { it.copy(tmdbRefused = false, trending = emptyList(), tmdbBackdrops = emptyMap(), tmdbFaces = emptyMap(), tmdbRelated = emptyMap(), tmdbTrailers = emptyMap(), tmdbLogos = emptyMap(), tmdbRatings = emptyMap(), tmdbSeasons = emptyMap()) }
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

    /** Titles prepared ahead this session (see [prepareTmdbMetadata]). */
    private val metadataPrepared: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * What TMDb says about a title (backdrop, trailer, rating, cast and directors with their photos), once a session:
     * what the store kept is shown at once, token or not; with the viewer's token, TMDb is asked when the store has
     * nothing or something old, by the TMDb id the provider gave, else by title (only those are sent). The answer, a
     * "not found" included, is kept. [ahead]: a title about to be seen (Home), fetched at a pace, two at a time.
     */
    fun findTmdbMetadata(key: String, series: Boolean, tmdbId: String?, title: String, year: Int?, ahead: Boolean = false) {
        val keys = MetadataKey.all(series, tmdbId, title, year)
        if (keys.isEmpty()) return
        if (!metadataAsked.add(key)) {
            // Asked by the header (the title's page opened from its poster): its answer is now the page's too, and the
            // remote leaving the posters no longer cancels it.
            headerFetches.remove(key)
            return
        }
        // The token as the page opens: one saved meanwhile asks again (the titles asked are forgotten then).
        val client = tmdbClient()
        scope.launch { tmdbMetadata(key, keys, series, tmdbId, title, year, ahead, client, show = true) }
    }

    /**
     * The same, waited for by the caller (the title a TV's header shows while the remote stays on it): cancelled with
     * it when the remote moves on, and then asked again the next time, unless the title's page opened meanwhile (the
     * page keeps the answer). Not paced behind the titles asked ahead: the header asks for one title at a time. Main
     * thread, as [metadataAsked].
     */
    suspend fun awaitTmdbMetadata(key: String, series: Boolean, tmdbId: String?, title: String, year: Int?) {
        val keys = MetadataKey.all(series, tmdbId, title, year)
        if (keys.isEmpty() || !metadataAsked.add(key)) return
        val client = tmdbClient()
        var failure: Exception? = null
        // In the repository's scope, so that a page opened meanwhile can keep it ([findTmdbMetadata]); its errors stay
        // in it (an error out of that scope would stop the app) and reach the caller.
        val fetch = scope.launch {
            try {
                tmdbMetadata(key, keys, series, tmdbId, title, year, ahead = false, client = client, show = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
        }
        headerFetches[key] = fetch
        try {
            fetch.join()
        } finally {
            if (headerFetches[key] === fetch) {
                headerFetches.remove(key)
                if (!fetch.isCompleted) {
                    // The remote moved on and no page wants it: asked again the next time the title shows.
                    fetch.cancel()
                    metadataAsked.remove(key)
                }
            }
        }
        failure?.let { throw it }
    }

    /**
     * The header's requests in flight ([awaitTmdbMetadata]), cancelled when the remote moves on unless a page opened
     * meanwhile took them over. Main thread, as [metadataAsked].
     */
    private val headerFetches = HashMap<String, Job>()

    /**
     * The same, waited for, and not shown on screen: a title prepared while Ondo is open (Settings, "Prepare new
     * titles"). Kept on the device like the others; false when it was already asked this session.
     */
    suspend fun prepareTmdbMetadata(key: String, series: Boolean, tmdbId: String?, title: String, year: Int?, changedAt: Long? = null): Boolean {
        val keys = MetadataKey.all(series, tmdbId, title, year)
        // Not marked as asked for the screen: the title's page, opened later, shows what was kept.
        if (keys.isEmpty() || key in metadataAsked || !metadataPrepared.add(key)) return false
        tmdbMetadata(key, keys, series, tmdbId, title, year, ahead = true, client = tmdbClient() ?: return false, show = false, changedAt = changedAt)
        return true
    }

    /**
     * [prepareTmdbMetadata] for the carousel ([com.markedoe.ondo.core.library.RecentReleases]): asked again when the
     * provider changed the title ([changedAt], epoch seconds: a series' new episode) since TMDb was; true when TMDb's
     * answer, now kept, has a release date.
     */
    suspend fun prepareTmdbRelease(key: String, series: Boolean, tmdbId: String?, title: String, year: Int?, changedAt: Long?): Boolean {
        if (!prepareTmdbMetadata(key, series, tmdbId, title, year, changedAt)) return false
        val keys = MetadataKey.all(series, tmdbId, title, year)
        return withContext(io) { runCatching { metadata.titles(keys).values.any { !it.releaseDate.isNullOrEmpty() } }.getOrDefault(false) }
    }

    /**
     * Of [titles] (a poster's key to its keys in the store, [MetadataKey.all], and when the provider last changed it), those
     * whose release the carousel needs asked ([com.markedoe.ondo.core.library.RecentReleases.needsAsking]).
     */
    suspend fun withoutReleaseDate(titles: Map<String, Pair<List<String>, Long?>>): Set<String> = withContext(io) {
        val kept = runCatching { metadata.titles(titles.values.flatMap { it.first }) }.getOrDefault(emptyMap())
        titles.filter { (_, title) ->
            val found = title.first.firstNotNullOfOrNull { kept[it] }
            com.markedoe.ondo.core.library.RecentReleases.needsAsking(found != null, found?.found == true, found?.releaseDate, found?.fetchedAt ?: 0L, title.second)
        }.keys
    }

    /**
     * [prepareTmdbMetadata] for a grid sorted by rating ([com.markedoe.ondo.core.library.RatingFill]): true when it was
     * asked and TMDb's answer, now kept, has a rating.
     */
    suspend fun prepareTmdbRating(key: String, series: Boolean, tmdbId: String?, title: String, year: Int?): Boolean {
        if (!prepareTmdbMetadata(key, series, tmdbId, title, year)) return false
        val keys = MetadataKey.all(series, tmdbId, title, year)
        return withContext(io) { runCatching { metadata.titles(keys).values.any { it.rating != null } }.getOrDefault(false) }
    }

    /**
     * Of [titles] (a poster's key to its keys in the store, [MetadataKey.all]), those TMDb never answered for: the device
     * kept nothing under any of their keys, a "not found" included.
     */
    suspend fun withoutTmdbAnswer(titles: Map<String, List<String>>): Set<String> = withContext(io) {
        val kept = runCatching { metadata.titles(titles.values.flatten()) }.getOrDefault(emptyMap())
        titles.filterValues { keys -> keys.none { it in kept } }.keys
    }

    /** [show]: put what was found on screen (a title shown); a title prepared ahead is only kept on the device. */
    private suspend fun tmdbMetadata(key: String, keys: List<String>, series: Boolean, tmdbId: String?, title: String, year: Int?, ahead: Boolean, client: TmdbClient?, show: Boolean, changedAt: Long? = null) {
        run {
            val stored = withContext(io) { storedMetadata(keys) }
            if (stored != null && show) showMetadata(key, stored.first, stored.second)
            // Fresh, unless the provider changed the title since (a series' new episode moves its latest air date).
            val changed = changedAt != null && stored != null && stored.first.found && changedAt * 1_000 > stored.first.fetchedAt
            if (stored != null && MetadataFreshness.isFresh(stored.first, now()) && !changed) return@run
            if (client == null) return@run
            val info = try {
                if (ahead) {
                    tmdbSlots.withPermit {
                        withContext(io) { client.titleInfo(series, tmdbId, title, year) }.also { delay(PACE_MS) }
                    }
                } else {
                    withContext(io) { client.titleInfo(series, tmdbId, title, year) }
                }
            } catch (e: CancellationException) {
                metadataAsked.remove(key)
                throw e
            } catch (e: Exception) {
                // Out of reach: asked again the next time the title shows. TMDb refused or failed: not this session.
                if (e is java.io.IOException && e !is HttpException) metadataAsked.remove(key)
                noteTmdbFailure(e)
                return@run
            }
            // Also under the id TMDb gave: found at once when the provider's details name it later.
            val saved = (keys + listOfNotNull(info?.let { MetadataKey.tmdb(series, it.tmdbId) })).distinct()
            val at = now()
            val rows = saved.map { TitleMetadata.of(it, series, info, at) }
            val credits = info?.let(CreditRecord::of).orEmpty()
            withContext(io) {
                metadata.save(rows)
                if (info != null) metadata.saveCredits(credits, saved)
            }
            metadataSaves.incrementAndGet()
            if (show) showMetadata(key, rows.first(), credits)
        }
    }

    /** The first of [keys] the store has, with its credits. */
    private fun storedMetadata(keys: List<String>): Pair<TitleMetadata, List<CreditRecord>>? {
        val titles = metadata.titles(keys)
        val title = keys.firstNotNullOfOrNull { titles[it] } ?: return null
        return title to if (title.found) metadata.credits(title.key) else emptyList()
    }

    /** The TMDb ids of the series whose metadata showed, by their key (`s-<id>`): their seasons are asked by it. */
    private val tmdbSeriesIds = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** The seasons a page showed before its series' TMDb id was known, by the series' key: asked once it is. */
    private val seasonsWanted = java.util.concurrent.ConcurrentHashMap<String, MutableSet<Int>>()

    /** The seasons asked this session ("s-<id>|<season>"). */
    private val seasonsAsked: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * TMDb's episodes of season [season] of the series [key] (`s-<id>`) whose page shows it: their stills and names,
     * for the episodes the provider leaves without them. Once a session; what the store kept shows at once and is asked
     * again after [MetadataFreshness.FOUND_MS]. One request for the whole season, by the series' TMDb id: asked once
     * that is known ([findTmdbMetadata]).
     */
    fun findTmdbSeason(key: String, season: Int) {
        val id = tmdbSeriesIds[key]
        if (id == null) {
            seasonsWanted.getOrPut(key) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(season)
            return
        }
        if (!seasonsAsked.add("$key|$season")) return
        val client = tmdbClient()
        scope.launch { tmdbSeason(key, id, season, client) }
    }

    private suspend fun tmdbSeason(key: String, id: Int, season: Int, client: TmdbClient?) {
        val storeKey = MetadataKey.season(id, season)
        val stored = withContext(io) { metadata.season(storeKey) }
        if (stored != null) showSeason(key, season, stored.second)
        val maxAge = if (stored?.second.isNullOrEmpty()) MetadataFreshness.NOT_FOUND_MS else MetadataFreshness.FOUND_MS
        if (stored != null && MetadataFreshness.isFresh(stored.first, maxAge, now())) return
        if (client == null) return
        val episodes = try {
            withContext(io) { client.seasonEpisodes(id, season) }
        } catch (e: CancellationException) {
            seasonsAsked.remove("$key|$season")
            throw e
        } catch (e: Exception) {
            // Out of reach: asked again the next time the season shows. TMDb refused or failed: not this session.
            if (e is java.io.IOException && e !is HttpException) seasonsAsked.remove("$key|$season")
            noteTmdbFailure(e)
            return
        }
        withContext(io) { metadata.saveSeason(storeKey, now(), episodes) }
        showSeason(key, season, episodes)
    }

    private fun showSeason(key: String, season: Int, episodes: List<TmdbEpisode>) {
        if (episodes.isEmpty()) return
        _state.update { state ->
            val seasons = state.tmdbSeasons[key].orEmpty()
            if (seasons[season] == episodes) state else state.copy(tmdbSeasons = state.tmdbSeasons + (key to seasons + (season to episodes)))
        }
    }

    private suspend fun showMetadata(key: String, title: TitleMetadata, credits: List<CreditRecord>) {
        if (title.kind == MetadataKey.kind(series = true)) title.tmdbId?.let { id ->
            tmdbSeriesIds[key] = id
            seasonsWanted.remove(key)?.forEach { findTmdbSeason(key, it) }
        }
        val faces = credits.mapNotNull { credit -> credit.photoUrl?.let { credit.personKey to it } }.distinctBy { it.first }.toMap()
        // The titles TMDb recommends with it that the playlist has, in TMDb's order.
        val recommended = title.related.orEmpty()
        val related = if (recommended.isEmpty()) emptyList() else metadataTitles()?.let { titles ->
            withContext(Dispatchers.Default) { recommended.flatMap { titles.match(it.keys) }.distinctBy { it.id } }
        }.orEmpty()
        _state.update { state ->
            state.copy(
                tmdbRelated = if (related.isEmpty()) state.tmdbRelated else state.tmdbRelated + (key to related),
                tmdbBackdrops = title.backdropUrl?.let { state.tmdbBackdrops + (key to it) } ?: state.tmdbBackdrops,
                tmdbTrailers = title.trailerKey?.let { state.tmdbTrailers + (key to it) } ?: state.tmdbTrailers,
                tmdbLogos = title.logoUrl?.let { state.tmdbLogos + (key to it) } ?: state.tmdbLogos,
                tmdbRatings = state.tmdbRatings.with(key, title.rating),
                tmdbFaces = if (faces.isEmpty()) state.tmdbFaces else state.tmdbFaces + (key to faces),
            )
        }
    }

    private fun Map<String, TmdbRating>.with(key: String, rating: TmdbRating?): Map<String, TmdbRating> =
        if (rating == null) this else this + (key to rating)

    /** A backdrop that did not load: not asked for again for some days, and the title gets TMDb's instead when it can. */
    fun backdropFailed(url: String) {
        if (url in _state.value.deadBackdrops) return
        _state.update { it.copy(deadBackdrops = it.deadBackdrops + url) }
        val at = now()
        scope.launch(io) { metadata.markDeadImage(url, at) }
    }

    /** The image addresses that led nowhere in the last days: their titles show TMDb's picture or the poster at once. */
    private suspend fun loadDeadImages() {
        val dead = withContext(io) { metadata.deadImages(now() - MetadataFreshness.DEAD_IMAGE_MS) }
        if (dead.isNotEmpty()) _state.update { it.copy(deadBackdrops = it.deadBackdrops + dead) }
    }

    /**
     * The people whose name matches a search: those the titles kept on the device credit (token or not), then, with
     * the viewer's token, those TMDb finds (only the words typed are sent).
     */
    suspend fun tmdbPeople(query: String): List<String> = tmdbPeopleWithPhotos(query).map { it.name }

    /** [tmdbPeople] with their photos (TMDb's) when known. TMDb's answer is kept a day: the same search is instant. */
    suspend fun tmdbPeopleWithPhotos(query: String): List<PersonRow> {
        val trimmed = query.trim()
        if (trimmed.length < 3) return emptyList()
        val local = localPeople(trimmed)
        val token = _state.value.settings.tmdbToken
        val online = if (token == null) {
            emptyList()
        } else {
            val key = "$token\u0000${TextNormalizer.normalize(trimmed)}"
            val askedAt = now()
            peopleSearches[key]?.takeIf { askedAt - it.first < PERSON_CREDITS_FRESH_MS }?.second
                ?: tmdb { people(trimmed) }?.filter { it.name.isNotBlank() }?.take(8)?.map { PersonRow(it.name, it.profileUrl) }?.also { found ->
                    if (peopleSearches.size >= PEOPLE_SEARCHES_KEPT) peopleSearches.keys.firstOrNull()?.let(peopleSearches::remove)
                    peopleSearches[key] = askedAt to found
                }.orEmpty()
        }
        val seen = HashSet<String>()
        return (local + online).filter { seen.add(MetadataKey.person(it.name)) }
    }

    /** TMDb's people searches of this session, by token and words. */
    private val peopleSearches = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<PersonRow>>>()

    /** The people the store knows by these words who have titles in this catalogue. */
    private suspend fun localPeople(query: String): List<PersonRow> {
        val people = withContext(io) { metadata.people(query, LOCAL_PEOPLE_LIMIT) }
        if (people.isEmpty()) return emptyList()
        val titles = metadataTitles() ?: return emptyList()
        return withContext(io) {
            people.filter { person -> titles.match(metadata.titleKeys(MetadataKey.person(person.name))).isNotEmpty() }.map { PersonRow(it.name, it.photoUrl) }
        }
    }

    /** The titles of this catalogue that the store credits the person with (their pages opened here or elsewhere). */
    suspend fun localWorks(name: String): List<TrendingMatch> {
        val key = MetadataKey.person(name)
        if (key.isEmpty()) return emptyList()
        val keys = withContext(io) { metadata.titleKeys(key) }
        if (keys.isEmpty()) return emptyList()
        return metadataTitles()?.match(keys).orEmpty()
    }

    private suspend fun metadataTitles(): MetadataTitles? {
        val content = _state.value.content
        if (content.movies.isEmpty() && content.series.isEmpty()) return null
        metadataTitles?.let { (known, titles) -> if (known === content) return titles }
        return withContext(Dispatchers.Default) { MetadataTitles(content.movies, content.series) }
            .also { if (_state.value.content === content) metadataTitles = content to it }
    }

    /** With the viewer's token, the person's movies and series on TMDb that the playlist has; null without a token. */
    suspend fun tmdbWorks(name: String): List<TrendingMatch>? {
        _state.value.settings.tmdbToken ?: return null
        val personKey = MetadataKey.person(name)
        if (personKey.isEmpty()) return emptyList()
        val titles = metadataTitles() ?: return emptyList()
        // Matched once per catalogue: back on the page, it shows at once.
        val content = _state.value.content
        // A person's credits are kept on the device and asked of TMDb again after a week.
        val askedAt = now()
        personMatches[personKey]?.takeIf { it.first === content && MetadataFreshness.isFresh(it.second, PERSON_WORKS_FRESH_MS, askedAt) }?.let { return it.third }
        val kept = withContext(io) { metadata.personWorks(personKey) }
        val fresh = kept?.takeIf { MetadataFreshness.isFresh(it.first, PERSON_WORKS_FRESH_MS, askedAt) }
        var fetchedAt = fresh?.first ?: askedAt
        val works = fresh?.second
            ?: tmdb { person(name)?.let { credits(it.id) } ?: emptyList() }
                ?.flatMap { item ->
                    val series = item.mediaType == TmdbItem.MediaType.TV
                    listOfNotNull(item.title, item.originalTitle).distinct().filter { it.isNotBlank() }.map { RelatedTitle(series, item.id, it, item.year) }
                }
                ?.also { found -> withContext(io) { metadata.savePersonWorks(personKey, askedAt, found) } }
            ?: kept?.also { fetchedAt = it.first }?.second
            ?: return null
        // Through the catalogue's index (built once): not the whole catalogue walked for each person.
        val matches = withContext(Dispatchers.Default) { works.flatMap { titles.match(it.keys) }.distinctBy { it.id } }
        if (_state.value.content === content) {
            if (personMatches.size >= PERSON_CREDITS_KEPT) personMatches.keys.firstOrNull()?.let(personMatches::remove)
            personMatches[personKey] = Triple(content, fetchedAt, matches)
        }
        return matches
    }

    /** The catalogue's titles each person opened has, for the catalogue they were matched in. */
    private val personMatches = java.util.concurrent.ConcurrentHashMap<String, Triple<PlaylistContent, Long, List<TrendingMatch>>>()

    companion object {
        const val REFRESH_AFTER_MS = 24 * 60 * 60 * 1000L
        const val HOUR_MS = 60 * 60 * 1000L

        /** The choices of automatic refresh, in hours (0: only when asked), as on Apple devices. */
        val AUTO_REFRESH_CHOICES = listOf(0, 6, 12, 24, 168)

        /** A guide kept on the device is used for this long before it is downloaded again (as on Apple devices). */
        const val GUIDE_FRESH_MS = 6 * 60 * 60 * 1000L

        /** TMDb's trending titles are asked for again after this (as on Apple devices). */
        const val TRENDING_FRESH_MS = 6 * 60 * 60 * 1000L
        private const val DETAILS_SAVE_DELAY_MS = 2_000L

        /** Titles asked of TMDb ahead of the viewer at the same time, and the pause after each (as on Apple devices). */
        private const val MAX_PREFETCHING = 2
        private const val PACE_MS = 250L
        private const val LOCAL_PEOPLE_LIMIT = 8
        private const val PHOTO_CANDIDATES = 5

        /** A person's TMDb credits are asked again after a day; the last people opened are kept. */
        const val PERSON_CREDITS_FRESH_MS = 24 * 60 * 60 * 1000L
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        /** Merges in the background before giving up until the next session, when the data keeps changing meanwhile. */
        private const val MERGE_TRIES = 3

        /** A person's movies and series on TMDb, kept on the device, are asked again after a week. */
        const val PERSON_WORKS_FRESH_MS = 7 * 24 * 60 * 60 * 1000L
        private const val PEOPLE_SEARCHES_KEPT = 100
        private const val SMALL_GUIDE_BUDGET = 48L * 1024 * 1024

        /** Pages of a genre's most popular titles asked of TMDb (20 a page), for movies and for series. */
        private const val GENRE_PAGES = 10
        private const val GENRE_PAGE_SIZE = 20
        private const val PERSON_CREDITS_KEPT = 64

        fun hostName(url: String): String = runCatching { java.net.URI(url.trim()).host }.getOrNull()?.removePrefix("www.") ?: "Playlist"
    }
}

/** The series of the episode playing ([episodeId]), with its seasons in order ([OndoRepository.playingSeries]). */
data class PlayingSeries(val series: Series, val episodeId: String, val seasons: List<Season>)
