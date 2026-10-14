import XCTest
import IPTVCore
@testable import Ondo

/// "Resume" against a real stream (Apple's public HLS sample, as in the UI tests): AVPlayer, its timing and
/// its seeks cannot be faked. Skipped when the stream cannot be reached.
@MainActor
final class ResumePlaybackTests: XCTestCase {
    private static let stream = URL(string: "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8")!

    private func film(startingAt start: Double?) -> PlaybackItem {
        PlaybackItem(
            reference: MediaReference(playlistID: UUID(), type: .movie, itemID: "bipbop"),
            title: "Sample",
            streamURL: Self.stream,
            isLive: false,
            startPosition: start
        )
    }

    private func waitUntilPlaying(_ controller: PlayerController) async throws {
        let deadline = Date().addingTimeInterval(45)
        while controller.state != .playing, Date() < deadline {
            if case .failed = controller.state { throw XCTSkip("The sample stream cannot be played here") }
            try await Task.sleep(for: .milliseconds(200))
        }
        guard controller.state == .playing else { throw XCTSkip("The sample stream did not start in time") }
    }

    /// The player used to report its own clock (still at 0) before the resume seek landed, which saved the
    /// beginning of the film over the resume point; and it buffered the beginning before seeking.
    func testResumeStartsAtTheSavedPositionAndNeverSavesTheBeginning() async throws {
        let controller = PlayerController()
        controller.isMuted = true
        var reported: [Double] = []
        controller.onProgress = { _, position, _ in reported.append(position) }

        controller.load(film(startingAt: 300))
        try await waitUntilPlaying(controller)
        try await Task.sleep(for: .seconds(1))

        XCTAssertGreaterThanOrEqual(controller.currentTime, 295, "playback starts at the resume point")
        controller.pause()
        XCTAssertFalse(reported.isEmpty)
        XCTAssertTrue(reported.allSatisfy { $0 >= 295 }, "positions saved: \(reported)")
        controller.stop()
    }

    func testAPauseBeforeTheResumeSeekLandsKeepsThePlayerPaused() async throws {
        let controller = PlayerController()
        controller.isMuted = true
        controller.load(film(startingAt: 120))
        controller.pause()
        let deadline = Date().addingTimeInterval(30)
        while controller.state == .loading, Date() < deadline {
            try await Task.sleep(for: .milliseconds(200))
        }
        if case .failed = controller.state { throw XCTSkip("The sample stream cannot be played here") }
        XCTAssertNotEqual(controller.state, .playing, "the viewer paused while it was opening")
        XCTAssertGreaterThanOrEqual(controller.currentTime, 115)
        controller.stop()
    }

    func testOnlyFilmsResumeAndNotFromTheFirstSeconds() {
        XCTAssertEqual(PlayerController.resumeStart(for: film(startingAt: 300)), 300)
        XCTAssertNil(PlayerController.resumeStart(for: film(startingAt: 3)))
        XCTAssertNil(PlayerController.resumeStart(for: film(startingAt: nil)))
        var live = film(startingAt: 300)
        live.isLive = true
        XCTAssertNil(PlayerController.resumeStart(for: live))
    }
}
