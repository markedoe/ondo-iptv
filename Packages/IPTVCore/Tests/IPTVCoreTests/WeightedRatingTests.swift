import XCTest
@testable import IPTVCore

/// The weighted rating of the carousel and of a grid sorted by rating. The Android core has the same tests (`WeightedRatingTest.kt`).
final class WeightedRatingTests: XCTestCase {
    func testTheScoreIsTheBayesianWeightedRating() {
        // 5000 / 5200 * 7.8 + 200 / 5200 * 6.5
        XCTAssertEqual(WeightedRating.weighted(average: 7.8, votes: 5_000)!, 7.75, accuracy: 0.001)
        // 50 / 250 * 10 + 200 / 250 * 6.5
        XCTAssertEqual(WeightedRating.weighted(average: 10, votes: 50)!, 7.2, accuracy: 0.001)
        // 200 votes: halfway between the title's average and the prior.
        XCTAssertEqual(WeightedRating.weighted(average: 8.5, votes: 200)!, 7.5, accuracy: 0.001)
        XCTAssertEqual(WeightedRating.priorVotes, 200)
        XCTAssertEqual(WeightedRating.priorAverage, 6.5)
    }

    // MARK: - A grid sorted by rating

    private struct Rated {
        var id: String
        var title: String
        var tmdb: (average: Double, count: Int)?
        var provider: Double?
    }

    private func ordered(_ items: [Rated]) -> [String] {
        WeightedRating.ratingOrder(items, tmdb: \.tmdb, provider: \.provider, title: \.title, id: \.id).map(\.title)
    }

    func testTheGridScoreHasNoFloorOfVotes() {
        // 4 / 204 * 9.8 + 200 / 204 * 6.5: four votes barely move it from the prior.
        XCTAssertEqual(WeightedRating.weighted(average: 9.8, votes: 4)!, 6.5647, accuracy: 0.001)
        XCTAssertNil(WeightedRating.weighted(average: 0, votes: 5_000))
        XCTAssertNil(WeightedRating.weighted(average: 8, votes: 0))
    }

    func testTitlesTMDbRatedComeBeforeTheProvidersWhateverTheirRating() {
        let items = [
            Rated(id: "1", title: "Provider Ten", tmdb: nil, provider: 9.9),
            Rated(id: "2", title: "Modest", tmdb: (6.0, 1_000), provider: 2.0),
            Rated(id: "3", title: "Provider Seven", tmdb: nil, provider: 7.0),
        ]
        XCTAssertEqual(ordered(items), ["Modest", "Provider Ten", "Provider Seven"])
    }

    func testAFewVotesDoNotTopTheList() {
        let items = [
            Rated(id: "1", title: "Four Votes", tmdb: (9.8, 4), provider: 9.8),
            Rated(id: "2", title: "Many Votes", tmdb: (7.8, 5_000), provider: 5.0),
            Rated(id: "3", title: "Unknown", tmdb: nil, provider: 9.9),
        ]
        // Four votes still rank among TMDb's (near the prior), ahead of a title TMDb has no rating for.
        XCTAssertEqual(ordered(items), ["Many Votes", "Four Votes", "Unknown"])
    }

    func testMissingDataGoesLast() {
        let items = [
            Rated(id: "1", title: "No Rating", tmdb: nil, provider: nil),
            Rated(id: "2", title: "Zero From Provider", tmdb: nil, provider: 0),
            Rated(id: "3", title: "No Votes", tmdb: (0, 0), provider: 6.0),
            Rated(id: "4", title: "Rated", tmdb: (7.0, 300), provider: nil),
        ]
        XCTAssertEqual(ordered(items), ["Rated", "No Votes", "No Rating", "Zero From Provider"])
    }

    func testTiesDoNotDependOnTheListsOrder() {
        let items = [
            Rated(id: "b", title: "same", tmdb: (7.0, 300), provider: nil),
            Rated(id: "a", title: "Same", tmdb: (7.0, 300), provider: nil),
            Rated(id: "c", title: "Fewer Votes", tmdb: (7.0, 100), provider: nil),
            Rated(id: "d", title: "Alpha", tmdb: (7.0, 300), provider: nil),
            Rated(id: "e", title: "Beta", tmdb: nil, provider: 8.0),
            Rated(id: "f", title: "alpha", tmdb: nil, provider: 8.0),
        ]
        let expected = ["Alpha", "Same", "same", "Fewer Votes", "alpha", "Beta"]
        XCTAssertEqual(ordered(items), expected)
        XCTAssertEqual(ordered(items.reversed()), expected)
    }
}
