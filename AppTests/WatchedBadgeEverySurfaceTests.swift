import XCTest
import IPTVCore
@testable import Ondo

/// A watched movie (any version of its film) and a series whose episodes are all watched show the watched badge on
/// every thumbnail, from one set (`AppModel.watchedKeys`), and the pages toggle it. Android has the same test
/// (`WatchedBadgeEverySurfaceTest`). A new screen that shows thumbnails is added to `surfaces` here.
@MainActor
final class WatchedBadgeEverySurfaceTests: XCTestCase {
    private var root: URL!

    nonisolated private static let catalogue = """
    #EXTM3U
    #EXTINF:6000 group-title="Movies | Drama",Glass City (2024)
    http://127.0.0.1:9/glass.mp4
    #EXTINF:6000 group-title="Movies | 4K",Glass City 4K (2024)
    http://127.0.0.1:9/glass-4k.mkv
    #EXTINF:6000 group-title="Movies | Drama",Quiet Water (2023)
    http://127.0.0.1:9/quiet.mp4
    #EXTINF:2700 group-title="Series | Drama",Harbor S01E01
    http://127.0.0.1:9/h101.mp4
    #EXTINF:2700 group-title="Series | Drama",Harbor S01E02
    http://127.0.0.1:9/h102.mp4
    #EXTINF:2700 group-title="Series | Mystery",Archive S01E01
    http://127.0.0.1:9/a101.mp4
    #EXTINF:2700 group-title="Series | Mystery",Archive S01E02
    http://127.0.0.1:9/a102.mp4
    #EXTINF:2700 group-title="Series | Mystery",Archive S02E01
    http://127.0.0.1:9/a201.mp4
    #EXTINF:2700 group-title="Series | Mystery",Archive S02E02
    http://127.0.0.1:9/a202.mp4
    """

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoWatchedBadge-\(UUID().uuidString)")
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

    func testMarkingWatchedOnAPageAndBack() async throws {
        let model = try await model()
        let glass = model.content.movies.filter { $0.name.hasPrefix("Glass City") }
        XCTAssertEqual(glass.count, 2)
        let quiet = try XCTUnwrap(model.content.movies.first { $0.name == "Quiet Water" })
        let series = try XCTUnwrap(model.content.series.first { $0.name == "Harbor" })
        let details = try await model.details(for: series)
        XCTAssertEqual(details.seasons.flatMap(\.episodes).count, 2)

        // A film of two versions: one marked, the film is watched, every version's thumbnail shows it.
        model.setWatched(true, movie: glass[0], version: glass[1])
        for version in glass {
            XCTAssertTrue(model.isWatched(.movie(version)), version.id)
            XCTAssertTrue(model.isWatchedFilm(version))
        }
        XCTAssertFalse(model.isWatched(.movie(quiet)))
        XCTAssertTrue(model.continueWatching(.movie).isEmpty)

        // A series: watched once every episode is.
        let episodes = details.seasons.flatMap(\.episodes)
        model.setWatched(true, episodes: [episodes[0]], in: series)
        XCTAssertFalse(model.isWatched(.series(series)))
        XCTAssertFalse(model.isWatched(series: series, details: details))
        // One episode watched: Continue Watching goes on with the next one, as after playback.
        XCTAssertEqual(model.continueWatching(.series).first?.reference.episodeID, episodes[1].id)
        model.setWatched(true, series: series, details: details)
        XCTAssertTrue(model.isWatched(.series(series)))
        XCTAssertTrue(model.isWatched(series: series, details: details))
        XCTAssertTrue(model.continueWatching(.series).isEmpty, "the last episode watched: the series leaves Continue Watching")

        // Unwatched again: every version, every episode, played from the start.
        model.setWatched(false, movie: glass[0])
        for version in glass { XCTAssertFalse(model.isWatched(.movie(version)), version.id) }
        XCTAssertNil(model.progress(movie: glass[1]).flatMap { $0.isResumable ? $0 : nil })
        model.setWatched(false, episodes: [episodes[1]], in: series)
        XCTAssertFalse(model.isWatched(.series(series)))
        XCTAssertTrue(model.isWatched(episode: episodes[0], in: series))
        XCTAssertFalse(model.isWatched(episode: episodes[1], in: series))
    }

    /// A film whose watched version is in a hidden category: its other version's page unmarks it all the same.
    func testUnmarkingReachesAVersionInAHiddenCategory() async throws {
        let model = try await model()
        let glass = model.content.movies.filter { $0.name.hasPrefix("Glass City") }
        let uhd = try XCTUnwrap(glass.first { $0.streamURL.pathExtension == "mkv" })
        let hd = try XCTUnwrap(glass.first { $0.streamURL.pathExtension == "mp4" })
        model.setWatched(true, movie: uhd, version: uhd)
        model.setHidden(true, category: try XCTUnwrap(model.content.movieCategories.first { $0.name.contains("4K") }))
        XCTAssertTrue(model.isWatched(.movie(hd)))
        XCTAssertTrue(model.isWatchedFilm(hd))
        model.setWatched(false, movie: hd)
        XCTAssertFalse(model.isWatched(.movie(hd)))
        XCTAssertFalse(model.isWatched(.movie(uhd)))
    }

