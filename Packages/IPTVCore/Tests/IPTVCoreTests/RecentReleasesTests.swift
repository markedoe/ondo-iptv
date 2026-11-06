import XCTest
@testable import IPTVCore

/// The home carousel: recent releases by release date, best first. The Android core has the same tests (`RecentReleasesTest.kt`).
final class RecentReleasesTests: XCTestCase {
    private let today = RecentReleases.epochDay("2026-11-05")!

    private func date(_ daysAgo: Int) -> String { RecentReleases.dayString(today - daysAgo) }

    private func title(_ name: String, _ daysAgo: Int?, average: Double = 7.5, votes: Int = 500, category: String = "1",
                       artwork: Bool = true, series: Bool = false) -> RecentReleases.Candidate {
        RecentReleases.Candidate(id: name, title: name, released: daysAgo.map { today - $0 }, average: average, votes: votes,
                                 categoryID: category, hasArtwork: artwork, series: series)
    }

    private func pick(_ titles: RecentReleases.Candidate..., size: Int = 5, hidden: Set<String> = []) -> [String] {
        RecentReleases.pick(titles, today: today, size: size, hidden: hidden).map(\.title)
    }

    func testOnlyAFullDateCounts() {
        XCTAssertEqual(RecentReleases.epochDay("1970-01-02"), 1)
        XCTAssertEqual(RecentReleases.epochDay("2026-11-05T20:00:00Z"), today)
        XCTAssertEqual(date(0), "2026-11-05")
        XCTAssertNil(RecentReleases.epochDay("2026"), "a year alone is not a release date")
        XCTAssertNil(RecentReleases.epochDay("2026-11"))
        XCTAssertNil(RecentReleases.epochDay("2025-02-30"))
        XCTAssertNil(RecentReleases.epochDay(""))
        XCTAssertNil(RecentReleases.epochDay(nil))
        // TMDb's first, the provider's when TMDb gives none.
        XCTAssertEqual(RecentReleases.releaseDay(tmdb: date(1), provider: date(300)), today - 1)
        XCTAssertEqual(RecentReleases.releaseDay(tmdb: "", provider: date(300)), today - 300)
        XCTAssertNil(RecentReleases.releaseDay(tmdb: "", provider: "2026"))
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Paris")!
        XCTAssertEqual(RecentReleases.today(Date(timeIntervalSince1970: TimeInterval(today) * 86_400 + 23.5 * 3600), calendar: calendar), today + 1,
                       "the viewer's own day")
    }

    func testAnOldSeriesWithNothingNewIsLeftOut() {
        // A 2014 series whose latest episode aired two years ago, however well rated.
        XCTAssertEqual(pick(title("Old Harbor", 700, average: 9, votes: 20_000), title("New Film", 3)), ["New Film"])
    }

    func testAnOldSeriesWithANewSeasonInTheWindowIsIn() {
        // The same 2014 series: its latest episode (a new season) aired two days ago.
        XCTAssertEqual(pick(title("Old Harbor", 2, average: 8.4, votes: 20_000), title("New Film", 3, average: 7)), ["Old Harbor", "New Film"])
    }

    func testAFutureReleaseIsLeftOut() {
        XCTAssertEqual(pick(title("Next Month", -20, average: 9.5, votes: 5_000), title("Tomorrow", -1), title("Today", 0)), ["Today"])
    }

    func testAMovieCountsNinetyDaysAndASeriesThirty() {
        XCTAssertEqual(RecentReleases.movieDays, 90)
        XCTAssertEqual(RecentReleases.seriesDays, 30)
        let picked = pick(title("Movie 89", 89), title("Movie 90", 90), title("Movie 91", 91),
                          title("Series 29", 29, series: true), title("Series 30", 30, series: true), title("Series 31", 31, series: true),
                          size: 10)
        XCTAssertEqual(Set(picked), ["Movie 89", "Series 29"])
        // Fewer titles than the carousel holds: those there are, never older ones.
        XCTAssertEqual(picked.count, 2)
    }

