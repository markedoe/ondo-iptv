import XCTest
import UIKit
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

    /// A 40 s MKV film (key frames 10 s apart) from the test bundle, with byte ranges, except for the first
    /// `wholeFileAnswers` requests, answered with the whole file as servers that ignore ranges do.
    private func filmServer(wholeFileAnswers: Int = 0) throws -> LocalHTTPServer {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "film-sample", withExtension: "mkv"))
        let data = try Data(contentsOf: url)
        let requests = RequestCount()
        return try LocalHTTPServer { request in
            var response = LocalHTTPServer.Response(headers: ["Content-Type": "video/x-matroska", "Accept-Ranges": "bytes"], body: data)
            if requests.next() > wholeFileAnswers, let range = request.headers["range"], range.hasPrefix("bytes=") {
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

    /// Each wait gives up after 45 s; beyond that, a test that hangs fails by name (CI enables test timeouts).
    override func setUp() async throws {
        executionTimeAllowance = 180
    }

    private func startAndSeek(
        with engine: PlayerEngine, url: URL = PlaybackTimingTests.stream, to target: Double = 120, inspect: (PlayerController) -> Void = { _ in }
    ) async throws {
        let controller = PlayerController()
        controller.isMuted = true
        controller.enginePreference = engine
        // As in the app (VLCVideoSurface): VLC draws into a view on screen, not into a window of its own.
        let window = Self.window()
        let surface = UIView(frame: window.bounds)
        window.addSubview(surface)
        window.isHidden = false
        controller.attachVLCDrawable(surface)
        defer {
            let attachment = XCTAttachment(string: controller.problemReport().text)
            attachment.name = "report-\(engine.rawValue)"
            attachment.lifetime = .keepAlways
            add(attachment)
            inspect(controller)
            controller.stop()
            window.isHidden = true
        }
        controller.load(film(at: url))
        try await wait(seconds: 45) { logged(controller, "picture moving after ") != nil }
        guard let start = logged(controller, "picture moving after ") else {
            if url == Self.stream { throw XCTSkip("The sample stream did not play here (no network?)") }
            return XCTFail("the film never started")
        }
        XCTAssertLessThan(controller.currentTime, 8, "the film starts at its beginning")
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

    /// A small window in the app's scene, for the video.
    private static func window() -> UIWindow {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        let window = scene.map { UIWindow(windowScene: $0) } ?? UIWindow()
        window.frame = CGRect(x: 0, y: 0, width: 320, height: 180)
        return window
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
        // The player is released, as when the viewer closes it: whatever VLC does then happens within this test and
        // its time allowance (a run hung in the next test once, which had passed alone).
        try await Task.sleep(for: .seconds(2))
    }

    /// An MKV film with the automatic engine: remuxed to HLS for the system player (HDR, Dolby Vision, Atmos, Picture in
    /// Picture), which starts it and seeks in it.
    func testTheSystemPlayerPlaysAnMKVFilmRemuxed() async throws {
        let server = try filmServer()
        let base = try await server.start()
        var engine = ""
        var log: [String] = []
        try await startAndSeek(with: .automatic, url: base.appendingPathComponent("movie/viewer/secret/43.mkv"), to: 25) { controller in
            engine = controller.engineName
            log = controller.log.entries.map(\.event)
        }
        XCTAssertEqual(engine, "AVPlayer (MKV remuxed)", log.joined(separator: "\n"))
        XCTAssertTrue(log.contains { $0.hasPrefix("remux ready") }, log.joined(separator: "\n"))
    }

    /// A server that sends the whole file instead of its parts cannot serve the remux: VLC plays the film. (The
    /// server sends parts to VLC: without them, VLC itself opens this MKV only some of the time.)
    func testVLCPlaysAnMKVFilmTheRemuxCannotRead() async throws {
        guard PlayerEngine.isVLCAvailable else { throw XCTSkip("No VLC engine in this build") }
        let server = try filmServer(wholeFileAnswers: 1)
        let base = try await server.start()
        var engine = ""
        var log: [String] = []
        try await startAndSeek(with: .automatic, url: base.appendingPathComponent("movie/viewer/secret/44.mkv"), to: 25) { controller in
            engine = controller.engineName
            log = controller.log.entries.map(\.event)
        }
        XCTAssertEqual(engine, "VLC", log.joined(separator: "\n"))
        XCTAssertTrue(log.contains { $0.hasPrefix("remux not possible") }, log.joined(separator: "\n"))
        try await Task.sleep(for: .seconds(2))
    }

    /// Raw MPEG-TS over HTTP goes straight to VLC: AVPlayer cannot open it, and trying cost every channel change.
    func testRawTransportStreamsGoToVLC() {
        XCTAssertTrue(PlayerEngine.prefersVLC(for: URL(string: "http://example.com/live/user/pass/1.ts")!))
        XCTAssertTrue(PlayerEngine.prefersVLC(for: URL(string: "http://example.com/movie/user/pass/2.mkv")!))
        XCTAssertFalse(PlayerEngine.prefersVLC(for: URL(string: "http://example.com/live/user/pass/1.m3u8")!))
        XCTAssertFalse(PlayerEngine.prefersVLC(for: URL(string: "http://example.com/movie/user/pass/2.mp4")!))
    }
}

/// Counts a test server's requests, from its queue.
private final class RequestCount: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0

    func next() -> Int {
        lock.withLock {
            count += 1
            return count
        }
    }
}
