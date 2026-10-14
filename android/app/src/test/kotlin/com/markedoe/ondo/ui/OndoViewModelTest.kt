package com.markedoe.ondo.ui

import app.cash.turbine.test
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.FakeHttp
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.ui.screens.PlaylistForm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class OndoViewModelTest {
    @get:Rule val folder = TemporaryFolder()

    private val list = "http://provider.example.com/list.m3u"
    private val catalogue = """
        #EXTM3U
        #EXTINF:-1 tvg-id="news.test" group-title="News",Test News
        http://provider.example.com/news.m3u8
        #EXTINF:6000 group-title="Movies | Drama",Long Road (2025)
        http://provider.example.com/road.mp4
        #EXTINF:2700 group-title="Series | Drama",Harbor (2025) S01E01
        http://provider.example.com/h101.mp4
    """.trimIndent()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): OndoViewModel {
        // The demo address answers with the same fictional catalogue: no network in tests.
        val http = FakeHttp(mapOf(list to catalogue, OndoViewModel.DEMO_PLAYLIST_URL to catalogue))
        val repository = OndoRepository(AppStore(folder.root), http, backgroundScope, io = StandardTestDispatcher(testScheduler))
        return OndoViewModel(repository, Labels(), computation = Dispatchers.Unconfined, minutes = flowOf(1_760_000_000_000L))
    }

    @Test
    fun theDemoAddsAPlaylistAndTheHomeFollows() = runTest {
        val viewModel = viewModel()
        viewModel.state.first { it.isReady }
        viewModel.addDemo("Demo")
        val state = viewModel.state.first { it.content.movies.isNotEmpty() }
        assertEquals(OndoViewModel.DEMO_PLAYLIST_URL, state.activePlaylist?.url)
        viewModel.home.test {
            var home = awaitItem()
            while (home == null || home.hero.isEmpty()) home = awaitItem()
            assertEquals("Demo", home.playlistName)
            assertEquals(listOf("Long Road"), home.newMovies.map { it.title })
            assertEquals(listOf("Harbor"), home.newSeries.map { it.title })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun searchWaitsForTypingToPause() = runTest {
        val viewModel = viewModel()
        viewModel.state.first { it.isReady }
        viewModel.addPlaylist(PlaylistForm.M3u("Home", list, "")) {}
        viewModel.state.first { it.content.movies.isNotEmpty() }
        viewModel.search.test {
            assertTrue(awaitItem().movies.isEmpty())
            viewModel.setQuery("lo")
            viewModel.setQuery("long r")
            val result = awaitItem()
            assertEquals("long r", result.query)
            assertEquals(listOf("Long Road"), result.movies.map { it.title })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun playingAMovieSendsAPlayRequest() = runTest {
        val viewModel = viewModel()
        viewModel.state.first { it.isReady }
        viewModel.addPlaylist(PlaylistForm.M3u("Home", list, "")) {}
        val movie = viewModel.state.first { it.content.movies.isNotEmpty() }.content.movies.single()
        viewModel.playRequests.test {
            viewModel.playMovie(movie.id)
            val request = awaitItem()
            assertEquals("Long Road", request.title)
            assertEquals(false, request.isLive)
            assertEquals("http://provider.example.com/road.mp4", request.urls.first())
        }
    }
}
