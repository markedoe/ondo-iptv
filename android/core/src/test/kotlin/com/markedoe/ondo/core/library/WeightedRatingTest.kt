package com.markedoe.ondo.core.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The carousel's weighted rating. The Apple core has the same tests (`WeightedRatingTests.swift`). */
class WeightedRatingTest {
    private val day = 86_400L
    private val now = 1_800_000_000L

    private fun candidate(title: String, average: Double, votes: Int, daysAgo: Double = 1.0) =
        WeightedRating.Candidate(title, title, average, votes, now - (daysAgo * day).toLong())

    @Test
    fun theScoreIsTheBayesianWeightedRating() {
        // 5000 / 5200 * 7.8 + 200 / 5200 * 6.5
        assertEquals(7.75, WeightedRating.score(7.8, 5_000)!!, 0.001)
        // 50 / 250 * 10 + 200 / 250 * 6.5
        assertEquals(7.2, WeightedRating.score(10.0, 50)!!, 0.001)
        // 200 votes: halfway between the title's average and the prior.
        assertEquals(7.5, WeightedRating.score(8.5, 200)!!, 0.001)
        assertEquals(200.0, WeightedRating.PRIOR_VOTES, 0.0)
        assertEquals(6.5, WeightedRating.PRIOR_AVERAGE, 0.0)
        assertEquals(50, WeightedRating.MINIMUM_VOTES)
    }

    @Test
    fun fewerThanFiftyVotesDoNotQualify() {
        assertNull(WeightedRating.score(10.0, 10))
        assertNull(WeightedRating.score(9.0, 49))
        assertNotNull(WeightedRating.score(9.0, 50))
        assertNull(WeightedRating.score(0.0, 5_000), "no rating")
        assertNull(WeightedRating.score(9.0, 0))
    }

    @Test
    fun aTenFromTenVotesLosesToAGoodRatingFromThousands() {
        val ranked = WeightedRating.rank(listOf(candidate("Ten Votes", 10.0, 10), candidate("Many Votes", 7.8, 5_000)))
        assertEquals(listOf("Many Votes"), ranked.map { it.title }, "ten votes do not qualify")
        // Even with enough votes to qualify, the weight keeps a thin ten behind a solid 7.8.
        val both = WeightedRating.rank(listOf(candidate("Fifty Votes", 10.0, 50), candidate("Many Votes", 7.8, 5_000)))
        assertEquals(listOf("Many Votes", "Fifty Votes"), both.map { it.title })
    }

    @Test
    fun theWindowIsFourDaysAndIncludesTheFourthDay() {
        assertEquals(4L, WeightedRating.RECENT_DAYS)
        assertTrue(WeightedRating.isRecent(now, now))
        assertTrue(WeightedRating.isRecent(now - 4 * day, now), "exactly four days ago")
        assertFalse(WeightedRating.isRecent(now - 4 * day - 1, now))
        assertFalse(WeightedRating.isRecent(now - 5 * day, now))
    }

    @Test
    fun aTenFromTenVotesAddedYesterdayIsNotInTheBestRecentPick() {
        val pick = WeightedRating.bestRecent(listOf(candidate("Ten Votes", 10.0, 10, daysAgo = 1.0), candidate("Solid", 7.8, 5_000, daysAgo = 1.0)), now)
        assertEquals(listOf("Solid"), pick.map { it.title })
    }

    @Test
    fun aTitleAddedFiveDaysAgoIsNotInTheBestRecentPick() {
        val pick = WeightedRating.bestRecent(listOf(candidate("Old", 9.0, 50_000, daysAgo = 5.0), candidate("Fresh", 7.0, 500, daysAgo = 3.0)), now)
        assertEquals(listOf("Fresh"), pick.map { it.title })
    }

    @Test
    fun tiesGoToTheLatestAddedThenToTheTitle() {
        val ranked = WeightedRating.rank(
            listOf(
                candidate("Beta", 8.0, 1_000, daysAgo = 2.0),
                candidate("Alpha", 8.0, 1_000, daysAgo = 2.0),
                candidate("Newest", 8.0, 1_000, daysAgo = 0.5),
                candidate("zulu", 8.0, 1_000, daysAgo = 3.0),
                candidate("Top", 8.5, 1_000, daysAgo = 3.0),
            ),
        )
        assertEquals(listOf("Top", "Newest", "Alpha", "Beta", "zulu"), ranked.map { it.title })
    }
}
