package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.Fixtures
import com.markedoe.ondo.core.MockHttpClient
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.Series
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class TmdbTest {
    @Test
    fun trendingDecodingAndMatching() = runTest {
        val http = MockHttpClient()
        http.routes["https://api.themoviedb.org/3/trending/all/week?language=fr-FR&api_key=KEY"] = Fixtures.bytes("tmdb_trending.json")
        val items = TmdbClient(" KEY ", http, language = "fr-FR").trending()
        assertEquals(4, items.size, "people are filtered out")
        assertEquals("Breaking Bad", items[1].title)
        assertEquals(2008, items[1].year)
        assertEquals("https://image.tmdb.org/t/p/w342/czembW0Rk1Ke7lCJGahbOhdCuhV.jpg", items[0].posterUrl)
        assertEquals(mapOf("Accept" to "application/json"), http.requestHeaders.single())

        val url = "http://x/1.mp4"
        val movies = listOf(
            Movie(id = "1", name = "FR - Dune: Part Two [MULTI] 4K", categoryId = "c", streamUrl = url, year = 2024),
            Movie(id = "2", name = "Inception", categoryId = "c", streamUrl = url, year = 1990),
            Movie(id = "3", name = "Fight Club (1999)", categoryId = "c", streamUrl = url),
        )
        val series = listOf(Series(id = "s", name = "Breaking Bad (US)", categoryId = "c"))
        val matches = CatalogueMatcher.match(items, movies, series)
        assertEquals(listOf("m-1", "s-s"), matches.take(2).map { it.id })
        assertEquals(listOf("m-1", "s-s", "m-3"), matches.map { it.id }, "Inception rejected: year too far apart")
        assertEquals("Fight Club", matches[2].trending.title)
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
}
