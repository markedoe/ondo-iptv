import XCTest
@testable import IPTVCore

/// "Mark as Watched" / "Mark as Unwatched" on a page: the same state as the end of playback, and back. Android has the
/// same cases (`LibraryStateTest`).
final class MarkWatchedTests: XCTestCase {
    let playlistID = UUID()
    let t0 = Date(timeIntervalSince1970: 1_000_000)

    private func ref(_ type: ContentType, _ id: String, episode: String? = nil) -> MediaReference {
        MediaReference(playlistID: playlistID, type: type, itemID: id, episodeID: episode)
    }

    func testMarkingAMovieNeverPlayedWatchedThenUnwatched() {
        var library = UserLibrary()
        library.markWatched(HistoryEntry(reference: ref(.movie, "m"), title: "M"), now: t0)
        XCTAssertTrue(library.isWatched(ref(.movie, "m")))
        XCTAssertTrue(library.continueWatching(playlistID: playlistID).isEmpty)
        XCTAssertEqual(library.history.first?.lastWatchedAt, t0)

        library.markUnwatched(ref(.movie, "m"), now: t0.addingTimeInterval(1))
        XCTAssertFalse(library.isWatched(ref(.movie, "m")))
        XCTAssertEqual(library.progress(for: ref(.movie, "m"))?.position, 0, "played from its start next time")
        XCTAssertTrue(library.continueWatching(playlistID: playlistID).isEmpty)
        XCTAssertTrue(library.historyTombstones.isEmpty, "no deletion: a series keeps its card")
    }

    func testMarkingAMovieInProgressWatchedTakesItOutOfContinueWatching() {
        var library = UserLibrary()
        library.recordPlayback(HistoryEntry(reference: ref(.movie, "m"), title: "M", lastWatchedAt: t0))
        library.updateProgress(ref(.movie, "m"), position: 1_200, duration: 6_000, now: t0)
        XCTAssertEqual(library.continueWatching(playlistID: playlistID).count, 1)

        library.markWatched(HistoryEntry(reference: ref(.movie, "m"), title: "M"), now: t0.addingTimeInterval(5))
        XCTAssertTrue(library.isWatched(ref(.movie, "m")))
        XCTAssertEqual(library.progress(for: ref(.movie, "m"))?.duration, 6_000, "its known length is kept")
        XCTAssertTrue(library.continueWatching(playlistID: playlistID).isEmpty)

        // Played again: from its start, as after watching it to the end.
        library.recordPlayback(HistoryEntry(reference: ref(.movie, "m"), title: "M", lastWatchedAt: t0.addingTimeInterval(10)))
        XCTAssertEqual(library.progress(for: ref(.movie, "m"))?.position, 0)
    }

    func testAWatchedEpisodeCanBeMarkedUnwatched() {
        var library = UserLibrary()
        let episode = ref(.series, "s", episode: "e1")
        library.recordPlayback(HistoryEntry(reference: episode, title: "S", lastWatchedAt: t0))
        library.updateProgress(episode, position: 2_700, duration: 2_700, now: t0)
        XCTAssertTrue(library.progress(for: episode)?.staysWatched == true)

        // The guard that keeps a watched episode watched during playback does not stop the viewer's own choice.
        library.markUnwatched(episode, now: t0.addingTimeInterval(1))
        XCTAssertFalse(library.isWatched(episode))
        library.markWatched(HistoryEntry(reference: episode, title: "S"), now: t0.addingTimeInterval(2))
        XCTAssertTrue(library.isWatched(episode))
        XCTAssertEqual(library.history.count, 1)
    }

    func testASeriesIsWatchedOnceEveryEpisodeIs() {
        var library = UserLibrary()
        library.markWatched(HistoryEntry(reference: ref(.series, "s", episode: "e1"), title: "S"), now: t0)
        var watched = WatchedTitles(history: library.history, playlistID: playlistID)
        XCTAssertFalse(watched.isSeriesWatched("s", episodeIDs: ["e1", "e2"]))
        library.markWatched(HistoryEntry(reference: ref(.series, "s", episode: "e2"), title: "S"), now: t0.addingTimeInterval(1))
        watched = WatchedTitles(history: library.history, playlistID: playlistID)
        XCTAssertTrue(watched.isSeriesWatched("s", episodeIDs: ["e1", "e2"]))
        XCTAssertFalse(watched.isSeriesWatched("s", episodeIDs: []), "no episode known: not watched")
        XCTAssertFalse(watched.isSeriesWatched("other", episodeIDs: ["e1"]))
        // Another playlist's history counts for nothing here.
        XCTAssertTrue(WatchedTitles(history: library.history, playlistID: UUID()).episodes.isEmpty)
        // A movie half seen is not watched.
        library.recordPlayback(HistoryEntry(reference: ref(.movie, "m"), title: "M", lastWatchedAt: t0, position: 100, duration: 6_000))
        XCTAssertFalse(WatchedTitles(history: library.history, playlistID: playlistID).movies.contains("m"))
    }

