import XCTest
import IPTVCore
@testable import Ondo

/// A film the playlist lists several times (4K Dolby Vision, 4K, HD, VOSTFR) shows once in every list and in every
/// category one of its versions is in; its state comes from its versions; "Group versions" off is the playlist as it is.
/// The same cases as Android's `FilmVersionsAppTest`.
@MainActor
final class FilmVersionsAppTests: XCTestCase {
    private var root: URL!

    nonisolated private static let catalogue = """
    #EXTM3U
    #EXTINF:6000 group-title="Movies | Drama",Glass City (2024)
    http://127.0.0.1:9/glass.mp4
    #EXTINF:6000 group-title="Movies | Drama",Quiet Water (2023)
    http://127.0.0.1:9/quiet.mp4
    #EXTINF:6000 group-title="Movies | 4K",Glass City 4K DV (2024)
    http://127.0.0.1:9/glass-dv.mkv
    #EXTINF:6000 group-title="Movies | 4K",Glass City 4K (2024)
    http://127.0.0.1:9/glass-4k.mkv
    #EXTINF:6000 group-title="Movies | Drama",Glass City VOSTFR (2024)
    http://127.0.0.1:9/glass-vostfr.mp4
    #EXTINF:6000 group-title="Movies | Drama",Glass City (2019)
    http://127.0.0.1:9/glass-remake.mp4
    """

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoVersions-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: root)
    }

    private func model(with text: String = catalogue) async throws -> AppModel {
        // A folder of its own: a test may make two.
        let folder = root.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let file = folder.appendingPathComponent("list.m3u")
        try Data(text.utf8).write(to: file)
        let model = AppModel(storageRoot: folder, secrets: InMemorySecretStore(), reminderScheduler: InMemoryReminderScheduler())
        await model.bootstrap()
        try await model.addPlaylist(Playlist(name: "Test", source: .remote(file), autoRefreshHours: 0))
        return model
    }

    private func movie(_ name: String, year: Int = 2024, in model: AppModel) throws -> Movie {
        try XCTUnwrap(model.content.movies.first { $0.name == name && $0.year == year })
    }

    private func names(_ movies: [Movie]) -> [String] { movies.map(\.name) }

    func testAFilmShowsOnceInEveryListAndInEachCategoryOfItsVersions() async throws {
        let model = try await model()
        XCTAssertEqual(names(model.movies(in: nil, sort: .playlist)), ["Glass City", "Quiet Water", "Glass City"], "the remake of 2019 is another film")
        let drama = try XCTUnwrap(model.content.movieCategories.first { $0.name == "Movies | Drama" })
        let uhd = try XCTUnwrap(model.content.movieCategories.first { $0.name == "Movies | 4K" })
        XCTAssertEqual(names(model.movies(in: drama, sort: .playlist)), ["Glass City", "Quiet Water", "Glass City"])
        XCTAssertEqual(names(model.movies(in: uhd, sort: .playlist)), ["Glass City 4K DV"], "in the 4K category too, by its first version there")
        XCTAssertEqual(model.search("glass").movies.count, 2)
        XCTAssertEqual(model.titles(for: .year(2024)).movies.count, 1)
    }

    func testGroupVersionsOffIsThePlaylistAsItIs() async throws {
        let model = try await model()
        model.updateSettings { $0.groupVersions = false }
        XCTAssertEqual(model.movies(in: nil, sort: .playlist).count, 6)
        XCTAssertEqual(model.search("glass").movies.count, 5)
        XCTAssertEqual(model.visibleVersions(of: try movie("Glass City", in: model)).count, 1)
    }

    func testAFilmIsWatchedWhenOneOfItsVersionsIsFinished() async throws {
        let model = try await model()
        model.updateSettings { $0.hideWatchedMovies = true }
        let item = try XCTUnwrap(model.downloadItem(for: try movie("Glass City VOSTFR", in: model)))
        model.recordStart(item)
        model.recordProgress(item, position: 5_990, duration: 6_000)
        XCTAssertEqual(names(model.movies(in: nil, sort: .playlist)), ["Quiet Water", "Glass City"], "every version of the 2024 film is left out")
    }

    func testAFilmIsInMyListWhenOneOfItsVersionsIs() async throws {
        let model = try await model()
        let hd = try movie("Glass City", in: model)
        let uhd = try movie("Glass City 4K", in: model)
        model.toggleFavorite(uhd)
        XCTAssertTrue(model.isFavoriteFilm(hd))
        XCTAssertEqual(model.myList(), [.movie(uhd)])
        model.toggleFavoriteFilm(hd)
        XCTAssertFalse(model.isFavoriteFilm(hd), "out of My List: every version kept goes")
        model.toggleFavoriteFilm(hd)
        XCTAssertTrue(model.isFavorite(.movie, id: model.defaultVersion(of: hd).id), "into it: the default version")
    }

    func testTheDefaultVersionAndTheBadges() async throws {
        let model = try await model()
        let hd = try movie("Glass City", in: model)
        let versions = model.visibleVersions(of: hd)
        XCTAssertEqual(names(versions), ["Glass City", "Glass City 4K DV", "Glass City 4K", "Glass City VOSTFR"])
        XCTAssertEqual(model.versionBadges(versions).map(\.label), [nil, "4K DV", "4K", "VOSTFR"])
        let vostfr = try movie("Glass City VOSTFR", in: model)
        model.rememberVersion(vostfr)
        XCTAssertEqual(model.defaultVersion(of: hd), vostfr, "the one chosen last")
        let dv = try movie("Glass City 4K DV", in: model)
        let item = try XCTUnwrap(model.downloadItem(for: dv))
        model.recordStart(item)
        model.recordProgress(item, position: 600, duration: 6_000)
        XCTAssertEqual(model.defaultVersion(of: hd), dv, "the one in progress first")
        // Continue Watching stays per stream and says which version is in progress.
        let entry = try XCTUnwrap(model.continueWatching(.movie).first)
        XCTAssertEqual(model.versionLabel(for: entry), "4K DV")
    }

    /// Two streams of the same tags are two badges, never collapsed: "4K DV" and "4K DV 2". (The M3U reader gives every
    /// entry its own id, so an address listed twice is two streams here; Android's test covers one id listed twice.)
    func testTheSameTagsTwiceAreTwoBadges() async throws {
        // The catalogue ends without a newline.
        let line = "\n#EXTINF:6000 group-title=\"Movies | 4K\",Glass City 4K DV (2024)\n"
        let other = try await model(with: Self.catalogue + line + "http://127.0.0.1:9/glass-dv-2.mkv\n")
        let versions = other.visibleVersions(of: try movie("Glass City", in: other))
        XCTAssertEqual(other.versionBadges(versions).compactMap(\.label), ["4K DV", "4K", "VOSTFR", "4K DV 2"])
    }
}
