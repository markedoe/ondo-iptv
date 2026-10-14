package com.markedoe.ondo.core.xtream

import com.markedoe.ondo.core.Fixtures
import com.markedoe.ondo.core.MockHttpClient
import com.markedoe.ondo.core.model.CatchupInfo
import com.markedoe.ondo.core.model.XtreamCredentials
import com.markedoe.ondo.core.library.PlaylistLoader
import com.markedoe.ondo.core.library.PlaylistLoaderException
import com.markedoe.ondo.core.model.Playlist
import com.markedoe.ondo.core.model.PlaylistSource
import com.markedoe.ondo.core.model.XtreamOutputFormat
import com.markedoe.ondo.core.net.HttpException
import java.net.SocketTimeoutException
import com.markedoe.ondo.core.net.Urls
import com.markedoe.ondo.core.utc
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class XtreamTest {
    private val credentials = XtreamCredentials("http://panel.example.com:8080", "demo", "se/cr+et")

    private fun mock() = MockHttpClient().apply {
        routes["api:"] = Fixtures.bytes("xtream_auth.json")
        routes["api:get_live_categories"] = Fixtures.bytes("xtream_live_categories.json")
        routes["api:get_live_streams"] = Fixtures.bytes("xtream_live_streams.json")
        routes["api:get_vod_categories"] = Fixtures.bytes("xtream_vod_categories.json")
        routes["api:get_vod_streams"] = Fixtures.bytes("xtream_vod_streams.json")
        routes["api:get_series_categories"] = Fixtures.bytes("xtream_series_categories.json")
        routes["api:get_series"] = Fixtures.bytes("xtream_series.json")
        routes["api:get_series_info"] = Fixtures.bytes("xtream_series_info.json")
        routes["api:get_vod_info"] = Fixtures.bytes("xtream_vod_info.json")
        routes["api:get_short_epg"] = Fixtures.bytes("xtream_short_epg.json")
    }

    @Test
    fun authentication() = runTest {
        val account = XtreamClient(credentials, mock()).authenticate()
        assertEquals("Active", account.status)
        assertTrue(account.isActive)
        assertEquals(2, account.maxConnections)
        assertEquals(1_924_992_000_000L, account.expiresAt)
        assertEquals(listOf("m3u8", "ts", "rtmp"), account.allowedOutputFormats)
        assertEquals("Europe/Paris", account.serverTimeZone)
    }

    @Test
    fun authenticationFailure() = runTest {
        val http = MockHttpClient()
        http.routes["api:"] = Fixtures.bytes("xtream_auth_failed.json")
        val refused = assertFailsWith<XtreamException.AuthenticationFailed> { XtreamClient(credentials, http).authenticate() }
        assertNull(refused.serverMessage)

        http.routes["api:"] = "<html>nginx</html>".encodeToByteArray()
        assertFailsWith<XtreamException.InvalidResponse> { XtreamClient(credentials, http).authenticate() }
    }

    @Test
    fun inactiveAccountAndServerMessage() = runTest {
        val http = MockHttpClient()
        http.routes["api:"] = """{"user_info":{"auth":"1","status":"Expired"}}""".encodeToByteArray()
        assertEquals("Expired", assertFailsWith<XtreamException.AccountNotActive> { XtreamClient(credentials, http).authenticate() }.status)

        http.routes["api:"] = """{"user_info":{"auth":false,"message":"Banned"}}""".encodeToByteArray()
        val refused = assertFailsWith<XtreamException.AuthenticationFailed> { XtreamClient(credentials, http).authenticate() }
        assertEquals("Banned", refused.message)

        http.routes["api:"] = """[1,2]""".encodeToByteArray()
        assertFailsWith<XtreamException.InvalidResponse> { XtreamClient(credentials, http).authenticate() }
    }

    @Test
    fun fetchContentWithMessyTypes() = runTest {
        val content = XtreamClient(credentials, mock(), clock = { 42L }).fetchContent(XtreamOutputFormat.M3U8)
        assertEquals(4, content.channels.size, "entries without stream_id and non-objects are skipped")
        val tf1 = content.channels[0]
        assertEquals("x101", tf1.id)
        assertEquals("TF1.fr", tf1.epgId)
        assertEquals(CatchupInfo(CatchupInfo.Mode.XTREAM, 7), tf1.catchup)
        assertEquals("http://panel.example.com:8080/live/demo/se%2Fcr+et/101.m3u8", tf1.streamUrl)
        assertEquals(102, content.channels[1].xtreamStreamId)
        assertNull(content.channels[1].logoUrl)
        assertNull(content.channels[1].catchup)

        assertEquals(listOf("FR | Généralistes", "FR | Sport", "XXX | Adult", "Other"), content.liveCategories.map { it.name })
        assertEquals("live-55", content.channels[3].categoryId)

        assertEquals(listOf("Inception", "Dune: Part Two"), content.movies.map { it.name })
        assertEquals(2010, content.movies[0].year)
        assertEquals(8.8, content.movies[0].rating)
        assertEquals(8.2, content.movies[1].rating ?: 0.0, 0.001, "rating_5based is converted to /10")
        assertEquals("http://panel.example.com:8080/movie/demo/se%2Fcr+et/5001.mkv", content.movies[0].streamUrl)
        assertEquals(1_650_000_000L, content.movies[0].addedAt, "epoch seconds")

        assertEquals(9000, content.series.first().xtreamSeriesId)
        assertEquals(2008, content.series.first().year)
        assertEquals("The Archive", content.series[1].name, "the year in the title is not part of the name")
        assertEquals(2025, content.series[1].year, "no year from the provider: the one in the title")
        assertEquals(1_700_000_000L, content.series.first().addedAt, "epoch seconds")
        assertEquals("/xmltv.php", Urls.parse(content.providerEpgUrls.first())?.path)
        assertNotNull(content.account)
        assertEquals(42L, content.fetchedAt)
    }

    @Test
    fun fetchContentSurvivesFailingSectionsAndRespectsAllowedFormats() = runTest {
        val http = mock()
        http.routes.remove("api:get_vod_streams")
        http.routes.remove("api:get_series")
        http.routes["api:"] = """{"user_info":{"auth":1,"status":"Active","allowed_output_formats":["ts"]}}""".encodeToByteArray()
        val content = XtreamClient(credentials, http).fetchContent(XtreamOutputFormat.M3U8)
        assertEquals(4, content.channels.size)
        assertTrue(content.channels.all { it.streamUrl.endsWith(".ts") }, "HLS is not offered by the panel")
        assertTrue(content.movies.isEmpty())
        assertTrue(content.series.isEmpty())
        assertEquals(listOf("Films | Action"), content.movieCategories.map { it.name })
    }

    @Test
    fun seriesDetailsWithDictionaryEpisodes() = runTest {
        val details = XtreamClient(credentials, mock()).seriesDetails(9000)
        assertEquals("Breaking Bad", details.info.title)
        assertEquals("http://img.example.com/bb_back.jpg", details.info.backdropUrl)
        assertEquals(listOf(1, 2), details.seasons.map { it.number })
        assertEquals("Saison 1", details.seasons[0].name)
        assertEquals("http://img.example.com/s1_big.jpg", details.seasons[0].coverUrl)
        assertEquals(listOf("Pilot", "Cat's in the Bag..."), details.seasons[0].episodes.map { it.title })
        assertEquals(3480, details.seasons[0].episodes[0].durationSeconds)
        assertEquals(2820, details.seasons[1].episodes[0].durationSeconds)
        assertEquals("http://panel.example.com:8080/series/demo/se%2Fcr+et/9010.mp4", details.seasons[1].episodes[0].streamUrl)
    }

    @Test
    fun seriesDetailsWithArrayEpisodes() = runTest {
        val http = MockHttpClient()
        http.routes["api:get_series_info"] = Fixtures.bytes("xtream_series_info_array.json")
        val details = XtreamClient(credentials, http).seriesDetails(1)
        assertEquals(listOf(1, 2), details.seasons.map { it.number })
        assertEquals(2, details.seasons[0].episodes.size)
        assertEquals("Season 2", details.seasons[1].name)
    }

    @Test
    fun movieDetails() = runTest {
        val details = XtreamClient(credentials, mock()).movieDetails(5001)
        assertEquals("Inception", details.title)
        assertEquals("Christopher Nolan", details.director)
        assertEquals(8880, details.durationSeconds)
        assertEquals("YoHD9XEInc0", details.trailerYouTubeId)
        assertEquals("27205", details.tmdbId)
        assertEquals("http://img.example.com/inception.jpg", details.posterUrl)
        assertEquals("http://img.example.com/inception_back.jpg", details.backdropUrl)
    }

    @Test
    fun shortEpgDecodesBase64() = runTest {
        val programs = XtreamClient(credentials, mock()).shortEpg(101, "TF1.fr")
        assertEquals(listOf("Le JT", "Film"), programs.map { it.title })
        assertEquals("L'actualité", programs[0].description)
        assertNull(programs[1].description)
        assertEquals(1_760_032_800_000L, programs[0].start)
        assertTrue(programs.all { it.channelId == "TF1.fr" })
    }

    @Test
    fun urlBuilder() {
        val builder = XtreamUrlBuilder(credentials)
        val api = builder.apiUrl("get_series_info", mapOf("series_id" to "3"))
        assertTrue(api.contains("password=se/cr%2Bet"), api)
        assertTrue(api.endsWith("action=get_series_info&series_id=3"), api)

        val start = utc(2025, 10, 9, 18, 30)
        val paris = ZoneId.of("Europe/Paris")
        assertEquals(
            "http://panel.example.com:8080/timeshift/demo/se%2Fcr+et/45/2025-10-09:20-30/101.ts",
            builder.timeshiftUrl(101, start, 45, paris, XtreamOutputFormat.TS),
        )
        assertEquals("username=demo&password=se/cr%2Bet&type=m3u_plus&output=ts", builder.m3uUrl(XtreamOutputFormat.TS).substringAfter('?'))
        assertTrue(builder.xmltvUrl.endsWith("password=se/cr%2Bet"), builder.xmltvUrl)
    }

    @Test
    fun urlBuilderEscaping() {
        val builder = XtreamUrlBuilder(XtreamCredentials("http://p.example.com/", "user name", "p&ss=#?"))
        assertEquals("http://p.example.com/player_api.php?username=user%20name&password=p%26ss=%23?", builder.apiUrl())
        assertEquals("http://p.example.com/live/user%20name/p&ss=%23%3F/7.m3u8", builder.liveUrl(7, XtreamOutputFormat.M3U8))
        assertEquals("http://p.example.com/series/user%20name/p&ss=%23%3F/a%2Fb.mp4", builder.episodeUrl("a/b", null))
        assertEquals(
            "http://p.example.com/streaming/timeshift.php?username=user%20name&password=p%26ss=%23?&stream=7&start=2025-10-09:18-30&duration=1",
            builder.timeshiftPhpUrl(7, utc(2025, 10, 9, 18, 30), 0, null),
        )
    }

    @Test
    fun credentialsNormalisation() {
        assertEquals("http://panel.example.com:8080", XtreamCredentials.normalizeServer("panel.example.com:8080/"))
        assertEquals("https://panel.example.com", XtreamCredentials.normalizeServer(" https://panel.example.com/player_api.php?x=1 "))
        assertNull(XtreamCredentials.normalizeServer("   "))

        val fromLink = XtreamCredentials.fromM3uLink("http://p.example.com:80/get.php?username=u1&password=p1&type=m3u_plus&output=ts")
        assertEquals(XtreamCredentials("http://p.example.com:80", "u1", "p1"), fromLink)
        assertNull(XtreamCredentials.fromM3uLink("http://p.example.com/list.m3u"))
        assertEquals(
            XtreamCredentials("http://my_host.example.com:8080", "u", "p+q"),
            XtreamCredentials.fromM3uLink("http://my_host.example.com:8080/player_api.php?username=u&password=p%2Bq"),
            "underscores in host names are accepted",
        )
    }

    @Test
    fun liveUrlRecognition() {
        val parsed = XtreamUrlBuilder.parseLiveUrl("http://h.example.com:8080/live/u/p/42.ts")
        assertEquals(42, parsed?.streamId)
        assertEquals("u", parsed?.username)
        assertEquals(42, XtreamUrlBuilder.parseLiveUrl("http://h.example.com:8080/u/p/42")?.streamId)
        assertNull(XtreamUrlBuilder.parseLiveUrl("http://h.example.com/movie/u/p/42.mkv"))
        assertNull(XtreamUrlBuilder.parseLiveUrl("http://h.example.com/hls/stream.m3u8"))
        assertEquals("http://h.example.com:8080/live/u/p/42.m3u8", XtreamUrlBuilder.alternateFormatUrl("http://h.example.com:8080/live/u/p/42.ts"))
        assertEquals("http://h.example.com:8080/live/u/p/42.ts", XtreamUrlBuilder.alternateFormatUrl("http://h.example.com:8080/live/u/p/42.m3u8"))
    }

    @Test
    fun aSectionThePanelDoesNotOfferIsEmpty() = runTest {
        val http = mock().apply {
            routes.remove("api:get_vod_streams") // 404
            routes["api:get_series"] = """{"error":"disabled"}""".encodeToByteArray()
        }
        val content = XtreamClient(credentials, http).fetchContent(XtreamOutputFormat.M3U8)
        assertEquals(4, content.channels.size)
        assertTrue(content.movies.isEmpty())
        assertTrue(content.series.isEmpty())
    }

    @Test
    fun aSectionThatFailsOnceIsAskedAgain() = runTest {
        val http = mock().apply { failures["api:get_live_streams"] = mutableListOf(HttpException.BadStatus(884)) }
        val content = XtreamClient(credentials, http).fetchContent(XtreamOutputFormat.M3U8)
        assertEquals(4, content.channels.size)
        assertEquals(2, http.requested.count { "get_live_streams" in it })
    }

    /** A refresh that lost its channels to a timeout must fail, or the app saves an empty catalogue. */
    @Test
    fun aSectionThatKeepsFailingFailsTheRefresh() = runTest {
        val http = mock().apply {
            failures["api:get_live_streams"] = mutableListOf(SocketTimeoutException("slow"), SocketTimeoutException("slow"))
        }
        assertFailsWith<SocketTimeoutException> { XtreamClient(credentials, http).fetchContent(XtreamOutputFormat.M3U8) }
    }

    @Test
    fun anAccountThatListsNothingIsAnError() = runTest {
        val http = MockHttpClient().apply { routes["api:"] = Fixtures.bytes("xtream_auth.json") }
        assertFailsWith<PlaylistLoaderException.EmptyPlaylist> {
            PlaylistLoader(http).load(Playlist(name = "Empty", source = PlaylistSource.Xtream(credentials)))
        }
    }
}