    func testTheRatingOfANewTitleAndItsFreshness() {
        // (v × R + 50 × 6.5) / (v + 50): 8 from 300 votes passes 9 from 20; under 5 votes, no rating.
        XCTAssertEqual(RecentReleases.carouselRating(average: 8, votes: 300)!, 2725.0 / 350.0, accuracy: 1e-12)
        XCTAssertEqual(RecentReleases.carouselRating(average: 9, votes: 20)!, 505.0 / 70.0, accuracy: 1e-12)
        XCTAssertEqual(RecentReleases.carouselRating(average: 10, votes: 10)!, 425.0 / 60.0, accuracy: 1e-12)
        XCTAssertNil(RecentReleases.carouselRating(average: 9.5, votes: 4))
        XCTAssertNil(RecentReleases.carouselRating(average: nil, votes: 500))
        XCTAssertEqual(RecentReleases.carouselMinimumVotes, 5)
        // Half a point on the release day, falling evenly to 0 at the end of the window.
        XCTAssertEqual(RecentReleases.freshness(age: 0, series: false), 0.5, accuracy: 1e-12)
        XCTAssertEqual(RecentReleases.freshness(age: 45, series: false), 0.25, accuracy: 1e-12)
        XCTAssertEqual(RecentReleases.freshness(age: 15, series: true), 0.25, accuracy: 1e-12)
        XCTAssertEqual(RecentReleases.freshness(age: 90, series: false), 0, accuracy: 1e-12)
    }

    func testRatingAndFreshnessMakeTheOrder() {
        let picked = pick(title("Fresh Good", 0, average: 7.5, votes: 200), title("Older Better", 60, average: 8, votes: 300),
                          title("Fresh Fine", 1, average: 7, votes: 200), title("Few Votes", 0, average: 9.5, votes: 3),
                          title("Unrated Older", 10, average: 0, votes: 0))
        // 7.79 + 0.17, then 7.3 + 0.5, then 6.9 + 0.49; the titles not rated enough after them, by date.
        XCTAssertEqual(picked, ["Older Better", "Fresh Good", "Fresh Fine", "Few Votes", "Unrated Older"])
        // Close ratings: the fresher first (7.22 + 0.47 before 7.3 + 0.06).
        XCTAssertEqual(pick(title("Close Old", 80, average: 7.5, votes: 200), title("Close New", 5, average: 7.4, votes: 200)), ["Close New", "Close Old"])
        // Ties: the newest, then the title.
        XCTAssertEqual(pick(title("Beta", 3, votes: 0), title("Alpha", 3, votes: 0), title("Newest", 1, votes: 0)), ["Newest", "Alpha", "Beta"])
    }

    func testMoviesAndSeriesTakeTurns() {
        func rated(_ name: String, _ average: Double, _ series: Bool) -> RecentReleases.Candidate {
            title(name, 0, average: average, votes: 500, series: series)
        }
        let titles: [RecentReleases.Candidate] = [rated("M1", 9, false), rated("M2", 8.5, false), rated("S1", 8, true), rated("M3", 7.5, false),
                                                  rated("S2", 7, true)]
        XCTAssertEqual(RecentReleases.pick(titles, today: today, size: 5).map(\.title), ["M1", "S1", "M2", "S2", "M3"])
        XCTAssertEqual(RecentReleases.pick(titles, today: today, size: 3).map(\.title), ["M1", "S1", "M2"])
        // A series first when it ranks first.
        XCTAssertEqual(RecentReleases.pick([rated("S0", 9.5, true)] + titles, today: today, size: 4).map(\.title), ["S0", "M1", "S1", "M2"])
    }

    func testWithoutVotesTheNewestReleasesStandIn() {
        // No TMDb votes (no token, or titles nobody rated yet): the newest releases of their window by date.
        let picked = pick(title("Ten Days", 10, average: 0, votes: 0), title("Yesterday", 1, average: 0, votes: 0),
                          title("Few Votes", 5, average: 9, votes: 3), title("Old", 400, average: 0, votes: 0), title("Undated", nil))
        XCTAssertEqual(picked, ["Yesterday", "Few Votes", "Ten Days"])
        // Nothing released lately: the carousel is empty, never filled with older titles.
        XCTAssertEqual(pick(title("Old", 400), title("Undated", nil)), [])
    }

    func testAHiddenCategoryOrATitleWithoutArtworkIsNeverPicked() {
        let picked = pick(title("Hidden", 1, average: 9, votes: 9_000, category: "adult"), title("No Poster", 1, average: 9, votes: 9_000, artwork: false),
                          title("Shown", 2), hidden: ["adult"])
        XCTAssertEqual(picked, ["Shown"])
        // Nor in the stand-in by date.
        XCTAssertEqual(pick(title("Hidden", 1, votes: 0, category: "adult"), hidden: ["adult"]), [])
    }

