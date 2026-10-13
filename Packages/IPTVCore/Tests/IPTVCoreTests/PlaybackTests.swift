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
