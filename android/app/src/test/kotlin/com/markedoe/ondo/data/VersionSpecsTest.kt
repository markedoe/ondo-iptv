package com.markedoe.ondo.data

import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.library.MeasuredSpecs
import com.markedoe.ondo.core.model.PictureRange
import com.markedoe.ondo.core.model.SpecSource
import com.markedoe.ondo.core.model.StreamSpecs
import com.markedoe.ondo.core.net.HttpClient
import com.markedoe.ondo.core.net.HttpException
import com.markedoe.ondo.ui.Labels
import com.markedoe.ondo.ui.UiMappers
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What a film's versions are made of, from the provider (`get_vod_info`'s video and audio blocks, kept with the details)
 * and from the player (kept on the device, by playlist and stream). The details of a film's other versions are asked only
 * from its page, one at a time, never while browsing: a counter on the details asked says so. Invented names and
 * example.com only.
 */
class VersionSpecsTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val folder = TemporaryFolder()

    /** An Xtream panel with one film in three versions; each version's details say what its stream is made of. */
    private class Panel(private val extra: Int = 0) : HttpClient {
        val asked = mutableListOf<String>()

        /** The versions whose details the panel refuses. */
        val refused = mutableSetOf<String>()

        override suspend fun get(url: String, headers: Map<String, String>): ByteArray {
            if ("xmltv.php" in url) throw HttpException.BadStatus(404)
            val action = Regex("[?&]action=([^&]+)").find(url)?.groupValues?.get(1).orEmpty()
            val body = when (action) {
                "" -> """{"user_info":{"username":"viewer","auth":1,"status":"Active","exp_date":"1924992000","max_connections":"1",""" +
                    """"allowed_output_formats":["m3u8","ts"]},"server_info":{"url":"panel.example.com","server_protocol":"http"}}"""
                "get_vod_categories" -> """[{"category_id":"10","category_name":"Drama","parent_id":0}]"""
                "get_vod_streams" -> """[""" +
                    """{"num":1,"name":"Sand Harbor","stream_type":"movie","stream_id":5001,"category_id":"10","container_extension":"mkv","tmdb":"4242"},""" +
                    """{"num":2,"name":"Sand Harbor 4K","stream_type":"movie","stream_id":5002,"category_id":"10","container_extension":"mkv","tmdb":"4242"},""" +
                    """{"num":3,"name":"Sand Harbor VOSTFR","stream_type":"movie","stream_id":5003,"category_id":"10","container_extension":"mkv","tmdb":"4242"}""" +
                    (1..extra).joinToString("") { i ->
                        """,{"num":${3 + i},"name":"Sand Harbor","stream_type":"movie","stream_id":${5003 + i},"category_id":"10","container_extension":"mp4","tmdb":"4242"}"""
                    } + "]"
                "get_series_categories", "get_series" -> "[]"
                "get_vod_info" -> {
                    val id = Regex("[?&]vod_id=([^&]+)").find(url)?.groupValues?.get(1).orEmpty()
                    asked += id
                    if (id in refused) throw HttpException.BadStatus(503)
                    when (id) {
                        "5001" -> """{"info":{"name":"Sand Harbor","bitrate":4521,"video":{"codec_name":"h264","width":1920,"height":802,"color_transfer":"bt709"},""" +
                            """"audio":{"codec_name":"aac","channels":6}},"movie_data":{"stream_id":5001,"name":"Sand Harbor"}}"""
                        "5002" -> """{"info":{"name":"Sand Harbor","video":{"codec_name":"hevc","width":"3840","height":"2160","color_transfer":"smpte2084",""" +
                            """"side_data_list":[{"side_data_type":"DOVI configuration record"}]},"audio":{"codec_name":"eac3","channels":6,""" +
                            """"profile":"Dolby Digital Plus + Dolby Atmos"}},"movie_data":{"stream_id":5002,"name":"Sand Harbor 4K"}}"""
                        // A panel that gives no specs.
                        else -> """{"info":{"name":"Sand Harbor","video":[],"audio":[]},"movie_data":{"stream_id":5003,"name":"Sand Harbor VOSTFR"}}"""
                    }
                }
                else -> "[]"
            }
            return body.encodeToByteArray()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.saved() {
        testScheduler.advanceTimeBy(3_000)
        testScheduler.runCurrent()
    }

    private fun TestScope.repository(http: HttpClient) =
        OndoRepository(AppStore(folder.root), http, backgroundScope, now = { 1_760_000_000_000L }, io = StandardTestDispatcher(testScheduler))

    private suspend fun TestScope.opened(panel: Panel): OndoRepository {
        val repository = repository(panel)
        repository.bootstrap()
        repository.state.first { it.isReady }
        assertTrue(repository.addPlaylist("Panel", "http://panel.example.com", "viewer", "secret").isSuccess)
        return repository
    }

    @Test
    fun theProvidersSpecsAreKeptWithTheDetailsAndShownOnThePage() = runTest {
        val panel = Panel()
        val repository = opened(panel)
        val movies = repository.state.value.content.movies
        repository.details(movies[0])
        repository.details(movies[1])
        val state = repository.state.value
        assertEquals(StreamSpecs(1920, 802, PictureRange.SDR, "H.264", "AAC", 6, bitrate = 4521), state.movieDetails[movies[0].id]?.streamSpecs)
        val page = UiMappers.movieDetail(state, movies[0], Locale.US, selected = movies[1].id)
        assertEquals(listOf("4K", "Dolby Vision", "HEVC", "Atmos"), page.specBadges)
        assertEquals(listOf("1080p · 5.1", "4K Dolby Vision · Atmos", "Original, French subtitles"), page.versions.map { it.label })
        UiMappers.forgetCatalogue()
    }

    /**
     * Browsing (Home, the rows, a category, the search, a film's page drawn) asks the panel for nothing; the page, once
     * open and idle, asks for the other versions' details one at a time, and never again for those it knows.
     */
    @Test
    fun theOtherVersionsAreAskedOnlyFromTheirPageAndOnce() = runTest {
        val panel = Panel()
        val repository = opened(panel)
        val state = repository.state.value
        val movies = state.content.movies
        val before = repository.movieDetailRequests.get()
        // Browsing: what the screens work out from the state.
        UiMappers.home(state, Labels(), 1_760_000_000_000L)
        UiMappers.vod(state, series = false, title = "")
        UiMappers.grid(state, false, null, false, "")
        UiMappers.movieDetail(state, movies[0], Locale.US)
        UiMappers.versionChips(state, UiMappers.visibleVersions(state, movies[0]))
        assertEquals("browsing asks for no details", before, repository.movieDetailRequests.get())
        assertEquals(emptyList<String>(), panel.asked)

        // The page of the first version: its own details, then the others', in order, one at a time.
        repository.details(movies[0])
        assertEquals(2, repository.versionDetails(movies.drop(1)))
        assertEquals(listOf("5001", "5002", "5003"), panel.asked)
        assertEquals(before + 3, repository.movieDetailRequests.get())
        // Opened again: nothing more, even for the version whose panel gave no specs.
        assertEquals(0, repository.versionDetails(movies.drop(1)))
        assertEquals(before + 3, repository.movieDetailRequests.get())
        UiMappers.forgetCatalogue()
    }

    /** A page asks at most six other versions each time it opens, about 400 ms apart. */
    @Test
    fun aPageAsksAFewVersionsPaced() = runTest {
        val panel = Panel(extra = 6)
        val repository = opened(panel)
        val movies = repository.state.value.content.movies
        assertEquals(9, movies.size)
        val start = testScheduler.currentTime
        assertEquals(OndoRepository.VERSIONS_PER_PAGE, repository.versionDetails(movies.drop(1)))
        assertEquals(6, panel.asked.size)
        assertTrue("paced: ${testScheduler.currentTime - start} ms", testScheduler.currentTime - start >= 5 * OndoRepository.VERSION_GAP_MS)
        // Opened again: the two left.
        assertEquals(2, repository.versionDetails(movies.drop(1)))
        assertEquals(8, panel.asked.size)
        UiMappers.forgetCatalogue()
    }

    /** The first refusal stops the round; the refused version is not asked again this session. */
    @Test
    fun aRefusalStopsAndIsRemembered() = runTest {
        val panel = Panel(extra = 2)
        val repository = opened(panel)
        val movies = repository.state.value.content.movies
        val refused = movies[2].xtreamStreamId.toString()
        panel.refused += refused
        assertEquals(2, repository.versionDetails(movies.drop(1)))
        assertEquals(listOf(movies[1].xtreamStreamId.toString(), refused), panel.asked.distinct())
        val refusals = panel.asked.count { it == refused }
        // The page opened again: the refused one is skipped, the others are asked.
        assertEquals(2, repository.versionDetails(movies.drop(1)))
        assertEquals("not asked again this session", refusals, panel.asked.count { it == refused })
        assertEquals(4, panel.asked.distinct().size)
        UiMappers.forgetCatalogue()
    }

    /** While the player is on top nothing is asked; back on the page, the versions are asked. */
    @Test
    fun nothingIsAskedWhileAFilmPlays() = runTest {
        val panel = Panel()
        val repository = opened(panel)
        val movies = repository.state.value.content.movies
        repository.playbackStarted("${repository.state.value.activePlaylistId}|MOVIE|${movies[0].id}|")
        assertEquals(0, repository.versionDetails(movies.drop(1)))
        assertEquals(emptyList<String>(), panel.asked)
        repository.playbackEnded()
        assertEquals(2, repository.versionDetails(movies.drop(1)))
        UiMappers.forgetCatalogue()
    }

    /** The versions a page asked are kept apart on disk: they never push the pages viewers opened out of the document. */
    @Test
    fun versionsAreKeptApartFromThePagesOpened() = runTest {
        val panel = Panel()
        val repository = opened(panel)
        val movies = repository.state.value.content.movies
        repository.details(movies[0])
        repository.versionDetails(movies.drop(1))
        saved()
        val archive = AppStore(folder.root).loadDetails(repository.state.value.activePlaylistId!!)!!
        assertEquals(setOf(movies[0].id), archive.movies.keys)
        assertEquals(setOf(movies[1].id, movies[2].id), archive.versions.keys)
        // Trimmed apart too.
        assertEquals(1, archive.trimmed(movieLimit = 1, versionLimit = 0).movies.size)
        UiMappers.forgetCatalogue()
    }

    /** A page closed while its versions are asked: the rest are not asked. */
    @Test
    fun aClosedPageStopsAsking() = runTest {
        val panel = Panel()
        val repository = opened(panel)
        val movies = repository.state.value.content.movies
        val job = launch { repository.versionDetails(movies) }
        job.cancel()
        testScheduler.runCurrent()
        assertTrue("cancelled before it asked them all: ${panel.asked}", panel.asked.size < 3)
    }

    @Test
    fun whatThePlayerMeasuredIsKeptByPlaylistAndReadBack() = runTest {
        val panel = Panel()
        val first = opened(panel)
        val movie = first.state.value.content.movies[0]
        val playlistId = first.state.value.activePlaylistId!!
        first.recordMeasuredSpecs(playlistId, movie.id, StreamSpecs(width = 1920, height = 1080, range = PictureRange.SDR, videoCodec = "H.264", audioCodec = "AAC", channels = 2))
        val kept = first.state.first { MeasuredSpecs.key(playlistId, movie.id) in it.measuredSpecs }
        assertEquals(SpecSource.MEASURED, kept.measuredSpecs[MeasuredSpecs.key(playlistId, movie.id)]?.source)
        assertEquals("1080p", UiMappers.streamSpecs(kept, movie.id)?.resolution)
        saved()

        // The next start reads it back, once a page or the player needs it.
        val second = repository(panel)
        second.bootstrap()
        second.state.first { it.isReady }
        second.loadMeasuredSpecs().join()
        assertEquals("2.0 AAC", second.state.value.measuredSpecs[MeasuredSpecs.key(playlistId, movie.id)]?.audioLabel)
        UiMappers.forgetCatalogue()
    }
}
