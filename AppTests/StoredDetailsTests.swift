import XCTest
import IPTVCore
@testable import Ondo

/// Movie and series details kept between launches: a panel on 127.0.0.1 answers the first launch; the next one shows
/// the backdrops and episodes from disk without asking it again, asks again once they are old, and keeps showing them
/// when the panel cannot be reached.
@MainActor
final class StoredDetailsTests: XCTestCase {
    private var root: URL!
    private let secrets = InMemorySecretStore()

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoDetails-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: root)
    }

    private func launch() async -> AppModel {
        let model = AppModel(storageRoot: root, secrets: secrets, reminderScheduler: InMemoryReminderScheduler())
        await model.bootstrap()
        return model
    }

    /// Writes the pending save, as when the app leaves the screen.
    private func quit(_ model: AppModel) async {
        model.flush()
        await model.storedDetailsWrite?.value
    }

    func testDetailsSeenOnceShowAtTheNextLaunchWithoutAskingThePanel() async throws {
        let panel = try FakePanel()
        let base = try await panel.server.start()
        let first = await launch()
        try await first.addPlaylist(Playlist(
            name: "Panel", source: .xtream(XtreamCredentials(server: base, username: "viewer", password: "secret")), autoRefreshHours: 0
        ))
        let movie = try XCTUnwrap(first.content.movies.first)
        let series = try XCTUnwrap(first.content.series.first)
        let movieDetails = await first.details(for: movie)
        let seriesDetails = try await first.details(for: series)
        XCTAssertEqual(movieDetails?.backdropURL?.lastPathComponent, "wide.jpg")
        XCTAssertEqual(seriesDetails.seasons.first?.episodes.first?.posterURL?.lastPathComponent, "still.jpg")
        XCTAssertEqual(panel.calls("get_vod_info"), 1)
        XCTAssertEqual(panel.calls("get_series_info"), 1)
        await quit(first)

        let second = await launch()
        await second.storedDetailsLoaded()
        XCTAssertEqual(second.cachedDetails(for: .movie(movie))?.backdropURL?.lastPathComponent, "wide.jpg", "Home's backdrop, from disk")
        XCTAssertEqual(second.storedDetails(for: series)?.seasons.first?.episodes.first?.posterURL?.lastPathComponent, "still.jpg")
        _ = await second.details(for: movie)
        _ = try await second.details(for: series)
        XCTAssertEqual(panel.calls("get_vod_info"), 1, "recent details are not asked for again")
        XCTAssertEqual(panel.calls("get_series_info"), 1)

        // A day later: the series is asked for again (new episodes), the movie is not yet.
        second.movieDetailsFetchedAt[movie.id] = Date(timeIntervalSinceNow: -24 * 3600)
        second.seriesDetailsFetchedAt[series.id] = Date(timeIntervalSinceNow: -24 * 3600)
        _ = await second.details(for: movie)
        let refreshed = try await second.details(for: series)
        XCTAssertEqual(refreshed.seasons.first?.episodes.count, 2, "the episode added since")
        XCTAssertEqual(panel.calls("get_vod_info"), 1)
        XCTAssertEqual(panel.calls("get_series_info"), 2)

        // The panel cannot be reached: the kept details stay.
        panel.isDown = true
        second.movieDetailsFetchedAt[movie.id] = Date(timeIntervalSinceNow: -30 * 24 * 3600)
        second.seriesDetailsFetchedAt[series.id] = Date(timeIntervalSinceNow: -24 * 3600)
        let keptMovie = await second.details(for: movie)
        let keptSeries = try await second.details(for: series)
        XCTAssertEqual(keptMovie?.backdropURL?.lastPathComponent, "wide.jpg")
        XCTAssertEqual(keptSeries.seasons.first?.episodes.count, 2)
    }

    func testDeletingThePlaylistDeletesItsDetails() async throws {
        let panel = try FakePanel()
        let base = try await panel.server.start()
        let model = await launch()
        try await model.addPlaylist(Playlist(
            name: "Panel", source: .xtream(XtreamCredentials(server: base, username: "viewer", password: "secret")), autoRefreshHours: 0
        ))
        let playlist = try XCTUnwrap(model.activePlaylist)
        let movie = try XCTUnwrap(model.content.movies.first)
        _ = await model.details(for: movie)
        await quit(model)
        let document = model.cacheStore.url(for: DetailsArchive.documentName(for: playlist.id))
        XCTAssertTrue(FileManager.default.fileExists(atPath: document.path))

        // Details fetched just before: their save, still pending, must not bring the document back.
        let series = try XCTUnwrap(model.content.series.first)
        _ = try await model.details(for: series)
        await model.deletePlaylist(playlist)
        model.saveStoredDetailsNow()
        await model.storedDetailsWrite?.value
        XCTAssertFalse(FileManager.default.fileExists(atPath: document.path))
    }
}

/// An Xtream panel with one movie and one series; the series gains an episode after its first visit.
private final class FakePanel: @unchecked Sendable {
    private let lock = NSLock()
    private var down = false
    private(set) var server: LocalHTTPServer!

    var isDown: Bool {
        get { lock.withLock { down } }
        set { lock.withLock { down = newValue } }
    }

    init() throws {
        server = try LocalHTTPServer { [unowned self] request in self.answer(request) }
    }

    func calls(_ action: String) -> Int {
        server.requests.filter { $0.query["action"] == action }.count
    }

    private func answer(_ request: LocalHTTPServer.Request) -> LocalHTTPServer.Response {
        guard !isDown else { return LocalHTTPServer.Response(status: 503) }
        guard request.path == "/player_api.php" else { return .notFound }
        switch request.query["action"] ?? "" {
        case "":
            return .json("""
            {"user_info":{"username":"viewer","auth":1,"status":"Active","exp_date":"1924992000","max_connections":"1",\
            "allowed_output_formats":["m3u8","ts"]},"server_info":{"url":"127.0.0.1","server_protocol":"http"}}
            """)
        case "get_vod_categories": return .json(#"[{"category_id":"10","category_name":"Drama","parent_id":0}]"#)
        case "get_vod_streams":
            return .json(#"[{"num":1,"name":"Quiet Water","stream_type":"movie","stream_id":5001,"category_id":"10","container_extension":"mp4"}]"#)
        case "get_series_categories": return .json(#"[{"category_id":"20","category_name":"Drama","parent_id":0}]"#)
        case "get_series": return .json(#"[{"num":1,"name":"Harbor Lights","series_id":9001,"category_id":"20"}]"#)
        case "get_vod_info":
            return .json(#"{"info":{"name":"Quiet Water","plot":"A lake.","backdrop_path":["http://img.example.com/wide.jpg"]},"movie_data":{"stream_id":5001,"name":"Quiet Water","container_extension":"mp4"}}"#)
        case "get_series_info":
            let episode = #"{"id":"7001","episode_num":1,"title":"Pilot","container_extension":"mkv","info":{"movie_image":"http://img.example.com/still.jpg"}}"#
            let added = #"{"id":"7002","episode_num":2,"title":"Second","container_extension":"mkv"}"#
            let episodes = calls("get_series_info") > 1 ? "\(episode),\(added)" : episode
            return .json(#"{"seasons":[],"info":{"name":"Harbor Lights"},"episodes":{"1":["# + episodes + "]}}")
        default: return .json("[]")
        }
    }
}
