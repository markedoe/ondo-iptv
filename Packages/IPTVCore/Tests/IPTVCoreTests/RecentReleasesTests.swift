import XCTest
@testable import IPTVCore

/// The home carousel: recent releases by release date, best first. The Android core has the same tests (`RecentReleasesTest.kt`).
final class RecentReleasesTests: XCTestCase {
    private let today = RecentReleases.epochDay("2026-11-05")!

    private func date(_ daysAgo: Int) -> String { RecentReleases.dayString(today - daysAgo) }

    private func title(_ name: String, _ daysAgo: Int?, average: Double = 7.5, votes: Int = 500, category: String = "1",
                       artwork: Bool = true) -> RecentReleases.Candidate {
        RecentReleases.Candidate(id: name, title: name, released: daysAgo.map { today - $0 }, average: average, votes: votes,
                                 categoryID: category, hasArtwork: artwork)
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

    func testTheBestWeightedRatingComesFirst() {
        let picked = pick(
            title("Thin Ten", 1, average: 10, votes: 12),
            title("Solid", 2, average: 7.8, votes: 3_000),
            title("Good", 3, average: 8.2, votes: 400),
            title("Too Few Votes", 1, average: 9.9, votes: 9),
            title("No Votes", 1, average: 0, votes: 0)
        )
        // 7.8 from 3,000 votes (7.72) before 8.2 from 400 (7.63); twelve votes at 10 stay near the prior (6.70).
        XCTAssertEqual(picked, ["Solid", "Good", "Thin Ten"])
        XCTAssertEqual(RecentReleases.minimumVotes, 10)
        // Ties: the newest, then the title.
        XCTAssertEqual(pick(title("Beta", 3), title("Alpha", 3), title("Newest", 1)), ["Newest", "Alpha", "Beta"])
    }

    func testTheWindowWidensStepByStepUntilTheCarouselIsFull() {
        XCTAssertEqual(RecentReleases.windows, [7, 14, 30, 60])
        let all = [title("This Week", 6, average: 6), title("Two Weeks", 13, average: 9), title("This Month", 29, average: 8),
                   title("Two Months", 59, average: 7), title("Older", 60, average: 9.5)]
        func pick(_ size: Int) -> [String] { RecentReleases.pick(all, today: today, size: size).map(\.title) }
        // Enough in the last seven days: the better rated older ones wait.
        XCTAssertEqual(pick(1), ["This Week"])
        // Not enough: 14 days, then 30, then 60; never older than 60 days.
        XCTAssertEqual(pick(2), ["Two Weeks", "This Week"])
        XCTAssertEqual(pick(3), ["Two Weeks", "This Month", "This Week"])
        XCTAssertEqual(pick(4), ["Two Weeks", "This Month", "Two Months", "This Week"])
        XCTAssertEqual(pick(15), ["Two Weeks", "This Month", "Two Months", "This Week"], "fewer, never older")
    }

    func testWithoutVotesTheNewestReleasesStandIn() {
        // No TMDb votes (no token, or titles nobody rated yet): the newest releases of the last 60 days by date.
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
        XCTAssertTrue(RecentReleases.mayBeRecent(year: 2025, addedAt: nil, today: today), "released last year: maybe in the last 60 days in January")
        XCTAssertFalse(RecentReleases.mayBeRecent(year: 2014, addedAt: nil, today: today))
        // A 2014 series the provider changed this week (a new episode).
        XCTAssertTrue(RecentReleases.mayBeRecent(year: 2014, addedAt: daysAgo(3), today: today))
        XCTAssertFalse(RecentReleases.mayBeRecent(year: 2014, addedAt: daysAgo(61), today: today))
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
}
