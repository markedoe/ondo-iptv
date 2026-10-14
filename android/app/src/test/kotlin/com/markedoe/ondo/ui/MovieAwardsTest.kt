package com.markedoe.ondo.ui

import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.core.library.AwardsCatalogue
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieAwardsTest {
    // "Glass City" (2024) won; "Silent Harbor" (2023) only competed.
    private val catalogue = AwardsCatalogue.parse(
        """
        {"awards":[{"id":"palme-dor","group":"cannes","names":{"en":"Palme d'Or","fr":"Palme d'or"}},
                   {"id":"oscar-best-picture","group":"oscars","names":{"en":"Academy Award for Best Picture"}}],
         "films":[{"titles":["Glass City"],"year":2024,"awards":[["palme-dor",2024,1],["oscar-best-picture",2025,0]]},
                  {"titles":["Silent Harbor"],"year":2023,"awards":[["palme-dor",2023,0]]}]}
        """.trimIndent(),
    )
    private val state = Fixtures.state().copy(awards = catalogue)

    @Test
    fun aMoviePageListsItsAwardsInTheViewersLanguage() {
        val rows = UiMappers.movieDetail(state, Fixtures.glassCity, Locale.FRENCH).awards
        assertEquals(listOf(AwardRow("Palme d'or", 2024, won = true, festival = true), AwardRow("Academy Award for Best Picture", 2025, won = false, festival = false)), rows)
        assertTrue(UiMappers.movieDetail(state, Fixtures.northernLights).awards.isEmpty())
    }

    @Test
    fun theMoviesTabShowsTheWinnersOnly() {
        val winners = UiMappers.vod(state, series = false, title = "").awardWinners.map { it.title }
        assertEquals(listOf("Glass City"), winners)
        assertTrue(UiMappers.vod(state, series = true, title = "").awardWinners.isEmpty())
    }
}
