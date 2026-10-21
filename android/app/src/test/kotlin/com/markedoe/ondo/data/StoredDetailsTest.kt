package com.markedoe.ondo.data

import com.markedoe.ondo.core.model.MediaDetails
import com.markedoe.ondo.core.model.SeriesDetails
import com.markedoe.ondo.core.net.HttpClient
import com.markedoe.ondo.core.net.HttpException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Movie and series details kept between launches: the next launch shows the backdrops and episodes from disk without
 * asking the panel again, asks again once they are old, and keeps showing them when the panel cannot be reached.
 */
class StoredDetailsTest {
    @get:Rule val folder = TemporaryFolder()

    private var clock = 1_760_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    /** An Xtream panel with one movie and one series. */
    private class Panel : HttpClient {
        val actions = mutableListOf<String>()
        var isDown = false
        var episodes = 1

        override suspend fun get(url: String, headers: Map<String, String>): ByteArray {
            if ("xmltv.php" in url) throw HttpException.BadStatus(404)
            val action = Regex("[?&]action=([^&]+)").find(url)?.groupValues?.get(1).orEmpty()
            actions += action
            if (isDown) throw HttpException.BadStatus(503)
            val pilot = """{"id":"7001","episode_num":1,"title":"Pilot","container_extension":"mkv","info":{"movie_image":"http://img.example.com/still.jpg"}}"""
            val second = """{"id":"7002","episode_num":2,"title":"Second","container_extension":"mkv"}"""
            val body = when (action) {
                "" -> """{"user_info":{"username":"viewer","auth":1,"status":"Active","exp_date":"1924992000","max_connections":"1",""" +
                    """"allowed_output_formats":["m3u8","ts"]},"server_info":{"url":"panel.example.com","server_protocol":"http"}}"""
                "get_vod_categories" -> """[{"category_id":"10","category_name":"Drama","parent_id":0}]"""
                "get_vod_streams" -> """[{"num":1,"name":"Quiet Water","stream_type":"movie","stream_id":5001,"category_id":"10","container_extension":"mp4"}]"""
                "get_series_categories" -> """[{"category_id":"20","category_name":"Drama","parent_id":0}]"""
                "get_series" -> """[{"num":1,"name":"Harbor Lights","series_id":9001,"category_id":"20"}]"""
                "get_vod_info" ->
                    """{"info":{"name":"Quiet Water","backdrop_path":["http://img.example.com/wide.jpg"]},"movie_data":{"stream_id":5001,"name":"Quiet Water"}}"""
                "get_series_info" -> {
                    val list = if (episodes > 1) "$pilot,$second" else pilot
                    """{"seasons":[],"info":{"name":"Harbor Lights"},"episodes":{"1":[$list]}}"""
                }
                else -> "[]"
            }
            return body.encodeToByteArray()
        }

        fun calls(action: String) = actions.count { it == action }
    }

    /**
     * Runs the save made a moment after the last fetch, and the writes. They run in the repository's background scope,
     * which `advanceUntilIdle` leaves alone.
     */
    private fun TestScope.saved() {
        testScheduler.advanceTimeBy(3_000)
        testScheduler.runCurrent()
    }

    private fun TestScope.repository(http: HttpClient) =
        OndoRepository(AppStore(folder.root), http, backgroundScope, now = { clock }, io = StandardTestDispatcher(testScheduler))

    @Test
    fun detailsSeenOnceShowAtTheNextLaunchWithoutAskingThePanel() = runTest {
        val panel = Panel()
        val first = repository(panel)
        first.bootstrap()
        first.state.first { it.isReady }
        assertTrue(first.addPlaylist("Panel", "http://panel.example.com", "viewer", "secret").isSuccess)
        val movie = first.state.value.content.movies.single()
        val series = first.state.value.content.series.single()
        assertEquals("http://img.example.com/wide.jpg", first.details(movie)?.backdropUrl)
        assertEquals("http://img.example.com/still.jpg", first.details(series).seasons.single().episodes.single().posterUrl)
        saved() // a moment after the last fetch

        val second = repository(panel)
        second.bootstrap()
        val restored = second.state.first { movie.id in it.movieDetails && series.id in it.seriesDetails }
        assertEquals("Home's backdrop, from disk", "http://img.example.com/wide.jpg", restored.movieDetails[movie.id]?.backdropUrl)
        second.details(movie)
        second.details(series)
        assertEquals("recent details are not asked for again", 1, panel.calls("get_vod_info"))
        assertEquals(1, panel.calls("get_series_info"))

        // A day later: the series is asked for again (new episodes), the movie is not yet.
        clock += day
        panel.episodes = 2
        second.details(movie)
        assertEquals(2, second.details(series).seasons.single().episodes.size)
        assertEquals(1, panel.calls("get_vod_info"))
        assertEquals(2, panel.calls("get_series_info"))

        // The panel cannot be reached: the kept details stay.
        clock += 30 * day
        panel.isDown = true
        assertEquals("http://img.example.com/wide.jpg", second.details(movie)?.backdropUrl)
        assertEquals(2, second.details(series).seasons.single().episodes.size)
    }

    @Test
    fun deletingThePlaylistDeletesItsDetails() = runTest {
        val panel = Panel()
        val repository = repository(panel)
        repository.bootstrap()
        repository.state.first { it.isReady }
        assertTrue(repository.addPlaylist("Panel", "http://panel.example.com", "viewer", "secret").isSuccess)
        val id = repository.state.value.activePlaylistId!!
        repository.details(repository.state.value.content.movies.single())
        saved()
        assertTrue(AppStore(folder.root).loadDetails(id)?.movies?.isNotEmpty() == true)

        // Details fetched just before: their save, still pending, must not bring the document back.
        repository.details(repository.state.value.content.series.single())
        repository.delete(id)
        saved()
        assertNull(AppStore(folder.root).loadDetails(id))
    }

    @Test
    fun aSeriesIsAskedForAgainSoonerThanAMovie() {
        val hour = 60 * 60 * 1000L
        fun fresh(age: Long, maxAge: Long) = DetailsArchive.isFresh(clock - age, maxAge, clock)
        assertTrue(fresh(11 * hour, DetailsArchive.SERIES_MAX_AGE_MS))
        assertFalse("new episodes are looked for twice a day", fresh(13 * hour, DetailsArchive.SERIES_MAX_AGE_MS))
        assertTrue(fresh(6 * day, DetailsArchive.MOVIE_MAX_AGE_MS))
        assertFalse("a movie's page is fetched again after a week", fresh(8 * day, DetailsArchive.MOVIE_MAX_AGE_MS))
        assertFalse("never fetched", DetailsArchive.isFresh(null, DetailsArchive.MOVIE_MAX_AGE_MS, clock))
        assertFalse("a date in the future is not trusted", fresh(-hour, DetailsArchive.MOVIE_MAX_AGE_MS))
    }

    @Test
    fun theMostRecentlyFetchedAreKept() {
        val archive = DetailsArchive(
            movies = (0 until 5).associate { "m$it" to DetailsArchive.Entry(MediaDetails(title = "Movie $it"), clock + it) },
            series = (0 until 5).associate { "s$it" to DetailsArchive.Entry(SeriesDetails(MediaDetails(title = "Series $it"), emptyList()), clock + it) },
        )
        val trimmed = archive.trimmed(movieLimit = 3, seriesLimit = 2)
        assertEquals(setOf("m2", "m3", "m4"), trimmed.movies.keys)
        assertEquals(setOf("s3", "s4"), trimmed.series.keys)
        assertEquals("under the limits, nothing is dropped", archive, archive.trimmed())
    }
}