    /// Halfway through a later episode, an earlier season marked: Continue Watching and Play stay on that episode. A whole
    /// series unmarked: Play starts again at its first episode. Never more than the limit at once.
    func testMarkingSeasonsKeepsWhereTheViewerIs() async throws {
        let model = try await model()
        let series = try XCTUnwrap(model.content.series.first { $0.name == "Archive" })
        let details = try await model.details(for: series)
        XCTAssertEqual(details.seasons.count, 2)
        let later = details.seasons[1].episodes[1]
        let item = try XCTUnwrap(model.downloadItem(for: later, in: series))
        model.recordStart(item)
        model.recordProgress(item, position: 900, duration: 2_700)
        XCTAssertEqual(model.continueWatching(.series).first?.reference.episodeID, later.id)

        model.setWatched(true, episodes: details.seasons[0].episodes, in: series)
        XCTAssertTrue(details.seasons[0].episodes.allSatisfy { model.isWatched(episode: $0, in: series) })
        XCTAssertEqual(model.continueWatching(.series).first?.reference.episodeID, later.id, "Continue Watching stays")
        XCTAssertEqual(model.nextEpisode(in: series, details: details)?.episode.id, later.id, "Play stays")

        model.setWatched(true, series: series, details: details)
        XCTAssertTrue(model.isWatched(.series(series)))
        model.setWatched(false, series: series, details: details)
        XCTAssertFalse(model.isWatched(.series(series)))
        XCTAssertEqual(model.nextEpisode(in: series, details: details)?.episode.id, details.seasons[0].episodes[0].id, "from the start")

        XCTAssertTrue(AppModel.canMarkAtOnce(UserLibrary.bulkMarkLimit))
        XCTAssertFalse(AppModel.canMarkAtOnce(UserLibrary.bulkMarkLimit + 1))
        let many = (1...UserLibrary.bulkMarkLimit + 1).map { number in
            Episode(id: "many-\(number)", title: "Episode \(number)", season: 9, number: number, streamURL: URL(string: "http://127.0.0.1:9/m\(number).mp4")!)
        }
        let before = model.library.history
        model.setWatched(true, episodes: many, in: series)
        XCTAssertEqual(model.library.history, before, "past the limit: nothing marked at once")
    }

    /// The set is made once per change of the history, not per card: asking it again gives the same set at once.
    func testTheBadgeIsASetLookup() async throws {
        let model = try await model()
        let quiet = try XCTUnwrap(model.content.movies.first { $0.name == "Quiet Water" })
        model.setWatched(true, movie: quiet)
        _ = model.watchedKeys
        let made = try XCTUnwrap(model.watchedKeysCache)
        for _ in 0..<1_000 { XCTAssertTrue(model.isWatched(.movie(quiet))) }
        XCTAssertEqual(model.watchedKeysCache?.keys, made.keys)
        XCTAssertTrue(model.watchedKeysCache?.history == made.history)
        model.setWatched(false, movie: quiet)
        XCTAssertFalse(model.isWatched(.movie(quiet)), "made again after a change")
    }

    /// Every surface that shows movie or series thumbnails, and how many thumbnail calls its source has: each passes the
    /// watched state (`watched:`), or is `ShowcasePoster`, which reads it itself. A new screen, or a new thumbnail on one,
    /// is added here.
    private static let surfaces: [String: Int] = [
        "App/Features/Common/Components.swift": 1,      // PosterCard → PosterArtwork
        "App/Features/Common/DetailScaffold.swift": 1,  // the poster of a movie's and a series' page
        "App/Features/Common/MediaShelves.swift": 1,    // ShowcasePoster → PosterArtwork
        "App/Features/Common/MetadataView.swift": 2,    // genre, year, award, festival pages
        "App/Features/Common/PersonView.swift": 2,      // an actor's or a director's titles
        "App/Features/Common/VODBrowser.swift": 8,      // Movies and Series tabs' rows and grids
        "App/Features/Favorites/FavoritesView.swift": 2, // My List
        "App/Features/Favorites/ListsViews.swift": 2,   // collections
        "App/Features/Home/HomeView.swift": 5,          // Home's rows and selections
        "App/Features/Movies/MoviesView.swift": 2,      // a category's grid, More Like This
        "App/Features/Search/SearchView.swift": 2,      // search results
        "App/Features/Series/SeriesViews.swift": 2,     // a category's grid, More Like This
    ]

    func testEverySurfaceShowsTheBadge() throws {
        var directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while !FileManager.default.fileExists(atPath: directory.appendingPathComponent("App/Features").path) {
            let parent = directory.deletingLastPathComponent()
            guard parent.path != directory.path else { throw XCTSkip("App/Features not found") }
            directory = parent
        }
        let features = directory.appendingPathComponent("App/Features")
        let files = try XCTUnwrap(FileManager.default.enumerator(at: features, includingPropertiesForKeys: nil))
            .compactMap { $0 as? URL }.filter { $0.pathExtension == "swift" }
        let call = try NSRegularExpression(pattern: #"(?<!struct )\b(PosterCard|SearchResultRow|PosterArtwork|ShowcasePoster)\("#)
        var found: [String: Int] = [:]
        for file in files {
            let source = try String(contentsOf: file, encoding: .utf8)
            let path = "App/Features/" + file.path.components(separatedBy: "/App/Features/").last!
            let text = source as NSString
            for match in call.matches(in: source, range: NSRange(location: 0, length: text.length)) {
                found[path, default: 0] += 1
                let name = text.substring(with: match.range(at: 1))
                guard name != "ShowcasePoster" else { continue }
                // The call's arguments, to its closing parenthesis.
                var depth = 0
                var end = match.range.location + match.range.length - 1
                repeat {
                    let character = text.character(at: end)
                    if character == 40 { depth += 1 } else if character == 41 { depth -= 1 }
                    end += 1
                } while depth > 0 && end < text.length
                let arguments = text.substring(with: NSRange(location: match.range.location, length: end - match.range.location))
                XCTAssertTrue(arguments.contains("watched:"), "\(path): \(name) without its watched badge: \(arguments)")
            }
        }
        XCTAssertEqual(found, Self.surfaces, "a surface with thumbnails is added to `surfaces`")
    }
}
