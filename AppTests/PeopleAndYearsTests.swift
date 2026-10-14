import XCTest
import IPTVCore
@testable import Ondo

/// Search filtered by release years, and the movies and series of a person tapped in a page's credits.
@MainActor
final class PeopleAndYearsTests: XCTestCase {
    private var root: URL!

    private static let playlistText = """
    #EXTM3U
    #EXTINF:-1 tvg-id="news.test" group-title="News",Test News
    http://127.0.0.1:9/news.m3u8
    #EXTINF:5400 group-title="Movies | Drama",Quiet Water (2021)
    http://127.0.0.1:9/movie/quiet.mp4
    #EXTINF:5400 group-title="Movies | Drama",Glass City (2024)
    http://127.0.0.1:9/movie/glass.mp4
    #EXTINF:5400 group-title="Movies | Drama",Bright Field (1998)
    http://127.0.0.1:9/movie/bright.mp4
    #EXTINF:5400 group-title="Movies | Drama",Northern Lights
    http://127.0.0.1:9/movie/northern.mp4
    """

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: root)
    }

    private func model() async throws -> AppModel {
        let file = root.appendingPathComponent("list.m3u")
        try Data(Self.playlistText.utf8).write(to: file)
        let model = AppModel(storageRoot: root, secrets: InMemorySecretStore(), reminderScheduler: InMemoryReminderScheduler())
        await model.bootstrap()
        try await model.addPlaylist(Playlist(name: "Test", source: .remote(file), autoRefreshHours: 0))
        return model
    }

    private func movie(_ name: String, in model: AppModel) throws -> Movie {
        try XCTUnwrap(model.content.movies.first { $0.name == name })
    }

    func testTheYearFilterOffersThePlaylistsReleaseYearsNewestFirst() async throws {
        let model = try await model()
        XCTAssertEqual(model.releaseYears(), [2024, 2021, 1998])
    }

    func testAnEmptyQueryListsWhatWasReleasedInTheYearsNewestFirst() async throws {
        let model = try await model()
        let results = model.search("", releasedIn: 2000...2024)
        XCTAssertEqual(results.movies.map(\.name), ["Glass City", "Quiet Water"])
        XCTAssertTrue(results.channels.isEmpty, "channels have no release year")
        XCTAssertEqual(model.search("", releasedIn: 1998...1998).movies.map(\.name), ["Bright Field"])
    }

    func testAQueryIsNarrowedToTheYears() async throws {
        let model = try await model()
        XCTAssertEqual(model.search("water", releasedIn: 2021...2021).movies.map(\.name), ["Quiet Water"])
        XCTAssertTrue(model.search("water", releasedIn: 2022...2030).movies.isEmpty)
        XCTAssertTrue(model.search("northern", releasedIn: 1900...2100).movies.isEmpty, "a title without a year is left out")
    }

    func testAPersonsWorksComeFromThePagesAlreadyOpenedWithoutATMDbKey() async throws {
        let model = try await model()
        let quiet = try movie("Quiet Water", in: model)
        let glass = try movie("Glass City", in: model)
        let bright = try movie("Bright Field", in: model)
        model.movieDetailsCache[quiet.id] = MediaDetails(title: quiet.name, cast: "Ada Vance (Mara), Tom Ellery")
        model.movieDetailsCache[glass.id] = MediaDetails(title: glass.name, director: "ada vance")
        model.movieDetailsCache[bright.id] = MediaDetails(title: bright.name, cast: "Tom Ellery")

        let works = await model.works(of: "Ada Vance")
        XCTAssertEqual(works.movies.map(\.name), ["Glass City", "Quiet Water"], "cast and director, newest first")
        XCTAssertFalse(works.searchedOnline, "no TMDb key: nothing is sent online")
        XCTAssertTrue(model.knownWorks(of: "Nobody Here").isEmpty)
    }
}
