import XCTest
@testable import IPTVCore

final class LiveBufferPolicyTests: XCTestCase {
    func testStartsWithTheNormalBuffer() {
        XCTAssertEqual(LiveBufferPolicy().seconds, LiveBufferPolicy.normal)
    }

    /// Stops the buffer covered (a provider sending in 2.5 s bursts, a 2 s hiccup) keep it.
    func testStopsTheBufferCoversKeepIt() {
        var policy = LiveBufferPolicy()
        policy.note(stall: 2.5, buffer: 3)
        policy.note(stall: 0, buffer: 3)
        XCTAssertEqual(policy.seconds, LiveBufferPolicy.normal)
    }

    /// A stop that used up the buffer raises it for the next channels, and it stays raised.
    func testAStopThatUsedUpTheBufferRaisesIt() {
        var policy = LiveBufferPolicy()
        policy.note(stall: 3, buffer: 3)
        XCTAssertEqual(policy.seconds, LiveBufferPolicy.raised)
        policy.note(stall: 0.5, buffer: 6)
        XCTAssertEqual(policy.seconds, LiveBufferPolicy.raised)
    }

    /// AVPlayer holds no VLC buffer: nothing to learn from it.
    func testNoBufferNoChange() {
        var policy = LiveBufferPolicy()
        policy.note(stall: 10, buffer: 0)
        XCTAssertEqual(policy.seconds, LiveBufferPolicy.normal)
    }
}
