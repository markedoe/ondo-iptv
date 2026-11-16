import XCTest
import IPTVCore
@testable import Ondo

/// Regression guard for Continue Watching, on every surface that shows it: a title watched (as its poster's check mark
/// has it, `AppModel.watchedKeys`) never shows there. A film leaves once one of its versions is marked watched, another
/// version left halfway included; a series leaves once every episode known is watched, whatever order they were watched
/// or marked in, and with its seasons not known yet (the playlist's own list); a title in progress and not watched
/// stays. The Android apps have the same test (`ContinueWatchingLeavesWatchedEverySurfaceTest`). A new screen that shows
/// Continue Watching is added to `surfaces` here.
@MainActor
final class ContinueWatchingLeavesWatchedEverySurfaceTests: XCTestCase {
    private var root: URL!

    nonisolated private static let catalogue = """
    #EXTM3U
    #EXTINF:6000 group-title="Movies | Drama",Glass City (2024)
    http://127.0.0.1:9/glass.mp4
    #EXTINF:6000 group-title="Movies | 4K",Glass City 4K DV (2024)
    http://127.0.0.1:9/glass-dv.mkv
    #EXTINF:6000 group-title="Movies | Drama",Northern Lights (2025)
    http://127.0.0.1:9/lights.mp4
    #EXTINF:6000 group-title="Movies | Thriller",Silent Harbor (2023)
    http://127.0.0.1:9/silent.mp4
    #EXTINF:2700 group-title="Series | Drama",Harbor S01E01
    http://127.0.0.1:9/h101.mp4
    #EXTINF:2700 group-title="Series | Drama",Harbor S01E02
    http://127.0.0.1:9/h102.mp4
    #EXTINF:2700 group-title="Series | Drama",Harbor S01E03
    http://127.0.0.1:9/h103.mp4
    #EXTINF:2700 group-title="Series | Mystery",Horizon S01E01
    http://127.0.0.1:9/z101.mp4
    #EXTINF:2700 group-title="Series | Mystery",Horizon S01E02
    http://127.0.0.1:9/z102.mp4
    #EXTINF:2700 group-title="Series | Mystery",Horizon S01E03
    http://127.0.0.1:9/z103.mp4
    #EXTINF:2700 group-title="Series | Mystery",Archive S01E01
    http://127.0.0.1:9/a101.mp4
    #EXTINF:2700 group-title="Series | Mystery",Archive S01E02
    http://127.0.0.1:9/a102.mp4
    """

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoContinueWatched-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: root)
    }

    /// Every surface that shows Continue Watching, with the titles (item ids) of its cards: Home's Movies in Progress and
    /// Series in Progress rows, their See All, the cards Home prepares (`HomeView`), the Movies and Series tabs'
    /// Continue Watching row (`VODBrowser`); all read `continueWatching(_:)`.
    private func surfaces(of model: AppModel) -> [String: Set<String>] {
        func titles(_ entries: [HistoryEntry]) -> Set<String> { Set(entries.map(\.reference.itemID)) }
        let movies = titles(model.continueWatching(.movie))
        let series = titles(model.continueWatching(.series))
        return [
            "Home (HomeView: Movies in Progress, Series in Progress)": movies.union(series),
            "See All in progress (HomeView)": movies.union(series),
            "Movies tab (VODBrowser)": movies,
            "Series tab (VODBrowser)": series,
        ]
    }

    private func expected(_ surface: String, movies: Set<String>, series: Set<String>) -> Set<String> {
        if surface.hasPrefix("Movies") { return movies }
        if surface.hasPrefix("Series") { return series }
        return movies.union(series)
    }

    /// No Continue Watching shows a title watched; those in progress and not watched stay. Android:
    /// `noContinueWatchingShowsATitleWatched`.
    func testNoContinueWatchingShowsATitleWatched() async throws {
        let file = root.appendingPathComponent("list.m3u")
        try Data(Self.catalogue.utf8).write(to: file)
        let model = AppModel(storageRoot: root, secrets: InMemorySecretStore(), reminderScheduler: InMemoryReminderScheduler())
        await model.bootstrap()
        try await model.addPlaylist(Playlist(name: "Test", source: .remote(file), autoRefreshHours: 0))

        func movie(_ name: String) throws -> Movie { try XCTUnwrap(model.content.movies.first { $0.name == name }) }
        func series(_ name: String) throws -> Series { try XCTUnwrap(model.content.series.first { $0.name == name }) }
        let glassHD = try movie("Glass City")
        let glassDV = try movie("Glass City 4K DV")
        let northern = try movie("Northern Lights")
        let silent = try movie("Silent Harbor")
        let harbor = try series("Harbor")
        let horizon = try series("Horizon")
        let archive = try series("Archive")
        XCTAssertTrue(model.films.versions(of: glassHD).contains(glassDV), "one film, two versions")
        // The series pages were opened: their seasons are known.
        let harborDetails = try await model.details(for: harbor)
        let horizonDetails = try await model.details(for: horizon)
        let archiveDetails = try await model.details(for: archive)
        let harborEpisodes = harborDetails.seasons.flatMap(\.episodes)
        let horizonEpisodes = horizonDetails.seasons.flatMap(\.episodes)
        let archiveEpisodes = archiveDetails.seasons.flatMap(\.episodes)

        func watch(_ movie: Movie, to position: Double) throws {
            let item = try XCTUnwrap(model.downloadItem(for: movie))
            model.recordStart(item)
            model.recordProgress(item, position: position, duration: 6_000)
        }
        func watch(_ episode: Episode, in series: Series, to position: Double) throws {
            let item = try XCTUnwrap(model.downloadItem(for: episode, in: series))
            model.recordStart(item)
            model.recordProgress(item, position: position, duration: 2_700)
        }
        // Glass City's 4K DV version, Northern Lights and Silent Harbor halfway; Harbor halfway through its second
        // episode; Horizon's third episode watched, then its first, its second halfway; Archive's first episode
        // watched, its second halfway.
        try watch(glassDV, to: 600)
        try watch(northern, to: 600)
        try watch(silent, to: 600)
        try watch(harborEpisodes[0], in: harbor, to: 2_700)
        try watch(harborEpisodes[1], in: harbor, to: 600)
        try watch(horizonEpisodes[2], in: horizon, to: 2_700)
        try watch(horizonEpisodes[0], in: horizon, to: 2_700)
        try watch(horizonEpisodes[1], in: horizon, to: 600)
        try watch(archiveEpisodes[0], in: archive, to: 2_700)
        try watch(archiveEpisodes[1], in: archive, to: 600)

        for (surface, shown) in surfaces(of: model) {
            XCTAssertEqual(shown, expected(surface, movies: [glassDV.id, northern.id, silent.id], series: [harbor.id, horizon.id, archive.id]),
                           "\(surface): every title in progress")
        }

        // As the pages mark them: Glass City's page marks its HD version (the version it shows), Northern Lights is
        // marked, Harbor is marked whole, Horizon's second episode is marked (every episode watched, the latest not the
        // last one).
        model.setWatched(true, movie: glassHD, version: glassHD)
        model.setWatched(true, movie: northern)
        model.setWatched(true, series: harbor, details: harborDetails)
        model.setWatched(true, episodes: [horizonEpisodes[1]], in: horizon)
        XCTAssertTrue(model.watchedKeys.isSuperset(of: ["m-" + glassDV.id, "s-" + horizon.id]), "watched, as their check mark")

        for (surface, shown) in surfaces(of: model) {
            XCTAssertEqual(shown, expected(surface, movies: [silent.id], series: [archive.id]),
                           "\(surface): only the titles in progress and not watched")
        }

        // Their seasons not known yet (a launch): the playlist's own list of episodes says the series are watched.
        let seasons = model.seriesDetailsCache
        model.seriesDetailsCache = [:]
        model.watchedKeysCache = nil
        XCTAssertEqual(Set(model.continueWatching(.series).map(\.reference.itemID)), [archive.id], "without their seasons")
        model.seriesDetailsCache = seasons
        model.watchedKeysCache = nil

        // Marked unwatched again (every version, as its page does), the film's version left halfway comes back with its
        // resume point: marking it watched never erased it.
        model.setWatched(false, movie: glassHD)
        let back = try XCTUnwrap(model.continueWatching(.movie).first { $0.reference.itemID == glassDV.id }, "Glass City is back by its 4K DV version")
        XCTAssertEqual(back.position, 600, accuracy: 1)
    }
}
