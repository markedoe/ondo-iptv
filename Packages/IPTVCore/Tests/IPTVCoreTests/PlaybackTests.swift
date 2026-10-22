import XCTest
@testable import IPTVCore

final class PlaybackTests: XCTestCase {
    func testNumberingUsesExplicitNumbersWhenPresent() {
        let numbers: [Int?] = [101, nil, 7, 250]
        XCTAssertEqual(ChannelNumbering.position(of: 7, numbers: numbers), 2)
        XCTAssertEqual(ChannelNumbering.position(of: 250, numbers: numbers), 3)
        XCTAssertNil(ChannelNumbering.position(of: 2, numbers: numbers), "positions are not used when the list has numbers")
        XCTAssertEqual(ChannelNumbering.displayNumber(at: 0, numbers: numbers), 101)
        XCTAssertNil(ChannelNumbering.displayNumber(at: 1, numbers: numbers))
    }

    func testNumberingFallsBackToPositions() {
        let numbers: [Int?] = [nil, nil, nil]
        XCTAssertEqual(ChannelNumbering.position(of: 1, numbers: numbers), 0)
        XCTAssertEqual(ChannelNumbering.position(of: 3, numbers: numbers), 2)
        XCTAssertNil(ChannelNumbering.position(of: 4, numbers: numbers))
        XCTAssertNil(ChannelNumbering.position(of: 0, numbers: numbers))
        XCTAssertEqual(ChannelNumbering.displayNumber(at: 2, numbers: numbers), 3)
        XCTAssertNil(ChannelNumbering.position(of: 1, numbers: []))
    }

    func testAWholeListIsNumberedLikeEachChannel() {
        for numbers: [Int?] in [[101, nil, 7, 250], [nil, nil, nil], []] {
            let one = numbers.indices.map { ChannelNumbering.displayNumber(at: $0, numbers: numbers) }
            XCTAssertEqual(ChannelNumbering.displayNumbers(numbers), one)
        }
    }

    private let playlist = Playlist(name: "P", source: .remote(URL(string: "http://example.com/list.m3u")!))

    private func channel(catchup: CatchupInfo?) -> Channel {
        Channel(id: "c1", name: "News", categoryID: "x", streamURL: URL(string: "http://example.com/news.m3u8")!, catchup: catchup)
    }

    func testRestartItemFromAnInstant() throws {
        let now = Date.utc(2025, 10, 9, 20, 10)
        let start = Date.utc(2025, 10, 9, 20, 0)
        let factory = PlaybackFactory(playlist: playlist)
        let item = try XCTUnwrap(factory.catchupItem(
            for: channel(catchup: CatchupInfo(mode: .shift, days: 2)),
            start: start,
            end: Date.utc(2025, 10, 9, 21, 0),
            title: "Evening News",
            now: now
        ))
        XCTAssertTrue(item.isCatchup)
        XCTAssertFalse(item.isLive)
        XCTAssertEqual(item.title, "Evening News")
        XCTAssertEqual(item.reference.type, .live)
        XCTAssertEqual(item.reference.itemID, "c1")
        XCTAssertEqual(item.streamURL.absoluteString, "http://example.com/news.m3u8?utc=1760040000&lutc=1760040600")
    }

    func testNoRestartWithoutArchive() {
        let now = Date.utc(2025, 10, 9, 20, 10)
        let factory = PlaybackFactory(playlist: playlist)
        XCTAssertNil(factory.catchupItem(for: channel(catchup: nil), start: now.addingTimeInterval(-600), end: now, now: now))
        let archived = channel(catchup: CatchupInfo(mode: .shift, days: 1))
        XCTAssertNil(factory.catchupItem(for: archived, start: now.addingTimeInterval(-3 * 86_400), end: now, now: now), "older than the archive")
        XCTAssertNil(factory.catchupItem(for: archived, start: now.addingTimeInterval(60), end: now.addingTimeInterval(600), now: now), "future")
    }
}

/// The format shown on pages and in the player's statistics.
final class MediaFormatTests: XCTestCase {
    private func url(_ text: String) throws -> URL { try XCTUnwrap(URL(string: text)) }

    func testTheAddressNamesTheFormat() throws {
        XCTAssertEqual(MediaFormat.label(for: try url("http://panel.example.com/movie/u/p/5001.mkv")), "MKV")
        XCTAssertEqual(MediaFormat.label(for: try url("http://panel.example.com/movie/u/p/5001.MP4?token=1")), "MP4")
        XCTAssertEqual(MediaFormat.label(for: try url("http://panel.example.com/live/u/p/101.m3u8")), "HLS")
        XCTAssertEqual(MediaFormat.label(for: try url("http://panel.example.com/live/u/p/101.ts")), "TS")
        XCTAssertNil(MediaFormat.label(for: try url("http://panel.example.com/play.php?id=5001")), "not a video format")
        XCTAssertNil(MediaFormat.label(for: try url("http://panel.example.com/movie/5001")), "no extension")
    }

    func testASeriesShowsItsFormatOnlyWhenEveryEpisodeSharesIt() throws {
        let mkv = [try url("http://panel.example.com/series/u/p/1.mkv"), try url("http://panel.example.com/series/u/p/2.mkv")]
        XCTAssertEqual(MediaFormat.label(for: mkv), "MKV")
        XCTAssertNil(MediaFormat.label(for: mkv + [try url("http://panel.example.com/series/u/p/3.mp4")]), "mixed formats")
        XCTAssertNil(MediaFormat.label(for: []))
    }
}
