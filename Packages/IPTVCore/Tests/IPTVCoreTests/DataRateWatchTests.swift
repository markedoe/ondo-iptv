import XCTest
@testable import IPTVCore

/// A 4K film whose picture uses 8.4 Mb/s (1.05 MB/s) while the data falls to 3.4 Mb/s, then comes back.
final class DataRateWatchTests: XCTestCase {
    private let consumed = 1_050_000.0

    func testTheLogSaysWhenTheDataFallsBehindAndWhenItCatchesUp() {
        var watch = DataRateWatch()
        XCTAssertNil(watch.note(received: 1_100_000, consumed: consumed), "keeping up")
        XCTAssertNil(watch.note(received: 900_000, consumed: consumed), "a little under, within the margin")
        XCTAssertEqual(watch.note(received: 425_000, consumed: consumed), .fellBehind(received: 425_000, consumed: consumed))
        XCTAssertNil(watch.note(received: 400_000, consumed: consumed), "said once")
        XCTAssertNil(watch.note(received: 950_000, consumed: consumed), "not caught up until it is close to the rate again")
        XCTAssertEqual(watch.note(received: 1_020_000, consumed: consumed), .caughtUp(received: 1_020_000))
        XCTAssertFalse(watch.isBehind)
    }

    /// A quiet scene: the player reads only what the picture uses, both fall together. Not behind.
    func testALowerRateThatThePictureFollowsIsNotBehind() {
        var watch = DataRateWatch()
        XCTAssertNil(watch.note(received: 425_000, consumed: 430_000))
    }

    func testNothingIsSaidWithoutTheRateThePictureUses() {
        var watch = DataRateWatch()
        XCTAssertNil(watch.note(received: 0, consumed: nil))
        XCTAssertNil(watch.note(received: nil, consumed: consumed))
        XCTAssertNil(watch.note(received: 10, consumed: 0))
    }
}
