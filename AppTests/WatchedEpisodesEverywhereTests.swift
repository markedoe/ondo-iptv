import XCTest
import IPTVCore
@testable import Ondo

/// An episode watched, whichever way it was started (the series page, Continue Watching, the next episode played at the
/// end of one, a series' Play from search or a row), shows on every surface that shows episodes: watched, or how far it
/// went, in the season list; Continue Watching, the series page's Play and the player's next episode go on with the
/// next one. A new surface or a new way in is added here.
@MainActor
final class WatchedEpisodesEverywhereTests: XCTestCase {
    private var root: URL!

    nonisolated private static let catalogue = """
    #EXTM3U
    #EXTINF:2700 group-title="Series | Drama",Harbor S01E01
    http://127.0.0.1:9/h101.mp4
    #EXTINF:2700 group-title="Series | Drama",Harbor S01E02
    http://127.0.0.1:9/h102.mp4
    #EXTINF:2700 group-title="Series | Drama",Harbor S01E03
    http://127.0.0.1:9/h103.mp4
    #EXTINF:2700 group-title="Series | Drama",Harbor S02E01
    http://127.0.0.1:9/h201.mp4
    #EXTINF:2700 group-title="Series | Drama",Harbor S02E02
    http://127.0.0.1:9/h202.mp4
    """

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoWatched-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: root)
    }

    private func model() async throws -> AppModel {
        let file = root.appendingPathComponent("list.m3u")
        try Data(Self.catalogue.utf8).write(to: file)
        let model = AppModel(storageRoot: root, secrets: InMemorySecretStore(), reminderScheduler: InMemoryReminderScheduler())
        await model.bootstrap()
        try await model.addPlaylist(Playlist(name: "Test", source: .remote(file), autoRefreshHours: 0))
        return model
    }

    /// The session playing `episode`, once it plays (some ways in fetch the seasons first).
    private func playing(_ episode: Episode, in model: AppModel, file: StaticString = #filePath, line: UInt = #line) async throws -> PlaybackSession {
        for _ in 0..<200 {
            if let session = model.playbackRequest, session.current?.reference.episodeID == episode.id { return session }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("\(episode.code) does not play (\(model.playbackRequest?.current?.reference.episodeID ?? "nothing"))", file: file, line: line)
        throw CancellationError()
    }

    func testAnEpisodeWatchedAnyWayInShowsOnEverySurface() async throws {
        let model = try await model()
        let series = try XCTUnwrap(model.content.series.first)
        // The series page was opened: its seasons are known.
        let details = try await model.details(for: series)
        let episodes = details.seasons.flatMap(\.episodes)
        XCTAssertEqual(episodes.map(\.code), ["S01E01", "S01E02", "S01E03", "S02E01", "S02E02"])

        /// What the player saves at the end of an episode (or when it stops in its end credits).
        func finish(_ session: PlaybackSession, at fraction: Double = 1) async throws {
            let item = try XCTUnwrap(session.current)
            try await Task.sleep(for: .milliseconds(20))
            model.recordProgress(item, position: 2_700 * fraction, duration: 2_700)
        }

        // Every surface that shows how far an episode went: watched, and the share seen.
        let surfaces: [String: (Episode) -> (watched: Bool, seen: Double)] = [
            "series page's season list (EpisodeRow)": { episode in
                let progress = model.progress(episode: episode, in: series)
                return (progress?.isFinished == true, progress?.progress ?? 0)
            },
        ]
        // Every surface that goes on with the next episode: its id, nil when there is none.
        func nextOn(_ session: PlaybackSession) -> [String: String?] {
            let card = model.continueWatching(.series)
            return [
                "Continue Watching (Home and the Series tab)": card.count == 1 ? card[0].reference.episodeID : nil,
                "series page's Play": model.nextEpisode(in: series, details: details)?.episode.id,
            ]
        }

        func check(_ way: String, _ session: PlaybackSession, watched: Set<String>, next: Episode?, halfway: Episode? = nil,
                   file: StaticString = #filePath, line: UInt = #line) {
            for (surface, shown) in surfaces {
                for episode in episodes {
                    let state = shown(episode)
                    XCTAssertEqual(state.watched, watched.contains(episode.id), "\(way) → \(surface): \(episode.code) watched", file: file, line: line)
                    if episode == halfway {
                        XCTAssertEqual(state.seen, 1.0 / 3, accuracy: 0.01, "\(way) → \(surface): \(episode.code) a third seen", file: file, line: line)
                    }
                }
            }
            for (surface, id) in nextOn(session) {
                XCTAssertEqual(id, next?.id, "\(way) → \(surface)", file: file, line: line)
            }
            // The player's next episode follows the one playing, in its season.
            let queued = session.hasNext ? session.queue[session.index + 1].reference.episodeID : nil
            let season = details.seasons.first { $0.episodes.contains { $0.id == session.current?.reference.episodeID } }
            let after = season.flatMap { season in
                season.episodes.firstIndex { $0.id == session.current?.reference.episodeID }.flatMap { season.episodes.dropFirst($0 + 1).first?.id }
            }
            XCTAssertEqual(queued, after, "\(way) → the player's next episode", file: file, line: line)
        }

        // The series page: an episode chosen in the season list.
        model.play(episodes[0], in: series, season: details.seasons[0].episodes)
        let first = try await playing(episodes[0], in: model)
        try await finish(first)
        check("series page", first, watched: [episodes[0].id], next: episodes[1])
        model.endSession(first)

        // Continue Watching: its card is the next episode, from its start; stopped a third of the way, then watched to the end.
        let card = try XCTUnwrap(model.continueWatching(.series).first)
        XCTAssertTrue(model.isUpNext(card))
        model.resume(card)
        let resumed = try await playing(episodes[1], in: model)
        XCTAssertNil(resumed.current?.startPosition, "from its start")
        try await finish(resumed, at: 1.0 / 3)
        XCTAssertFalse(model.isUpNext(try XCTUnwrap(model.continueWatching(.series).first)))
        check("Continue Watching, stopped", resumed, watched: [episodes[0].id], next: episodes[1], halfway: episodes[1])
        try await finish(resumed)
        check("Continue Watching", resumed, watched: Set(episodes.prefix(2).map(\.id)), next: episodes[2])

        // The end of an episode: the player goes on with the next one.
        let ended = try XCTUnwrap(resumed.current)
        resumed.controller.onEnded?(ended)
        let upNext = try await playing(episodes[2], in: model)
        try await finish(upNext)
        check("next episode at the end", upNext, watched: Set(episodes.prefix(3).map(\.id)), next: episodes[3])
        model.endSession(upNext)

        // Its seasons not known (a launch, before they are fetched): the finished episode stands for the series, and its
        // card plays the next one.
        let kept = model.seriesDetailsCache
        model.seriesDetailsCache = [:]
        let unknown = try XCTUnwrap(model.continueWatching(.series).first)
        XCTAssertEqual(unknown.reference.episodeID, episodes[2].id)
        XCTAssertTrue(model.isUpNext(unknown))
        model.seriesDetailsCache = kept
        model.resume(unknown)
        let fromCard = try await playing(episodes[3], in: model)
        model.endSession(fromCard)

        // A series' Play (a search result, a row, My List, the carousel): its next episode, the next season's first.
        await model.playShowcase(.series(series))
        let fromSearch = try await playing(episodes[3], in: model)
        try await finish(fromSearch)
        check("a series' Play", fromSearch, watched: Set(episodes.prefix(4).map(\.id)), next: episodes[4])
        model.endSession(fromSearch)

        // The last episode: every one watched, and the series leaves Continue Watching.
        model.play(episodes[4], in: series, season: details.seasons[1].episodes)
        let last = try await playing(episodes[4], in: model)
        try await finish(last)
        for (surface, shown) in surfaces {
            for episode in episodes { XCTAssertTrue(shown(episode).watched, "last episode → \(surface): \(episode.code)") }
        }
        XCTAssertEqual(model.continueWatching(.series), [])
        model.endSession(last)
    }
}
