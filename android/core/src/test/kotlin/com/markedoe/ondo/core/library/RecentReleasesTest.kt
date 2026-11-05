package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.HangGuard
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule

/** The home carousel: recent releases by release date, best first. The Apple core has the same tests (`RecentReleasesTests.swift`). */
class RecentReleasesTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val today = LocalDate.parse("2026-11-05").toEpochDay()

    private fun date(daysAgo: Long) = LocalDate.ofEpochDay(today - daysAgo).toString()

    private fun title(name: String, daysAgo: Long?, average: Double = 7.5, votes: Int = 500, category: String = "1", artwork: Boolean = true) =
        RecentReleases.Candidate(name, name, daysAgo?.let { today - it }, average, votes, category, artwork)

    private fun pick(vararg titles: RecentReleases.Candidate, size: Int = 5, hidden: Set<String> = emptySet()) =
        RecentReleases.pick(titles.toList(), today, size, hidden).map { it.title }

    @Test
    fun onlyAFullDateCounts() {
        assertEquals(today, RecentReleases.epochDay("2026-11-05"))
        assertEquals(today, RecentReleases.epochDay("2026-11-05T20:00:00Z"))
        assertNull(RecentReleases.epochDay("2026"), "a year alone is not a release date")
        assertNull(RecentReleases.epochDay("2026-11"))
        assertNull(RecentReleases.epochDay("2025-02-30"))
        assertNull(RecentReleases.epochDay(""))
        assertNull(RecentReleases.epochDay(null))
        // TMDb's first, the provider's when TMDb gives none.
        assertEquals(today - 1, RecentReleases.releaseDay(date(1), date(300)))
        assertEquals(today - 300, RecentReleases.releaseDay("", date(300)))
        assertNull(RecentReleases.releaseDay("", "2026"))
    }

    @Test
    fun anOldSeriesWithNothingNewIsLeftOut() {
        // A 2014 series whose latest episode aired two years ago, however well rated.
        val picked = pick(title("Old Harbor", daysAgo = 700, average = 9.0, votes = 20_000), title("New Film", daysAgo = 3))
        assertEquals(listOf("New Film"), picked)
    }

    @Test
    fun anOldSeriesWithANewSeasonInTheWindowIsIn() {
        // The same 2014 series: its latest episode (a new season) aired two days ago.
        val picked = pick(title("Old Harbor", daysAgo = 2, average = 8.4, votes = 20_000), title("New Film", daysAgo = 3, average = 7.0))
        assertEquals(listOf("Old Harbor", "New Film"), picked)
    }

    @Test
    fun aFutureReleaseIsLeftOut() {
        val picked = pick(title("Next Month", daysAgo = -20, average = 9.5, votes = 5_000), title("Tomorrow", daysAgo = -1), title("Today", daysAgo = 0))
        assertEquals(listOf("Today"), picked)
    }

    @Test
    fun theBestWeightedRatingComesFirst() {
        val picked = pick(
            title("Thin Ten", daysAgo = 1, average = 10.0, votes = 12),
            title("Solid", daysAgo = 2, average = 7.8, votes = 3_000),
            title("Good", daysAgo = 3, average = 8.2, votes = 400),
            title("Too Few Votes", daysAgo = 1, average = 9.9, votes = 9),
            title("No Votes", daysAgo = 1, average = 0.0, votes = 0),
        )
        // 7.8 from 3,000 votes (7.72) before 8.2 from 400 (7.63); twelve votes at 10 stay near the prior (6.70).
        assertEquals(listOf("Solid", "Good", "Thin Ten"), picked)
        assertEquals(10, RecentReleases.MINIMUM_VOTES)
        // Ties: the newest, then the title.
        val tied = pick(title("Beta", 3), title("Alpha", 3), title("Newest", 1))
        assertEquals(listOf("Newest", "Alpha", "Beta"), tied)
    }

    @Test
    fun theWindowWidensStepByStepUntilTheCarouselIsFull() {
        assertEquals(listOf(7L, 14L, 30L, 60L), RecentReleases.WINDOWS)
        val week = title("This Week", daysAgo = 6, average = 6.0)
        val twoWeeks = title("Two Weeks", daysAgo = 13, average = 9.0)
        val month = title("This Month", daysAgo = 29, average = 8.0)
        val twoMonths = title("Two Months", daysAgo = 59, average = 7.0)
        val older = title("Older", daysAgo = 60, average = 9.5)
        val all = arrayOf(week, twoWeeks, month, twoMonths, older)
        // Enough in the last seven days: the better rated older ones wait.
        assertEquals(listOf("This Week"), pick(*all, size = 1))
        // Not enough: 14 days, then 30, then 60; never older than 60 days.
        assertEquals(listOf("Two Weeks", "This Week"), pick(*all, size = 2))
        assertEquals(listOf("Two Weeks", "This Month", "This Week"), pick(*all, size = 3))
        assertEquals(listOf("Two Weeks", "This Month", "Two Months", "This Week"), pick(*all, size = 4))
        assertEquals(listOf("Two Weeks", "This Month", "Two Months", "This Week"), pick(*all, size = 15), "fewer, never older")
    }

    @Test
    fun withoutVotesTheNewestReleasesStandIn() {
        // No TMDb votes (no token, or titles nobody rated yet): the newest releases of the last 60 days by date.
        val picked = pick(
            title("Ten Days", daysAgo = 10, average = 0.0, votes = 0),
            title("Yesterday", daysAgo = 1, average = 0.0, votes = 0),
            title("Few Votes", daysAgo = 5, average = 9.0, votes = 3),
            title("Old", daysAgo = 400, average = 0.0, votes = 0),
            title("Undated", daysAgo = null),
        )
        assertEquals(listOf("Yesterday", "Few Votes", "Ten Days"), picked)
        // Nothing released lately: the carousel is empty, never filled with older titles.
        assertEquals(emptyList(), pick(title("Old", daysAgo = 400), title("Undated", daysAgo = null)))
    }

    @Test
    fun aHiddenCategoryOrATitleWithoutArtworkIsNeverPicked() {
        val picked = pick(
            title("Hidden", daysAgo = 1, average = 9.0, votes = 9_000, category = "adult"),
            title("No Poster", daysAgo = 1, average = 9.0, votes = 9_000, artwork = false),
            title("Shown", daysAgo = 2),
            hidden = setOf("adult"),
        )
        assertEquals(listOf("Shown"), picked)
        // Nor in the stand-in by date.
        assertEquals(emptyList(), pick(title("Hidden", daysAgo = 1, votes = 0, category = "adult"), hidden = setOf("adult")))
    }

    @Test
    fun onlyTitlesThatMayBeRecentAreAskedAbout() {
        val second = 86_400L
        assertTrue(RecentReleases.mayBeRecent(2026, null, today))
        assertTrue(RecentReleases.mayBeRecent(2025, null, today), "released last year: maybe in the last 60 days in January")
        assertFalse(RecentReleases.mayBeRecent(2014, null, today))
        // A 2014 series the provider changed this week (a new episode).
        assertTrue(RecentReleases.mayBeRecent(2014, (today - 3) * second, today))
        assertFalse(RecentReleases.mayBeRecent(2014, (today - 61) * second, today))
        assertFalse(RecentReleases.mayBeRecent(null, null, today))
    }

    @Test
    fun aSeriesIsAskedAgainOnceTheProviderChangedIt() {
        val fetchedAt = (today - 10) * 86_400_000L
        assertTrue(RecentReleases.needsAsking(kept = false, found = false, releaseDate = null, fetchedAt = 0, addedAt = null))
        assertFalse(RecentReleases.needsAsking(kept = true, found = false, releaseDate = null, fetchedAt = fetchedAt, addedAt = null), "TMDb has no such title")
        assertTrue(RecentReleases.needsAsking(kept = true, found = true, releaseDate = null, fetchedAt = fetchedAt, addedAt = null), "kept before release dates")
        assertFalse(RecentReleases.needsAsking(kept = true, found = true, releaseDate = "2014-03-01", fetchedAt = fetchedAt, addedAt = (today - 20) * 86_400))
        assertTrue(RecentReleases.needsAsking(kept = true, found = true, releaseDate = "2014-03-01", fetchedAt = fetchedAt, addedAt = (today - 2) * 86_400), "a new episode since")
    }
}
