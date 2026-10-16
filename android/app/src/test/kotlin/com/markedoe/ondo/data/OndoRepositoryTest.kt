package com.markedoe.ondo.data

import com.markedoe.ondo.core.model.EpgProgram
import com.markedoe.ondo.core.net.HttpClient
import com.markedoe.ondo.core.net.HttpException
import com.markedoe.ondo.ui.theme.Appearance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
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

    /** Files are read and written on the test's dispatcher: nothing outlives the test (its folder is deleted). */
    private fun TestScope.repository(http: HttpClient = FakeHttp(mapOf(list to catalogue))) =
        OndoRepository(AppStore(folder.root), http, backgroundScope, now = { clock }, io = StandardTestDispatcher(testScheduler))

    // runTest skips virtual time, so waits on real work (files, parsing) must not use withTimeout / delay.
    private suspend fun OndoRepository.awaitState(condition: (OndoState) -> Boolean): OndoState = state.first(condition)

    /**
     * What is on disk once the pending saves ran. They are queued on the test's dispatcher (the repository's
     * `io`), so running every pending task writes them: no polling, no sleep.
     */
    private fun TestScope.disk(): AppStore {
        testScheduler.advanceUntilIdle()
        return AppStore(folder.root)
    }

    @Test
    fun addingAPlaylistLoadsSavesAndActivatesIt() = runTest {
        val repository = repository()
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
        val first = repository()
        first.bootstrap()
        first.awaitState { it.isReady }
        first.addPlaylist("Home", list)
        val movie = first.state.value.content.movies.first()
        first.toggleFavorite(ItemKind.MOVIE, movie.id, movie.name, null)
        val request = first.playRequest(movie)!!
        first.recordProgress(request.progressKey!!, 600.0, 5_400.0)
        val saved = disk().loadLibrary()
        assertEquals(600.0, saved.history.first().position, 0.0)
        assertTrue(saved.isFavorite(first.state.value.activePlaylistId!!, ItemKind.MOVIE, movie.id))

        val offline = FakeHttp(emptyMap())
        val second = repository(offline)
        second.bootstrap()
        val restored = second.awaitState { it.isReady && it.content.movies.isNotEmpty() }
        assertEquals("Home", restored.activePlaylist?.name)
        assertTrue(restored.library.isFavorite(restored.activePlaylistId!!, ItemKind.MOVIE, movie.id))
        assertEquals(600.0, restored.library.continueWatching(restored.activePlaylistId!!).single().position, 0.0)
        assertTrue("the cached catalogue is fresh: no download", offline.requests.isEmpty())
    }

    /**
     * After the system killed the app in the background, it can recreate the player alone: the progress it
     * saves before the library is loaded must not replace the favourites and the history.
     */
    @Test
    fun progressSavedBeforeTheLibraryLoadsKeepsTheLibrary() = runTest {
        val favorite = Favorite("p1", ItemKind.MOVIE, "m1", "Quiet Water", addedAt = clock)
        val watched = WatchEntry("p1", ItemKind.MOVIE, "m2", title = "Long Road", position = 60.0, duration = 6_000.0, lastWatchedAt = clock)
        AppStore(folder.root).saveLibrary(LibraryState(favorites = listOf(favorite), history = listOf(watched)))

        val repository = repository() // nobody called bootstrap(): only the player exists
        repository.recordProgress(watched.key, 1_200.0, 6_000.0)

        val state = repository.awaitState { it.isReady }
        assertEquals(listOf(favorite), state.library.favorites)
        assertEquals(1_200.0, state.library.progress(watched.key)!!.position, 0.0)
        val saved = disk().loadLibrary()
        assertEquals(listOf(favorite), saved.favorites)
        assertEquals(1_200.0, saved.progress(watched.key)!!.position, 0.0)
    }

    @Test
    fun aReminderIsScheduledThenCancelled() = runTest {
        val scheduler = RecordingScheduler()
        val repository = OndoRepository(AppStore(folder.root), FakeHttp(mapOf(list to catalogue)), backgroundScope, now = { clock }, io = StandardTestDispatcher(testScheduler), reminderScheduler = scheduler)
        repository.bootstrap()
        repository.awaitState { it.isReady }
        repository.addPlaylist("Home", list)
        val channel = repository.state.value.content.channels.first()
        val program = EpgProgram("test.news", clock + 3_600_000, clock + 7_200_000, "Evening News")
        repository.toggleReminder(channel, program)
        assertEquals(listOf("Evening News"), repository.state.value.library.reminders.map { it.title })
        assertEquals(listOf("schedule Evening News"), scheduler.calls)
        repository.toggleReminder(channel, program)
        assertTrue(repository.state.value.library.reminders.isEmpty())
        assertEquals(listOf("schedule Evening News", "cancel Evening News"), scheduler.calls)
    }

    private class RecordingScheduler : ReminderScheduler {
        val calls = mutableListOf<String>()

        override fun schedule(reminder: Reminder, now: Long) {
            calls += "schedule ${reminder.title}"
        }

        override fun cancel(reminder: Reminder) {
            calls += "cancel ${reminder.title}"
        }
    }

    /** An awards file made on [generated], with [films] films (the app keeps files of a thousand films or more). */
    private fun awardsJson(generated: String, films: Int = 1_000): String {
        val list = (0 until films).joinToString(",") { """{"titles":["Film $it"],"year":1990,"awards":[["golden-bear",1990,0]]}""" }
        return """{"generated":"$generated","awards":[{"id":"golden-bear","group":"berlin","names":{"en":"Golden Bear"}}],"films":[$list]}"""
    }

    @Test
    fun newerAwardsReplaceTheBundledOnesOnceAWeek() = runTest {
        val url = "https://example.com/awards.json"
        val saved = folder.root.resolve("cache/awards.json")
        val http = FakeHttp(mapOf(url to awardsJson("2099-01-01")))
        fun repository() = OndoRepository(
            AppStore(folder.root), http, backgroundScope, now = { clock }, io = StandardTestDispatcher(testScheduler),
            awardsSource = { awardsJson("2026-01-01", films = 1) }, awardsDownload = AwardsDownload(url, saved),
        )
        val first = repository()
        first.bootstrap()
        assertEquals("2099-01-01", first.awaitState { it.awards.generated == "2099-01-01" }.awards.generated)
        assertTrue("kept for the next launch", saved.exists())
        assertEquals(1, http.requests.size)

        // Next launch, the same week: the download is used, nothing is asked.
        val second = repository()
        second.bootstrap()
        assertEquals("2099-01-01", second.awaitState { it.awards.generated == "2099-01-01" }.awards.generated)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun awardsAreNotDownloadedWhenTurnedOff() = runTest {
        val url = "https://example.com/awards.json"
        val http = FakeHttp(mapOf(url to awardsJson("2099-01-01")))
        AppStore(folder.root).saveSettings(AppSettings(updateAwards = false))
        val repository = OndoRepository(
            AppStore(folder.root), http, backgroundScope, now = { clock }, io = StandardTestDispatcher(testScheduler),
            awardsSource = { awardsJson("2026-01-01", films = 1) }, awardsDownload = AwardsDownload(url, folder.root.resolve("awards.json")),
        )
        repository.bootstrap()
        repository.awaitState { it.awards.generated == "2026-01-01" }
        testScheduler.advanceUntilIdle()
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun aFailedDownloadSavesNothingAndExplainsWhy() = runTest {
        val repository = repository(FakeHttp(emptyMap()))
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
        val repository = repository()
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

    /** The player can be recreated alone after the process was killed: the request carries the buffer settings. */
    @Test
    fun playRequestsCarryTheBufferSettingsAndTheKind() = runTest {
        val repository = repository()
        repository.bootstrap()
        repository.awaitState { it.isReady }
        repository.addPlaylist("Home", list)
        repository.setLiveBuffer(3)
        repository.setVodBuffer(10)
        val content = repository.state.value.content
        val movie = repository.playRequest(content.movies.first { it.name == "Long Road" })!!
        assertEquals("film", movie.kind)
        assertEquals(10, movie.bufferSeconds)
        val channel = repository.playRequest(content.channels.single())!!
        assertEquals("live channel", channel.kind)
        assertEquals(3, channel.bufferSeconds)
        val series = content.series.single()
        val episode = repository.playRequest(series, repository.details(series).seasons.single().episodes.first())!!
        assertEquals("episode", episode.kind)
        assertEquals(10, episode.bufferSeconds)
        assertEquals(3, disk().loadSettings().liveBuffer)
    }

    @Test
    fun episodesHaveTheirOwnProgress() = runTest {
        val repository = repository()
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
        val repository = repository(FakeHttp(mapOf(list to catalogue, other to catalogue)))
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
    fun activatingAnotherPlaylistShowsItsCatalogueAndIsRemembered() = runTest {
        val other = "http://other.example.com/list.m3u"
        val news = "#EXTM3U\n#EXTINF:-1 group-title=\"News\",Other News\nhttp://other.example.com/news.m3u8\n"
        val repository = repository(FakeHttp(mapOf(list to catalogue, other to news)))
        repository.bootstrap()
        repository.awaitState { it.isReady }
        repository.addPlaylist("First", list)
        repository.addPlaylist("Second", other)
        val first = repository.state.value.playlists.first { it.name == "First" }

        repository.activate(first.id)
        val state = repository.awaitState { it.content.movies.isNotEmpty() }
        assertEquals("First", state.activePlaylist?.name)
        assertEquals(listOf("Test News"), state.content.channels.map { it.name })
        assertEquals(first.id, disk().loadSettings().activePlaylistId)
    }

    @Test
    fun settingsArePersisted() = runTest {
        val repository = repository()
        repository.bootstrap()
        repository.awaitState { it.isReady }
        repository.setShowAdultCategories(true)
        repository.setAppearance(Appearance.LIGHT)
        val saved = disk().loadSettings()
        assertEquals(Appearance.LIGHT, saved.appearance)
        assertTrue(saved.showAdultCategories)
    }

    @Test
    fun playlistNamesDefaultToTheHost() {
        assertEquals("provider.example.com", OndoRepository.hostName("http://www.provider.example.com/get.php"))
        assertEquals("Playlist", OndoRepository.hostName("not a url"))
    }
}
