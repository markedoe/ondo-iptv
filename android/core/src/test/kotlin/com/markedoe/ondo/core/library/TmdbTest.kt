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
        assertEquals("mobland", CatalogueMatcher.filmKey("|FR-4K DV| MobLand"), "a provider's tag between bars is not the title")
        assertEquals("mobland", CatalogueMatcher.searchQuery("|FR-4K DV| MobLand (MULTi)"))
        // Bars set apart by spaces separate the parts of a name: the title between them stays.
        assertEquals("sandstorm", CatalogueMatcher.key("FR | Sandstorm | 4K"))
        assertEquals("sandstorm", CatalogueMatcher.searchQuery("| FR | Sandstorm |MULTI|"))
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
        http.routes["https://api.themoviedb.org/3/movie/910001?append_to_response=credits,videos,recommendations,images&include_video_language=fr,en,null&include_image_language=fr,en,null&language=fr-FR&api_key=KEY"] = (
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

    /** The same call brings the title's genres and the titles TMDb recommends with it; a genre's titles come by page. */
    @Test
    fun genresAndRecommendationsComeWithTheTitle() = runTest {
        val http = MockHttpClient()
        http.routes["https://api.themoviedb.org/3/movie/5?append_to_response=credits,videos,recommendations,images&include_video_language=en,null&include_image_language=en,null&language=en-US&api_key=KEY"] = (
            """{"id":5,"title":"Harbor","genres":[{"id":80,"name":"Crime"},{"id":18,"name":"Drama"}],""" +
                """"recommendations":{"results":[{"id":6,"title":"Dock Street","release_date":"2015-03-01"},""" +
                """{"id":7,"name":"Port Watch","media_type":"tv","first_air_date":"2019-01-01"},{"id":8,"name":"Somebody","media_type":"person"}]}}"""
            ).encodeToByteArray()
        http.routes["https://api.themoviedb.org/3/genre/movie/list?language=en-US&api_key=KEY"] =
            """{"genres":[{"id":80,"name":"Crime"}]}""".encodeToByteArray()
        http.routes["https://api.themoviedb.org/3/discover/movie?with_genres=80&sort_by=popularity.desc&page=2&language=en-US&api_key=KEY"] =
            """{"results":[{"id":6,"title":"Dock Street","release_date":"2015-03-01"}]}""".encodeToByteArray()
        val client = TmdbClient("KEY", http)
        val info = client.titleInfo(series = false, tmdbId = "5", title = "Harbor", year = null)!!
        assertEquals(listOf(TmdbGenre(80, "Crime"), TmdbGenre(18, "Drama")), info.genres)
        assertEquals(listOf("Dock Street" to TmdbItem.MediaType.MOVIE, "Port Watch" to TmdbItem.MediaType.TV), info.related.map { it.title to it.mediaType })
        assertEquals(listOf(TmdbGenre(80, "Crime")), client.genres(series = false))
        assertEquals(listOf(6 to TmdbItem.MediaType.MOVIE), client.discover(series = false, genreId = 80, page = 2).map { it.id to it.mediaType })
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
        http.routes["https://api.themoviedb.org/3/movie/3?append_to_response=credits,videos,recommendations,images&include_video_language=en,null&include_image_language=en,null&language=en-US&api_key=KEY"] =
            """{"id":3,"title":"Sandstorm","credits":{"cast":[{"id":21,"name":"Ruth Ames","profile_path":"/ruth.jpg"}],"crew":[]}}""".encodeToByteArray()
        http.routes["https://api.themoviedb.org/3/search/tv?query=copper%20valley&language=en-US&api_key=KEY"] =
            """{"results":[{"id":7,"name":"Copper Valley","first_air_date":"2008-01-20"}]}""".encodeToByteArray()
        http.routes["https://api.themoviedb.org/3/tv/7?append_to_response=credits,videos,recommendations,images&include_video_language=en,null&include_image_language=en,null&language=en-US&api_key=KEY"] =
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

    /**
     * A subtitle tag before a bar ("STFR| ") is the provider's, not the title's: the film is searched and matched without
     * it, so its page gets TMDb's cast with their photos.
     */
    @Test
    fun aSubtitleTagBeforeABarIsNotSearched() = runTest {
        val http = MockHttpClient()
        http.routes["https://api.themoviedb.org/3/search/movie?query=the%20gardener&language=en-US&api_key=KEY"] =
            """{"results":[{"id":31,"title":"The Gardener","release_date":"2026-02-01"}]}""".encodeToByteArray()
        http.routes["https://api.themoviedb.org/3/movie/31?append_to_response=credits,videos,recommendations,images&include_video_language=en,null&include_image_language=en,null&language=en-US&api_key=KEY"] =
            """{"id":31,"title":"The Gardener","credits":{"cast":[{"id":41,"name":"Nora Quill","profile_path":"/nora.jpg"}],"crew":[]}}""".encodeToByteArray()
        val info = TmdbClient("KEY", http).titleInfo(series = false, tmdbId = null, title = "STFR| The Gardener", year = 2026)
        assertEquals(31, info?.tmdbId)
        assertEquals("https://image.tmdb.org/t/p/w185/nora.jpg", info?.cast?.single()?.profileUrl)
        assertEquals("movie:thegardener:2026", MetadataKey.title(series = false, name = "STFR| The Gardener", year = 2026))
        for (title in listOf("STFR| The Gardener", "ST FR - The Gardener", "VOSTFR | The Gardener", "[SUBFR] The Gardener", "FR SUB | The Gardener", "The Gardener VOSTFR")) {
            assertEquals("thegardener", CatalogueMatcher.key(title), title)
        }
    }

    /** A refused token is not taken for "TMDb does not know this title": it throws. */
    @Test
    fun aRefusedTokenIsNotANotFound() = runTest {
        val http = MockHttpClient()
        http.failures["https://api.themoviedb.org/3/movie/910001?append_to_response=credits,videos,recommendations,images&include_video_language=en,null&include_image_language=en,null&language=en-US&api_key=KEY"] =
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

    @Test
    fun theLogoShownIsTheViewersLanguageThenEnglishThenNone() {
        val french = TmdbLogo("/fr.png", "fr")
        val english = TmdbLogo("/en.png", "en")
        val none = TmdbLogo("/none.png", null)
        val german = TmdbLogo("/de.png", "de")
        assertEquals(french, TmdbLogo.best(listOf(none, english, german, french), "fr"))
        assertEquals(french, TmdbLogo.best(listOf(english, french), "fr-FR"))
        assertEquals(english, TmdbLogo.best(listOf(none, german, english), "fr"))
        assertEquals(none, TmdbLogo.best(listOf(german, none), "fr"))
        assertNull(TmdbLogo.best(listOf(german), "fr"), "another language's words are not shown")
        assertEquals(TmdbLogo("/first.png", "en"), TmdbLogo.best(listOf(TmdbLogo("/first.png", "en"), TmdbLogo("/second.png", "en")), "en"), "TMDb's order: the best voted first")
        assertEquals(english, TmdbLogo.best(listOf(TmdbLogo("/fr.svg", "fr"), english), "fr"), "an SVG is left out")
        assertEquals("https://image.tmdb.org/t/p/w500/en.png", english.url)
    }

    @Test
    fun aTitlesLogoComesWithItsDetails() = runTest {
        val http = MockHttpClient()
        http.routes["https://api.themoviedb.org/3/movie/42?append_to_response=credits,videos,recommendations,images&include_video_language=fr,en,null&include_image_language=fr,en,null&language=fr-FR&api_key=KEY"] =
            """{"id":42,"title":"Le Transport","images":{"logos":[{"file_path":"/en.png","iso_639_1":"en"},{"file_path":"/fr.png","iso_639_1":"fr"}]}}""".encodeToByteArray()
        val info = TmdbClient("KEY", http, language = "fr-FR").titleInfo(series = false, tmdbId = "42", title = "Le Transport", year = null)
        assertEquals("https://image.tmdb.org/t/p/w500/fr.png", info?.logoUrl)
        assertEquals(info?.logoUrl, TitleMetadata.of("movie:42", series = false, info = info, fetchedAt = 1).logoUrl)
    }

    @Test
    fun aProvidersTitleWithoutItsTags() {
        assertEquals("Le Transport", CatalogueMatcher.displayTitle("|FR| Le Transport"))
        assertEquals("Le Transport", CatalogueMatcher.displayTitle("[FR] Le Transport"))
        assertEquals("Le Transport", CatalogueMatcher.displayTitle("FR - Le Transport"))
        assertEquals("Le Transport", CatalogueMatcher.displayTitle("FR: Le Transport"))
        assertEquals("Le Transport", CatalogueMatcher.displayTitle("Le Transport VFF"))
        assertEquals("Le Transport", CatalogueMatcher.displayTitle("Le Transport MULTI 4K"))
        assertEquals("Le Transport", CatalogueMatcher.displayTitle("Le Transport (MULTI) (2021)"))
        assertEquals("Le Transport", CatalogueMatcher.displayTitle("|FR-4K DV| Le Transport [VOSTFR]"))
        assertEquals("MobLand", CatalogueMatcher.displayTitle("MobLand |MULTI|"))
        // Subtitle tags, before a bar, a dash or in brackets, in one word or two.
        for (tag in listOf("STFR| ", "STFR | ", "ST FR - ", "VOSTFR| ", "SUBFR - ", "FR SUB | ", "VOST - ", "[STFR] ", "[ST FR] ")) {
            assertEquals("Le Transport", CatalogueMatcher.displayTitle(tag + "Le Transport"), tag)
        }
        assertEquals("Le Transport", CatalogueMatcher.displayTitle("Le Transport STFR"))
        // The film's own words stay.
        assertEquals("St. Elmo's Fire", CatalogueMatcher.displayTitle("St. Elmo's Fire"))
        assertEquals("Sub Rosa - The Return", CatalogueMatcher.displayTitle("Sub Rosa - The Return"))
        assertEquals("Spider-Man: No Way Home", CatalogueMatcher.displayTitle("Spider-Man: No Way Home"))
        assertEquals("De Gaulle", CatalogueMatcher.displayTitle("De Gaulle"))
        assertEquals("Up", CatalogueMatcher.displayTitle("Up"))
        assertEquals("Star Runner 2049", CatalogueMatcher.displayTitle("Star Runner 2049"))
        assertEquals("Vost", CatalogueMatcher.displayTitle("Vost"))
        // Tags that are words too stay words unless in capitals.
        assertEquals("French Kiss", CatalogueMatcher.displayTitle("French Kiss"))
        assertEquals("Johnny English", CatalogueMatcher.displayTitle("Johnny English"))
        assertEquals("Le Transport", CatalogueMatcher.displayTitle("Le Transport FRENCH"))
        // Nothing left: the title as it is.
        assertEquals("[MULTI]", CatalogueMatcher.displayTitle("[MULTI]"))
    }
}
