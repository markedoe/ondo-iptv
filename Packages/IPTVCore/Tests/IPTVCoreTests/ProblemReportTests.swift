import XCTest
@testable import IPTVCore

/// A problem report leaves the device: it must never carry an address, a login, a host or a title.
final class ProblemReportTests: XCTestCase {
    // Fictitious login on example.com, the very thing a report must never carry. leak-guard: allow
    private let secretURL = "http://viewer:secret-pass@panel.example.com:8080/movie/viewer/secret-pass/42.mkv"  // leak-guard: allow

    func testTheScrubberRemovesAddressesLoginsHostsAndIPs() {
        let samples = [
            "Failed to open \(secretURL) (error -1100)",
            "redirected to https://cdn.example.net/hls/index.m3u8?token=abc123",
            "login viewer:secret-pass@panel.example.com",  // leak-guard: allow
            "server 203.0.113.7:8080 refused",
            "IPv6 2001:db8:0:0:0:0:0:1 unreachable",
            "contact someone@example.org",
            "host panel.example.com timed out",
        ]
        for text in samples {
            let scrubbed = PrivacyScrubber.scrub(text)
            for secret in ["secret-pass", "viewer", "example.com", "example.net", "example.org", "203.0.113.7", "2001:db8", "abc123", "someone"] {
                XCTAssertFalse(scrubbed.contains(secret), "\(secret) left in: \(scrubbed)")
            }
        }
        XCTAssertEqual(PrivacyScrubber.scrub("seek to 600.0 s done after 0.4 s"), "seek to 600.0 s done after 0.4 s")
        XCTAssertEqual(PrivacyScrubber.scrub("VLC 3.0.21, iOS 18.0.1"), "VLC 3.0.21, iOS 18.0.1")
    }

    /// A relative address (a playlist line a player quotes whole) has no scheme: the login in its query or in its Xtream
    /// path goes too.
    func testTheScrubberRemovesTheLoginOfARelativeAddress() {
        let scrubbed = PrivacyScrubber.scrub(
            "Couldn't match METHOD=(NONE|AES-128) in #EXT-X-KEY:URI=\"key.php?username=viewer&password=secret-pass&token=abc123\" / "  // leak-guard: allow
                + "Couldn't match NAME=\"(.+?)\" in #EXT-X-MEDIA:TYPE=AUDIO,URI=\"/movie/viewer/secret-pass/42.m3u8\""
        )
        for secret in ["secret-pass", "viewer", "abc123"] {
            XCTAssertFalse(scrubbed.contains(secret), "\(secret) left in: \(scrubbed)")
        }
        XCTAssertTrue(scrubbed.contains("Couldn't match METHOD=(NONE|AES-128) in #EXT-X-KEY:URI="), scrubbed)
    }

    /// Hosts under any top-level domain and shortened IPv6 addresses go; the names of errors, codecs and versions stay.
    func testTheScrubberKnowsEveryHostAndKeepsWhatHelps() {
        let samples = [
            "Unable to resolve host \"panel.example.vip\": No address associated with hostname",
            "failed to connect to panel.example.co/2001:db8::7 (port 8080) after 10000ms",
            "connecting to [2001:db8::1]:8080",
            "The certificate for this server is invalid. You might be connecting to a server that is pretending to be “panel.example.one” which could put your confidential information at risk.",
            "PANEL.EXAMPLE.TV refused, then fe80::1%en0",
        ]
        for text in samples {
            let scrubbed = PrivacyScrubber.scrub(text)
            for secret in ["panel", "example", "2001:db8", "db8::", "fe80"] {
                XCTAssertFalse(scrubbed.lowercased().contains(secret), "\(secret) left in: \(scrubbed)")
            }
        }
        XCTAssertEqual(
            PrivacyScrubber.scrub("failed to connect to panel.example.co/2001:db8::7 (port 8080)"), "failed to connect to <host>/<ip> (port 8080)"
        )
        XCTAssertEqual(PrivacyScrubber.scrub("connecting to [2001:db8::1]:8080"), "connecting to <ip>")
        let readable = [
            "java.net.UnknownHostException: Unable to resolve host",
            "javax.net.ssl.SSLHandshakeException: java.security.cert.CertPathValidatorException",
            "androidx.media3.exoplayer.ExoPlaybackException: Source error",
            "Error Domain=NSURLErrorDomain Code=-1003, CoreMediaErrorDomain -12642",
            "codecs avc1.64001F, mp4a.40.2, hvc1.2.4.L153.B0, ec-3",
            "ExoPlayer 1.11.0, seek to 600.0 s at 12:30:45, e.g. a key frame. Then i.e. the next one.",
            "remux served /seg-3.m4s, /init.mp4 and /media.m3u8",
        ]
        for text in readable {
            XCTAssertEqual(PrivacyScrubber.scrub(text), text)
        }
    }

    func testTheLogKeepsTheLastLinesScrubbed() {
        var log = PlaybackLog()
        for index in 0..<(PlaybackLog.limit + 10) { log.add("event \(index)", at: Double(index)) }
        XCTAssertEqual(log.entries.count, PlaybackLog.limit)
        XCTAssertEqual(log.entries.first?.event, "event 10")
        log.add("opening \(secretURL)", at: 1)
        XCTAssertFalse(log.entries.last?.event.contains("secret-pass") ?? true)
    }

    func testTheReportHoldsTimingsAndNoPrivateData() {
        var health = PlaybackHealth(buffer: 3)
        for step in 0...40 {
            let clock = Double(step) / 2
            health.add(PlaybackSample(
                clock: clock, mediaTime: clock, receivedBytes: step * 200_000, consumedBytes: step * 190_000,
                displayedPictures: step * 12, lostPictures: 0, isPlaying: true
            ))
        }
        var log = PlaybackLog()
        log.add("open film with VLC, buffer 3 s", at: 0)
        log.add("first picture after 1.4 s", at: 1.4)
        log.add("seek from 10.0 to 20.0 s", at: 12)
        log.add("seek done after 0.6 s", at: 12.6)
        log.add("error: The server \(secretURL) did not answer", at: 15)
        let context = ProblemReport.Context(
            app: "Ondo 1.0 (12)", system: "macOS 15.1", engine: "VLC", enginePreference: "automatic",
            liveBufferSetting: 0, vodBufferSetting: 5, bufferInUse: 5, kind: "film", container: "mkv",
            video: "1920×1080", duration: 5400, position: 20,
            state: "failed: The stream \(secretURL) could not be opened"
        )
        let report = ProblemReport(context: context, health: health, log: log).text
        for expected in ["Engine: VLC", "films and series 5 s", "Playing: film, .mkv, 1920×1080", "first picture after 1.4 s", "seek done after 0.6 s", "Verdict: smooth", "Samples"] {
            XCTAssertTrue(report.contains(expected), "missing \(expected)")
        }
        for secret in ["secret-pass", "viewer", "panel.example.com", "http", "42.mkv"] {
            XCTAssertFalse(report.contains(secret), "\(secret) in the report")
        }
    }
}
