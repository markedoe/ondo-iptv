import XCTest
import IPTVCore
@testable import Ondo

/// Tiles, sound focus and limits of the multiview (players never reach the network).
@MainActor
final class MultiviewTests: XCTestCase {
    private let playlistID = UUID()

    private func item(_ id: String) -> PlaybackItem {
        PlaybackItem(
            reference: MediaReference(playlistID: playlistID, type: .live, itemID: id),
            title: id,
            streamURL: URL(string: "http://127.0.0.1:9/\(id).m3u8")!,
            isLive: true
        )
    }

    func testOnlyOneTileHasSound() {
        let multiview = Multiview()
        XCTAssertTrue(multiview.add(item("a")) { _ in })
        XCTAssertTrue(multiview.add(item("b")) { _ in })
        XCTAssertTrue(multiview.add(item("c")) { _ in })
        XCTAssertEqual(multiview.tiles.map { $0.controller.isMuted }, [false, true, true])

        multiview.focusAudio(at: 2)
        XCTAssertEqual(multiview.audioIndex, 2)
        XCTAssertEqual(multiview.tiles.map { $0.controller.isMuted }, [true, true, false])

        multiview.remove(at: 0)
        XCTAssertEqual(multiview.tiles.map { $0.current?.title }, ["b", "c"])
        XCTAssertEqual(multiview.audioIndex, 1, "the sound stays on the same channel")
        XCTAssertEqual(multiview.tiles.map { $0.controller.isMuted }, [true, false])

        multiview.remove(at: 1)
        XCTAssertEqual(multiview.audioIndex, 0)
        XCTAssertEqual(multiview.tiles.map { $0.controller.isMuted }, [false], "the remaining tile gets the sound")
    }

    func testLimitsAndDuplicates() {
        let multiview = Multiview()
        var configured = 0
        for id in ["a", "b", "c", "d"] {
            XCTAssertTrue(multiview.add(item(id)) { _ in configured += 1 })
        }
        XCTAssertTrue(multiview.isFull)
        XCTAssertFalse(multiview.add(item("e")) { _ in configured += 1 })
        XCTAssertEqual(configured, 4)

        multiview.remove(at: 3)
        XCTAssertFalse(multiview.add(item("a")) { _ in }, "a channel is shown once")

        multiview.replace(at: 0, with: item("z"))
        XCTAssertEqual(multiview.tiles.first?.current?.title, "z")

        multiview.stopAll()
        XCTAssertTrue(multiview.tiles.isEmpty)
    }
}
