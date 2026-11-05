import XCTest
@testable import IPTVCore

/// The background fill of a grid sorted by rating, with a fake TMDb. The Android core has the same tests (`RatingFillTest.kt`).
final class RatingFillTests: XCTestCase {
    /// A fake TMDb: answers after a moment, with a rating for the titles `rated` accepts; counts the requests at once.
    private actor FakeTMDb {
        let rated: @Sendable (String) -> Bool
        let answer: Duration
        private(set) var asked: [String] = []
        private(set) var running = 0
        private(set) var mostAtOnce = 0

        init(answer: Duration = .milliseconds(2), rated: @escaping @Sendable (String) -> Bool = { _ in true }) {
            self.answer = answer
            self.rated = rated
        }

        func fetch(_ id: String) async -> Bool {
            asked.append(id)
            running += 1
            mostAtOnce = max(mostAtOnce, running)
            try? await Task.sleep(for: answer)
            running -= 1
            return rated(id)
        }
    }

    private actor Counter {
        private(set) var values: [Int] = []
        func add(_ value: Int) { values.append(value) }
    }

    private let ids = (1...50).map { "t\($0)" }

    func testOnlyTheTitlesWithoutAnAnswerAreAskedInTheGridsOrderAndBounded() {
        let known: Set<String> = ["t1", "t3"]
        XCTAssertEqual(RatingFill.toAsk(ids, isKnown: { known.contains($0) }, limit: 3), ["t2", "t4", "t5"])
        XCTAssertEqual(RatingFill.toAsk((1...500).map { "t\($0)" }, isKnown: { _ in false }).count, RatingFill.limit)
        XCTAssertTrue(RatingFill.toAsk(ids, isKnown: { _ in true }).isEmpty, "everything known: nothing asked")
        XCTAssertEqual(RatingFill.limit, 100)
        XCTAssertEqual(RatingFill.firstBatch, 20)
        XCTAssertEqual(RatingFill.concurrency, 2)
    }

    func testTheGridIsResortedTwiceNotAtEachAnswer() async {
        let tmdb = FakeTMDb()
        let resortedAfter = Counter()
        let resorts = await RatingFill.run(ids, fetch: { await tmdb.fetch($0) }, resort: { await resortedAfter.add(await tmdb.asked.count) })
        let asked = await tmdb.asked
        let after = await resortedAfter.values
        let mostAtOnce = await tmdb.mostAtOnce
        // Each title once; two run at once, so two neighbours may start in either order.
        XCTAssertEqual(asked.count, ids.count)
        XCTAssertEqual(Set(asked), Set(ids))
        XCTAssertEqual(resorts, 2)
        XCTAssertTrue((RatingFill.firstBatch...RatingFill.firstBatch + RatingFill.concurrency).contains(after.first ?? 0), "first after a batch: \(after)")
        XCTAssertEqual(after.last, ids.count)
        XCTAssertLessThanOrEqual(mostAtOnce, RatingFill.concurrency)
    }

    func testWithoutNewRatingsTheGridDoesNotMove() async {
        let tmdb = FakeTMDb(rated: { _ in false })
        let none = await RatingFill.run(ids, fetch: { await tmdb.fetch($0) }, resort: { XCTFail("no rating came: no re-sort") })
        XCTAssertEqual(none, 0)
        // Fewer than a batch: one re-sort, at the end.
        let few = FakeTMDb(rated: { ["t1", "t2", "t3"].contains($0) })
        let once = await RatingFill.run(ids, fetch: { await few.fetch($0) }, resort: {})
        XCTAssertEqual(once, 1)
    }

    func testLeavingTheGridStopsTheFill() async throws {
        let tmdb = FakeTMDb(answer: .milliseconds(50))
        let resorts = Counter()
        let ids = self.ids
        let fill = Task { await RatingFill.run(ids, fetch: { await tmdb.fetch($0) }, resort: { await resorts.add(1) }) }
        try await Task.sleep(for: .milliseconds(120))
        fill.cancel()
        _ = await fill.value
        let asked = await tmdb.asked.count
        XCTAssertLessThan(asked, ids.count, "stopped")
        let count = await resorts.values.count
        XCTAssertEqual(count, 0, "no re-sort once the grid is left")
    }
}
