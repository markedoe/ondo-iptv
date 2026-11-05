package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.HangGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule

/** The weighted rating of the carousel and of a grid sorted by rating. The Apple core has the same tests (`WeightedRatingTests.swift`). */
class WeightedRatingTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @Test
    fun theScoreIsTheBayesianWeightedRating() {
        // 5000 / 5200 * 7.8 + 200 / 5200 * 6.5
        assertEquals(7.75, WeightedRating.weighted(7.8, 5_000)!!, 0.001)
        // 50 / 250 * 10 + 200 / 250 * 6.5
        assertEquals(7.2, WeightedRating.weighted(10.0, 50)!!, 0.001)
        // 200 votes: halfway between the title's average and the prior.
        assertEquals(7.5, WeightedRating.weighted(8.5, 200)!!, 0.001)
        assertEquals(200.0, WeightedRating.PRIOR_VOTES, 0.0)
        assertEquals(6.5, WeightedRating.PRIOR_AVERAGE, 0.0)
    }

    // A grid sorted by rating.

    private data class Rated(val id: String, val title: String, val tmdb: TmdbRating?, val provider: Double?)

    private fun ordered(items: List<Rated>) =
        WeightedRating.ratingOrder(items, tmdb = { it.tmdb }, provider = { it.provider }, title = { it.title }, id = { it.id }).map { it.title }

    @Test
    fun theGridScoreHasNoFloorOfVotes() {
        // 4 / 204 * 9.8 + 200 / 204 * 6.5: four votes barely move it from the prior.
        assertEquals(6.5647, WeightedRating.weighted(9.8, 4)!!, 0.001)
        assertNull(WeightedRating.weighted(0.0, 5_000))
        assertNull(WeightedRating.weighted(8.0, 0))
    }

    @Test
    fun titlesTmdbRatedComeBeforeTheProvidersWhateverTheirRating() {
        val items = listOf(
            Rated("1", "Provider Ten", null, 9.9),
            Rated("2", "Modest", TmdbRating(6.0, 1_000), 2.0),
            Rated("3", "Provider Seven", null, 7.0),
        )
        assertEquals(listOf("Modest", "Provider Ten", "Provider Seven"), ordered(items))
    }

    @Test
    fun aFewVotesDoNotTopTheList() {
        val items = listOf(
            Rated("1", "Four Votes", TmdbRating(9.8, 4), 9.8),
            Rated("2", "Many Votes", TmdbRating(7.8, 5_000), 5.0),
            Rated("3", "Unknown", null, 9.9),
        )
        // Four votes still rank among TMDb's (near the prior), ahead of a title TMDb has no rating for.
        assertEquals(listOf("Many Votes", "Four Votes", "Unknown"), ordered(items))
    }

    @Test
    fun missingDataGoesLast() {
        val items = listOf(
            Rated("1", "No Rating", null, null),
            Rated("2", "Zero From Provider", null, 0.0),
            Rated("3", "No Votes", TmdbRating(0.0, 0), 6.0),
            Rated("4", "Rated", TmdbRating(7.0, 300), null),
        )
        assertEquals(listOf("Rated", "No Votes", "No Rating", "Zero From Provider"), ordered(items))
    }

    @Test
    fun tiesDoNotDependOnTheListsOrder() {
        val items = listOf(
            Rated("b", "same", TmdbRating(7.0, 300), null),
            Rated("a", "Same", TmdbRating(7.0, 300), null),
            Rated("c", "Fewer Votes", TmdbRating(7.0, 100), null),
            Rated("d", "Alpha", TmdbRating(7.0, 300), null),
            Rated("e", "Beta", null, 8.0),
            Rated("f", "alpha", null, 8.0),
        )
        val expected = listOf("Alpha", "Same", "same", "Fewer Votes", "alpha", "Beta")
        assertEquals(expected, ordered(items))
        assertEquals(expected, ordered(items.reversed()))
    }
}
