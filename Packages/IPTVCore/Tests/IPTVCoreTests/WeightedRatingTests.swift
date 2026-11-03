import XCTest
@testable import IPTVCore

/// The carousel's weighted rating. The Android core has the same tests (`WeightedRatingTest.kt`).
final class WeightedRatingTests: XCTestCase {
    private let day: TimeInterval = 24 * 3600
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func candidate(_ title: String, _ average: Double, _ votes: Int, daysAgo: Double = 1) -> WeightedRating.Candidate {
        WeightedRating.Candidate(id: title, title: title, average: average, votes: votes, addedAt: now.addingTimeInterval(-daysAgo * day))
    }

    func testTheScoreIsTheBayesianWeightedRating() {
        // 5000 / 5200 * 7.8 + 200 / 5200 * 6.5
        XCTAssertEqual(WeightedRating.score(average: 7.8, votes: 5_000)!, 7.75, accuracy: 0.001)
        // 50 / 250 * 10 + 200 / 250 * 6.5
        XCTAssertEqual(WeightedRating.score(average: 10, votes: 50)!, 7.2, accuracy: 0.001)
        // 200 votes: halfway between the title's average and the prior.
        XCTAssertEqual(WeightedRating.score(average: 8.5, votes: 200)!, 7.5, accuracy: 0.001)
        XCTAssertEqual(WeightedRating.priorVotes, 200)
        XCTAssertEqual(WeightedRating.priorAverage, 6.5)
        XCTAssertEqual(WeightedRating.minimumVotes, 50)
    }

    func testFewerThanFiftyVotesDoNotQualify() {
        XCTAssertNil(WeightedRating.score(average: 10, votes: 10))
        XCTAssertNil(WeightedRating.score(average: 9, votes: 49))
        XCTAssertNotNil(WeightedRating.score(average: 9, votes: 50))
        XCTAssertNil(WeightedRating.score(average: 0, votes: 5_000), "no rating")
        XCTAssertNil(WeightedRating.score(average: 9, votes: 0))
    }

    func testATenFromTenVotesLosesToAGoodRatingFromThousands() {
        let ranked = WeightedRating.rank([candidate("Ten Votes", 10, 10), candidate("Many Votes", 7.8, 5_000)])
        XCTAssertEqual(ranked.map(\.title), ["Many Votes"], "ten votes do not qualify")
        // Even with enough votes to qualify, the weight keeps a thin ten behind a solid 7.8.
        let both = WeightedRating.rank([candidate("Fifty Votes", 10, 50), candidate("Many Votes", 7.8, 5_000)])
        XCTAssertEqual(both.map(\.title), ["Many Votes", "Fifty Votes"])
    }

    func testTheWindowIsFourDaysAndIncludesTheFourthDay() {
        XCTAssertEqual(WeightedRating.recentDays, 4)
        XCTAssertTrue(WeightedRating.isRecent(now, now: now))
        XCTAssertTrue(WeightedRating.isRecent(now.addingTimeInterval(-4 * day), now: now), "exactly four days ago")
        XCTAssertFalse(WeightedRating.isRecent(now.addingTimeInterval(-4 * day - 1), now: now))
        XCTAssertFalse(WeightedRating.isRecent(now.addingTimeInterval(-5 * day), now: now))
    }

    func testATenFromTenVotesAddedYesterdayIsNotInTheBestRecentPick() {
        let pick = WeightedRating.bestRecent([candidate("Ten Votes", 10, 10, daysAgo: 1), candidate("Solid", 7.8, 5_000, daysAgo: 1)], now: now)
        XCTAssertEqual(pick.map(\.title), ["Solid"])
    }

    func testATitleAddedFiveDaysAgoIsNotInTheBestRecentPick() {
        let pick = WeightedRating.bestRecent([candidate("Old", 9, 50_000, daysAgo: 5), candidate("Fresh", 7, 500, daysAgo: 3)], now: now)
        XCTAssertEqual(pick.map(\.title), ["Fresh"])
    }

    func testTiesGoToTheLatestAddedThenToTheTitle() {
        let ranked = WeightedRating.rank([
            candidate("Beta", 8, 1_000, daysAgo: 2),
            candidate("Alpha", 8, 1_000, daysAgo: 2),
            candidate("Newest", 8, 1_000, daysAgo: 0.5),
            candidate("zulu", 8, 1_000, daysAgo: 3),
            candidate("Top", 8.5, 1_000, daysAgo: 3),
        ])
        XCTAssertEqual(ranked.map(\.title), ["Top", "Newest", "Alpha", "Beta", "zulu"])
    }
}
