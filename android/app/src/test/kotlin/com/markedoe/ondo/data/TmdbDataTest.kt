package com.markedoe.ondo.data

import com.markedoe.ondo.core.library.TrendingMatch
import com.markedoe.ondo.core.net.HttpClient
import com.markedoe.ondo.core.net.HttpException
import com.markedoe.ondo.core.sync.SyncKey
import com.markedoe.ondo.core.sync.SyncSnapshot
import java.net.UnknownHostException
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The viewer's TMDb token on Android: checked with TMDb when saved, carried by sync, and what it brings. */
class TmdbDataTest {
    @get:Rule val folder = TemporaryFolder()

    private val list = "http://provider.example.com/list.m3u"
    private val catalogue = """
        #EXTM3U
        #EXTINF:5400 group-title="Movies | Drama",Quiet Water (2021)
        http://provider.example.com/quiet.mp4
        #EXTINF:6000 group-title="Movies | Drama",Long Road (2020)
        http://provider.example.com/road.mp4
    """.trimIndent()

    private val authentication = "https://api.themoviedb.org/3/authentication?language=en-US&api_key="
    private val trending = "https://api.themoviedb.org/3/trending/all/week?language=en-US&api_key="

    /** Answers by address: a body, or the failure a request to it meets. */
    private class Http(val answers: MutableMap<String, Any>) : HttpClient {
        val requested = mutableListOf<String>()

        override suspend fun get(url: String, headers: Map<String, String>): ByteArray {
            requested += url
            return when (val answer = answers[url]) {
                is String -> answer.encodeToByteArray()
                is Exception -> throw answer
                else -> throw HttpException.BadStatus(404)
            }
        }
    }

    private lateinit var locale: Locale
    private var time = 1_760_000_000_000L

    @Before
    fun englishTitles() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() = Locale.setDefault(locale)

    private val answers = mutableMapOf<String, Any>(
        list to catalogue,
        authentication + "GOOD" to """{"success":true}""",
        authentication + "BAD" to HttpException.BadStatus(401),
        authentication + "OFFLINE" to UnknownHostException("api.themoviedb.org"),
        trending + "GOOD" to """{"results":[{"id":5,"media_type":"movie","title":"Quiet Water","release_date":"2021-04-01"},""" +
            """{"id":6,"media_type":"movie","title":"Somewhere Else","release_date":"2019-01-01"}]}""",
        trending + "REVOKED" to HttpException.BadStatus(401),
    )

    private fun TestScope.device(name: String, http: Http = Http(answers)) =
        OndoRepository(AppStore(folder.newFolder(name)), http, backgroundScope, now = { time }, io = StandardTestDispatcher(testScheduler))

    private suspend fun OndoRepository.ready(): OndoRepository {
        bootstrap()
        state.first { it.isReady }
        return this
    }

    private fun session(client: OndoRepository, server: OndoRepository) {
        val answer = requireNotNull(server.mergeSync(requireNotNull(client.syncSnapshot("client")), "server"))
        client.mergeSync(answer, "client")
    }

    @Test
    fun aTokenIsSavedOnlyOnceTmdbAcceptsIt() = runTest {
        val tv = device("tv").ready()
        assertEquals(TmdbCheck.REFUSED, tv.checkAndSaveTmdbToken(" BAD "))
        assertEquals(TmdbCheck.UNREACHABLE, tv.checkAndSaveTmdbToken("OFFLINE"))
        assertEquals(TmdbCheck.REFUSED, tv.checkAndSaveTmdbToken("   "))
        assertNull(tv.state.value.settings.tmdbToken)
        assertEquals(TmdbCheck.ACCEPTED, tv.checkAndSaveTmdbToken(" GOOD "))
        assertEquals("GOOD", tv.state.value.settings.tmdbToken)
        // Kept for the next launch.
        advanceUntilIdle()
        val again = OndoRepository(AppStore(folder.root.resolve("tv")), Http(answers), backgroundScope, now = { time }, io = StandardTestDispatcher(testScheduler)).ready()
        assertEquals("GOOD", again.state.value.settings.tmdbToken)
    }

