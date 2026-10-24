package com.markedoe.ondo.data

import com.markedoe.ondo.core.library.PlaceholderNames
import com.markedoe.ondo.core.model.EpgProgram
import com.markedoe.ondo.core.net.HttpClient
import com.markedoe.ondo.core.net.HttpException
import com.markedoe.ondo.core.sync.SyncPlaylist
import com.markedoe.ondo.core.sync.SyncSnapshot
import com.markedoe.ondo.ui.theme.Appearance
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

    /** Back on Welcome while the demo loads cancels the add: the buttons must not stay disabled. */
    @Test
    fun aCancelledAddDoesNotLeaveTheAppLoading() = runTest {
        val silent = object : HttpClient {
            override suspend fun get(url: String, headers: Map<String, String>): ByteArray = awaitCancellation()
        }
        val repository = repository(silent)
        repository.bootstrap()
        repository.awaitState { it.isReady }
        val adding = launch { repository.addPlaylist("Home", list) }
        repository.awaitState { it.isLoading }
        adding.cancel()
        adding.join()
        assertFalse(repository.state.value.isLoading)
        assertTrue(repository.state.value.playlists.isEmpty())
    }

    /** Runs the repository's file and network work only when the test says so: the test acts between two of its steps. */
    private class StepDispatcher : CoroutineDispatcher() {
        private val tasks = LinkedBlockingQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.add(block)
        }

        /**
         * The next step, once it is there (parsing runs on another thread, and sends its result here). The wait is long:
         * on a busy machine, the first parse in a test JVM (class loading, a cold JIT) has taken over 10 seconds.
         */
        fun runNext() = requireNotNull(tasks.poll(60, TimeUnit.SECONDS)) { "no step came" }.run()

        /** Waits until [count] steps are there: parsing on other threads ends in any order and at any time. */
        fun awaitQueued(count: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (tasks.size < count) {
                check(System.nanoTime() < deadline) { "only ${tasks.size} of $count steps came" }
                Thread.sleep(5)
            }
        }

        fun runAll() {
            while (true) (tasks.poll() ?: return).run()
        }
    }

    /** Runs the test's tasks (background ones included) and the steps until [done]. */
    private fun TestScope.runSteps(io: StepDispatcher, done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!done()) {
            check(System.nanoTime() < deadline) { "the repository never got there" }
            testScheduler.runCurrent()
            io.runAll()
        }
    }

    /** A playlist that arrives by sync while an added one is being saved stays, in memory and on disk. */
    @Test
    fun aPlaylistSyncedWhileAnotherIsAddedIsKept() = runTest {
        val io = StepDispatcher()
        val other = "http://provider.example.com/other.m3u"
        val repository = OndoRepository(AppStore(folder.root), FakeHttp(mapOf(list to catalogue, other to catalogue)), backgroundScope, now = { clock }, io = io)
        repository.bootstrap()
        runSteps(io) { repository.state.value.isReady }
        val adding = launch { repository.addPlaylist("Home", list) }
        testScheduler.runCurrent() // the add asks for its download
        io.runNext() // downloaded, parsing
        io.runNext() // parsed
        testScheduler.runCurrent() // the add saves its playlists, on the writer
        val synced = SyncPlaylist(id = "from-phone", name = "Phone", kind = SyncPlaylist.M3U, url = other, addedAt = clock, updatedAt = clock)
        assertNotNull(repository.mergeSync(SyncSnapshot(device = "phone", playlists = listOf(synced)), "TV"))
        runSteps(io) { adding.isCompleted }
        assertEquals(setOf("Home", "Phone"), repository.state.value.playlists.map { it.name }.toSet())
        assertEquals("Home", repository.state.value.activePlaylist?.name)
        io.runAll()
        assertEquals(setOf("Home", "Phone"), AppStore(folder.root).loadPlaylists().map { it.name }.toSet())
    }

    /** Two playlists added at once (a second tap on Save while the first loads): both stay, in memory and on disk. */
    @Test
    fun twoPlaylistsAddedAtOnceAreBothKept() = runTest {
        val io = StepDispatcher()
        val other = "http://provider.example.com/other.m3u"
        val repository = OndoRepository(AppStore(folder.root), FakeHttp(mapOf(list to catalogue, other to catalogue)), backgroundScope, now = { clock }, io = io)
        repository.bootstrap()
        runSteps(io) { repository.state.value.isReady }
        val first = launch { assertTrue(repository.addPlaylist("Home", list).isSuccess) }
        val second = launch { assertTrue(repository.addPlaylist("Office", other).isSuccess) }
        testScheduler.runCurrent() // both ask for their download
        io.awaitQueued(2)
        io.runNext()
        io.runNext() // both downloaded, parsing on other threads
        io.awaitQueued(2) // both parsed: each continues on the steps' dispatcher
        io.runNext()
        io.runNext() // each has read the playlists and saves its catalogue on the writer
        runSteps(io) { first.isCompleted && second.isCompleted }
        assertEquals(setOf("Home", "Office"), repository.state.value.playlists.map { it.name }.toSet())
        io.runAll()
        assertEquals(setOf("Home", "Office"), AppStore(folder.root).loadPlaylists().map { it.name }.toSet())
    }

    /** A catalogue too large for the device's memory (an entry-level TV) is an error on screen, not a stop of the app. */
    @Test
    fun aCatalogueTooLargeForTheDeviceIsAnErrorNotAStop() = runTest {
        val tooLarge = object : HttpClient {
            override suspend fun get(url: String, headers: Map<String, String>): ByteArray = throw OutOfMemoryError("Failed to allocate")
        }
        val repository = repository(tooLarge)
        repository.bootstrap()
        repository.awaitState { it.isReady }
        assertTrue(repository.addPlaylist("Home", list).isFailure)
        assertTrue(repository.state.value.playlists.isEmpty())
        assertFalse(repository.state.value.isLoading)
    }

    /** A rating too large for a number ("1e999") must not keep the catalogue from being saved. */
    @Test
    fun anXtreamCatalogueWithARatingOutOfRangeIsSaved() = runTest {
        val panel = object : HttpClient {
            override suspend fun get(url: String, headers: Map<String, String>): ByteArray {
                if (!url.contains("/player_api.php")) throw HttpException.BadStatus(404)
                return when (Regex("[?&]action=([^&]+)").find(url)?.groupValues?.get(1)) {
                    null -> """{"user_info":{"username":"demo","auth":1,"status":"Active"},"server_info":{}}"""
                    "get_vod_streams" -> """[{"stream_id":1,"name":"Quiet Water","category_id":"1","rating":"1e999"},""" +
                        """{"stream_id":2,"name":"Long Road","category_id":"1","rating_5based":"${"9".repeat(400)}"}]"""
                    else -> "[]"
                }.encodeToByteArray()
            }
        }
        val repository = repository(panel)
        repository.bootstrap()
        repository.awaitState { it.isReady }
        assertTrue(repository.addPlaylist("Panel", "http://panel.example.com", "demo", "secret").isSuccess)
        val id = requireNotNull(repository.state.value.activePlaylistId)
        assertEquals(listOf("Quiet Water", "Long Road"), disk().loadContent(id)?.movies?.map { it.name })
    }

    /** The last error that stopped the app is read at start, for Settings → About. */
    @Test
    fun theLastCrashIsReadAtStart() = runTest {
        CrashLog.record(java.io.File(folder.root, CrashLog.FILE_NAME), "Ondo test", "main", IllegalStateException("broken"), at = clock - 60_000)
        val repository = repository()
        repository.bootstrap()
        val crash = requireNotNull(repository.awaitState { it.isReady }.lastCrash)
        assertEquals(clock - 60_000, crash.at)
        assertTrue(crash.report.contains("java.lang.IllegalStateException: broken"))
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

    /**
     * The names the core gives in English show in the app's words, again when its language changes; the saved catalogue
     * keeps the core's, and a catalogue taken off the screen does not come back with another language.
     */
    @Test
    fun theCoresNamesShowInTheAppsWordsAndStayInEnglishOnDisk() = runTest {
        fun words(uncategorized: String, episode: String) = PlaceholderNames(uncategorized, "-", "- %1\$d", "- %1\$d", "- %1\$d", "- %1\$d", episode)
        val untitled = """
            #EXTM3U
            #EXTINF:-1,Test News
            http://provider.example.com/news.m3u8
            #EXTINF:2700 group-title="Series | Drama",Harbor S01E02
            http://provider.example.com/h102.mp4
        """.trimIndent()
        val repository = OndoRepository(
            AppStore(folder.root), FakeHttp(mapOf(list to untitled)), backgroundScope, now = { clock }, io = StandardTestDispatcher(testScheduler),
            placeholderNames = words("Non classé", "Épisode %1\$d"),
        )
        repository.bootstrap()
        repository.awaitState { it.isReady }
        assertTrue(repository.addPlaylist("Home", list).isSuccess)
        val id = repository.state.value.activePlaylistId!!
        assertEquals(listOf("Non classé"), repository.state.value.content.liveCategories.map { it.name })
        assertEquals(listOf("Uncategorized"), disk().loadContent(id)?.liveCategories?.map { it.name })
        val harbor = repository.state.value.content.series.single()
        assertEquals("Épisode 2", repository.details(harbor).seasons.single().episodes.single().title)

        // Another app language: the catalogue on screen and a series' episodes follow.
        repository.setPlaceholderNames(words("Sin categoría", "Episodio %1\$d"))
        assertEquals(listOf("Sin categoría"), repository.state.value.content.liveCategories.map { it.name })
        val again = repository.state.value.content.series.single()
        assertEquals("Episodio 2", repository.details(again).seasons.single().episodes.single().title)

        repository.delete(id)
        repository.setPlaceholderNames(words("未分类", "第 %1\$d 集"))
        assertTrue("a catalogue taken off the screen does not come back", repository.state.value.content.isEmpty)
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

    /** At start, Home waits for the saved catalogue rather than calling the playlist empty (and the splash screen stays). */
    @Test
    fun aRestartIsRestoringUntilTheSavedCatalogueIsRead() = runTest {
        val first = repository()
        first.bootstrap()
        first.awaitState { it.isReady }
        first.addPlaylist("Home", list)
        assertFalse(first.state.value.isRestoring)
        disk()

        val second = repository(FakeHttp(emptyMap()))
        val seen = mutableListOf<OndoState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { second.state.collect { seen += it } }
        second.bootstrap()
        second.awaitState { it.content.movies.isNotEmpty() }
        val ready = seen.filter { it.isReady }
        assertTrue("restoring from the first ready state", ready.first().isRestoring)
        assertTrue("never ready, empty and quiet", ready.none { it.content.isEmpty && !it.isRestoring && !it.isLoading })
        assertFalse(second.state.value.isRestoring)
    }

    @Test
    fun aFirstLaunchHasNothingToRestore() = runTest {
        val repository = repository()
        repository.bootstrap()
        assertFalse(repository.awaitState { it.isReady }.isRestoring)
    }
}