    /// Sync and merges go by the latest change: marking unwatched on one device wins over the older watched state, and
    /// the other way round.
    func testTheLaterMarkWinsAMerge() {
        var phone = UserLibrary()
        phone.markWatched(HistoryEntry(reference: ref(.movie, "m"), title: "M"), now: t0)
        var tablet = phone
        tablet.markUnwatched(ref(.movie, "m"), now: t0.addingTimeInterval(10))
        XCTAssertFalse(phone.merged(with: tablet).isWatched(ref(.movie, "m")))
        XCTAssertFalse(tablet.merged(with: phone).isWatched(ref(.movie, "m")))
        phone.markWatched(HistoryEntry(reference: ref(.movie, "m"), title: "M"), now: t0.addingTimeInterval(20))
        XCTAssertTrue(phone.merged(with: tablet).isWatched(ref(.movie, "m")))
        XCTAssertTrue(tablet.merged(with: phone).isWatched(ref(.movie, "m")))
    }

    private func episodes(_ ids: [String]) -> [HistoryEntry] {
        ids.map { HistoryEntry(reference: ref(.series, "s", episode: $0), title: "S") }
    }

    private func stamps(_ count: Int, from start: Double) -> [Date] {
        (0..<count).map { t0.addingTimeInterval(start + Double($0)) }
    }

    /// The series' most recent entry decides where Continue Watching and Play go on.
    private func latest(_ library: UserLibrary) -> HistoryEntry? {
        library.history.filter { $0.reference.itemID == "s" }.max { $0.lastWatchedAt < $1.lastWatchedAt }
    }

    private let order = ["1-1", "1-2", "1-3", "2-1", "2-2", "3-1", "3-2", "3-3", "3-4", "3-5"]

    /// A whole series unmarked: the first episode is the newest, so Play starts again at the beginning, not at the last one.
    func testUnmarkingASeriesStartsItAgainAtItsFirstEpisode() {
        var library = UserLibrary()
        library.markEpisodes(episodes(order), watched: true, order: order, stamps: stamps(order.count, from: 0))
        XCTAssertEqual(latest(library)?.reference.episodeID, "3-5", "watched: the series goes on after its last episode")
        library.markEpisodes(episodes(order), watched: false, order: order, stamps: stamps(order.count, from: 100))
        XCTAssertEqual(latest(library)?.reference.episodeID, "1-1")
        XCTAssertFalse(library.history.contains { $0.isFinished })
    }

    /// Halfway through S03E05, season 1 marked watched (or unwatched): the series stays at S03E05.
    func testMarkingAnEarlierSeasonDoesNotMoveTheSeriesBack() {
        var library = UserLibrary()
        let playing = ref(.series, "s", episode: "3-5")
        library.recordPlayback(HistoryEntry(reference: playing, title: "S", lastWatchedAt: t0.addingTimeInterval(50)))
        library.updateProgress(playing, position: 900, duration: 2_700, now: t0.addingTimeInterval(50))
        let season = ["1-1", "1-2", "1-3"]
        library.markEpisodes(episodes(season), watched: true, order: order, stamps: stamps(3, from: 100))
        XCTAssertEqual(latest(library)?.reference.episodeID, "3-5")
        XCTAssertTrue(season.allSatisfy { library.isWatched(ref(.series, "s", episode: $0)) })
        let times = season.compactMap { id in library.progress(for: ref(.series, "s", episode: id))?.lastWatchedAt }
        XCTAssertEqual(times, times.sorted(), "in their order, just before the episode being watched")
        XCTAssertTrue(times.allSatisfy { $0 < t0.addingTimeInterval(50) })
        library.markEpisodes(episodes(season), watched: false, order: order, stamps: stamps(3, from: 200))
        XCTAssertEqual(latest(library)?.reference.episodeID, "3-5")
        XCTAssertFalse(season.contains { library.isWatched(ref(.series, "s", episode: $0)) })
        // A later season marked: that is where the series goes on.
        library.markEpisodes(episodes(["3-5"]), watched: true, order: order, stamps: stamps(1, from: 300))
        XCTAssertEqual(latest(library)?.reference.episodeID, "3-5")
        XCTAssertTrue(library.isWatched(playing))
    }

    func testABulkMarkHasALimit() {
        XCTAssertEqual(UserLibrary.bulkMarkLimit, 200)
        XCTAssertLessThan(UserLibrary.bulkMarkLimit, UserLibrary.maxHistory)
    }
}
