import AVFoundation
import XCTest
import IPTVCore
@testable import Ondo

/// "Resume" against a real stream (Apple's public HLS sample, as in the demo): AVPlayer, its timing and its
/// seeks cannot be faked. When the resume does not start, the same stream is played from the beginning: the
/// test fails if that works (the resume path stalls) and is skipped if it does not (no network here).
/// What the player went through is attached to the result (published with the CI screenshots, in `debug/`).
@MainActor
final class ResumePlaybackTests: XCTestCase {
    private static let stream = URL(string: "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8")!
    private var timeline: [String] = []

    private func film(startingAt start: Double?) -> PlaybackItem {
        PlaybackItem(
            reference: MediaReference(playlistID: UUID(), type: .movie, itemID: "bipbop"),
            title: "Sample",
            streamURL: Self.stream,
            isLive: false,
            startPosition: start
        )
    }

    /// Waits until `done` holds (or the player fails), noting every change of the player's state.
    private func wait(_ controller: PlayerController, seconds: Double, until done: () -> Bool) async throws {
        let begin = Date()
        var last = ""
        while true {
            let now = describe(controller)
            if now != last {
                timeline.append(String(format: "%6.1fs ", Date().timeIntervalSince(begin)) + now)
                last = now
            }
            if done() || Date().timeIntervalSince(begin) > seconds { return }
            if case .failed = controller.state { return }
            try await Task.sleep(for: .milliseconds(200))
        }
    }

    private func describe(_ controller: PlayerController) -> String {
        let player = controller.player
        let item = player.currentItem
        let loaded = item?.loadedTimeRanges.map { value -> String in
            let range = value.timeRangeValue
            return String(format: "%.0f+%.0f", range.start.seconds, range.duration.seconds)
        }.joined(separator: ",") ?? "-"
        let log = item?.errorLog()?.events.last.map { "\($0.errorDomain) \($0.errorStatusCode) \($0.errorComment ?? "")" } ?? "-"
        let status = item.map { String($0.status.rawValue) } ?? "none"
        let clock = player.currentTime().seconds
        let parts: [String] = [
            "state=\(controller.state)",
            "engine=\(controller.activeEngine.rawValue)",
            "item=\(status)",
            "control=\(player.timeControlStatus.rawValue)",
            "waiting=\(player.reasonForWaitingToPlay?.rawValue ?? "-")",
            String(format: "clock=%.1f", clock),
            String(format: "shown=%.1f", controller.currentTime),
            "loaded=[\(loaded)]",
            "error=\(item?.error?.localizedDescription ?? "-")",
            "log=\(log)",
        ]
        return parts.joined(separator: " ")
    }

    private func attachTimeline(_ name: String) {
        let attachment = XCTAttachment(string: timeline.joined(separator: "\n"))
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// Whether the sample stream plays from its beginning here (tells a stalled resume from a missing network).
    private func samplePlaysFromTheBeginning() async throws -> Bool {
        timeline.append("— control: the same stream from the beginning —")
        let control = PlayerController()
        control.isMuted = true
        control.load(film(startingAt: nil))
        try await wait(control, seconds: 45) { control.state == .playing }
        let plays = control.state == .playing
        control.stop()
        return plays
    }

    /// The player used to report its own clock (still at 0) before the resume seek landed, which saved the
    /// beginning of the film over the resume point; and it buffered the beginning before seeking.
    func testResumeStartsAtTheSavedPositionAndNeverSavesTheBeginning() async throws {
        defer { attachTimeline("resume-timeline") }
        let controller = PlayerController()
        controller.isMuted = true
        var reported: [Double] = []
        controller.onProgress = { _, position, _ in reported.append(position) }

        controller.load(film(startingAt: 300))
        try await wait(controller, seconds: 45) { controller.state == .playing }
        guard controller.state == .playing else {
            controller.stop()
            if try await samplePlaysFromTheBeginning() {
                return XCTFail("The stream plays from the beginning but the resume never started")
            }
            throw XCTSkip("The sample stream cannot be played here")
        }
        try await Task.sleep(for: .seconds(1))

        XCTAssertGreaterThanOrEqual(controller.player.currentTime().seconds, 295, "playback starts at the resume point")
        XCTAssertGreaterThanOrEqual(controller.currentTime, 295)
        controller.pause()
        XCTAssertFalse(reported.isEmpty)
        XCTAssertTrue(reported.allSatisfy { $0 >= 295 }, "positions saved: \(reported)")
        controller.stop()
    }

    /// Paused while it opens: it stays paused at the resume point, and Play then starts it there (Play used to
    /// be ignored until a seek that a paused player never finished).
    func testAPauseBeforeTheResumeSeekLandsKeepsThePlayerPaused() async throws {
        defer { attachTimeline("resume-pause-timeline") }
        let controller = PlayerController()
        controller.isMuted = true
        controller.load(film(startingAt: 120))
        controller.pause()
        // Shown as paused at once; the resume seek is sent once the item is ready.
        try await wait(controller, seconds: 45) {
            controller.player.currentItem?.status == .readyToPlay && controller.player.currentTime().seconds >= 115
        }
        XCTAssertNotEqual(controller.state, .playing, "the viewer paused while it was opening")
        guard controller.player.currentItem?.status == .readyToPlay else {
            controller.stop()
            if try await samplePlaysFromTheBeginning() {
                return XCTFail("The stream plays from the beginning but the paused resume never got ready")
            }
            throw XCTSkip("The sample stream cannot be played here")
        }
        XCTAssertEqual(controller.state, .paused)
        XCTAssertGreaterThanOrEqual(controller.player.currentTime().seconds, 115, "the resume seek was sent")
        try await Task.sleep(for: .seconds(1))
        XCTAssertEqual(controller.state, .paused, "it does not start on its own")
        XCTAssertEqual(controller.player.rate, 0)

        controller.play()
        try await wait(controller, seconds: 30) { controller.state == .playing }
        XCTAssertEqual(controller.state, .playing, "Play starts the paused resume")
        XCTAssertGreaterThanOrEqual(controller.player.currentTime().seconds, 115, "from the resume point")
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
