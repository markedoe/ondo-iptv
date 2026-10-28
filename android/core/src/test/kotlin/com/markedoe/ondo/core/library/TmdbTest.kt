package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.Fixtures
import com.markedoe.ondo.core.MockHttpClient
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.core.net.HttpException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class TmdbTest {
    @Test
    fun trendingDecodingAndMatching() = runTest {
        val http = MockHttpClient()
        http.routes["https://api.themoviedb.org/3/trending/all/week?language=fr-FR&api_key=KEY"] = Fixtures.bytes("tmdb_trending.json")
        val items = TmdbClient(" KEY ", http, language = "fr-FR").trending()
        assertEquals(4, items.size, "people are filtered out")
        assertEquals("Copper Valley", items[1].title)
        assertEquals(2008, items[1].year)
        assertEquals("https://image.tmdb.org/t/p/w342/poster-sandstorm.jpg", items[0].posterUrl)
        assertEquals(mapOf("Accept" to "application/json"), http.requestHeaders.single())

        val url = "http://x/1.mp4"
        val movies = listOf(
            Movie(id = "1", name = "FR - Sandstorm: Part Two [MULTI] 4K", categoryId = "c", streamUrl = url, year = 2024),
            Movie(id = "2", name = "Lighthouse", categoryId = "c", streamUrl = url, year = 1990),
            Movie(id = "3", name = "Paper Trail (1999)", categoryId = "c", streamUrl = url),
        )
        val series = listOf(Series(id = "s", name = "Copper Valley (US)", categoryId = "c"))
        val matches = CatalogueMatcher.match(items, movies, series)
        assertEquals(listOf("m-1", "s-s"), matches.take(2).map { it.id })
        assertEquals(listOf("m-1", "s-s", "m-3"), matches.map { it.id }, "Lighthouse rejected: year too far apart")
        assertEquals("Paper Trail", matches[2].trending.title)
    }

    @Test
    fun copiesOfOneFilmShareItsKey() {
        val sandstorm = listOf("Sandstorm", "FR - Sandstorm (2021) 4K", "Sandstorm VOSTFR", "Sandstorm [MULTI]", "|EN| Sandstorm HD", "Sandstorm (VF)", "Sandstorm VF")
        assertEquals(setOf("sandstorm"), sandstorm.map(CatalogueMatcher::filmKey).toSet())
        assertEquals("it", CatalogueMatcher.filmKey("It"), "a title is never a version word only")
        assertEquals("", CatalogueMatcher.filmKey("[4K]"))
        assertTrue(CatalogueMatcher.isSameFilm("Sandstorm VOSTFR", null, "FR - Sandstorm (2021)", null))
        assertTrue(CatalogueMatcher.isSameFilm("Sandstorm", 2021, "Sandstorm EN", 2022), "release dates differ between countries")
        assertFalse(CatalogueMatcher.isSameFilm("Dune (1984)", null, "Dune (2021)", null), "a remake is another film")
        assertFalse(CatalogueMatcher.isSameFilm("Dune", 1984, "Dune", 2021))
        assertFalse(CatalogueMatcher.isSameFilm("Sandstorm", null, "Sandstorm 2", null), "a sequel is another film")
        assertFalse(CatalogueMatcher.isSameFilm("[4K]", null, "[HD]", null), "nothing left to compare")
    }

    @Test
    fun bearerTokenUsesHeader() = runTest {
        val http = MockHttpClient()
        val token = "a".repeat(120)
        http.routes["https://api.themoviedb.org/3/trending/all/week?language=en-US"] = """{"results":[]}""".encodeToByteArray()
        val items = TmdbClient(token, http).trending()
        assertTrue(items.isEmpty())
        assertEquals("Bearer $token", http.requestHeaders.single()["Authorization"])
    }

    @Test
    fun malformedPagesAreRejected() = runTest {
        val http = MockHttpClient()
        http.routes["https://api.themoviedb.org/3/trending/all/day?language=en-US&api_key=k"] = """{"results":[{"title":"No id"}]}""".encodeToByteArray()
        assertTrue(runCatching { TmdbClient("k", http).trending("day") }.isFailure)
    }

    /**
     * Everything shown of a title from TMDb comes in one call: by the provider's TMDb id, its backdrop, its trailer (the
     * viewer's language first, a trailer before a teaser), its rating with its votes, its directors (no editor) then its
     * cast, with their photos.
     */
    @Test
    fun aTitlesInfoComesInOneCallByItsId() = runTest {
        val http = MockHttpClient()
        http.routes["https://api.themoviedb.org/3/movie/910001?append_to_response=credits,videos&include_video_language=fr,en,null&language=fr-FR&api_key=KEY"] = (
            """{"id":910001,"title":"Lighthouse","backdrop_path":"/lighthouse-wide.jpg","vote_average":7.346,"vote_count":12345,"credits":{""" +
                """"cast":[{"id":11,"name":"Ana Lima","profile_path":"/ana.jpg","known_for_department":"Acting"},""" +
                """{"id":12,"name":"José Silva","profile_path":null}],""" +
                """"crew":[{"id":13,"name":"Kim Park","job":"Director","profile_path":"/kim.jpg"},{"id":14,"name":"Lee Moss","job":"Editor","profile_path":"/lee.jpg"}]},""" +
                """"videos":{"results":[""" +
                """{"key":"teaserFR001","site":"YouTube","type":"Teaser","official":true,"iso_639_1":"fr"},""" +
                """{"key":"trailerEN01","site":"YouTube","type":"Trailer","official":true,"iso_639_1":"en"},""" +
                """{"key":"trailerFR01","site":"YouTube","type":"Trailer","official":false,"iso_639_1":"fr"},""" +
                """{"key":"vimeo123456","site":"Vimeo","type":"Trailer","official":true,"iso_639_1":"fr"}]}}"""
            ).encodeToByteArray()
        val info = TmdbClient("KEY", http, language = "fr-FR").titleInfo(series = false, tmdbId = "910001", title = "Lighthouse", year = 2010)!!
        assertEquals(910001, info.tmdbId)
        assertEquals("https://image.tmdb.org/t/p/w1280/lighthouse-wide.jpg", info.backdropUrl)
        assertEquals("trailerFR01", info.trailerKey, "a French trailer first")
        assertEquals(TmdbRating(7.346, 12345), info.rating)
        assertEquals(listOf("Kim Park"), info.directors.map { it.name }, "no editor")
        assertEquals(listOf("Ana Lima", "José Silva"), info.cast.map { it.name })
        assertEquals(listOf("https://image.tmdb.org/t/p/w185/ana.jpg", null), info.cast.map { it.profileUrl })
        assertEquals(1, http.requested.size, "one call")
    }

    /**
     * Without an id (or with one TMDb does not know), the first search result with the same title, a year apart at most;
     * the search result's backdrop and rating when the details have none. Another title of that name is not taken.
     */
    @Test
    fun aTitlesInfoIsFoundByTitleOtherwise() = runTest {
        val http = MockHttpClient()
        http.routes["https://api.themoviedb.org/3/search/movie?query=sandstorm&language=en-US&api_key=KEY"] =
            ("""{"results":[{"id":1,"title":"Sandstorm Rising","backdrop_path":"/rising.jpg","release_date":"2021-01-01"},""" +
                """{"id":2,"title":"Sandstorm","backdrop_path":"/old.jpg","release_date":"1988-05-01"},""" +
                """{"id":3,"title":"Sandstorm","backdrop_path":"/sandstorm.jpg","release_date":"2021-03-01","vote_average":6.5,"vote_count":80}]}""").encodeToByteArray()
        http.routes["https://api.themoviedb.org/3/movie/3?append_to_response=credits,videos&include_video_language=en,null&language=en-US&api_key=KEY"] =
            """{"id":3,"title":"Sandstorm","credits":{"cast":[{"id":21,"name":"Ruth Ames","profile_path":"/ruth.jpg"}],"crew":[]}}""".encodeToByteArray()
        http.routes["https://api.themoviedb.org/3/search/tv?query=copper%20valley&language=en-US&api_key=KEY"] =
            """{"results":[{"id":7,"name":"Copper Valley","first_air_date":"2008-01-20"}]}""".encodeToByteArray()
        http.routes["https://api.themoviedb.org/3/tv/7?append_to_response=credits,videos&include_video_language=en,null&language=en-US&api_key=KEY"] =
            """{"id":7,"name":"Copper Valley","videos":{"results":[{"key":"teaserEN001","site":"YouTube","type":"Teaser","iso_639_1":"en"}]}}""".encodeToByteArray()
        http.routes["https://api.themoviedb.org/3/search/movie?query=paper%20trail&language=en-US&api_key=KEY"] =
            """{"results":[{"id":9,"title":"Paper Trail","release_date":"1960-01-01"}]}""".encodeToByteArray()
        val client = TmdbClient("KEY", http)

        // An id TMDb does not know (404): by title.
        val sandstorm = client.titleInfo(series = false, tmdbId = "404404", title = "FR - Sandstorm (2020) [4K]", year = 2020)!!
        assertEquals(3, sandstorm.tmdbId, "same title, a year apart at most")
        assertEquals("https://image.tmdb.org/t/p/w1280/sandstorm.jpg", sandstorm.backdropUrl)
        assertEquals(TmdbRating(6.5, 80), sandstorm.rating)
        assertEquals(listOf("Ruth Ames"), sandstorm.cast.map { it.name })
        assertNull(sandstorm.trailerKey)

        val copper = client.titleInfo(series = true, tmdbId = null, title = "FR - Copper Valley (US)", year = 2008)!!
        assertEquals("teaserEN001", copper.trailerKey, "a teaser when there is no trailer")
        assertNull(copper.backdropUrl, "TMDb has no backdrop either")
        assertNull(copper.rating, "no votes, no rating")
        assertTrue(copper.cast.isEmpty() && copper.directors.isEmpty())

        assertNull(client.titleInfo(series = false, tmdbId = null, title = "Paper Trail", year = 1999), "another Paper Trail")
        assertNull(client.titleInfo(series = false, tmdbId = null, title = "", year = null))
    }

    /** A refused token is not taken for "TMDb does not know this title": it throws. */
    @Test
    fun aRefusedTokenIsNotANotFound() = runTest {
        val http = MockHttpClient()
        http.failures["https://api.themoviedb.org/3/movie/910001?append_to_response=credits,videos&include_video_language=en,null&language=en-US&api_key=KEY"] =
            mutableListOf(HttpException.BadStatus(401))
        val error = assertFailsWith<HttpException.BadStatus> {
            TmdbClient("KEY", http).titleInfo(series = false, tmdbId = "910001", title = "Lighthouse", year = 2010)
        }
        assertEquals(401, error.code)
    }

    /** A key is checked with TMDb before it is saved: accepted, refused, or TMDb out of reach. */
    @Test
    fun aKeyIsCheckedBeforeItIsSaved() = runTest {
        val http = MockHttpClient()
        http.routes["https://api.themoviedb.org/3/authentication?language=en-US&api_key=GOOD"] = """{"success":true}""".encodeToByteArray()
        assertTrue(TmdbClient("GOOD", http).isAccepted())
        // TMDb answers 401 to a key it does not know.
        http.failures["https://api.themoviedb.org/3/authentication?language=en-US&api_key=BAD"] = mutableListOf(HttpException.BadStatus(401))
        assertFalse(TmdbClient("BAD", http).isAccepted())
        http.failures["https://api.themoviedb.org/3/authentication?language=en-US&api_key=OFFLINE"] = mutableListOf(UnknownHostException("api.themoviedb.org"))
        assertFailsWith<UnknownHostException>("an unreachable TMDb is not a refusal") { TmdbClient("OFFLINE", http).isAccepted() }
    }

    /** Only TMDb's own answer accepts a key: a page that is not it (a network's sign-in page) checks nothing. */
    @Test
    fun anAnswerThatIsNotTmdbsAcceptsNoKey() = runTest {
        val http = MockHttpClient()
        for ((key, body) in listOf("PAGE" to "<html><body>Sign in to the network</body></html>", "EMPTY" to "{}")) {
            http.routes["https://api.themoviedb.org/3/authentication?language=en-US&api_key=$key"] = body.encodeToByteArray()
            assertTrue(runCatching { TmdbClient(key, http).isAccepted() }.isFailure, "$body is not TMDb's answer")
        }
        // TMDb's own answer refusing a key without a 401 still refuses it.
        http.routes["https://api.themoviedb.org/3/authentication?language=en-US&api_key=NO"] =
            """{"success":false,"status_code":7,"status_message":"Invalid API key."}""".encodeToByteArray()
        assertFalse(TmdbClient("NO", http).isAccepted())
    }

    /** TMDb's people for a search, the most popular first. */
    @Test
    fun peopleAreSearchedOnTmdb() = runTest {
        val http = MockHttpClient()
        http.routes["https://api.themoviedb.org/3/search/person?query=ada&language=en-US&api_key=KEY"] =
            """{"results":[{"id":1,"name":"Ada Lowe","popularity":2.5},{"id":2,"name":"Ada Vance","popularity":9.1}]}""".encodeToByteArray()
        assertEquals(listOf("Ada Vance", "Ada Lowe"), TmdbClient("KEY", http).people(" ada ").map { it.name })
    }

    /** A name in a movie's details: the person's credits, then what of it the catalogue has. */
    @Test
    fun aPersonsCreditsAreFoundInTheCatalogue() = runTest {
        val http = MockHttpClient()
        http.routes["https://api.themoviedb.org/3/search/person?query=Ada%20Vance&language=en-US&api_key=KEY"] = Fixtures.bytes("tmdb_person_search.json")
        http.routes["https://api.themoviedb.org/3/person/902/combined_credits?language=en-US&api_key=KEY"] = Fixtures.bytes("tmdb_person_credits.json")
        val client = TmdbClient("KEY", http)

        val person = requireNotNull(client.person(" Ada Vance "))
        assertEquals(902, person.id, "the most popular person of that name")
        assertEquals("https://image.tmdb.org/t/p/w185/ada-vance.jpg", person.profileUrl)

        val credits = client.credits(person.id)
        assertEquals(
            listOf("Sandstorm: Part Two", "Lighthouse", "Copper Valley", "Paper Trail", "Unreleased Story"),
            credits.map { it.title },
            "cast and crew, each once, newest first, undated last",
        )
        val url = "http://x/1.mp4"
        val movies = listOf(
            Movie(id = "1", name = "FR - Sandstorm: Part Two [MULTI] 4K", categoryId = "c", streamUrl = url, year = 2024),
            Movie(id = "2", name = "Paper Trail (1999)", categoryId = "c", streamUrl = url),
            Movie(id = "3", name = "Another Film", categoryId = "c", streamUrl = url, year = 2015),
        )
        val series = listOf(Series(id = "s", name = "Copper Valley (US)", categoryId = "c"))
        assertEquals(listOf("m-1", "s-s", "m-2"), CatalogueMatcher.match(credits, movies, series).map { it.id })
    }

    @Test
    fun nobodyIsSearchedForAnEmptyName() = runTest {
        val http = MockHttpClient()
        assertNull(TmdbClient("KEY", http).person("  "))
        assertTrue(TmdbClient("KEY", http).people(" ").isEmpty())
        assertTrue(http.requested.isEmpty())
    }
}