    func testOnlyTitlesThatMayBeRecentAreAskedAbout() {
        func daysAgo(_ days: Int) -> Date { Date(timeIntervalSince1970: TimeInterval(today - days) * 86_400) }
        XCTAssertTrue(RecentReleases.mayBeRecent(year: 2026, addedAt: nil, today: today))
        XCTAssertTrue(RecentReleases.mayBeRecent(year: 2025, addedAt: nil, today: today), "released last year: maybe in the last 90 days in January")
        XCTAssertFalse(RecentReleases.mayBeRecent(year: 2014, addedAt: nil, today: today))
        // A 2014 series the provider changed this week (a new episode).
        XCTAssertTrue(RecentReleases.mayBeRecent(year: 2014, addedAt: daysAgo(3), today: today))
        XCTAssertTrue(RecentReleases.mayBeRecent(year: 2014, addedAt: daysAgo(61), today: today))
        XCTAssertFalse(RecentReleases.mayBeRecent(year: 2014, addedAt: daysAgo(91), today: today))
        XCTAssertFalse(RecentReleases.mayBeRecent(year: nil, addedAt: nil, today: today))
    }

    func testASeriesIsAskedAgainOnceTheProviderChangedIt() {
        let fetchedAt = Date(timeIntervalSince1970: TimeInterval(today - 10) * 86_400)
        func changed(_ days: Int) -> Date { Date(timeIntervalSince1970: TimeInterval(today - days) * 86_400) }
        XCTAssertTrue(RecentReleases.needsAsking(kept: false, found: false, releaseDate: nil, fetchedAt: .distantPast, addedAt: nil))
        XCTAssertFalse(RecentReleases.needsAsking(kept: true, found: false, releaseDate: nil, fetchedAt: fetchedAt, addedAt: nil), "TMDb has no such title")
        XCTAssertTrue(RecentReleases.needsAsking(kept: true, found: true, releaseDate: nil, fetchedAt: fetchedAt, addedAt: nil), "kept before release dates")
        XCTAssertFalse(RecentReleases.needsAsking(kept: true, found: true, releaseDate: "2014-03-01", fetchedAt: fetchedAt, addedAt: changed(20)))
        XCTAssertTrue(RecentReleases.needsAsking(kept: true, found: true, releaseDate: "2014-03-01", fetchedAt: fetchedAt, addedAt: changed(2)), "a new episode since")
    }

    /// One title, one card: a series listed once per new episode, or again under other categories, is one candidate (the
    /// one released last); a remake is another film. The same cases as the Kotlin `RecentReleasesTest`.
    func testATitleListedSeveralTimesIsOneCandidate() {
        let episodes: [String] = ["Harbor S01E08", "Harbor S01E09", "Harbor S01E10", "|FR| Harbor", "Harbor 4K"]
        let identities: Set<String> = Set(episodes.map { RecentReleases.identity(title: $0, series: true, year: nil) })
        XCTAssertEqual(identities, ["series:harbor"])
        XCTAssertEqual(RecentReleases.identity(title: "Dune", series: false, year: 2021), RecentReleases.identity(title: "|FR| Dune 4K", series: false, year: 2021))
        XCTAssertNotEqual(RecentReleases.identity(title: "Dune", series: false, year: 1984), RecentReleases.identity(title: "Dune", series: false, year: 2021))
        XCTAssertNotEqual(RecentReleases.identity(title: "Harbor", series: true, year: nil), RecentReleases.identity(title: "Harbor", series: false, year: nil))
        let today = 20_000
        func candidate(_ id: String, _ title: String, _ daysAgo: Int, votes: Int = 500) -> RecentReleases.Candidate {
            RecentReleases.Candidate(id: id, title: title, released: today - daysAgo, average: 7.5, votes: votes,
                                     identity: RecentReleases.identity(title: title, series: true, year: nil), series: true)
        }
        let candidates: [RecentReleases.Candidate] = [
            candidate("e8", "Harbor S01E08", 6), candidate("e9", "Harbor S01E09", 3), candidate("e10", "Harbor S01E10", 1),
            candidate("fr", "|FR| Harbor", 2), candidate("4k", "Harbor 4K", 4), candidate("other", "Quiet Valley", 2),
        ]
        XCTAssertEqual(RecentReleases.pick(candidates, today: today, size: 10).map(\.id), ["e10", "other"])
        // The same day: more votes, then the smaller id.
        let tied: [RecentReleases.Candidate] = [candidate("b", "Harbor S01E01", 1, votes: 900), candidate("a", "Harbor S01E02", 1, votes: 900),
                                                candidate("c", "Harbor", 1, votes: 50)]
        XCTAssertEqual(RecentReleases.distinctTitles(tied).map(\.id), ["a"])
        // Without an identity, each id is its own title, as before.
        let plain: [RecentReleases.Candidate] = [RecentReleases.Candidate(id: "x", title: "Harbor", released: today),
                                                 RecentReleases.Candidate(id: "y", title: "Harbor", released: today)]
        XCTAssertEqual(RecentReleases.distinctTitles(plain).count, 2)
    }
}
