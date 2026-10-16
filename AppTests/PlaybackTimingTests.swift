import XCTest
import IPTVCore
@testable import Ondo

/// Start and seek times with the real engines, as the viewer waits for them, measured by the player's own log (the
/// one a problem report carries), against Apple's public HLS sample. The bounds are wide (shared CI machines): they
/// catch a start or a seek that hangs, and the times are attached to the result. Skipped without network.
@MainActor
final class PlaybackTimingTests: XCTestCase {
    private static let stream = URL(string: "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8")!

    private func film() -> PlaybackItem {
        PlaybackItem(
            reference: MediaReference(playlistID: UUID(), type: .movie, itemID: "bipbop"),
            title: "Sample",
            streamURL: Self.stream,
            isLive: false
        )
    }

    /// Seconds of the last log line starting with `prefix` ("picture moving after 1.2 s").
    private func logged(_ controller: PlayerController, _ prefix: String) -> Double? {
        controller.log.entries.last { $0.event.hasPrefix(prefix) }
            .flatMap { Double($0.event.dropFirst(prefix.count).prefix { $0 != " " }) }
    }

    private func wait(seconds: Double, until done: () -> Bool) async throws {
        let begin = Date()
        while !done() && Date().timeIntervalSince(begin) < seconds {
            try await Task.sleep(for: .milliseconds(200))
        }
    }

    private func startAndSeek(with engine: PlayerEngine) async throws {
        let controller = PlayerController()
        controller.isMuted = true
        controller.enginePreference = engine
        defer {
            let attachment = XCTAttachment(string: controller.problemReport().text)
            attachment.name = "report-\(engine.rawValue)"
            attachment.lifetime = .keepAlways
            add(attachment)
            controller.stop()
        }
        controller.load(film())
        try await wait(seconds: 45) { logged(controller, "picture moving after ") != nil }
        guard let start = logged(controller, "picture moving after ") else {
            throw XCTSkip("The sample stream did not play here (no network?)")
        }
        controller.seek(to: 120, tolerance: 1)
        try await wait(seconds: 45) { logged(controller, "seek done after ") != nil }
        let seek = try XCTUnwrap(logged(controller, "seek done after "), "the seek never finished")
        XCTAssertLessThan(start, 20, "first picture")
        XCTAssertLessThan(seek, 20, "seek")
        XCTAssertGreaterThanOrEqual(controller.currentTime, 110, "the seek landed")

        let report = controller.problemReport().text
        XCTAssertFalse(report.contains("devstreaming"), "the address never goes into a report")
        XCTAssertFalse(report.contains("bipbop"), "nor any part of it")
        XCTAssertTrue(report.contains("seek done after"))
    }

    func testAVPlayerStartsAndSeeks() async throws {
        try await startAndSeek(with: .apple)
    }

    func testVLCStartsAndSeeks() async throws {
        guard PlayerEngine.isVLCAvailable else { throw XCTSkip("No VLC engine in this build") }
        try await startAndSeek(with: .vlc)
    }

    /// Raw MPEG-TS over HTTP goes straight to VLC: AVPlayer cannot open it, and trying cost every channel change.
    func testRawTransportStreamsGoToVLC() {
        XCTAssertTrue(PlayerEngine.prefersVLC(for: URL(string: "http://example.com/live/user/pass/1.ts")!))
        XCTAssertTrue(PlayerEngine.prefersVLC(for: URL(string: "http://example.com/movie/user/pass/2.mkv")!))
        XCTAssertFalse(PlayerEngine.prefersVLC(for: URL(string: "http://example.com/live/user/pass/1.m3u8")!))
        XCTAssertFalse(PlayerEngine.prefersVLC(for: URL(string: "http://example.com/movie/user/pass/2.mp4")!))
    }
}
