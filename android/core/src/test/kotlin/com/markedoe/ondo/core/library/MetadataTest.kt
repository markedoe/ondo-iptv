package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.Series
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetadataTest {
    /** TMDb's id when the provider gives it, else the title's words and year. */
    @Test
    fun aTitlesKeysDoNotDependOnThePlaylist() {
        assertEquals(listOf("movie:603", "movie:sandstorm:2021"), MetadataKey.all(series = false, tmdbId = " 603 ", name = "FR - Sandstorm (2021)", year = null))
        assertEquals(listOf("series:coppervalley:"), MetadataKey.all(series = true, tmdbId = "x", name = "Copper Valley", year = null))
        assertEquals("movie:sandstorm:2020", MetadataKey.title(series = false, name = "Sandstorm (2021)", year = 2020), "the list's year first")
        assertNull(MetadataKey.title(series = false, name = "[4K]", year = 2020), "no word, no key")
        assertNull(MetadataKey.tmdb(series = false, id = "0"))
        assertEquals("jose silva", MetadataKey.person(" José SILVA "))
    }

    @Test
    fun aKnownTitleIsTrustedLongerThanAnUnknownOne() {
        val day = 24 * 60 * 60 * 1000L
        val found = TitleMetadata("movie:1", "movie", fetchedAt = 0)
        val notFound = found.copy(found = false)
        assertTrue(MetadataFreshness.isFresh(found, 20 * day))
        assertFalse(MetadataFreshness.isFresh(found, 31 * day))
        assertTrue(MetadataFreshness.isFresh(notFound, 6 * day))
        assertFalse(MetadataFreshness.isFresh(notFound, 8 * day))
        assertFalse(MetadataFreshness.isFresh(found, -day), "a date in the future is not trusted")
    }

    @Test
    fun aRatingNeedsVotes() {
        val title = TitleMetadata("movie:1", "movie", voteAverage = 7.3, voteCount = 120, fetchedAt = 0)
        assertEquals(TmdbRating(7.3, 120), title.rating)
        assertNull(title.copy(voteCount = 0).rating)
        assertNull(title.copy(found = false).rating)
    }

    /** TMDb's answer, "not found" included, and its credits: directors first, each in TMDb's order. */
    @Test
    fun anAnswerIsKeptWithItsCredits() {
        val info = TmdbTitleInfo(
            7, "https://image.tmdb.org/t/p/w1280/b.jpg", "trailer0001", TmdbRating(8.0, 10),
            directors = listOf(TmdbPerson(1, "Kim Park", profilePath = "/kim.jpg")),
            cast = listOf(TmdbPerson(2, "Ana Lima"), TmdbPerson(3, "José Silva", profilePath = "/jose.jpg")),
        )
        val row = TitleMetadata.of("series:copper:", series = true, info = info, fetchedAt = 5)
        assertEquals(TitleMetadata("series:copper:", "series", 7, info.backdropUrl, "trailer0001", 8.0, 10, 5, found = true), row)
        assertFalse(TitleMetadata.of("movie:x:", series = false, info = null, fetchedAt = 5).found)
        assertEquals(
            listOf(
                CreditRecord("kim park", "Kim Park", CreditRecord.Role.DIRECTOR, 0, "https://image.tmdb.org/t/p/w185/kim.jpg"),
                CreditRecord("ana lima", "Ana Lima", CreditRecord.Role.CAST, 0, null),
                CreditRecord("jose silva", "José Silva", CreditRecord.Role.CAST, 1, "https://image.tmdb.org/t/p/w185/jose.jpg"),
            ),
            CreditRecord.of(info),
        )
    }

    /** The people the kept credits name, found by the start of their words, those credited most first. */
    @Test
    fun theStoreFindsPeopleByTheirWords() {
        val store = InMemoryMetadataStore()
        val ana = CreditRecord("ana lima", "Ana Lima", CreditRecord.Role.CAST, 0, "https://example.com/ana.jpg")
        val anabel = CreditRecord("anabel moss", "Anabel Moss", CreditRecord.Role.CAST, 1)
        store.saveCredits(listOf(ana, anabel), listOf("movie:603", "movie:sandstorm:2021"))
        store.saveCredits(listOf(anabel), listOf("movie:lighthouse:2010"))
        assertEquals(listOf(IndexedPerson("Anabel Moss", null, 3), IndexedPerson("Ana Lima", "https://example.com/ana.jpg", 2)), store.people("ana", 10))
        assertEquals(listOf("Ana Lima"), store.people("lim an", 10).map { it.name })
        assertTrue(store.people("an", 10).isEmpty(), "three letters at least")
        assertEquals(listOf("movie:603", "movie:sandstorm:2021"), store.titleKeys("ana lima"))
        // A title's credits are replaced, not added to.
        store.saveCredits(listOf(anabel), listOf("movie:603", "movie:sandstorm:2021"))
        assertTrue(store.titleKeys("ana lima").isEmpty())
    }

    /** A person's title keys lead to the catalogue's titles, whatever the copy; a TMDb id key leads nowhere. */
    @Test
    fun titleKeysLeadToTheCatalogue() {
        val url = "http://example.com/1.mp4"
        val movies = listOf(
            Movie(id = "1", name = "FR - Sandstorm [MULTI] 4K", categoryId = "c", streamUrl = url, year = 2021),
            Movie(id = "2", name = "Sandstorm", categoryId = "c", streamUrl = url, year = 1988),
            Movie(id = "3", name = "Sandstorm (2022)", categoryId = "c", streamUrl = url),
        )
        val series = listOf(Series(id = "s", name = "Copper Valley (US)", categoryId = "c"))
        val titles = MetadataTitles(movies, series)
        assertEquals(listOf("m-1", "m-3"), titles.match(listOf("movie:sandstorm:2021", "movie:603")).map { it.id }, "the 1988 one is another film")
        assertEquals(listOf("m-1", "m-2", "m-3"), titles.match(listOf("movie:sandstorm:")).map { it.id }, "without a year, every copy")
        assertEquals(listOf("s-s"), titles.match(listOf("series:coppervalley:2008", "series:coppervalley:")).map { it.id }, "each once")
        assertTrue(titles.match(listOf("movie:603", "nonsense")).isEmpty())
    }
}
