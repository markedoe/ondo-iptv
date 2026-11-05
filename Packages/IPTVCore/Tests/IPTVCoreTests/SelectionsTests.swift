import XCTest
@testable import IPTVCore

/// The selections' tags, their match and the daily pick (the same cases as the Kotlin `SelectionsTest`).
final class SelectionsTests: XCTestCase {
    private let drama = Selections.genre("Drama")!
    private let cannes = Selections.award("cannes")
    private let nineties = Selections.decade(1994)
    private let manga = Selections.style("manga")

    private let film = Selections.Facts(genres: ["drama", "thriller"], awardGroups: ["cannes"], year: 1994, styles: [])
    private let anime = Selections.Facts(genres: ["animation"], awardGroups: [], year: 2003, styles: Selections.styles(of: "VOSTFR | Mangas"))

    func testTagsAreNamedByKind() {
        XCTAssertEqual(drama, "genre:drama")
        XCTAssertEqual(Selections.genre("  Science-Fiction "), "genre:science fiction")
        XCTAssertNil(Selections.genre(" - "))
        XCTAssertEqual(cannes, "award:cannes")
        XCTAssertEqual(nineties, "decade:1990")
        XCTAssertEqual(Selections.decade(2000), "decade:2000")
        XCTAssertEqual(manga, "style:manga")
        XCTAssertEqual(Selections.rowKey("abc"), "sel:abc")
        XCTAssertEqual(Selections.id(ofRow: "sel:abc"), "abc")
        XCTAssertNil(Selections.id(ofRow: "cat:1|drama"))
        XCTAssertNil(Selections.id(ofRow: "sel:"))
        XCTAssertEqual(Selections.styles(of: "Kids & Cartoons"), ["kids"])
    }

    func testEachGroupOfTagsMatches() {
        XCTAssertTrue(Selections.matches(tags: [drama], all: false, facts: film))
        XCTAssertFalse(Selections.matches(tags: [drama], all: false, facts: anime))
        XCTAssertTrue(Selections.matches(tags: [cannes], all: false, facts: film))
        XCTAssertFalse(Selections.matches(tags: [cannes], all: false, facts: anime))
        XCTAssertTrue(Selections.matches(tags: [nineties], all: false, facts: film))
        XCTAssertFalse(Selections.matches(tags: [nineties], all: false, facts: anime))
        XCTAssertFalse(Selections.matches(tags: [nineties], all: false, facts: Selections.Facts()))
        XCTAssertTrue(Selections.matches(tags: [manga], all: false, facts: anime))
        XCTAssertFalse(Selections.matches(tags: [manga], all: false, facts: film))
    }

    func testAnyTagOrAllTags() {
        XCTAssertTrue(Selections.matches(tags: [drama, manga], all: false, facts: film))
        XCTAssertTrue(Selections.matches(tags: [drama, manga], all: false, facts: anime))
        XCTAssertFalse(Selections.matches(tags: [drama, manga], all: true, facts: film))
        XCTAssertTrue(Selections.matches(tags: [drama, cannes, nineties], all: true, facts: film))
        // No tag matches nothing; a tag of a kind this version does not know is left out, in both modes.
        XCTAssertFalse(Selections.matches(tags: [], all: false, facts: film))
        XCTAssertFalse(Selections.matches(tags: [], all: true, facts: film))
        XCTAssertTrue(Selections.matches(tags: [drama, "mood:dark"], all: true, facts: film))
        XCTAssertFalse(Selections.matches(tags: ["mood:dark"], all: false, facts: film))
        XCTAssertFalse(Selections.matches(tags: ["genre:"], all: false, facts: film))
    }

    func testTheDrawIsTheSameOnEveryPlatform() {
        // The same numbers as the Kotlin test: the daily order is the same on Android and Apple devices.
        XCTAssertEqual(Selections.draw("a"), 0.010753497314070604)
        XCTAssertEqual(Selections.draw("s1|2026-11-05|movie:42"), 0.8617043371713481)
        for seed in ["", "x", "s1|2026-11-06|series:7"] {
            XCTAssertTrue((0..<1).contains(Selections.draw(seed)))
        }
    }

    private struct Title {
        let id: String
        let rating: (average: Double, count: Int)?
        var watched = false
    }

    private func titles() -> [Title] {
        (0..<30).map { index in
            Title(id: "movie:\(index)", rating: index % 3 == 0 ? nil : (5.0 + Double(index % 5), 50 * (index + 1)))
        }
    }

    private func order(_ titles: [Title], day: String, id: String = "s1") -> [String] {
        Selections.dailyOrder(titles, selectionID: id, day: day, key: \.id, rating: \.rating, watched: \.watched).map(\.id)
    }

    func testTheSameDayGivesTheSameOrderAndTheNextDayAnother() {
        let list = titles()
        let today = order(list, day: "2026-11-05")
        XCTAssertEqual(today, order(list.reversed(), day: "2026-11-05"))
        XCTAssertEqual(Set(today), Set(list.map(\.id)))
        XCTAssertNotEqual(today, order(list, day: "2026-11-06"))
        // Another selection of the same titles has its own pick.
        XCTAssertNotEqual(today, order(list, day: "2026-11-05", id: "s2"))
        XCTAssertEqual(today.prefix(5), ["movie:29", "movie:14", "movie:4", "movie:19", "movie:22"])
    }

    func testTheBestRatedComeFirstMostDays() {
        // Over many days, a title of 9 with thousands of votes leads the row far more often than one TMDb does not know.
        let top = Title(id: "movie:top", rating: (9.0, 20_000))
        let unknown = Title(id: "movie:unknown", rating: nil)
        let few = Title(id: "movie:few", rating: (10.0, 5))
        var topFirst = 0
        for day in 1...28 {
            let picked = order([unknown, few, top], day: "2026-02-\(day < 10 ? "0" : "")\(day)")
            if picked.first == "movie:top" { topFirst += 1 }
        }
        XCTAssertGreaterThan(topFirst, 20)
        // Too few votes count as no rating.
        XCTAssertEqual(Selections.score((10.0, 5)), WeightedRating.priorAverage)
        XCTAssertEqual(Selections.score(nil), WeightedRating.priorAverage)
        XCTAssertEqual(Selections.score((8.5, 200)), 7.5, accuracy: 0.0001)
    }

    func testTitlesWatchedComeLast() {
        var list = titles()
        for index in list.indices where index % 4 == 0 { list[index].watched = true }
        let picked = order(list, day: "2026-11-05")
        let watched = Set(list.filter(\.watched).map(\.id))
        XCTAssertEqual(Set(picked.suffix(watched.count)), watched)
        XCTAssertFalse(watched.contains(picked[0]))
    }

    func testTheDefaultNameAndTheDecades() {
        XCTAssertEqual(Selections.defaultName(tags: [drama, "mood:dark", cannes, nineties, manga], name: { $0 }),
                       "genre:drama · award:cannes · decade:1990")
        XCTAssertEqual(Selections.decades([1994, nil, 1999, 2003, 1, 2024]), [2020, 2000, 1990])
    }
}
