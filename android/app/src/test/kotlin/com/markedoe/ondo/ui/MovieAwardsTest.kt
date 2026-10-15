package com.markedoe.ondo.ui

import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.core.library.AwardsCatalogue
import com.markedoe.ondo.core.model.MediaDetails
import com.markedoe.ondo.ui.screens.BrowseKind
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
        assertEquals(
            listOf(
                AwardRow("Palme d'or", 2024, won = true, festival = true, awardId = "palme-dor"),
                AwardRow("Academy Award for Best Picture", 2025, won = false, festival = false, awardId = "oscar-best-picture"),
            ),
            rows,
        )
        assertTrue(UiMappers.movieDetail(state, Fixtures.northernLights).awards.isEmpty())
    }

    @Test
    fun theMoviesTabShowsTheWinnersOnly() {
        val winners = UiMappers.vod(state, series = false, title = "").awardWinners.map { it.title }
        assertEquals(listOf("Glass City"), winners)
        assertTrue(UiMappers.vod(state, series = true, title = "").awardWinners.isEmpty())
    }

    @Test
    fun theValuesOfAPageOpenTheTitlesSharingThem() {
        val award = UiMappers.browse(state, BrowseKind.AWARD, "palme-dor", Locale.FRENCH)
        assertEquals("Palme d'or", award.name)
        assertEquals("winners first", listOf("Glass City", "Silent Harbor"), award.movies.map { it.title })
        assertEquals("cannes", award.festival)
        assertEquals(listOf("Glass City", "Silent Harbor"), UiMappers.browse(state, BrowseKind.FESTIVAL, "cannes").movies.map { it.title })
        val year = UiMappers.browse(state, BrowseKind.YEAR, "2024")
        assertEquals(listOf("Glass City", "Midnight Orchard"), year.movies.map { it.title })
        assertEquals(listOf("The Archive"), year.series.map { it.title })
        // A series' genre comes with the catalogue; a movie's once its page was opened.
        assertEquals(listOf("Blue Horizon", "The Archive"), UiMappers.browse(state, BrowseKind.GENRE, "mystery").series.map { it.title })
        val opened = state.copy(movieDetails = mapOf(Fixtures.glassCity.id to MediaDetails(title = "Glass City", genre = "Science Fiction, Mystery")))
        assertEquals(listOf("Glass City"), UiMappers.browse(opened, BrowseKind.GENRE, "Mystery").movies.map { it.title })
        assertEquals(listOf("Action", "Adventure", "Sci-Fi"), UiMappers.genres("Action / Adventure, Sci-Fi"))
    }
}
