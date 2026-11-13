package com.markedoe.ondo.ui

import androidx.lifecycle.viewModelScope
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.FakeHttp
import com.markedoe.ondo.data.OndoRepository
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Movies tab worked out ahead once Home is whole: watched only until it is, never for good. The catalogue replaced
 * while it is worked out (a download that ends during playback, Home not on screen): the watch ends all the same, and
 * the tab is no longer worked out at each change of the state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TabsWorkedOutAheadTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val folder = TemporaryFolder()

    /** The playlist as the provider gives it again: another catalogue than the one saved. */
    private val downloaded = "#EXTM3U\n#EXTINF:-1 group-title=\"Movies | Drama\",Long Road (2025)\nhttp://example.com/movie/road.mp4\n"

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(20)
        }
        return true
    }

    @Test
    fun aCatalogueReplacedWhileTheMoviesTabIsWorkedOutEndsItsWatch() {
        val store = AppStore(folder.root)
        store.savePlaylists(listOf(Fixtures.playlist))
        store.saveSettings(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID))
        store.saveContent(Fixtures.PLAYLIST_ID, Fixtures.content.copy(fetchedAt = System.currentTimeMillis()))
        // The view model's clock is read as the Movies tab is worked out: its first working out waits there.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val clock = {
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
            Fixtures.NOW
        }
        val executor = Executors.newSingleThreadExecutor { Thread(it, "main").apply { isDaemon = true } }
        val main = executor.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val watching = CoroutineScope(SupervisorJob() + main)
        var model: OndoViewModel? = null
        try {
            val repository = OndoRepository(store, FakeHttp(mapOf(Fixtures.playlist.url to downloaded)), scope, metadata = Fixtures.metadata())
            val viewModel = runBlocking(main) { OndoViewModel(repository, Labels(), clock = clock, minutes = flowOf(Fixtures.NOW)) }
            model = viewModel
            val home = watching.launch { viewModel.home.collect {} }
            assertTrue("the Movies tab is worked out ahead once Home is whole", entered.await(20, TimeUnit.SECONDS))
            assertEquals("the Movies tab watched", 1, viewModel.tabsWatched.get())
            val saved = viewModel.state.value.content
            // Home left (the player in front): its state is no longer worked out, five seconds after.
            home.cancel()
            Thread.sleep(6_000)
            // The catalogue downloaded again meanwhile.
            runBlocking(main) { repository.refresh() }
            assertTrue("another catalogue", waitUntil(20_000) { viewModel.state.value.content !== saved && !viewModel.state.value.isLoading })
            release.countDown()
            assertTrue("the watch ends: ${viewModel.tabsWatched.get()} tab(s) watched", waitUntil(10_000) { viewModel.tabsWatched.get() == 0 })
            // No one watches the tab: it stops five seconds after, and is no longer worked out at each change.
            Thread.sleep(6_000)
            val workedOut = viewModel.moviesComputations.get()
            runBlocking(main) { viewModel.setHideWatchedMovies(true) }
            Thread.sleep(1_000)
            assertEquals("the Movies tab not worked out again", workedOut, viewModel.moviesComputations.get())
        } finally {
            release.countDown()
            watching.cancel()
            model?.let { runBlocking(main) { it.viewModelScope.cancel() } }
            scope.cancel()
            Dispatchers.resetMain()
            main.close()
        }
    }
}
