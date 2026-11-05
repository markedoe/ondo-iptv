package com.markedoe.ondo.ui

import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.library.AwardsCatalogue
import com.markedoe.ondo.core.library.RowOrder
import com.markedoe.ondo.ui.screens.BrowseKind
import com.markedoe.ondo.ui.screens.SearchFilters
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** TV series awards on every surface films show theirs: the Series tab's row, the series page, award pages, search. */
class SeriesAwardsTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    // "Harbor Lights" (2025) won an Emmy; "The Archive" (2024) was nominated for a Golden Globe. A series called "Glass
    // City" won an Emmy and a film called "Harbor Lights" a Palme d'Or: neither may reach the title of the other kind.
    private val catalogue = AwardsCatalogue.parse(
        """
        {"awards":[{"id":"palme-dor","group":"cannes","names":{"en":"Palme d'Or","fr":"Palme d'or"}}],
         "films":[{"titles":["Glass City"],"year":2024,"awards":[["palme-dor",2024,1]]},
                  {"titles":["Harbor Lights"],"year":2025,"awards":[["palme-dor",2025,1]]}],
         "seriesAwards":[{"id":"emmy-drama-series","group":"emmys","names":{"en":"Primetime Emmy Award for Outstanding Drama Series","fr":"Emmy de la meilleure série dramatique"}},
                         {"id":"golden-globe-tv-drama","group":"golden-globes","names":{"en":"Golden Globe Award for Best Television Series – Drama"}}],
         "series":[{"titles":["Harbor Lights"],"year":2025,"awards":[["emmy-drama-series",2025,1],["golden-globe-tv-drama",2026,0]]},
                   {"titles":["The Archive"],"year":2024,"awards":[["golden-globe-tv-drama",2025,0]]},
                   {"titles":["Glass City"],"year":2024,"awards":[["emmy-drama-series",2024,1]]}]}
        """.trimIndent(),
    )
    private val state = Fixtures.state().copy(awards = catalogue)

    @Test
    fun aSeriesPageListsItsAwardsInTheViewersLanguage() {
        assertEquals(
            listOf(
                AwardRow("Emmy de la meilleure série dramatique", 2025, won = true, festival = false, awardId = "emmy-drama-series"),
                AwardRow("Golden Globe Award for Best Television Series – Drama", 2026, won = false, festival = false, awardId = "golden-globe-tv-drama"),
            ),
            UiMappers.seriesDetail(state, Fixtures.harborLights, null, null, Locale.FRENCH).awards,
        )
        assertTrue(UiMappers.seriesDetail(state, Fixtures.blueHorizon, null, null).awards.isEmpty())
        // The movie "Glass City" keeps its own award, not the series' Emmy.
        assertEquals(listOf("palme-dor"), UiMappers.movieDetail(state, Fixtures.glassCity).awards.map { it.awardId })
    }

    @Test
    fun theSeriesTabShowsItsWinnersAndNoRowWithoutThem() {
        val series = UiMappers.vod(state, series = true, title = "")
        assertEquals(listOf("Harbor Lights"), series.awardWinners.map { it.title })
        assertTrue(RowOrder.AWARD_WINNERS in series.shownRows)
        assertEquals(listOf("Glass City"), UiMappers.vod(state, series = false, title = "").awardWinners.map { it.title })
        // A file without series (or a playlist none of whose series won): no row.
        val filmsOnly = Fixtures.state().copy(
            awards = AwardsCatalogue.parse("""{"awards":[{"id":"palme-dor","group":"cannes","names":{"en":"Palme d'Or"}}],"films":[{"titles":["Harbor Lights"],"year":2025,"awards":[["palme-dor",2025,1]]}]}"""),
        )
        val none = UiMappers.vod(filmsOnly, series = true, title = "")
        assertTrue(none.awardWinners.isEmpty())
        assertFalse(RowOrder.AWARD_WINNERS in none.shownRows)
        // The row has its key in Settings → Row order, the same as in Movies.
        assertTrue(RowOrder.AWARD_WINNERS in UiMappers.tabRowKeys(emptyList(), series = true))
    }

    @Test
    fun anAwardOrACeremonyOpensItsSeries() {
        val award = UiMappers.browse(state, BrowseKind.AWARD, "golden-globe-tv-drama")
        assertEquals("Golden Globe Award for Best Television Series – Drama", award.name)
        assertEquals("the most recent first", listOf("Harbor Lights", "The Archive"), award.series.map { it.title })
        assertTrue(award.movies.isEmpty())
        assertEquals("golden-globes", award.festival)
        assertEquals(listOf("Harbor Lights"), UiMappers.browse(state, BrowseKind.FESTIVAL, "emmys").series.map { it.title })
        // A film award opens films only.
        val palme = UiMappers.browse(state, BrowseKind.AWARD, "palme-dor")
        assertEquals(listOf("Glass City"), palme.movies.map { it.title })
        assertTrue(palme.series.isEmpty())
    }

    @Test
    fun searchFiltersSeriesByTheirAwards() {
        val index = UiMappers.SearchIndex(state.content, UiMappers.hiddenCategoryIds(state))
        fun titles(filters: SearchFilters) = UiMappers.search(index, "", state, Fixtures.NOW, filters).let { result ->
            result.movies.map { it.title } to result.series.map { it.title }
        }
        assertEquals(emptyList<String>() to listOf("Harbor Lights", "The Archive"), titles(SearchFilters(awardGroup = "golden-globes")))
        assertEquals(emptyList<String>() to emptyList<String>(), titles(SearchFilters(awardGroup = "golden-globes", winnersOnly = true)))
        assertEquals(emptyList<String>() to listOf("Harbor Lights"), titles(SearchFilters(awardGroup = "emmys", winnersOnly = true)))
        assertEquals(listOf("Glass City") to emptyList<String>(), titles(SearchFilters(awardGroup = "cannes")))
        assertEquals(listOf("cannes", "emmys", "golden-globes"), UiMappers.search(index, "", state, Fixtures.NOW).awardGroups)
    }
}
