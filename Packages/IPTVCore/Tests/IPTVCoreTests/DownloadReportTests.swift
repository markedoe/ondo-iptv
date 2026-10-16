import XCTest
@testable import IPTVCore

/// A download's speed, time left and details: a 13 GB 4K film from a provider sending about 8 Mb/s (1 MB/s).
final class DownloadReportTests: XCTestCase {
    private let total: Int64 = 13_000_000_000

    private func meter(seconds: Int, bytesPerSecond: Int64, from start: Int64 = 0) -> TransferMeter {
        var meter = TransferMeter()
        for second in 0...seconds {
            meter.note(bytes: start + Int64(second) * bytesPerSecond, at: Double(second))
        }
        return meter
    }

    func testSpeedAndTimeLeft() throws {
        let meter = meter(seconds: 60, bytesPerSecond: 1_000_000)
        XCTAssertEqual(try XCTUnwrap(meter.rate()), 1_000_000, accuracy: 1)
        XCTAssertEqual(try XCTUnwrap(meter.averageRate), 1_000_000, accuracy: 1)
        // 12.94 GB left at 1 MB/s: 3 h 35 min, as the viewer saw it stay near the start.
        XCTAssertEqual(try XCTUnwrap(meter.remaining(total: total)), 12_940, accuracy: 1)
        XCTAssertEqual(DownloadReport.duration(12_940), "3 h 35 min")
        XCTAssertEqual(meter.lastArrival, 60)
    }

    /// Nothing comes any more: the recent speed falls to 0 and no time left is promised.
    func testAStoppedDownloadHasNoTimeLeft() throws {
        var meter = meter(seconds: 30, bytesPerSecond: 1_000_000)
        for second in 31...45 { meter.note(bytes: 30_000_000, at: Double(second)) }
        XCTAssertEqual(try XCTUnwrap(meter.rate()), 0)
        XCTAssertNil(meter.remaining(total: total))
        XCTAssertEqual(meter.lastArrival, 30)
    }

    func testTheFirstSecondsAreNotMeasuredAndCountsNeverGoBack() {
        var meter = TransferMeter()
        meter.note(bytes: 0, at: 0)
        meter.note(bytes: 500_000, at: 1)
        XCTAssertNil(meter.rate())
        XCTAssertNil(meter.remaining(total: total))
        meter.note(bytes: 100, at: 2)
        XCTAssertEqual(meter.points.last?.bytes, 500_000)
    }

    func testOnlyTheLastTwoMinutesAreKept() {
        let meter = meter(seconds: 600, bytesPerSecond: 1_000)
        XCTAssertEqual(meter.points.first?.time, 480)
        XCTAssertEqual(meter.first?.time, 0, "the average still starts at the first measure")
    }

    func testTheReportSaysWhatTheProviderAnsweredAndHoldsNothingPrivate() {
        var log = PlaybackLog()
        log.add("start", at: 0)
        // Fictitious login on example.com, the very thing a report must never carry.
        log.add("answer: HTTP 200 from http://user:pass@panel.example.com:8080/movie/u/p/1.mkv", at: 0.6)  // leak-guard: allow
        let context = DownloadReport.Context(
            app: "Ondo 1.0 (13)", system: "macOS 27", kind: "film", container: "mkv", status: "downloading", task: "running",
            received: 60_000_000, total: total, httpStatus: 200,
            headers: ["Content-Type": "video/x-matroska", "Content-Length": "13000000000", "Accept-Ranges": "bytes",
                      "Content-Disposition": "attachment; filename=\"A Film (2024).mkv\""],
            problem: nil, now: 60.5
        )
        let text = DownloadReport(context: context, meter: meter(seconds: 60, bytesPerSecond: 1_000_000), log: log).text
        XCTAssertTrue(text.contains("Received: 60.0 MB of 13.00 GB (0.5 %)"), text)
        XCTAssertTrue(text.contains("Speed: 8.00 Mb/s over 10 s; 8.00 Mb/s on average"), text)
        XCTAssertTrue(text.contains("Last data: 0.5 s ago"), text)
        XCTAssertTrue(text.contains("Time left: 3 h 35 min at this speed"), text)
        XCTAssertTrue(text.contains("Answer: HTTP 200; Content-Type: video/x-matroska; Content-Length: 13000000000; Accept-Ranges: bytes"), text)
        XCTAssertFalse(text.contains("A Film"), "no header that could carry the title")
        XCTAssertFalse(text.contains("example.com"))
        XCTAssertFalse(text.contains("pass"))
        XCTAssertTrue(text.contains("  50.0 50 8.00"), text)
    }

    func testAWaitingDownloadSaysWhatItWaitsFor() {
        let context = DownloadReport.Context(
            app: "Ondo", system: "iOS", kind: "episode", container: nil, status: "downloading", task: "running", received: 0,
            total: 0, httpStatus: nil, headers: [:], problem: .noResponse, now: 31
        )
        let text = DownloadReport(context: context, meter: TransferMeter(), log: PlaybackLog()).text
        XCTAssertTrue(text.contains("Received: 0.0 MB of an unknown size"), text)
        XCTAssertTrue(text.contains("Speed: not measured yet"), text)
        XCTAssertTrue(text.contains("Answer: none yet"), text)
        XCTAssertTrue(text.contains("Waiting: no answer from the provider yet"), text)
    }
}
