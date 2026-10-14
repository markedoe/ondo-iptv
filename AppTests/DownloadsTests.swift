import XCTest
import IPTVCore
@testable import Ondo

@MainActor
final class DownloadsTests: XCTestCase {
    private func record(total: Int64, received: Int64?, progress: Double = 0) -> DownloadRecord {
        let url = URL(string: "https://example.com/movie/1.mkv")!
        let item = PlaybackItem(
            reference: MediaReference(playlistID: UUID(), type: .movie, itemID: "1"),
            title: "Quiet Water",
            streamURL: url,
            isLive: false,
            startPosition: nil
        )
        return DownloadRecord(
            id: item.id, item: item, remoteURL: url, fileName: "a.mkv", status: .downloading,
            progress: progress, totalBytes: total, createdAt: Date(), receivedBytes: received
        )
    }

    /// Providers that do not give the size left the download at "Starting…" while it ran.
    func testADownloadWithoutSizeShowsWhatArrived() {
        XCTAssertEqual(DownloadsView.progressText(record(total: 0, received: nil)), String(localized: "Starting…"))
        XCTAssertTrue(DownloadsView.progressText(record(total: 0, received: 250_000_000)).contains(Formatting.bytes(250_000_000)))
        let known = DownloadsView.progressText(record(total: 1_000_000_000, received: 500_000_000, progress: 0.5))
        XCTAssertTrue(known.contains(Formatting.bytes(1_000_000_000)))
    }

    /// Records saved before the received size existed still load.
    func testRecordsSavedBeforeStillLoad() throws {
        var saved = try JSONSerialization.jsonObject(with: JSONEncoder().encode(record(total: 0, received: 12))) as? [String: Any]
        saved?["receivedBytes"] = nil
        let data = try JSONSerialization.data(withJSONObject: try XCTUnwrap(saved))
        XCTAssertNil(try JSONDecoder().decode(DownloadRecord.self, from: data).receivedBytes)
    }

    func testEveryProblemHasItsOwnExplanation() {
        let problems: [DownloadProblem] = [
            .refused(status: 403), .notFound(status: 404), .tooManyConnections(status: 509), .httpError(status: 500),
            .errorPage, .noResponse, .noData, .downloadStalled, .offline, .unreachable, .timedOut, .connectionLost,
        ]
        let messages = problems.map { DownloadManager.message(for: $0) }
        XCTAssertEqual(Set(messages).count, problems.count)
        XCTAssertTrue(DownloadManager.message(for: .refused(status: 403)).contains("403"))
    }
}
