package com.markedoe.ondo.core.subtitles

import com.markedoe.ondo.core.HangGuard
import com.markedoe.ondo.core.net.HttpClient
import com.markedoe.ondo.core.net.HttpException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/**
 * The subtitle services' clients against their recorded answers (`fixtures/subtitles`), with no network: what each
 * asks, what it reads, how a key is checked, and the search over every service at once.
 */
class OnlineSubtitlesTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    /** A request as the client sent it. */
    data class Sent(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

    /** Answers by the address without its query (or the whole address); an exception is thrown, a missing one is a 404. */
    class FakeHttp : HttpClient {
        val routes = LinkedHashMap<String, Any>()
        val sent = ArrayList<Sent>()

        private fun answer(url: String): ByteArray {
            val answer = routes[url] ?: routes[url.substringBefore('?')] ?: throw HttpException.BadStatus(404)
            if (answer is Throwable) throw answer
            return answer as ByteArray
        }

        override suspend fun get(url: String, headers: Map<String, String>): ByteArray {
            sent += Sent("GET", url, headers, null)
            return answer(url)
        }

        override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray, contentType: String): ByteArray {
            sent += Sent("POST", url, headers, body.decodeToString())
            return answer(url)
        }
    }

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/fixtures/subtitles/$name")?.use { it.readBytes() } ?: error("Missing fixture $name")

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, text) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private val episode = SubtitleQuery("Copper Valley", year = 2018, season = 3, episode = 3, languages = listOf("en", "fr"))
    private val film = SubtitleQuery("Northern Lights", year = 2025, languages = listOf("fr"))
    private val srt = "1\n00:00:01,000 --> 00:00:02,000\nHello\n"

    @Test
    fun openSubtitlesSearchesByNameWithSortedLowerCaseParameters() = runBlocking {
        val http = FakeHttp().apply { routes["https://api.opensubtitles.com/api/v1/subtitles"] = fixture("opensubtitles_search.json") }
        val client = OpenSubtitlesClient("example-api-key", userAgent = "Ondo v2.0.0", http = http)
        val found = client.search(episode)
        val request = http.sent.single()
        assertEquals(
            "https://api.opensubtitles.com/api/v1/subtitles?episode_number=3&languages=en,fr&query=copper%20valley&season_number=3&type=episode",
            request.url.replace("%2C", ","),
        )
        assertEquals("example-api-key", request.headers["Api-Key"])
        assertEquals("Ondo v2.0.0", request.headers["User-Agent"])
        // A result without a file is left out; a regional language is its base code.
        assertEquals(listOf("7000001", "7000002"), found.map { it.reference })
        assertEquals(listOf("en", "fr"), found.map { it.language })
        assertEquals("Copper.Valley.S03E03.1080p.WEB.H264", found[0].release)
        // No release name: the file's.
        assertEquals("Copper.Valley.S03E03.FR.SDH.srt", found[1].release)
        assertEquals(listOf(false, true), found.map { it.hearingImpaired })
        assertEquals(1520, found[0].downloads)
    }

    @Test
    fun openSubtitlesSearchesByTmdbIdWhenItIsKnown() = runBlocking {
        val http = FakeHttp().apply { routes["https://api.opensubtitles.com/api/v1/subtitles"] = fixture("opensubtitles_search.json") }
        val client = OpenSubtitlesClient("example-api-key", userAgent = "Ondo", http = http)
        client.search(episode.copy(tmdbId = 73375))
        client.search(film.copy(tmdbId = 42))
        val (series, movie) = http.sent.map { it.url }
        assertTrue(series, "parent_tmdb_id=73375" in series && "query=" !in series)
        assertTrue(movie, "tmdb_id=42" in movie && "query=" !in movie && "type=movie" in movie)
    }

    @Test
    fun openSubtitlesSignsInOnlyToDownloadAndOnItsOwnHost() = runBlocking {
        val http = FakeHttp().apply {
            routes["https://api.opensubtitles.com/api/v1/login"] = fixture("opensubtitles_login.json")
            routes["https://vip-api.opensubtitles.com/api/v1/download"] = fixture("opensubtitles_download.json")
            routes["https://www.opensubtitles.com/download/example/subfile/Copper.Valley.S03E03.srt"] = srt.toByteArray()
            routes["https://vip-api.opensubtitles.com/api/v1/subtitles"] = fixture("opensubtitles_search.json")
        }
        val client = OpenSubtitlesClient("example-api-key", "example-user", "example-password", userAgent = "Ondo", http = http)
        val subtitle = OnlineSubtitle(SubtitleService.OPEN_SUBTITLES, "7000001", "en", "Copper.Valley.S03E03")
        val file = client.download(subtitle, episode)
        assertEquals("Copper.Valley.S03E03.srt", file.fileName)
        assertEquals(srt, file.data.decodeToString())
        val (login, link, _) = http.sent
        assertEquals("POST", login.method)
        assertTrue(login.body!!.contains("\"username\":\"example-user\""))
        assertEquals("""{"file_id":7000001}""", link.body)
        assertEquals("Bearer example-session-token", link.headers["Authorization"])
        // A second download keeps the session: no new sign-in.
        client.download(subtitle, episode)
        assertEquals(1, http.sent.count { it.url.endsWith("/login") })
    }

    @Test
    fun openSubtitlesDownloadLimitIsSaidAsSuch() = runBlocking {
        val http = FakeHttp().apply { routes["https://api.opensubtitles.com/api/v1/download"] = HttpException.BadStatus(406) }
        val client = OpenSubtitlesClient("example-api-key", userAgent = "Ondo", http = http)
        try {
            client.download(OnlineSubtitle(SubtitleService.OPEN_SUBTITLES, "7000001", "en", ""), episode)
            fail("downloaded")
        } catch (e: SubtitleSearchException.QuotaReached) {
            assertEquals(SubtitleService.OPEN_SUBTITLES, e.service)
        }
    }

    @Test
    fun openSubtitlesCredentialsAreAcceptedRefusedOrUnchecked() = runBlocking {
        val formats = "https://api.opensubtitles.com/api/v1/infos/formats"
        fun client(answer: Any, login: Any? = null, user: String? = null): OpenSubtitlesClient {
            val http = FakeHttp().apply {
                routes[formats] = answer
                login?.let { routes["https://api.opensubtitles.com/api/v1/login"] = it }
            }
            return OpenSubtitlesClient("example-api-key", user, user?.let { "example-password" }, userAgent = "Ondo", http = http)
        }
        assertEquals(CredentialCheck.ACCEPTED, client(fixture("opensubtitles_formats.json")).checkCredentials())
        assertEquals(CredentialCheck.REFUSED, client(HttpException.BadStatus(401)).checkCredentials())
        assertEquals(CredentialCheck.REFUSED, client(HttpException.BadStatus(403)).checkCredentials())
        assertEquals(CredentialCheck.UNREACHABLE, client(IOException("offline")).checkCredentials())
        assertEquals(CredentialCheck.UNREACHABLE, client(HttpException.BadStatus(503)).checkCredentials())
        // The key is good, the account is not.
        assertEquals(CredentialCheck.REFUSED, client(fixture("opensubtitles_formats.json"), HttpException.BadStatus(401), "example-user").checkCredentials())
        assertEquals(CredentialCheck.ACCEPTED, client(fixture("opensubtitles_formats.json"), fixture("opensubtitles_login.json"), "example-user").checkCredentials())
    }

    @Test
    fun subdlSearchesAnEpisodeAndKeepsItsFileFromTheSeasonArchive() = runBlocking {
        val http = FakeHttp().apply {
            routes["https://api.subdl.com/api/v1/subtitles"] = fixture("subdl_search.json")
            routes["https://dl.subdl.com/subtitle/1000-2000.zip"] = zip("Copper.Valley.S03E02.srt" to "two", "Copper.Valley.S03E03.vtt" to "WEBVTT", "Copper.Valley.S03E03.srt" to srt)
        }
        val client = SubDLClient("example-subdl-key", http)
        val found = client.search(episode)
        val url = http.sent.single().url
        assertTrue(url, "api_key=example-subdl-key" in url && "film_name=Copper%20Valley" in url && "type=tv" in url && "season_number=3" in url && "episode_number=3" in url && "languages=EN%2CFR" in url)
        // An episode is not searched by year.
        assertFalse(url, "year=" in url)
        assertEquals(listOf("en", "fr"), found.map { it.language })
        assertEquals(listOf(false, true), found.map { it.hearingImpaired })
        val file = client.download(found[0], episode)
        assertEquals("Copper.Valley.S03E03.srt", file.fileName)
        assertEquals(srt, file.data.decodeToString())
    }

    @Test
    fun subdlTellsAKeyItRefusesFromATitleItDoesNotKnow() = runBlocking {
        val notFound = FakeHttp().apply { routes["https://api.subdl.com/api/v1/subtitles"] = fixture("subdl_not_found.json") }
        assertEquals(emptyList<OnlineSubtitle>(), SubDLClient("k", notFound).search(film))
        val badKey = FakeHttp().apply { routes["https://api.subdl.com/api/v1/subtitles"] = fixture("subdl_bad_key.json") }
        assertEquals(CredentialCheck.REFUSED, SubDLClient("k", badKey).checkKey())
        val good = FakeHttp().apply { routes["https://api.subdl.com/api/v1/subtitles"] = fixture("subdl_search.json") }
        assertEquals(CredentialCheck.ACCEPTED, SubDLClient("k", good).checkKey())
        val down = FakeHttp().apply { routes["https://api.subdl.com/api/v1/subtitles"] = IOException("offline") }
        assertEquals(CredentialCheck.UNREACHABLE, SubDLClient("k", down).checkKey())
        // By TMDb's id when it is known, not by name.
        SubDLClient("k", good).search(film.copy(tmdbId = 42))
        assertTrue(good.sent.last().url.let { "tmdb_id=42" in it && "film_name" !in it })
    }

    @Test
    fun subSourceFindsTheSeasonThenTheEpisodesSubtitles() = runBlocking {
        val http = FakeHttp().apply {
            routes["https://api.subsource.net/api/v1/movies/search"] = fixture("subsource_titles.json")
            routes["https://api.subsource.net/api/v1/subtitles"] = fixture("subsource_subtitles.json")
            routes["https://api.subsource.net/api/v1/subtitles/8001/download"] = zip("Copper.Valley.S03E03.srt" to srt)
        }
        val client = SubSourceClient("example-subsource-key", http)
        val found = client.search(episode)
        assertEquals("example-subsource-key", http.sent.first().headers["X-API-Key"])
        // Season 3 of the exact title, in English then French; the season lists S03E04 too, which is left out.
        assertTrue(http.sent[1].url, "movieId=502" in http.sent[1].url && "language=english" in http.sent[1].url)
        assertTrue(http.sent[2].url, "language=french" in http.sent[2].url)
        assertEquals(listOf("8001", "8001"), found.map { it.reference })
        assertEquals(srt, client.download(found[0], episode).data.decodeToString())
        val refused = FakeHttp().apply { routes["https://api.subsource.net/api/v1/movies/search"] = HttpException.BadStatus(403) }
        assertEquals(CredentialCheck.REFUSED, SubSourceClient("k", refused).checkKey())
        assertEquals(CredentialCheck.ACCEPTED, SubSourceClient("k", http).checkKey())
    }

    @Test
    fun addic7edFindsTheEpisodeThroughGestdown() = runBlocking {
        val http = FakeHttp().apply {
            routes["https://api.gestdown.info/shows/search/Copper%20Valley"] = fixture("gestdown_shows.json")
            routes["https://api.gestdown.info/shows/3e3e3e3e-0000-0000-0000-000000000001/3/English"] = fixture("gestdown_season.json")
            routes["https://api.gestdown.info/subtitles/download/a1"] = srt.toByteArray()
        }
        val client = Addic7edClient("Ondo", http)
        // French has no page (404): nothing in French, no failure. The unfinished subtitle and the next episode are left out.
        val found = client.search(episode)
        assertEquals(listOf("/subtitles/download/a1"), found.map { it.reference })
        assertEquals("Copper Valley · S03E03 · WEB", found.single().release)
        assertEquals("Copper Valley.S03E03.en.srt", client.download(found.single(), episode).fileName)
        // Films: Addic7ed has none, nothing is asked.
        val before = http.sent.size
        assertEquals(emptyList<OnlineSubtitle>(), client.search(film))
        assertEquals(before, http.sent.size)
    }

    private class Fixed(override val service: SubtitleService, private val answer: () -> List<OnlineSubtitle>) : SubtitleProvider {
        var searches = 0
        override suspend fun search(query: SubtitleQuery): List<OnlineSubtitle> {
            searches++
            return answer()
        }

        override suspend fun download(subtitle: OnlineSubtitle, query: SubtitleQuery) = DownloadedSubtitle("a.srt", ByteArray(0))
    }

    @Test
    fun aServiceThatFailsDoesNotHideTheOthersAndAnswersAreKept() = runBlocking {
        val open = Fixed(SubtitleService.OPEN_SUBTITLES) {
            listOf(
                OnlineSubtitle(SubtitleService.OPEN_SUBTITLES, "1", "fr", "b", downloads = 10),
                OnlineSubtitle(SubtitleService.OPEN_SUBTITLES, "2", "en", "a", downloads = 99),
            )
        }
        val subdl = Fixed(SubtitleService.SUBDL) { listOf(OnlineSubtitle(SubtitleService.SUBDL, "3", "en", "c", downloads = 500)) }
        var down = true
        val subSource = Fixed(SubtitleService.SUBSOURCE) { if (down) throw IOException("offline") else emptyList() }
        val search = SubtitleSearch(listOf(open, subdl, subSource))
        val outcome = search.search(episode)
        // English first (the first language), then the services in their order, then the most downloaded.
        assertEquals(listOf("2", "3", "1"), outcome.results.map { it.reference })
        assertEquals(setOf(SubtitleService.SUBSOURCE), outcome.failures.keys)
        // A failed search is asked again; once every service answered, the same search asks none.
        down = false
        search.search(episode)
        search.search(episode)
        assertEquals(listOf(2, 2, 2), listOf(open.searches, subdl.searches, subSource.searches))
    }

    @Test
    fun anArchiveGivesTheEpisodesFileSrtFirst() {
        assertEquals("b.S01E02.srt", SubtitleArchive.pick(zip("a.S01E01.srt" to "1", "b.S01E02.srt" to "2", "readme.txt" to "x"), "S01E02").fileName)
        assertEquals("film.srt", SubtitleArchive.pick(zip("film.vtt" to "WEBVTT", "film.srt" to srt), null).fileName)
        assertEquals("ass", SubtitleArchive.pick(zip("Sub/film.ass" to "[Script Info]"), null).format)
        try {
            SubtitleArchive.pick("not a zip".toByteArray(), null)
            fail("picked")
        } catch (_: SubtitleSearchException.NoSubtitleInArchive) {
        }
    }

    @Test
    fun languagesAreReadInEveryFormTheServicesUse() {
        assertEquals("fr", SubtitleLanguages.code("French"))
        assertEquals("fr", SubtitleLanguages.code("FR"))
        assertEquals("pt", SubtitleLanguages.code("pt-BR"))
        assertEquals("de", SubtitleLanguages.code("deu"))
        assertNull(SubtitleLanguages.code("klingon"))
        assertEquals(listOf("fr", "de"), SubtitleLanguages.defaults("fr", listOf("fr-FR", "de-DE")))
        assertEquals(listOf("de", "en"), SubtitleLanguages.defaults(null, listOf("de-DE")))
    }
}
