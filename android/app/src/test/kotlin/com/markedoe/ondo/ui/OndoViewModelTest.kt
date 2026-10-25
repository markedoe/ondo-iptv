package com.markedoe.ondo.ui

import app.cash.turbine.test
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.core.net.HttpClient
import com.markedoe.ondo.core.net.HttpException
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.FakeHttp
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.data.SavedPlaylist
import com.markedoe.ondo.data.TmdbCheck
import com.markedoe.ondo.ui.components.HeroPage
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.HomeUiState
import com.markedoe.ondo.ui.screens.PlaylistForm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A provider's server answering series details, once [gate] opens; it counts the requests and how many run at once. */
private class Panel(private val gate: CompletableDeferred<Unit>? = null) : HttpClient {
    val seriesRequests = mutableListOf<String>()
    var mostAtOnce = 0
    private var running = 0

    override suspend fun get(url: String, headers: Map<String, String>): ByteArray {
        if ("get_series_info" !in url) throw HttpException.BadStatus(404)
        seriesRequests += url
        running++
        mostAtOnce = maxOf(mostAtOnce, running)
        try {
            gate?.await()
            yield()
        } finally {
            running--
        }
        return """{"info":{"name":"Show"},"episodes":{"1":[{"id":"101","episode_num":1,"title":"Pilot","container_extension":"mp4"}]}}""".encodeToByteArray()
    }
}

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

    /** Runs what is due now, the repository's work in [TestScope.backgroundScope] too (which advanceUntilIdle leaves). */
    private fun TestScope.settle() = repeat(20) { testScheduler.runCurrent() }

    private fun TestScope.viewModel(): OndoViewModel {
        // The demo address answers with the same fictional catalogue: no network in tests.
        val http = FakeHttp(mapOf(list to catalogue, OndoViewModel.DEMO_PLAYLIST_URL to catalogue))
        val repository = OndoRepository(AppStore(folder.root), http, backgroundScope, io = StandardTestDispatcher(testScheduler))
        return OndoViewModel(repository, Labels(), computation = Dispatchers.Unconfined, minutes = flowOf(1_760_000_000_000L))
    }

    /** A provider's playlist of six series, saved earlier (restored without downloading). */
    private fun TestScope.providerViewModel(panel: Panel): OndoViewModel {
        val store = AppStore(folder.root)
        val shows = MediaCategory("s-shows", "Shows", ContentType.SERIES)
        store.savePlaylists(listOf(SavedPlaylist("x1", "Panel", "http://panel.example.com", username = "viewer", password = "example")))
        store.saveSettings(AppSettings(activePlaylistId = "x1"))
        store.saveContent(
            "x1",
            PlaylistContent(
                series = (1..6).map { Series(id = "$it", name = "Show $it", categoryId = shows.id, year = 2025, xtreamSeriesId = it) },
                seriesCategories = listOf(shows),
                fetchedAt = System.currentTimeMillis(),
            ),
        )
        val repository = OndoRepository(store, panel, backgroundScope, io = StandardTestDispatcher(testScheduler))
        return OndoViewModel(repository, Labels(), computation = Dispatchers.Unconfined, minutes = flowOf(1_760_000_000_000L))
    }

    /**
     * A movie page without a backdrop that loads: with the viewer's TMDb token, TMDb's shows instead, found by title and
     * year (once a session); a provider's backdrop that fails gives way to it too.
     */
    @Test
    fun aTitleWithoutABackdropThatLoadsGetsTmdbsWithTheToken() = runTest {
        val searches = mutableListOf<String>()
        val http = object : HttpClient {
            override suspend fun get(url: String, headers: Map<String, String>): ByteArray = when {
                url == list -> catalogue
                url.startsWith("https://api.themoviedb.org/3/authentication?") -> """{"success":true}"""
                url.startsWith("https://api.themoviedb.org/3/search/movie?") -> {
                    searches += url
                    """{"results":[{"id":9,"title":"Long Road","release_date":"2025-03-01","backdrop_path":"/road.jpg"}]}"""
                }
                else -> throw HttpException.BadStatus(404)
            }.encodeToByteArray()
        }
        val repository = OndoRepository(AppStore(folder.root), http, backgroundScope, io = StandardTestDispatcher(testScheduler))
        val viewModel = OndoViewModel(repository, Labels(), computation = Dispatchers.Unconfined, minutes = flowOf(1_760_000_000_000L))
        repository.addPlaylist("Home", list)
        val movie = viewModel.state.first { it.content.movies.isNotEmpty() }.content.movies.single()
        val item = ShowcaseItem.MovieItem(movie)

        // Without a token, nothing is asked of TMDb.
        viewModel.loadMovie(movie.id)
        settle()
        assertTrue(searches.isEmpty())
        assertNull(UiMappers.movieDetail(viewModel.state.value, movie).backdropUrl)

        assertEquals(TmdbCheck.ACCEPTED, repository.checkAndSaveTmdbToken("GOOD"))
        viewModel.loadMovie(movie.id)
        settle()
        val tmdb = "https://image.tmdb.org/t/p/w1280/road.jpg"
        assertEquals(tmdb, UiMappers.movieDetail(viewModel.state.value, movie).backdropUrl)
        assertTrue("by title (${searches.single()})", Regex("query=long(%20|\\+)road", RegexOption.IGNORE_CASE).containsMatchIn(searches.single()))
        // Once a session.
        viewModel.loadMovie(movie.id)
        settle()
        assertEquals(1, searches.size)

        // TMDb's picture failing too leaves the blurred poster, and it is not asked for again.
        viewModel.backdropFailed(item, tmdb)
        settle()
        assertTrue(tmdb in viewModel.state.value.deadBackdrops)
        assertNull(UiMappers.movieDetail(viewModel.state.value, movie).backdropUrl)
        assertEquals(1, searches.size)
    }

    @Test
    fun playOnASeriesShowsProgressAndASecondTapDoesNotPlayTwice() = runTest {
        val gate = CompletableDeferred<Unit>()
        val panel = Panel(gate)
        val viewModel = providerViewModel(panel)
        val item = ShowcaseItem.SeriesItem(viewModel.state.first { it.content.series.isNotEmpty() }.content.series.first())
        viewModel.playRequests.test {
            viewModel.play(item)
            assertEquals("its Play shows progress", item.key, viewModel.preparing.value)
            viewModel.play(item)
            gate.complete(Unit)
            assertEquals("http://panel.example.com/series/viewer/example/101.mp4", awaitItem().urls.first())
            assertEquals(null, viewModel.preparing.first { it == null })
            assertEquals(1, panel.seriesRequests.size)
            expectNoEvents()
        }
    }

    @Test
    fun homeFetchesAFewSeriesAheadOneAtATime() = runTest {
        val panel = Panel()
        val viewModel = providerViewModel(panel)
        val series = viewModel.state.first { it.content.series.isNotEmpty() }.content.series
        val home = HomeUiState("Panel", hero = series.map { HeroPage(ShowcaseItem.SeriesItem(it), "", inMyList = false, resumable = false) })
        viewModel.prefetchSeries(home)
        assertEquals(OndoViewModel.PREFETCH_LIMIT, panel.seriesRequests.size)
        assertEquals(1, panel.mostAtOnce)
        assertEquals(series.take(OndoViewModel.PREFETCH_LIMIT).map { it.id }.toSet(), viewModel.state.value.seriesDetails.keys)
        // Already known: not asked again.
        viewModel.prefetchSeries(home)
        assertEquals(OndoViewModel.PREFETCH_LIMIT, panel.seriesRequests.size)
    }

    /**
     * A series page too large for the memory left: the HTTP client hands the OutOfMemoryError to its caller (it would
     * stop the app on OkHttp's thread). Fetching ahead, which Home starts by itself, gives up on that series.
     */
    @Test
    fun homeFetchingAheadSurvivesASeriesPageTooLargeForTheMemory() = runTest {
        val panel = object : HttpClient {
            override suspend fun get(url: String, headers: Map<String, String>): ByteArray = throw OutOfMemoryError("Failed to allocate")
        }
        val store = AppStore(folder.root)
        val shows = MediaCategory("s-shows", "Shows", ContentType.SERIES)
        store.savePlaylists(listOf(SavedPlaylist("x1", "Panel", "http://panel.example.com", username = "viewer", password = "example")))
        store.saveSettings(AppSettings(activePlaylistId = "x1"))
        store.saveContent("x1", PlaylistContent(series = listOf(Series(id = "1", name = "Show", categoryId = shows.id, year = 2025, xtreamSeriesId = 1)), seriesCategories = listOf(shows), fetchedAt = System.currentTimeMillis()))
        val repository = OndoRepository(store, panel, backgroundScope, io = StandardTestDispatcher(testScheduler))
        val viewModel = OndoViewModel(repository, Labels(), computation = Dispatchers.Unconfined, minutes = flowOf(1_760_000_000_000L))
        val series = viewModel.state.first { it.content.series.isNotEmpty() }.content.series
        val home = HomeUiState("Panel", hero = series.map { HeroPage(ShowcaseItem.SeriesItem(it), "", inMyList = false, resumable = false) })
        viewModel.prefetchSeries(home)
        assertTrue(viewModel.state.value.seriesDetails.isEmpty())
    }

    @Test
    fun leavingHomeStopsFetchingAhead() = runTest {
        val gate = CompletableDeferred<Unit>()
        val panel = Panel(gate)
        val viewModel = providerViewModel(panel)
        val series = viewModel.state.first { it.content.series.isNotEmpty() }.content.series
        val home = HomeUiState("Panel", hero = series.map { HeroPage(ShowcaseItem.SeriesItem(it), "", inMyList = false, resumable = false) })
        val prefetch = launch { viewModel.prefetchSeries(home) }
        testScheduler.advanceUntilIdle()
        assertEquals(1, panel.seriesRequests.size)
        prefetch.cancel()
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals("no request after leaving", 1, panel.seriesRequests.size)
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

    /** A Live TV category chosen on one playlist does not follow the viewer to another one, nor outlive its category. */
    @Test
    fun aLiveCategoryChosenOnOnePlaylistDoesNotFollowToTheNext() = runTest {
        val other = "http://other.example.com/list.m3u"
        val otherList = "#EXTM3U\n#EXTINF:-1 group-title=\"News\",Other News\nhttp://other.example.com/news.m3u8\n" +
            "#EXTINF:-1 group-title=\"Sport\",Other Sport\nhttp://other.example.com/sport.m3u8\n"
        val repository = OndoRepository(AppStore(folder.root), FakeHttp(mapOf(list to catalogue, other to otherList)), backgroundScope, io = StandardTestDispatcher(testScheduler))
        val viewModel = OndoViewModel(repository, Labels(), computation = Dispatchers.Unconfined, minutes = flowOf(1_760_000_000_000L))
        repository.bootstrap()
        viewModel.state.first { it.isReady }
        assertTrue(repository.addPlaylist("Home", list).isSuccess)
        val home = viewModel.state.value.activePlaylistId
        val news = viewModel.state.value.content.liveCategories.single { it.name == "News" }.id
        viewModel.setLiveFilter(LiveFilter(categoryId = news))
        assertEquals(news, viewModel.live.first { it?.selectedCategoryId == news }?.selectedCategoryId)

        assertTrue(repository.addPlaylist("Other", other).isSuccess)
        val shown = viewModel.live.first { it != null && viewModel.state.value.activePlaylistId != home && it.channels.size + it.categories.size > 0 }!!
        assertEquals("all channels, as on a playlist just opened", null, shown.selectedCategoryId)
        assertEquals(2, shown.channels.size)
        // A category that is gone (hidden, or another playlist's): all channels, none selected.
        val gone = UiMappers.live(viewModel.state.value, "gone", favoritesOnly = false, now = 0)
        assertEquals(null, gone.selectedCategoryId)
        assertEquals(2, gone.channels.size)
    }
}
