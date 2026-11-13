package com.markedoe.ondo.ui

import androidx.lifecycle.viewModelScope
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.Profile
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.core.sync.SyncRows
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.Favorite
import com.markedoe.ondo.data.FakeHttp
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.data.SavedPlaylist
import com.markedoe.ondo.data.WatchEntry
import com.markedoe.ondo.player.PlayRequest
import com.markedoe.ondo.ui.model.ChannelRow
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.HomeUiState
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Home kept for the next start ([HomeSnapshots]): shown at a cold start only for the same playlist and profile and
 * nothing else Home is made of changed; never another profile's titles in progress or My List; a title it shows that is
 * no longer in the catalogue plays nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeSnapshotTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val folder = TemporaryFolder()

    private val labels = Labels(locale = Locale.ENGLISH)
    private val me = Profile.main("Me")
    private val kids = Profile(id = "22222222-2222-2222-2222-222222222222", name = "Kids", isKids = true)
    private val other = SavedPlaylist(id = "p2", name = "Other", url = "http://example.com/other.m3u", addedAt = Fixtures.NOW)

    /** Two films in progress and one in My List: the main profile's. */
    private val mine = LibraryState(
        history = listOf(Fixtures.northernLights, Fixtures.glassCity).mapIndexed { index, movie ->
            WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, movie.id, title = movie.name, position = 600.0, duration = 6_000.0, lastWatchedAt = Fixtures.NOW - 1_000L - index)
        },
        favorites = listOf(Favorite(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, Fixtures.lastSignal.id, Fixtures.lastSignal.name, addedAt = Fixtures.NOW - 5_000L)),
    )

    private val state: OndoState = Fixtures.state(library = mine).copy(profiles = listOf(me, kids), playlists = listOf(Fixtures.playlist, other))

    private fun snapshotOf(state: OndoState) = requireNotNull(HomeSnapshots.of(UiMappers.home(state, labels, Fixtures.NOW, Fixtures.kept()), state, labels))

    @Test
    fun theSameStateMatchesAndDrawsTheSameHome() {
        val home = UiMappers.home(state, labels, Fixtures.NOW, Fixtures.kept())
        assertTrue("a carousel to keep", home.hero.isNotEmpty())
        assertTrue("titles in progress to keep", home.continueWatching.isNotEmpty())
        val snapshot = requireNotNull(HomeSnapshots.of(home, state, labels)) { "a playlist in use: a Home to keep" }
        assertTrue(HomeSnapshots.matches(snapshot, state, labels))
        val drawn = HomeSnapshots.home(snapshot, state.copy(isRestoring = true), Fixtures.NOW)
        assertEquals(home.hero.map { it.item.key }, drawn.hero.map { it.item.key })
        assertEquals(home.hero.map { it.meta }, drawn.hero.map { it.meta })
        assertEquals(home.continueWatching.map { it.key }, drawn.continueWatching.map { it.key })
        assertEquals(home.myList.map { it.key }, drawn.myList.map { it.key })
        assertEquals(home.newMovies.map { it.key }, drawn.newMovies.map { it.key })
        assertEquals(home.newSeries.map { it.key }, drawn.newSeries.map { it.key })
        assertEquals(home.shownRows, drawn.shownRows)
        assertEquals(home.channels.map { it.channel.id }, drawn.channels.map { it.channel.id })
        assertTrue("drawn while the catalogue is read", drawn.isRestoring)
    }

    @Test
    fun keptAndReadBackAsItWas() {
        val snapshot = snapshotOf(state)
        val store = AppStore(folder.root)
        store.saveHomeSnapshot(snapshot)
        assertEquals(snapshot, store.loadHomeSnapshot(Fixtures.PLAYLIST_ID))
        assertNull("another playlist's", store.loadHomeSnapshot(other.id))
        store.deleteHomeSnapshot(Fixtures.PLAYLIST_ID)
        assertNull("gone with its playlist", store.loadHomeSnapshot(Fixtures.PLAYLIST_ID))
    }

    /** Each change that makes Home another one: the kept one is not shown. */
    @Test
    fun anotherProfilePlaylistHiddenCategoryLanguageOrRowOrderLeavesItUnused() {
        val snapshot = snapshotOf(state)
        val changes = mapOf(
            "another profile" to state.copy(settings = state.settings.copy(activeProfileId = kids.id), library = LibraryState()),
            "another profile with the same lists" to state.copy(settings = state.settings.copy(activeProfileId = kids.id)),
            "another playlist" to state.copy(settings = state.settings.copy(activePlaylistId = other.id)),
            "a category hidden" to state.copy(library = mine.copy(hiddenCategories = setOf("${Fixtures.PLAYLIST_ID}|${Fixtures.drama.id}"))),
            "a language hidden" to state.copy(library = mine.copy(hiddenTags = setOf("FR"))),
            "a word hidden" to state.copy(library = mine.copy(hiddenWords = setOf("harbor"))),
            "the rows' order" to state.copy(library = mine.keepingRowOrder(SyncRows("home", order = listOf("newMovies"), updatedAt = Fixtures.NOW))),
            "adult categories shown" to state.copy(settings = state.settings.copy(showAdultCategories = true)),
            "a title in progress more" to state.copy(library = mine.copy(history = mine.history + WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, Fixtures.longRoad.id, title = Fixtures.longRoad.name, position = 60.0, duration = 6_000.0, lastWatchedAt = Fixtures.NOW))),
            "My List changed" to state.copy(library = mine.copy(favorites = emptyList())),
        )
        for ((change, changed) in changes) {
            assertFalse("$change: Home kept is not shown", HomeSnapshots.matches(snapshot, changed, labels))
        }
        assertFalse("another language of the app", HomeSnapshots.matches(snapshot, state, Labels(locale = Locale.FRENCH)))
        assertTrue("the same again", HomeSnapshots.matches(snapshot, state.copy(), labels))
    }

    // The view model, on a store as the app keeps it.

    private fun store(launchProfile: String): AppStore {
        val store = AppStore(folder.root.resolve("app"))
        store.savePlaylists(listOf(Fixtures.playlist))
        store.saveSettings(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID, launchProfileId = launchProfile))
        store.saveProfiles(listOf(me, kids))
        if (store.loadContent(Fixtures.PLAYLIST_ID) == null) {
            store.saveContent(Fixtures.PLAYLIST_ID, Fixtures.content.copy(fetchedAt = System.currentTimeMillis()))
            store.saveLibrary(mine)
        }
        return store
    }

    /** What one start showed: each Home, and how many of them were the one kept. */
    private class Start(val homes: List<HomeUiState>, val savedHomesShown: Int, val requests: List<PlayRequest>)

    /**
     * Ondo started on [store]: a repository and a view model, Home watched as the screen does, until Home was worked out
     * with its carousel and kept for the next start; then [act] on the view model.
     */
    private fun start(store: AppStore, act: (OndoViewModel) -> Unit = {}): Start {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "main").apply { isDaemon = true } }
        val main = executor.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val watching = CoroutineScope(SupervisorJob() + main)
        var model: OndoViewModel? = null
        try {
            val repository = OndoRepository(store, FakeHttp(emptyMap()), scope, mainProfileName = "Me", metadata = Fixtures.metadata())
            val viewModel = runBlocking(main) { OndoViewModel(repository, labels, minutes = flowOf(Fixtures.NOW)) }
            model = viewModel
            val homes = Collections.synchronizedList(mutableListOf<HomeUiState>())
            val requests = Collections.synchronizedList(mutableListOf<PlayRequest>())
            watching.launch { viewModel.home.collect { home -> if (home != null) homes += home } }
            watching.launch { viewModel.playRequests.collect { requests += it } }
            val file = folder.root.resolve("app/home-${Fixtures.PLAYLIST_ID}.json")
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                val state = viewModel.state.value
                val worked = viewModel.homeComputations.get() > 0 && !state.isRestoring && state.content.movies.isNotEmpty() &&
                    homes.lastOrNull()?.hero?.isNotEmpty() == true
                if (worked && file.isFile) break
                Thread.sleep(20)
            }
            Thread.sleep(300)
            runBlocking(main) { act(viewModel) }
            Thread.sleep(300)
            return Start(homes.toList(), viewModel.savedHomesShown.get(), requests.toList())
        } finally {
            watching.cancel()
            model?.let { runBlocking(main) { it.viewModelScope.cancel() } }
            scope.cancel()
            Dispatchers.resetMain()
            main.close()
            executor.shutdownNow()
        }
    }

    /**
     * Home kept by the main profile, the next start opening the Kids profile: none of the main profile's titles in
     * progress or of its My List shows, not even while the catalogue is read. The same start on the main profile shows
     * the kept Home (the check that it is kept and used).
     */
    @Test
    fun continueWatchingNeverShowsAnotherProfiles() {
        val first = start(store(launchProfile = me.id))
        assertEquals("nothing kept before the first start", 0, first.savedHomesShown)
        val mineKeys = first.homes.last().continueWatching.map { it.key }.toSet()
        assertTrue("the main profile's titles in progress on its Home", mineKeys.isNotEmpty())
        val kept = requireNotNull(AppStore(folder.root.resolve("app")).loadHomeSnapshot(Fixtures.PLAYLIST_ID)) { "Home kept after the first start" }
        assertEquals("kept for the main profile", me.id, kept.profileId)

        val again = start(store(launchProfile = me.id))
        assertTrue("the main profile's next start shows Home kept", again.savedHomesShown > 0)
        val firstWithHero = again.homes.first { it.hero.isNotEmpty() }
        assertEquals("Home kept: its carousel and the main profile's titles in progress", mineKeys, firstWithHero.continueWatching.map { it.key }.toSet())

        val kidsStart = start(store(launchProfile = kids.id))
        assertEquals("Home kept by another profile is not shown", 0, kidsStart.savedHomesShown)
        for (home in kidsStart.homes) {
            assertTrue("no title in progress of the main profile: ${home.continueWatching.map { it.key }}", home.continueWatching.none { it.key in mineKeys })
            assertTrue("nothing of the main profile's My List: ${home.myList.map { it.key }}", home.myList.none { it.key == "m-" + Fixtures.lastSignal.id })
        }
    }

    /** A series or a channel Home kept from the last start shows, no longer in the catalogue: Play plays nothing. */
    @Test
    fun aTitleNoLongerInTheCataloguePlaysNothing() {
        start(store(launchProfile = me.id))
        val gone = Series(id = "gone", name = "Gone Series", categoryId = Fixtures.seriesDrama.id, xtreamSeriesId = 404)
        val goneChannel = Channel(id = "gone", name = "Gone Channel", categoryId = Fixtures.news.id, streamUrl = "http://example.com/live/gone.m3u8")
        val started = start(store(launchProfile = me.id)) { viewModel ->
            viewModel.play(ShowcaseItem.SeriesItem(gone))
            viewModel.playChannel(ChannelRow(goneChannel, null, null, null))
            assertNull("no series made ready", viewModel.preparing.value)
        }
        assertTrue("nothing played: ${started.requests}", started.requests.isEmpty())
        // A channel of the catalogue still plays.
        val channel = Fixtures.content.channels.first()
        val playing = start(store(launchProfile = me.id)) { viewModel -> viewModel.playChannel(ChannelRow(channel, null, null, null)) }
        assertEquals("the catalogue's channel plays", 1, playing.requests.size)
    }
}
