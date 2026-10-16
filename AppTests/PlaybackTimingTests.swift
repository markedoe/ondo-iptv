import XCTest
import IPTVCore
@testable import Ondo

/// Start and seek times with the real engines, as the viewer waits for them, measured by the player's own log (the
/// one a problem report carries): AVPlayer on Apple's public HLS sample (skipped without network), VLC on an MKV film
/// served from the test runner with byte ranges, as providers serve films. The bounds are wide (shared CI machines):
/// they catch a start or a seek that hangs, and the reports are attached to the result.
@MainActor
final class PlaybackTimingTests: XCTestCase {
    private static let stream = URL(string: "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8")!

    private func film(at url: URL = PlaybackTimingTests.stream) -> PlaybackItem {
        PlaybackItem(
            reference: MediaReference(playlistID: UUID(), type: .movie, itemID: "sample"),
            title: "Sample",
            streamURL: url,
            isLive: false
        )
    }

    /// A 40 s MKV film (key frames 10 s apart) from the test bundle, with byte ranges.
    private func filmServer() throws -> LocalHTTPServer {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "film-sample", withExtension: "mkv"))
        let data = try Data(contentsOf: url)
        return try LocalHTTPServer { request in
            var response = LocalHTTPServer.Response(headers: ["Content-Type": "video/x-matroska", "Accept-Ranges": "bytes"], body: data)
            if let range = request.headers["range"], range.hasPrefix("bytes=") {
                let bounds = range.dropFirst(6).split(separator: "-", omittingEmptySubsequences: false)
                let first = Int(bounds.first ?? "") ?? 0
                let last = min(Int(bounds.dropFirst().first ?? "") ?? data.count - 1, data.count - 1)
                guard first <= last else { return LocalHTTPServer.Response(status: 416) }
                response.status = 206
                response.headers["Content-Range"] = "bytes \(first)-\(last)/\(data.count)"
                response.body = data.subdata(in: first..<(last + 1))
            }
            return response
        }
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

    private func startAndSeek(with engine: PlayerEngine, url: URL = PlaybackTimingTests.stream, to target: Double = 120) async throws {
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
        controller.load(film(at: url))
        try await wait(seconds: 45) { logged(controller, "picture moving after ") != nil }
        guard let start = logged(controller, "picture moving after ") else {
            if url == Self.stream { throw XCTSkip("The sample stream did not play here (no network?)") }
            return XCTFail("the film never started")
        }
        controller.seek(to: target, tolerance: 1)
        try await wait(seconds: 45) { logged(controller, "seek done after ") != nil }
        let seek = try XCTUnwrap(logged(controller, "seek done after "), "the seek never finished")
        XCTAssertLessThan(start, 20, "first picture")
        XCTAssertLessThan(seek, 20, "seek")
        XCTAssertGreaterThanOrEqual(controller.currentTime, target - 10, "the seek landed")

        let report = controller.problemReport().text
        for part in [url.host ?? "", url.lastPathComponent, "secret", "viewer", "bipbop", "127.0.0.1"] where !part.isEmpty {
            XCTAssertFalse(report.contains(part), "the address never goes into a report: \(part)")
        }
        XCTAssertTrue(report.contains("seek done after"))
    }

    func testAVPlayerStartsAndSeeks() async throws {
        try await startAndSeek(with: .apple)
    }

    func testVLCStartsAndSeeksInAnMKVFilm() async throws {
        guard PlayerEngine.isVLCAvailable else { throw XCTSkip("No VLC engine in this build") }
        let server = try filmServer()
        let base = try await server.start()
        try await startAndSeek(with: .vlc, url: base.appendingPathComponent("movie/viewer/secret/42.mkv"), to: 25)
        XCTAssertTrue(server.requests.contains { $0.headers["range"] != nil }, "VLC reads the film by byte ranges")
    }

    /// Raw MPEG-TS over HTTP goes straight to VLC: AVPlayer cannot open it, and trying cost every channel change.
    func testRawTransportStreamsGoToVLC() {
        XCTAssertTrue(PlayerEngine.prefersVLC(for: URL(string: "http://example.com/live/user/pass/1.ts")!))
        XCTAssertTrue(PlayerEngine.prefersVLC(for: URL(string: "http://example.com/movie/user/pass/2.mkv")!))
        XCTAssertFalse(PlayerEngine.prefersVLC(for: URL(string: "http://example.com/live/user/pass/1.m3u8")!))
        XCTAssertFalse(PlayerEngine.prefersVLC(for: URL(string: "http://example.com/movie/user/pass/2.mp4")!))
    }
}
