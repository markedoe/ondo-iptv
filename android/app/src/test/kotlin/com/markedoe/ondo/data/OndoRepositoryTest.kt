package com.markedoe.ondo.data

import com.markedoe.ondo.core.net.HttpClient
import com.markedoe.ondo.core.net.HttpException
import com.markedoe.ondo.ui.theme.Appearance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Serves fixed answers by URL; anything else is a 404. */
class FakeHttp(private val answers: Map<String, String>) : HttpClient {
    val requests = mutableListOf<Pair<String, Map<String, String>>>()

    override suspend fun get(url: String, headers: Map<String, String>): ByteArray {
        requests += url to headers
        return answers[url]?.encodeToByteArray() ?: throw HttpException.BadStatus(404)
    }
}

/** Playlists end to end: download, parse, persist, restore, library, playback requests. */
class OndoRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    private val list = "http://provider.example.com/list.m3u"
    private val catalogue = """
        #EXTM3U
        #EXTINF:-1 tvg-id="news.test" group-title="News",Test News
        http://provider.example.com/news.m3u8
        #EXTINF:5400 group-title="Movies | Drama",Quiet Water (2021)
        http://provider.example.com/quiet.mp4
        #EXTINF:6000 group-title="Movies | Drama",Long Road (2020)
        http://provider.example.com/road.mp4
        #EXTINF:2700 group-title="Series | Drama",Harbor S01E01
        http://provider.example.com/h101.mp4
        #EXTINF:2700 group-title="Series | Drama",Harbor S01E02
        http://provider.example.com/h102.mp4
    """.trimIndent()

    private val clock = 1_760_000_000_000L

    private fun repository(scope: CoroutineScope, http: HttpClient = FakeHttp(mapOf(list to catalogue))) =
        OndoRepository(AppStore(folder.root), http, scope, now = { clock })

    // runTest skips virtual time, so waits on real work (files, parsing) must not use withTimeout / delay.
    private suspend fun OndoRepository.awaitState(condition: (OndoState) -> Boolean): OndoState = state.first(condition)

    /** Saves run on a background thread: polls the disk in real time. */
    private suspend fun awaitDisk(condition: (AppStore) -> Boolean) = withContext(Dispatchers.IO) {
        val store = AppStore(folder.root)
        repeat(500) {
            if (condition(store)) return@withContext
            Thread.sleep(20)
        }
        error("the change never reached the disk")
    }

    @Test
    fun addingAPlaylistLoadsSavesAndActivatesIt() = runTest {
        val repository = repository(backgroundScope)
        repository.bootstrap()
        repository.awaitState { it.isReady }

        assertTrue(repository.addPlaylist("Home", list).isSuccess)
        val state = repository.state.value
        assertEquals("Home", state.activePlaylist?.name)
        assertEquals(listOf("Test News"), state.content.channels.map { it.name })
        assertEquals(setOf("Quiet Water", "Long Road"), state.content.movies.map { it.name }.toSet())
        assertEquals(listOf("Harbor"), state.content.series.map { it.name })
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun aRestartRestoresPlaylistsCatalogueAndLibraryWithoutDownloading() = runTest {
        val first = repository(backgroundScope)
        first.bootstrap()
        first.awaitState { it.isReady }
        first.addPlaylist("Home", list)
        val movie = first.state.value.content.movies.first()
        first.toggleFavorite(ItemKind.MOVIE, movie.id, movie.name, null)
        val request = first.playRequest(movie)!!
        first.recordProgress(request.progressKey!!, 600.0, 5_400.0)
        awaitDisk { it.loadLibrary().history.firstOrNull()?.position == 600.0 && it.loadLibrary().favorites.isNotEmpty() }

        val offline = FakeHttp(emptyMap())
        val second = repository(backgroundScope, offline)
        second.bootstrap()
        val restored = second.awaitState { it.isReady && it.content.movies.isNotEmpty() }
        assertEquals("Home", restored.activePlaylist?.name)
        assertTrue(restored.library.isFavorite(restored.activePlaylistId!!, ItemKind.MOVIE, movie.id))
        assertEquals(600.0, restored.library.continueWatching(restored.activePlaylistId!!).single().position, 0.0)
        assertTrue("the cached catalogue is fresh: no download", offline.requests.isEmpty())
    }

    @Test
    fun aFailedDownloadSavesNothingAndExplainsWhy() = runTest {
        val repository = repository(backgroundScope, FakeHttp(emptyMap()))
        repository.bootstrap()
        repository.awaitState { it.isReady }
        val result = repository.addPlaylist("Broken", list)
        assertTrue(result.isFailure)
        val state = repository.state.value
        assertTrue(state.playlists.isEmpty())
        assertNotNull(state.error)
        repository.clearError()
        assertNull(repository.state.value.error)
    }

    @Test
    fun resumingAMovieStartsWhereItStopped() = runTest {
        val repository = repository(backgroundScope)
        repository.bootstrap()
        repository.awaitState { it.isReady }
        repository.addPlaylist("Home", list)
        val movie = repository.state.value.content.movies.first { it.name == "Long Road" }
        val first = repository.playRequest(movie)!!
        assertEquals(0L, first.startPositionMs)
        assertEquals(listOf("http://provider.example.com/road.mp4"), first.urls.take(1))
        repository.recordProgress(first.progressKey!!, 1_200.0, 6_000.0)
        assertEquals(1_200_000L, repository.playRequest(movie)!!.startPositionMs)
        assertEquals(0L, repository.playRequest(movie, fromStart = true)!!.startPositionMs)
    }

    @Test
    fun episodesHaveTheirOwnProgress() = runTest {
        val repository = repository(backgroundScope)
        repository.bootstrap()
        repository.awaitState { it.isReady }
        repository.addPlaylist("Home", list)
        val series = repository.state.value.content.series.single()
        val details = repository.details(series)
        val (first, second) = details.seasons.single().episodes
        val request = repository.playRequest(series, first)!!
        repository.recordProgress(request.progressKey!!, 900.0, 2_700.0)
        assertEquals(900_000L, repository.playRequest(series, first)!!.startPositionMs)
        assertEquals(0L, repository.playRequest(series, second)!!.startPositionMs)
    }

    @Test
    fun deletingTheActivePlaylistSwitchesToAnother() = runTest {
        val other = "http://other.example.com/list.m3u"
        val repository = repository(backgroundScope, FakeHttp(mapOf(list to catalogue, other to catalogue)))
        repository.bootstrap()
        repository.awaitState { it.isReady }
        repository.addPlaylist("First", list)
        repository.addPlaylist("Second", other)
        val second = repository.state.value.activePlaylistId!!
        repository.delete(second)
        val state = repository.awaitState { it.activePlaylist?.name == "First" }
        assertEquals(listOf("First"), state.playlists.map { it.name })
    }

    @Test
    fun settingsArePersisted() = runTest {
        val repository = repository(backgroundScope)
        repository.bootstrap()
        repository.awaitState { it.isReady }
        repository.setShowAdultCategories(true)
        repository.setAppearance(Appearance.LIGHT)
        awaitDisk { it.loadSettings().appearance == Appearance.LIGHT }
        assertTrue(AppStore(folder.root).loadSettings().showAdultCategories)
    }

    @Test
    fun playlistNamesDefaultToTheHost() {
        assertEquals("provider.example.com", OndoRepository.hostName("http://www.provider.example.com/get.php"))
        assertEquals("Playlist", OndoRepository.hostName("not a url"))
    }
}
