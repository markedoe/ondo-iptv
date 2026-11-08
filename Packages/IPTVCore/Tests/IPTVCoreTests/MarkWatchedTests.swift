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
}
