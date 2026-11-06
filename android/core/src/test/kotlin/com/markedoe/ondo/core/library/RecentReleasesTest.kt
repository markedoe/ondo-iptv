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

    private fun title(name: String, daysAgo: Long?, average: Double = 7.5, votes: Int = 500, category: String = "1", artwork: Boolean = true, series: Boolean = false) =
        RecentReleases.Candidate(name, name, daysAgo?.let { today - it }, average, votes, category, artwork, series = series)

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
    fun aMovieCountsNinetyDaysAndASeriesThirty() {
        assertEquals(90L, RecentReleases.MOVIE_DAYS)
        assertEquals(30L, RecentReleases.SERIES_DAYS)
        val picked = pick(
            title("Movie 89", daysAgo = 89), title("Movie 90", daysAgo = 90), title("Movie 91", daysAgo = 91),
            title("Series 29", daysAgo = 29, series = true), title("Series 30", daysAgo = 30, series = true), title("Series 31", daysAgo = 31, series = true),
            size = 10,
        )
        assertEquals(setOf("Movie 89", "Series 29"), picked.toSet())
        // Fewer titles than the carousel holds: those there are, never older ones.
        assertEquals(2, picked.size)
    }

    @Test
    fun theRatingOfANewTitleAndItsFreshness() {
        // (v × R + 50 × 6.5) / (v + 50): 8 from 300 votes passes 9 from 20; under 5 votes, no rating.
        assertEquals(2725.0 / 350.0, RecentReleases.carouselRating(8.0, 300)!!, 1e-12)
        assertEquals(505.0 / 70.0, RecentReleases.carouselRating(9.0, 20)!!, 1e-12)
        assertEquals(425.0 / 60.0, RecentReleases.carouselRating(10.0, 10)!!, 1e-12)
        assertNull(RecentReleases.carouselRating(9.5, 4))
        assertNull(RecentReleases.carouselRating(null, 500))
        assertEquals(5, RecentReleases.CAROUSEL_MINIMUM_VOTES)
        // Half a point on the release day, falling evenly to 0 at the end of the window.
        assertEquals(0.5, RecentReleases.freshness(0, series = false), 1e-12)
        assertEquals(0.25, RecentReleases.freshness(45, series = false), 1e-12)
        assertEquals(0.25, RecentReleases.freshness(15, series = true), 1e-12)
        assertEquals(0.0, RecentReleases.freshness(90, series = false), 1e-12)
    }

    @Test
    fun ratingAndFreshnessMakeTheOrder() {
        val picked = pick(
            title("Fresh Good", daysAgo = 0, average = 7.5, votes = 200),
            title("Older Better", daysAgo = 60, average = 8.0, votes = 300),
            title("Fresh Fine", daysAgo = 1, average = 7.0, votes = 200),
            title("Few Votes", daysAgo = 0, average = 9.5, votes = 3),
            title("Unrated Older", daysAgo = 10, average = 0.0, votes = 0),
        )
        // 7.79 + 0.17, then 7.3 + 0.5, then 6.9 + 0.49; the titles not rated enough after them, by date.
        assertEquals(listOf("Older Better", "Fresh Good", "Fresh Fine", "Few Votes", "Unrated Older"), picked)
        // Close ratings: the fresher first (7.22 + 0.47 before 7.3 + 0.06).
        assertEquals(listOf("Close New", "Close Old"), pick(title("Close Old", daysAgo = 80, average = 7.5, votes = 200), title("Close New", daysAgo = 5, average = 7.4, votes = 200)))
        // Ties: the newest, then the title.
        assertEquals(listOf("Newest", "Alpha", "Beta"), pick(title("Beta", 3, votes = 0), title("Alpha", 3, votes = 0), title("Newest", 1, votes = 0)))
    }

    @Test
    fun moviesAndSeriesTakeTurns() {
        fun rated(name: String, average: Double, series: Boolean) = title(name, daysAgo = 0, average = average, votes = 500, series = series)
        val titles = arrayOf(rated("M1", 9.0, false), rated("M2", 8.5, false), rated("S1", 8.0, true), rated("M3", 7.5, false), rated("S2", 7.0, true))
        assertEquals(listOf("M1", "S1", "M2", "S2", "M3"), pick(*titles))
        assertEquals(listOf("M1", "S1", "M2"), pick(*titles, size = 3))
        // A series first when it ranks first.
        assertEquals(listOf("S0", "M1", "S1", "M2"), pick(rated("S0", 9.5, true), *titles, size = 4))
    }

    @Test
    fun withoutVotesTheNewestReleasesStandIn() {
        // No TMDb votes (no token, or titles nobody rated yet): the newest releases of their window by date.
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
        assertTrue(RecentReleases.mayBeRecent(2025, null, today), "released last year: maybe in the last 90 days in January")
        assertFalse(RecentReleases.mayBeRecent(2014, null, today))
        // A 2014 series the provider changed this week (a new episode).
        assertTrue(RecentReleases.mayBeRecent(2014, (today - 3) * second, today))
        assertTrue(RecentReleases.mayBeRecent(2014, (today - 61) * second, today))
        assertFalse(RecentReleases.mayBeRecent(2014, (today - 91) * second, today))
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

    /**
     * One title, one card: a series listed once per new episode, or again under other categories, is one candidate (the
     * one released last); a remake is another film. The same cases as the Swift `RecentReleasesTests`.
     */
    @Test
    fun aTitleListedSeveralTimesIsOneCandidate() {
        val episodes = listOf("Harbor S01E08", "Harbor S01E09", "Harbor S01E10", "|FR| Harbor", "Harbor 4K")
        val identities = episodes.map { RecentReleases.identity(it, series = true, year = null) }.distinct()
        assertEquals(listOf("series:harbor"), identities)
        assertEquals(RecentReleases.identity("Dune", series = false, year = 2021), RecentReleases.identity("|FR| Dune 4K", series = false, year = 2021))
        assertFalse(RecentReleases.identity("Dune", series = false, year = 1984) == RecentReleases.identity("Dune", series = false, year = 2021))
        assertFalse(RecentReleases.identity("Harbor", series = true, year = null) == RecentReleases.identity("Harbor", series = false, year = null))
        val today = 20_000L
        fun candidate(id: String, title: String, daysAgo: Long, votes: Int = 500) =
            RecentReleases.Candidate(id, title, today - daysAgo, 7.5, votes, identity = RecentReleases.identity(title, series = true, year = null), series = true)
        val candidates = listOf(
            candidate("e8", "Harbor S01E08", 6), candidate("e9", "Harbor S01E09", 3), candidate("e10", "Harbor S01E10", 1),
            candidate("fr", "|FR| Harbor", 2), candidate("4k", "Harbor 4K", 4), candidate("other", "Quiet Valley", 2),
        )
        assertEquals(listOf("e10", "other"), RecentReleases.pick(candidates, today, 10).map { it.id })
        // The same day: more votes, then the smaller id.
        val tied = listOf(candidate("b", "Harbor S01E01", 1, votes = 900), candidate("a", "Harbor S01E02", 1, votes = 900), candidate("c", "Harbor", 1, votes = 50))
        assertEquals(listOf("a"), RecentReleases.distinctTitles(tied).map { it.id })
        // Without an identity, each id is its own title, as before.
        assertEquals(2, RecentReleases.distinctTitles(listOf(RecentReleases.Candidate("x", "Harbor", today), RecentReleases.Candidate("y", "Harbor", today))).size)
    }
}