    @Test
    fun theTokenTravelsBySyncAndItsRemovalFollows() = runTest {
        val phone = device("phone").ready()
        val tv = device("tv").ready()
        assertEquals(TmdbCheck.ACCEPTED, phone.checkAndSaveTmdbToken("GOOD"))
        session(phone, tv)
        assertEquals("GOOD", tv.state.value.settings.tmdbToken)

        // A device that does not sync keys answers without them: the token stays.
        tv.mergeSync(SyncSnapshot(device = "old"), "tv")
        assertEquals("GOOD", tv.state.value.settings.tmdbToken)

        // Removed on the TV later: gone from the phone at the next session, and it does not come back.
        time += 1_000
        tv.removeTmdbToken()
        session(phone, tv)
        assertNull(phone.state.value.settings.tmdbToken)
        session(phone, tv)
        assertNull(tv.state.value.settings.tmdbToken)

        // The key of a service this version does not know passes through.
        time += 1_000
        val other = SyncKey("future-service", "example-other-key", time)
        tv.mergeSync(SyncSnapshot(device = "mac", keys = listOf(other)), "tv")
        assertEquals(listOf(other), tv.syncSnapshot("tv")!!.keys)
    }

    @Test
    fun trendingTitlesThePlaylistHasShowWithTheToken() = runTest {
        val tv = device("tv").ready()
        tv.addPlaylist("Home", list)
        tv.state.first { it.content.movies.isNotEmpty() }
        assertTrue(tv.state.value.trending.isEmpty())
        assertEquals(TmdbCheck.ACCEPTED, tv.checkAndSaveTmdbToken("GOOD"))
        val shown = tv.state.first { it.trending.isNotEmpty() }.trending
        assertEquals(listOf("Quiet Water"), shown.map { (it as TrendingMatch.MovieMatch).movie.name })
        // Without the token, nothing from TMDb.
        tv.removeTmdbToken()
        assertTrue(tv.state.value.trending.isEmpty())
    }

    /** A person's page asks TMDb for their credits once a week (kept on the device): back on the page, they show at once. */
    @Test
    fun aPersonsCreditsAreAskedOnceAWeek() = runTest {
        val search = "https://api.themoviedb.org/3/search/person?query=Aster&language=en-US&api_key=GOOD"
        val credits = "https://api.themoviedb.org/3/person/7/combined_credits?language=en-US&api_key=GOOD"
        val http = Http(answers.apply {
            put(search, """{"results":[{"id":7,"name":"Aster","popularity":3.0}]}""")
            put(credits, """{"cast":[{"id":5,"media_type":"movie","title":"Quiet Water","release_date":"2021-04-01"}],"crew":[]}""")
        })
        val tv = device("tv", http).ready()
        tv.addPlaylist("Home", list)
        tv.state.first { it.content.movies.isNotEmpty() }
        assertEquals(TmdbCheck.ACCEPTED, tv.checkAndSaveTmdbToken("GOOD"))
        val first = tv.tmdbWorks("Aster")
        assertEquals(listOf("Quiet Water"), first?.map { (it as TrendingMatch.MovieMatch).movie.name })
        assertEquals(first, tv.tmdbWorks("aster"))
        assertEquals(1, http.requested.count { it == credits })
        time += OndoRepository.PERSON_CREDITS_FRESH_MS
        tv.tmdbWorks("Aster")
        assertEquals("a day later, still kept", 1, http.requested.count { it == credits })
        time += OndoRepository.PERSON_WORKS_FRESH_MS
        tv.tmdbWorks("Aster")
        assertEquals(2, http.requested.count { it == credits })
    }

    /** A token that arrived by sync was checked elsewhere: when TMDb refuses it here, Settings says so. */
    @Test
    fun aTokenTmdbRefusesLaterIsShownAsRefused() = runTest {
        val http = Http(answers)
        val tv = device("tv", http).ready()
        tv.addPlaylist("Home", list)
        tv.state.first { it.content.movies.isNotEmpty() }
        tv.mergeSync(SyncSnapshot(device = "phone", keys = listOf(SyncKey(SyncKey.TMDB, "REVOKED", time))), "tv")
        assertEquals("REVOKED", tv.state.value.settings.tmdbToken)
        assertTrue(tv.state.first { it.tmdbRefused }.tmdbRefused)
        // Nothing more is asked with it.
        val asked = http.requested.size
        tv.loadTrending(force = true)
        // What is due now, the repository's work in backgroundScope too (advanceUntilIdle leaves it).
        repeat(20) { testScheduler.runCurrent() }
        assertEquals(asked, http.requested.size)
        assertFalse(tv.state.value.trending.isNotEmpty())
    }
}
