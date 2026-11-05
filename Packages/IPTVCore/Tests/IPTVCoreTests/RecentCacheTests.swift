import XCTest
@testable import IPTVCore

final class RecentCacheTests: XCTestCase {
    /// A full cache lets go of the value used longest ago only: one used at every redraw stays, however many others come.
    func testAFullCacheLetsGoOfTheOneUsedLongestAgo() {
        var cache = RecentCache<[Int]>(capacity: 3)
        cache.store([1], key: "all")
        for row in 0..<40 {
            XCTAssertEqual(cache.value("all"), [1], "row \(row)")
            cache.store([row], key: "row-\(row)")
            XCTAssertLessThanOrEqual(cache.count, 3)
        }
        XCTAssertEqual(cache.value("row-39"), [39])
        XCTAssertNil(cache.value("row-0"))
    }

    func testStoringAgainReplacesTheValue() {
        var cache = RecentCache<String>(capacity: 2)
        cache.store("a", key: "k")
        cache.store("b", key: "k")
        XCTAssertEqual(cache.count, 1)
        XCTAssertEqual(cache.value("k"), "b")
        cache.store("c", key: "other")
        cache.store("d", key: "third")
        XCTAssertNil(cache.value("k"))
        cache.removeAll()
        XCTAssertEqual(cache.count, 0)
    }
}
