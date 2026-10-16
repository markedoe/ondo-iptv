import XCTest
@testable import IPTVCore

final class MovementWatchTests: XCTestCase {
    func testTheFirstPictureIsWhenThePositionMoves() {
        var watch = MovementWatch()
        watch.start(at: 0)
        XCTAssertNil(watch.sample(clock: 0.5, position: 0))
        XCTAssertNil(watch.sample(clock: 1.0, position: 0))
        XCTAssertEqual(watch.sample(clock: 1.5, position: 0.4), 1.5)
        XCTAssertNil(watch.since)
        XCTAssertNil(watch.sample(clock: 2.0, position: 0.9))
    }

    /// The position jumps to the target at once and stands there until the picture plays: the wait ends when it
    /// moves on, not at the jump.
    func testASeekEndsWhenThePictureMovesOnFromTheTarget() {
        var watch = MovementWatch()
        watch.start(at: 10, target: 40, from: 30.2)
        XCTAssertNil(watch.sample(clock: 10.5, position: 30.4))  // not there yet
        XCTAssertNil(watch.sample(clock: 11.0, position: 40))  // landed
        XCTAssertNil(watch.sample(clock: 11.5, position: 40))
        XCTAssertEqual(watch.sample(clock: 12.0, position: 40.3) ?? 0, 2, accuracy: 0.001)
    }

    /// Backwards, and onto the key frame before the target.
    func testASeekBackLandingOnAKeyFrame() {
        var watch = MovementWatch()
        watch.start(at: 20, target: 15, from: 25)
        XCTAssertNil(watch.sample(clock: 20.5, position: 10))
        XCTAssertEqual(watch.sample(clock: 21.0, position: 10.5) ?? 0, 1, accuracy: 0.001)
    }

    /// Paused (no position) or cancelled: no time.
    func testNothingWhilePausedOrCancelled() {
        var watch = MovementWatch()
        watch.start(at: 0)
        XCTAssertNil(watch.sample(clock: 1, position: nil))
        watch.cancel()
        XCTAssertNil(watch.sample(clock: 2, position: 1))
        XCTAssertNil(watch.sample(clock: 3, position: 2))
    }
}
