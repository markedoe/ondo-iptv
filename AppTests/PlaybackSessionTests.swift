import XCTest
import IPTVCore
@testable import Ondo

/// Zapping, last channel and time shifting of a player session (no network: the URLs never resolve).
@MainActor
final class PlaybackSessionTests: XCTestCase {
    private let playlistID = UUID()
    private var clock = Date(timeIntervalSince1970: 1_760_000_000)

    private func liveItem(_ id: String) -> PlaybackItem {
        PlaybackItem(
            reference: MediaReference(playlistID: playlistID, type: .live, itemID: id),
            title: id.capitalized,
            streamURL: URL(string: "http://127.0.0.1:9/\(id).m3u8")!,
            isLive: true
        )
    }

    private func makeSession(archive: Bool = true, index: Int = 0) -> PlaybackSession {
        let session = PlaybackSession(queue: ["news", "sport", "music"].map(liveItem), index: index)
        session.now = { [unowned self] in self.clock }
        if archive {
            session.catchupItem = { item, start, _, title in
                var replay = item
                replay.isLive = false
                replay.isCatchup = true
                replay.title = title ?? item.title
                replay.streamURL = URL(string: "http://127.0.0.1:9/\(item.reference.itemID).m3u8?utc=\(Int(start.timeIntervalSince1970))")!
                return replay
            }
        }
        return session
    }

    func testLastChannelSwapsBackAndForth() {
        let session = makeSession()
        session.start()
        XCTAssertFalse(session.canReturnToPreviousChannel)

        session.select(index: 2)
        XCTAssertTrue(session.canReturnToPreviousChannel)
        session.returnToPreviousChannel()
        XCTAssertEqual(session.index, 0)
        session.returnToPreviousChannel()
        XCTAssertEqual(session.index, 2)

        session.zap(1)
        XCTAssertEqual(session.index, 0, "live zapping wraps around")
        session.returnToPreviousChannel()
        XCTAssertEqual(session.index, 2)
    }

    func testRestartingAProgrammeKeepsTheChannel() throws {
        let session = makeSession()
        session.start()
        let program = EPGProgram(channelID: "news", title: "Evening News", start: clock.addingTimeInterval(-900), end: clock.addingTimeInterval(900))

        XCTAssertTrue(session.canTimeshift(from: program.start))
        XCTAssertTrue(session.play(program))
        XCTAssertTrue(session.isTimeshifted)
        XCTAssertFalse(session.isAtLiveEdge)
        XCTAssertEqual(session.current?.reference.itemID, "news", "the queue still points at the live channel")
        XCTAssertEqual(session.playingItem?.title, "Evening News")
        XCTAssertEqual(session.controller.item?.isCatchup, true)
        XCTAssertEqual(session.timeshift?.programTitle, "Evening News")

        session.returnToLive()
        XCTAssertFalse(session.isTimeshifted)
        XCTAssertEqual(session.controller.item?.isLive, true)
        XCTAssertEqual(session.playingItem?.id, session.current?.id)
    }

    func testZappingLeavesTheArchive() {
        let session = makeSession()
        session.start()
        XCTAssertTrue(session.playFromArchive(start: clock.addingTimeInterval(-600), end: clock, title: nil))
        session.zap(1)
        XCTAssertFalse(session.isTimeshifted)
        XCTAssertEqual(session.current?.reference.itemID, "sport")
        XCTAssertEqual(session.controller.item?.isLive, true)
    }

    func testStartingDirectlyInAProgramme() {
        let session = makeSession(index: 1)
        let program = EPGProgram(channelID: "sport", title: "Final", start: clock.addingTimeInterval(-3600), end: clock.addingTimeInterval(-600))
        var started: [String] = []
        session.onItemStarted = { started.append($0.reference.itemID) }
        session.start(from: program)
        XCTAssertTrue(session.isTimeshifted)
        XCTAssertEqual(session.playingItem?.title, "Final")
        XCTAssertEqual(started, ["sport"], "history records the channel")
    }

    func testLongLivePauseResumesFromTheArchive() throws {
        let session = makeSession()
        session.start()
        let pausedAt = clock
        session.pause()
        XCTAssertEqual(session.livePausedAt, pausedAt)

        clock = clock.addingTimeInterval(120)
        session.play()
        XCTAssertTrue(session.isTimeshifted)
        XCTAssertEqual(session.timeshift?.start, pausedAt)
        XCTAssertNil(session.livePausedAt)
        let url = try XCTUnwrap(session.controller.item?.streamURL.absoluteString)
        XCTAssertTrue(url.hasSuffix("utc=\(Int(pausedAt.timeIntervalSince1970))"), url)
    }

    func testShortLivePauseStaysLive() {
        let session = makeSession()
        session.start()
        session.pause()
        clock = clock.addingTimeInterval(PlaybackSession.livePauseThreshold - 2)
        session.play()
        XCTAssertFalse(session.isTimeshifted)
        XCTAssertNil(session.livePausedAt)
    }

    func testChannelsWithoutArchiveCannotTimeshift() {
        let session = makeSession(archive: false)
        session.start()
        let program = EPGProgram(channelID: "news", title: "News", start: clock.addingTimeInterval(-600), end: clock.addingTimeInterval(600))
        XCTAssertFalse(session.canTimeshift(from: program.start))
        XCTAssertFalse(session.play(program))
        session.pause()
        XCTAssertNil(session.livePausedAt, "nothing to resume from: the pause is a plain pause")
        clock = clock.addingTimeInterval(300)
        session.play()
        XCTAssertFalse(session.isTimeshifted)
    }
}
