package com.markedoe.ondo.data

import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.R
import com.markedoe.ondo.core.library.MeasuredSpecs
import com.markedoe.ondo.core.library.PictureSupport
import com.markedoe.ondo.core.library.ScreenNote
import com.markedoe.ondo.core.model.MediaDetails
import com.markedoe.ondo.core.model.StreamSpecs
import com.markedoe.ondo.core.net.HttpClient
import com.markedoe.ondo.core.net.HttpException
import com.markedoe.ondo.player.MeasuredTracks
import com.markedoe.ondo.player.TitleData
import com.markedoe.ondo.ui.UiMappers
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A film in two versions on an Xtream panel, the way a TV met it: a plain one and a "4K DV" one, each in its category.
 * What the player measured shows on the page as soon as it is back (the catalogue's ids, "x700101", are the keys the
 * player records under); details kept by an earlier version of the app, which did not read the provider's technical
 * blocks, are asked again; the title data says, for each version, what the provider's details held (or how they
 * failed) and whether they are kept. Invented names, ids and example.com only.
 */
class SpecsDiagnosisTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val folder = TemporaryFolder()

    private class Panel : HttpClient {
        val asked = mutableListOf<String>()
        val refused = mutableSetOf<String>()

        override suspend fun get(url: String, headers: Map<String, String>): ByteArray {
            if ("xmltv.php" in url) throw HttpException.BadStatus(404)
            val action = Regex("[?&]action=([^&]+)").find(url)?.groupValues?.get(1).orEmpty()
            val body = when (action) {
                "" -> """{"user_info":{"username":"viewer","auth":1,"status":"Active","exp_date":"1924992000","max_connections":"1",""" +
                    """"allowed_output_formats":["m3u8","ts"]},"server_info":{"url":"panel.example.com","server_protocol":"http"}}"""
                "get_vod_categories" -> """[{"category_id":"31","category_name":"|FR| NEW","parent_id":0},""" +
                    """{"category_id":"32","category_name":"|FR| 4K DOLBY VISION","parent_id":0}]"""
                "get_vod_streams" -> """[""" +
                    """{"num":1,"name":"|FR| Copper Lake","stream_type":"movie","stream_id":700101,"category_id":"31","container_extension":"mkv","tmdb":"4343"},""" +
                    """{"num":2,"name":"|FR-4K DV| Copper Lake","stream_type":"movie","stream_id":700102,"category_id":"32","container_extension":"mkv","tmdb":"4343"}""" +
                    "]"
                "get_series_categories", "get_series" -> "[]"
                "get_vod_info" -> {
                    val id = Regex("[?&]vod_id=([^&]+)").find(url)?.groupValues?.get(1).orEmpty()
                    asked += id
                    if (id in refused) throw HttpException.BadStatus(503)
                    when (id) {
                        "700101" -> """{"info":{"name":"Copper Lake","plot":"A lake.","video":{"codec_name":"h264","width":1920,"height":1080},""" +
                            """"audio":{"codec_name":"ac3","channels":6},"http://bad.example.com/key":"x"},"movie_data":{"stream_id":700101}}"""
                        // A panel that gives no technical block.
                        else -> """{"info":{"name":"Copper Lake","plot":"A lake.","video":[],"audio":[]},"movie_data":{"stream_id":700102}}"""
                    }
                }
                else -> "[]"
            }
            return body.encodeToByteArray()
        }
    }

    @After
    fun forget() {
        UiMappers.forgetCatalogue()
        UiMappers.pictureSupport = PictureSupport()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.saved() {
        testScheduler.advanceTimeBy(3_000)
        testScheduler.runCurrent()
    }

    private fun TestScope.repository(http: HttpClient) =
        OndoRepository(AppStore(folder.root), http, backgroundScope, now = { NOW }, io = StandardTestDispatcher(testScheduler))

    private suspend fun TestScope.opened(panel: Panel): OndoRepository {
        val repository = repository(panel)
        repository.bootstrap()
        repository.state.first { it.isReady }
        assertTrue(repository.addPlaylist("Panel", "http://panel.example.com", "viewer", "secret").isSuccess)
        return repository
    }

    /**
     * Played, recorded, back on the page: the chip and the title data say what the player measured, with no restart. The
     * key the player records under (from its request) is the one the page reads (the catalogue's id, "x700101").
     */
    @Test
    fun whatThePlayerMeasuredShowsOnThePageOnceBack() = runTest {
        UiMappers.pictureSupport = SAMSUNG_4K
        val repository = opened(Panel())
        val movies = repository.state.value.content.movies
        val plain = movies.single { it.xtreamStreamId == 700101 }
        assertEquals("x700101", plain.id)
        val before = UiMappers.movieDetail(repository.state.value, plain, Locale.US)
        assertEquals(listOf("Standard", "4K Dolby Vision"), before.versions.map { it.label })

        // The player plays the plain version from its page's request, and records what it read under its own key.
        val request = repository.playRequest(plain)!!
        val (playlistId, movieId) = MeasuredTracks.recordedFilm(request, urlIndex = 0)!!
        assertEquals(repository.state.value.activePlaylistId, playlistId)
        assertEquals("x700101", movieId)
        assertEquals("a fallback's address is not this stream", null, MeasuredTracks.recordedFilm(request, urlIndex = 1))
        repository.recordMeasuredSpecs(playlistId, movieId, StreamSpecs(width = 1920, height = 1080, videoCodec = "H.264", audioCodec = "AC-3", channels = 6))
        val back = repository.state.first { MeasuredSpecs.key(playlistId, movieId) in it.measuredSpecs }

        val page = UiMappers.movieDetail(back, plain, Locale.US)
        assertEquals("1080p · 5.1", page.versions.first { plain.id in it.members }.label)
        val data = TitleData.film(back, plain, shownTitle = plain.name)
        val version = data.versions.single { it.id == plain.id }
        assertEquals(R.string.title_data_specs_measured, version.specSource)
        assertEquals("1920×1080 · 1080p · H.264 · 5.1 AC-3", version.specs)
    }

    /**
     * Details kept by a version of the app that did not note what `get_vod_info` held (no technical blocks read) are asked
     * again: the page's own when it opens, the other versions' when the page is idle.
     */
    @Test
    fun detailsKeptBeforeTheSpecsWereReadAreAskedAgain() = runTest {
        val panel = Panel()
        val first = opened(panel)
        saved()
        val playlistId = first.state.value.activePlaylistId!!
        val movies = first.state.value.content.movies
        val plain = movies.single { it.xtreamStreamId == 700101 }
        val dv = movies.single { it.xtreamStreamId == 700102 }
        // As the earlier build left them: details without the provider's info, fetched a moment ago.
        AppStore(folder.root).saveDetails(
            playlistId,
            DetailsArchive(
                movies = mapOf(plain.id to DetailsArchive.Entry(MediaDetails(title = "Copper Lake", plot = "A lake."), NOW - 60_000)),
                versions = mapOf(dv.id to DetailsArchive.Entry(MediaDetails(title = "Copper Lake", plot = "A lake."), NOW - 60_000)),
            ),
        )
        val second = repository(panel)
        second.bootstrap()
        // The kept details are read back, as they were.
        second.state.first { it.isReady && plain.id in it.movieDetails && dv.id in it.movieDetails }
        assertEquals(null, second.state.value.movieDetails[plain.id]?.streamSpecs)
        val opened = second.details(plain)
        assertEquals("asked again", listOf("700101"), panel.asked)
        assertEquals(StreamSpecs(1920, 1080, null, "H.264", "AC-3", 6), opened?.streamSpecs)
        assertEquals(1, second.versionDetails(listOf(dv)))
        assertEquals(listOf("700101", "700102"), panel.asked)
        // Read by this version: not asked again.
        assertEquals(0, second.versionDetails(listOf(dv)))
        second.details(plain)
        assertEquals(2, panel.asked.size)
    }

    /** The title data says what each version's details held, how they failed, which are kept; field names only. */
    @Test
    fun theTitleDataSaysWhatTheProvidersDetailsHeld() = runTest {
        UiMappers.pictureSupport = SAMSUNG_4K
        val panel = Panel()
        val repository = opened(panel)
        val movies = repository.state.value.content.movies
        val plain = movies.single { it.xtreamStreamId == 700101 }
        val dv = movies.single { it.xtreamStreamId == 700102 }

        var data = TitleData.film(repository.state.value, plain, shownTitle = plain.name)
        assertEquals(listOf(TitleData.ProviderStatus.NOT_REQUESTED, TitleData.ProviderStatus.NOT_REQUESTED), data.versions.map { it.provider })
        assertEquals(listOf(false, false), data.versions.map { it.detailsKept })

        repository.details(plain)
        panel.refused += "700102"
        repository.versionDetails(listOf(dv))
        data = TitleData.film(repository.state.value, plain, shownTitle = plain.name)
        val shown = data.versions.associateBy { it.id }
        assertEquals(TitleData.ProviderStatus.VIDEO_AND_AUDIO, shown.getValue(plain.id).provider)
        assertEquals(listOf("audio", "name", "plot", "video"), shown.getValue(plain.id).providerKeys)
        assertTrue(shown.getValue(plain.id).detailsKept)
        assertEquals(TitleData.ProviderStatus.FAILED, shown.getValue(dv.id).provider)
        assertEquals("HTTP 503", shown.getValue(dv.id).providerFailure)
        assertFalse(shown.getValue(dv.id).detailsKept)
        // No profile known: a Dolby Vision version this screen may show in wrong colours, not played first.
        assertTrue(shown.getValue(dv.id).dolbyVision)
        assertEquals(null, shown.getValue(dv.id).dolbyVisionProfile)
        assertEquals(listOf(ScreenNote.DOLBY_VISION_UNKNOWN), shown.getValue(dv.id).screenNotes)
        assertTrue(shown.getValue(plain.id).first)
        assertEquals(SAMSUNG_4K, data.support)
        assertTrue(data.app.isNotEmpty())

        // Asked again, it answers without a technical block.
        panel.refused.clear()
        repository.details(dv)
        data = TitleData.film(repository.state.value, plain, shownTitle = plain.name)
        assertEquals(TitleData.ProviderStatus.NO_TECHNICAL_BLOCK, data.versions.single { it.id == dv.id }.provider)
        for (version in data.versions) for (key in version.providerKeys) assertFalse(key, "example.com" in key || "/" in key)
        saved()
    }

    @Test
    fun thePlayerRecordsOnlyAFilmsOwnStream() {
        val film = com.markedoe.ondo.player.PlayRequest(title = "Copper Lake", urls = listOf("http://panel.example.com/movie/a/b/700101.mkv"), isLive = false, progressKey = "p1|MOVIE|x700101|")
        assertEquals("p1" to "x700101", MeasuredTracks.recordedFilm(film, 0))
        assertEquals(null, MeasuredTracks.recordedFilm(film.copy(progressKey = "p1|SERIES|x5|e1"), 0))
        assertEquals(null, MeasuredTracks.recordedFilm(film.copy(isLive = true, progressKey = "p1|CHANNEL|c1|"), 0))
        assertEquals(null, MeasuredTracks.recordedFilm(film.copy(progressKey = null), 0))
    }

    private companion object {
        const val NOW = 1_760_000_000_000L

        /** A 4K screen with HDR10, HDR10+ and HLG, no Dolby Vision. */
        val SAMSUNG_4K = PictureSupport(hdr = true, dolbyVision = false, uhd = true)
    }
}
