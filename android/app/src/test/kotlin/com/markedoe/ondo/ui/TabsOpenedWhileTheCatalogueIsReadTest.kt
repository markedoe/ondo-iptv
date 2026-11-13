package com.markedoe.ondo.ui

import androidx.lifecycle.viewModelScope
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.FakeHttp
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.data.WatchEntry
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.ui.screens.VodUiState
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Home kept from the last start shows at once, so the viewer can go to the Movies or the Series tab while the saved
 * catalogue is still read (a TV with a large one). That tab then shows its titles to continue watching, each with its
 * title for the TV's header (the one Home kept, its year included), not an empty header. Home left more than five
 * seconds before the catalogue is read is no longer worked out, yet the other tab is still worked out in the background
 * once the catalogue is: its first opening is at once, the same for Movies and Series.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TabsOpenedWhileTheCatalogueIsReadTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val folder = TemporaryFolder()

    private val labels = Labels(locale = Locale.ENGLISH)

    /** A film and an episode in progress: one card to continue watching on each tab. */
    private val library = LibraryState(
        history = listOf(
            WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, Fixtures.northernLights.id, title = Fixtures.northernLights.name, position = 600.0, duration = 6_000.0, lastWatchedAt = Fixtures.NOW - 1_000L),
            WatchEntry(
                Fixtures.PLAYLIST_ID, ItemKind.SERIES, Fixtures.harborLights.id, "${Fixtures.harborLights.id}-1-2", Fixtures.harborLights.name,
                position = 300.0, duration = 2_400.0, lastWatchedAt = Fixtures.NOW - 2_000L,
            ),
        ),
    )

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(20)
        }
        return true
    }

    /** The app's store, saved once with the catalogue and the library; [open] reads its documents. */
    private fun store(open: (File) -> InputStream = File::inputStream): AppStore {
        val directory = folder.root.resolve("app")
        val saved = directory.resolve("content-${Fixtures.PLAYLIST_ID}.json").isFile
        val store = AppStore(directory, open = open)
        if (!saved) {
            store.savePlaylists(listOf(Fixtures.playlist))
            store.saveSettings(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID))
            store.saveContent(Fixtures.PLAYLIST_ID, Fixtures.content.copy(fetchedAt = System.currentTimeMillis()))
            store.saveLibrary(library)
        }
        return store
    }

    /** A start of the app on [store]: [run] with its view model, then everything stopped. */
    private fun <T> started(store: AppStore, run: (OndoViewModel, CoroutineScope) -> T): T {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "main").apply { isDaemon = true } }
        val main = executor.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val watching = CoroutineScope(SupervisorJob() + main)
        var model: OndoViewModel? = null
        try {
            val repository = OndoRepository(store, FakeHttp(emptyMap()), scope, metadata = Fixtures.metadata())
            val viewModel = runBlocking(main) { OndoViewModel(repository, labels, minutes = flowOf(Fixtures.NOW)) }
            model = viewModel
            return run(viewModel, watching)
        } finally {
            watching.cancel()
            model?.let { runBlocking(main) { it.viewModelScope.cancel() } }
            scope.cancel()
            Dispatchers.resetMain()
            main.close()
            executor.shutdownNow()
        }
    }

    /** A first start: Home worked out with its catalogue and kept for the next one. */
    private fun keepHome() {
        val kept = folder.root.resolve("app/home-${Fixtures.PLAYLIST_ID}.json")
        if (kept.isFile) return
        started(store()) { viewModel, watching ->
            watching.launch { viewModel.home.collect {} }
            assertTrue("Home kept for the next start", waitUntil(20_000) { kept.isFile })
        }
    }

    private fun tab(viewModel: OndoViewModel, series: Boolean): StateFlow<VodUiState?> = if (series) viewModel.series else viewModel.movies

    /** Every tab with a header above its rows: a new one is added here. */
    private val surfaces = mapOf("Movies" to false, "Series" to true)

    @Test
    fun aTabOpenedWhileTheCatalogueIsReadShowsItsTitlesAndTheOtherTabIsWorkedOutAfter() {
        for ((name, series) in surfaces) {
            val other = surfaces.entries.first { it.value != series }.key
            keepHome()
            val gate = CountDownLatch(1)
            val gated = store(open = { file ->
                if (file.name.startsWith("content-")) gate.await(30, TimeUnit.SECONDS)
                file.inputStream()
            })
            try {
                started(gated) { viewModel, watching ->
                    // Home kept, at once.
                    val home = watching.launch { viewModel.home.collect {} }
                    assertTrue("$name: Home kept is shown", waitUntil(10_000) { viewModel.savedHomesShown.get() > 0 })
                    assertTrue("$name: the catalogue is being read", viewModel.state.value.isRestoring)

                    // The viewer goes to the tab before the catalogue is read.
                    home.cancel()
                    watching.launch { tab(viewModel, series).collect {} }
                    assertTrue("$name: the tab shows", waitUntil(10_000) { tab(viewModel, series).value != null })
                    val opened = tab(viewModel, series).value!!
                    assertTrue("$name: its title to continue watching", opened.continueWatching.isNotEmpty())
                    for (card in opened.continueWatching) {
                        val title = card.showcase
                        assertTrue("$name: ${card.title} has its title for the header", title != null)
                        assertTrue("$name: ${card.title} as Home kept it, its year included: $title", title!!.year != null)
                    }

                    // Home left for more than five seconds: it is no longer worked out. The catalogue is read then.
                    Thread.sleep(6_000)
                    gate.countDown()
                    assertTrue("$name: the catalogue read", waitUntil(20_000) { !viewModel.state.value.isRestoring && viewModel.state.value.content.movies.isNotEmpty() })
                    assertTrue("$name: the tab with the catalogue's rows", waitUntil(20_000) { tab(viewModel, series).value?.all?.isNotEmpty() == true })

                    // The other tab, not opened yet, worked out in the background all the same.
                    assertTrue(
                        "$other: worked out in the background once the catalogue is read, the viewer on $name",
                        waitUntil(20_000) { tab(viewModel, !series).value?.all?.isNotEmpty() == true },
                    )
                }
            } finally {
                gate.countDown()
            }
        }
    }

    /**
     * Once the catalogue is read, a title to continue watching that has left it has no title for the header (as
     * before): nothing of it is shown or fetched. While the catalogue is read, every card has one.
     */
    @Test
    fun aTitleGoneFromTheReadCatalogueHasNoTitleForTheHeader() {
        val gone = LibraryState(
            history = listOf(
                WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, "gone-movie", title = "Gone Film", position = 600.0, duration = 6_000.0, lastWatchedAt = Fixtures.NOW - 1_000L),
                WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.SERIES, "gone-series", "gone-series-1-1", "Gone Series", position = 300.0, duration = 2_400.0, lastWatchedAt = Fixtures.NOW - 2_000L),
            ),
        )
        val read = Fixtures.state(library = gone)
        val reading = read.copy(content = PlaylistContent(), seriesDetails = emptyMap(), isRestoring = true)
        for ((name, series) in surfaces) {
            val whole = UiMappers.vod(read, series = series, title = name, now = Fixtures.NOW)
            assertTrue("$name: the card still listed", whole.continueWatching.isNotEmpty())
            assertTrue("$name: no title for a title gone: ${whole.continueWatching.map { it.showcase }}", whole.continueWatching.all { it.showcase == null })
            val opened = UiMappers.vod(reading, series = series, title = name, now = Fixtures.NOW)
            assertTrue("$name: the card while the catalogue is read", opened.continueWatching.isNotEmpty())
            assertTrue("$name: its title while the catalogue is read", opened.continueWatching.all { it.showcase != null })
        }
    }

    /**
     * A playlist of channels only: what the device kept of TMDb is the same empty map for every catalogue. The tab open
     * (Home not), the catalogue read: the tab is worked out with it, so the other one is worked out after it and no tab
     * is watched for nothing until the minute's limit.
     */
    @Test
    fun aPlaylistOfChannelsOnlyEndsTheTabsWatchAtOnce() {
        val store = AppStore(folder.root.resolve("channels"))
        store.savePlaylists(listOf(Fixtures.playlist))
        store.saveSettings(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID))
        store.saveContent(Fixtures.PLAYLIST_ID, Fixtures.content.copy(movies = emptyList(), series = emptyList(), fetchedAt = System.currentTimeMillis()))
        for ((name, series) in surfaces) {
            started(store) { viewModel, watching ->
                watching.launch { tab(viewModel, series).collect {} }
                assertTrue("$name: the catalogue read", waitUntil(20_000) { !viewModel.state.value.isRestoring && viewModel.state.value.content.channels.isNotEmpty() })
                assertTrue("$name: the other tab worked out after it", waitUntil(15_000) { tab(viewModel, !series).value != null })
                assertTrue("$name: no tab watched for nothing: ${viewModel.tabsWatched.get()}", waitUntil(15_000) { viewModel.tabsWatched.get() == 0 })
            }
        }
    }
}
