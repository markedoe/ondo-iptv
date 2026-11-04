package com.markedoe.ondo.ui

import androidx.lifecycle.viewModelScope
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.core.net.HttpClient
import com.markedoe.ondo.core.net.HttpException
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.data.SavedPlaylist
import com.markedoe.ondo.data.TmdbCheck
import com.markedoe.ondo.data.WatchEntry
import com.markedoe.ondo.player.UpNext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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

/**
 * An episode reads the same on every screen that names it: its own title (TMDb's name, else the provider's without the
 * series' name, tags and code, else "Episode N"), never the provider's whole name, and its still from TMDb where the
 * provider has none. Every surface is listed here: a new screen naming episodes is added to it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeSurfacesTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val folder = TemporaryFolder()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    private val models = mutableListOf<OndoViewModel>()

    @After
    fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private fun TestScope.settle() = repeat(20) { testScheduler.runCurrent() }

    private fun TestScope.settleUntil(done: () -> Boolean) {
        repeat(1_000) {
            if (done()) return
            settle()
            Thread.sleep(10)
        }
    }

    private val name = "|EN| Tom Clancy's Jack Ryan"

    /** Season 3 as the provider gives it: pictures for episodes 1 and 4 only, each named "<series> S03E0N". */
    private val seriesInfo = buildString {
        append("""{"info":{"name":"$name","tmdb_id":"73375"},"episodes":{"3":[""")
        append(
            (1..5).joinToString(",") { n ->
                val image = if (n == 1 || n == 4) ""","info":{"movie_image":"http://img.example.com/provider-$n.jpg"}""" else ""
                """{"id":"30$n","episode_num":$n,"title":"$name S03E0$n","container_extension":"mp4"$image}"""
            },
        )
        append("]}}")
    }

    private val tmdbSeason = """{"episodes":[{"episode_number":1,"name":"Falcon","still_path":"/tmdb-1.jpg"},""" +
        """{"episode_number":2,"name":"Paralysis","still_path":"/tmdb-2.jpg"},{"episode_number":3,"name":"Episode 3","still_path":"/tmdb-3.jpg"},""" +
        """{"episode_number":4,"name":"Ghosts","still_path":"/tmdb-4.jpg"},{"episode_number":5,"name":"Episode 5","still_path":null}]}"""

    @Test
    fun anEpisodeReadsTheSameOnEveryScreen() = runTest {
        val seasonRequests = mutableListOf<String>()
        val http = object : HttpClient {
            override suspend fun get(url: String, headers: Map<String, String>): ByteArray = when {
                "get_series_info" in url -> seriesInfo
                url.startsWith("https://api.themoviedb.org/3/authentication?") -> """{"success":true}"""
                url.startsWith("https://api.themoviedb.org/3/tv/73375?") -> """{"id":73375,"name":"Tom Clancy's Jack Ryan"}"""
                url.startsWith("https://api.themoviedb.org/3/tv/73375/season/3?") -> {
                    seasonRequests += url
                    tmdbSeason
                }
                else -> throw HttpException.BadStatus(404)
            }.encodeToByteArray()
        }
        val store = AppStore(folder.root)
        val shows = MediaCategory("s-shows", "Shows", ContentType.SERIES)
        store.savePlaylists(listOf(SavedPlaylist("x1", "Panel", "http://panel.example.com", username = "viewer", password = "example")))
        store.saveSettings(AppSettings(activePlaylistId = "x1"))
        val jackRyan = Series(id = "1", name = name, categoryId = shows.id, xtreamSeriesId = 1)
        store.saveContent("x1", PlaylistContent(series = listOf(jackRyan), seriesCategories = listOf(shows), fetchedAt = System.currentTimeMillis()))
        val repository = OndoRepository(store, http, backgroundScope, io = StandardTestDispatcher(testScheduler))
        val viewModel = OndoViewModel(repository, Labels(), computation = Dispatchers.Unconfined, minutes = flowOf(1_760_000_000_000L)).also { models += it }
        val series = viewModel.state.first { it.content.series.isNotEmpty() }.content.series.single()
        assertEquals(TmdbCheck.ACCEPTED, repository.checkAndSaveTmdbToken("GOOD"))

        // The page opens on season 3: TMDb's episodes of it are asked once the series' TMDb id is known.
        viewModel.wantSeason(series.id, 3)
        viewModel.loadSeries(series.id)
        // Empty until the provider's details arrive.
        fun page() = UiMappers.seriesDetail(viewModel.state.value, series, 3, null).seasons.singleOrNull()?.episodes.orEmpty()
        settleUntil { page().getOrNull(1)?.posterUrl?.contains("tmdb") == true }

        val titles = listOf("Falcon", "Paralysis", "Episode 3", "Ghosts", "Episode 5")
        // 1. The series' page.
        assertEquals(titles, page().map { it.title })
        assertEquals(
            listOf("http://img.example.com/provider-1.jpg", "https://image.tmdb.org/t/p/w300/tmdb-2.jpg", "https://image.tmdb.org/t/p/w300/tmdb-3.jpg", "http://img.example.com/provider-4.jpg", null),
            page().map { it.posterUrl },
        )
        viewModel.wantSeason(series.id, 3)
        settle()
        assertEquals("one request for the season, once a session", 1, seasonRequests.size)

        // 2. The player: its line under the series' name, its Episodes row and Up next.
        val second = page()[1]
        val request = repository.playRequest(series, second)!!
        assertEquals("S03E02 · Paralysis", request.subtitle)
        assertEquals(titles, repository.playingSeries(request)!!.seasons.single().episodes.map { it.title })
        assertEquals("S03E03 · Episode 3", UpNext.offer(repository.nextEpisode(request), leftMs = 1_000)!!.subtitle)
        assertTrue("Up next names the series without its tags", !UpNext.offer(repository.nextEpisode(request), leftMs = 1_000)!!.title.contains("|EN|"))

        // 3. Continue watching, a line kept before this change included.
        fun resumeLine(subtitle: String, episodeId: String?) = UiMappers.resume(
            WatchEntry("x1", ItemKind.SERIES, series.id, episodeId, name, subtitle, lastWatchedAt = 1, position = 600.0, duration = 3_000.0),
            viewModel.state.value,
        ).subtitle
        assertEquals("S03E02 · Paralysis", resumeLine("S03E02 · $name S03E02", "302"))
        assertEquals("S03E05 · Episode 5", resumeLine("S03E05 · $name S03E05", "305"))
        assertEquals("unknown episode: the kept line, cleaned", "S03E07 · Episode 7", resumeLine("S03E07 · $name S03E07", "999"))

        // 4. Search: Android's lists series, channels and people, never an episode's line (nothing to clean there).
    }
}
