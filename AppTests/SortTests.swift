import XCTest
@testable import Ondo

@MainActor
final class SortTests: XCTestCase {
    /// "Release date": newest year first, titles without a year last, the latest addition first within a year.
    func testReleaseDateSortPutsTheNewestFirstAndUndatedLast() {
        let added = Date(timeIntervalSince1970: 1_700_000_000)
        let keys: [AppModel.ReleaseKey] = [
            (year: 2019, added: added, name: "Quiet Water"),
            (year: nil, added: added.addingTimeInterval(100), name: "Harbor Lights"),
            (year: 2024, added: added, name: "Glass City"),
            (year: 2024, added: added.addingTimeInterval(60), name: "Northern Lights"),
            (year: 2024, added: added, name: "Bright Field"),
        ]
        let sorted = keys.sorted { AppModel.releasedFirst($0, $1) }.map(\.name)
        XCTAssertEqual(sorted, ["Northern Lights", "Bright Field", "Glass City", "Quiet Water", "Harbor Lights"])
    }
}
