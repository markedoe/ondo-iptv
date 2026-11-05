package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.HangGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The selections' tags, their match and the daily pick (the same cases as the Swift `SelectionsTests`). */
class SelectionsTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val drama = Selections.genre("Drama")!!
    private val cannes = Selections.award("cannes")
    private val nineties = Selections.decade(1994)
    private val manga = Selections.style("manga")

    private val film = Selections.Facts(genres = setOf("drama", "thriller"), awardGroups = setOf("cannes"), year = 1994)
    private val anime = Selections.Facts(genres = setOf("animation"), year = 2003, styles = Selections.stylesOf("VOSTFR | Mangas"))

    @Test
    fun tagsAreNamedByKind() {
        assertEquals("genre:drama", drama)
        assertEquals("genre:science fiction", Selections.genre("  Science-Fiction "))
        assertNull(Selections.genre(" - "))
        assertEquals("award:cannes", cannes)
        assertEquals("decade:1990", nineties)
        assertEquals("decade:2000", Selections.decade(2000))
        assertEquals("style:manga", manga)
        assertEquals("sel:abc", Selections.rowKey("abc"))
        assertEquals("abc", Selections.idOfRow("sel:abc"))
        assertNull(Selections.idOfRow("cat:1|drama"))
        assertNull(Selections.idOfRow("sel:"))
        assertEquals(setOf("kids"), Selections.stylesOf("Kids & Cartoons"))
    }

    @Test
    fun eachGroupOfTagsMatches() {
        assertTrue(Selections.matches(listOf(drama), false, film))
        assertFalse(Selections.matches(listOf(drama), false, anime))
        assertTrue(Selections.matches(listOf(cannes), false, film))
        assertFalse(Selections.matches(listOf(cannes), false, anime))
        assertTrue(Selections.matches(listOf(nineties), false, film))
        assertFalse(Selections.matches(listOf(nineties), false, anime))
        assertFalse(Selections.matches(listOf(nineties), false, Selections.Facts()))
        assertTrue(Selections.matches(listOf(manga), false, anime))
        assertFalse(Selections.matches(listOf(manga), false, film))
    }

    @Test
    fun anyTagOrAllTags() {
        assertTrue(Selections.matches(listOf(drama, manga), false, film))
        assertTrue(Selections.matches(listOf(drama, manga), false, anime))
        assertFalse(Selections.matches(listOf(drama, manga), true, film))
        assertTrue(Selections.matches(listOf(drama, cannes, nineties), true, film))
        // No tag matches nothing; a tag of a kind this version does not know is left out, in both modes.
        assertFalse(Selections.matches(emptyList(), false, film))
        assertFalse(Selections.matches(emptyList(), true, film))
        assertTrue(Selections.matches(listOf(drama, "mood:dark"), true, film))
        assertFalse(Selections.matches(listOf("mood:dark"), false, film))
        assertFalse(Selections.matches(listOf("genre:"), false, film))
    }

    @Test
    fun theDrawIsTheSameOnEveryPlatform() {
        // The same numbers as the Swift test: the daily order is the same on Android and Apple devices.
        assertEquals(0.010753497314070604, Selections.draw("a"), 0.0)
        assertEquals(0.8617043371713481, Selections.draw("s1|2026-11-05|movie:42"), 0.0)
        for (seed in listOf("", "x", "s1|2026-11-06|series:7")) assertTrue(Selections.draw(seed).let { it >= 0.0 && it < 1.0 })
    }

    private data class Title(val id: String, val rating: TmdbRating?, val watched: Boolean = false)

    private fun titles() = (0 until 30).map { index ->
        Title("movie:$index", if (index % 3 == 0) null else TmdbRating(5.0 + index % 5, 50 * (index + 1)))
    }

    private fun order(titles: List<Title>, day: String, id: String = "s1") =
        Selections.dailyOrder(titles, id, day, { it.id }, { it.rating }, { it.watched }).map { it.id }

    @Test
    fun theSameDayGivesTheSameOrderAndTheNextDayAnother() {
        val list = titles()
        val today = order(list, "2026-11-05")
        assertEquals(today, order(list.reversed(), "2026-11-05"))
        assertEquals(list.map { it.id }.toSet(), today.toSet())
        assertNotEquals(today, order(list, "2026-11-06"))
        // Another selection of the same titles has its own pick.
        assertNotEquals(today, order(list, "2026-11-05", id = "s2"))
        assertEquals(listOf("movie:29", "movie:14", "movie:4", "movie:19", "movie:22"), today.take(5))
    }

    @Test
    fun theBestRatedComeFirstMostDays() {
        // Over many days, a title of 9 with thousands of votes leads the row far more often than one TMDb does not know.
        val top = Title("movie:top", TmdbRating(9.0, 20_000))
        val unknown = Title("movie:unknown", null)
        val few = Title("movie:few", TmdbRating(10.0, 5))
        val topFirst = (1..28).count { day -> order(listOf(unknown, few, top), String.format(java.util.Locale.ROOT, "2026-02-%02d", day)).first() == "movie:top" }
        assertTrue("$topFirst", topFirst > 20)
        // Too few votes count as no rating.
        assertEquals(WeightedRating.PRIOR_AVERAGE, Selections.score(TmdbRating(10.0, 5)), 0.0)
        assertEquals(WeightedRating.PRIOR_AVERAGE, Selections.score(null), 0.0)
        assertEquals(7.5, Selections.score(TmdbRating(8.5, 200)), 0.0001)
    }

    @Test
    fun titlesWatchedComeLast() {
        val list = titles().mapIndexed { index, title -> title.copy(watched = index % 4 == 0) }
        val picked = order(list, "2026-11-05")
        val watched = list.filter { it.watched }.map { it.id }.toSet()
        assertEquals(watched, picked.takeLast(watched.size).toSet())
        assertFalse(picked[0] in watched)
    }

    @Test
    fun theDefaultNameAndTheDecades() {
        assertEquals(
            "genre:drama · award:cannes · decade:1990",
            Selections.defaultName(listOf(drama, "mood:dark", cannes, nineties, manga)) { it },
        )
        assertEquals(listOf(2020, 2000, 1990), Selections.decades(listOf(1994, null, 1999, 2003, 1, 2024)))
    }
}
