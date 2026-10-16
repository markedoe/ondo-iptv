import XCTest
@testable import IPTVCore

final class BufferPolicyTests: XCTestCase {
    func testStartsWithTheNormalBuffer() {
        XCTAssertEqual(BufferPolicy.live.seconds, 3)
        XCTAssertEqual(BufferPolicy.film.seconds, 1)
    }

    /// Stops the buffer covered (a provider sending in 2.5 s bursts, a 2 s hiccup) keep it.
    func testStopsTheBufferCoversKeepIt() {
        var policy = BufferPolicy.live
        policy.note(stall: 2.5, buffer: 3)
        policy.note(stall: 0, buffer: 3)
        XCTAssertEqual(policy.seconds, policy.normal)
    }

    /// A stop that used up the buffer raises it for the next items, and it stays raised.
    func testAStopThatUsedUpTheBufferRaisesIt() {
        var policy = BufferPolicy.film
        policy.note(stall: 1.5, buffer: 1)
        XCTAssertEqual(policy.seconds, 5)
        policy.note(stall: 0.5, buffer: 5)
        XCTAssertEqual(policy.seconds, 5)
    }

    /// AVPlayer holds no VLC buffer: nothing to learn from it.
    func testNoBufferNoChange() {
        var policy = BufferPolicy.live
        policy.note(stall: 10, buffer: 0)
        XCTAssertEqual(policy.seconds, policy.normal)
    }

    func testTheSettingWinsOverTheAutomaticValue() {
        XCTAssertEqual(PlaybackBuffer.seconds(setting: 0, automatic: 3), 3)
        XCTAssertEqual(PlaybackBuffer.seconds(setting: 10, automatic: 3), 10)
        XCTAssertEqual(PlaybackBuffer.choices.first, 0)
    }
}
