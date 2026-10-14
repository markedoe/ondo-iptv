import XCTest
import IPTVCore
@testable import Ondo

/// Adding a playlist end to end: the app's own HTTP client against a real server on 127.0.0.1, which
/// records what arrived. Unit tests feed the parsers canned data; these check what only a real request
/// shows (headers, redirects, how credentials are encoded, HTTP errors). Loopback may be exempt from
/// App Transport Security: that part is AppTransportSecurityTests'.
@MainActor
final class PlaylistOverHTTPTests: XCTestCase {
    private var root: URL!
    private let year = Calendar.current.component(.year, from: .now)

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoHTTP-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: root)
    }

    private func makeModel() async -> AppModel {
        let model = AppModel(storageRoot: root, secrets: InMemorySecretStore(), reminderScheduler: InMemoryReminderScheduler())
        await model.bootstrap()
        return model
    }

    func testM3UPlaylistFollowsARedirectAndIdentifiesAsAPlayer() async throws {
        let playlist = """
        #EXTM3U
        #EXTINF:-1 tvg-id="harbor.news" group-title="News",Harbor News
        http://127.0.0.1:9/news.m3u8
        #EXTINF:5400 group-title="Movies | Drama",Quiet Water (\(year))
        http://127.0.0.1:9/quiet.mp4
        """
        let server = try LocalHTTPServer { request in
            switch request.path {
            case "/get.php": return .redirect(to: "/lists/home.m3u")
            case "/lists/home.m3u": return .text(playlist, type: "audio/x-mpegurl")
            default: return .notFound
            }
        }
        let base = try await server.start()
        let model = await makeModel()

        try await model.addPlaylist(Playlist(name: "Home", source: .remote(base.appending(path: "get.php")), autoRefreshHours: 0))

        XCTAssertEqual(model.content.channels.map(\.name), ["Harbor News"])
        XCTAssertEqual(model.content.movies.map(\.name), ["Quiet Water"])
        let requests = server.requests
        XCTAssertEqual(requests.prefix(2).map(\.path), ["/get.php", "/lists/home.m3u"], "the redirect is followed")
        for request in requests {
            XCTAssertEqual(request.headers["user-agent"], defaultIPTVUserAgent, "many panels refuse unknown players")
        }
    }

    func testRefusedPlaylistReportsTheHTTPStatusAndAddsNothing() async throws {
        let server = try LocalHTTPServer { _ in LocalHTTPServer.Response(status: 403) }
        let base = try await server.start()
        let model = await makeModel()

        do {
            try await model.addPlaylist(Playlist(name: "Home", source: .remote(base.appending(path: "list.m3u")), autoRefreshHours: 0))
            XCTFail("a refused download must not add the playlist")
        } catch {
            XCTAssertEqual(error as? HTTPClientError, .badStatus(403), "\(error)")
        }
        XCTAssertTrue(model.playlists.isEmpty)
    }

    /// The panel decodes the query like PHP ("+" is a space) and finds the account in stream paths:
    /// a password with + / & = must reach it unchanged, or the login fails.
    func testXtreamLoginListsAndStreamsCarryTheCredentialsIntact() async throws {
        let username = "viewer"
        let password = "pa+ss/w&rd=1"
        let year = self.year
        let server = try LocalHTTPServer { request in
            if request.path.hasPrefix("/movie/") { return .text("stream") }
            guard request.path == "/player_api.php" else { return .notFound }
            guard request.query["username"] == username, request.query["password"] == password else {
                return .json(#"{"user_info":{"auth":0}}"#)
            }
            switch request.query["action"] ?? "" {
            case "":
                return .json("""
                {"user_info":{"username":"viewer","auth":1,"status":"Active","exp_date":"1924992000","max_connections":"1",\
                "allowed_output_formats":["m3u8","ts"]},"server_info":{"url":"127.0.0.1","server_protocol":"http"}}
                """)
            case "get_live_categories": return .json(#"[{"category_id":"1","category_name":"News","parent_id":0}]"#)
            case "get_live_streams":
                return .json(#"[{"num":1,"name":"Harbor News","stream_type":"live","stream_id":101,"category_id":"1"}]"#)
            case "get_vod_categories": return .json(#"[{"category_id":"10","category_name":"Drama","parent_id":0}]"#)
            case "get_vod_streams":
                return .json("""
                [{"num":1,"name":"Quiet Water (\(year))","stream_type":"movie","stream_id":5001,"category_id":"10",\
                "added":"1700000000","container_extension":"mp4"}]
                """)
            case "get_series_categories": return .json(#"[{"category_id":"20","category_name":"Drama","parent_id":0}]"#)
            case "get_series": return .json(#"[{"num":1,"name":"Harbor Lights","series_id":9001,"category_id":"20"}]"#)
            default: return .json("[]")
            }
        }
        let base = try await server.start()
        let model = await makeModel()
        let credentials = XtreamCredentials(server: base, username: username, password: password)

        try await model.addPlaylist(Playlist(name: "Panel", source: .xtream(credentials), autoRefreshHours: 0))

        XCTAssertEqual(model.content.channels.map(\.name), ["Harbor News"])
        XCTAssertEqual(model.content.movies.map(\.name), ["Quiet Water"])
        XCTAssertEqual(model.content.series.map(\.name), ["Harbor Lights"])
        let api = server.requests.filter { $0.path == "/player_api.php" }
        let lists: Set = ["get_live_categories", "get_live_streams", "get_vod_categories", "get_vod_streams", "get_series_categories", "get_series"]
        XCTAssertTrue(lists.isSubset(of: Set(api.compactMap { $0.query["action"] })), "login, then the six lists")
        for request in api {
            XCTAssertEqual(request.query["password"], password, "the password arrives as typed")
            XCTAssertEqual(request.headers["user-agent"], defaultIPTVUserAgent)
        }

        // Playing a movie: the panel splits the path into its segments.
        let movie = try XCTUnwrap(model.content.movies.first)
        _ = try await URLSession.shared.data(from: movie.streamURL)
        let stream = try XCTUnwrap(server.requests.last { $0.path.hasPrefix("/movie/") })
        XCTAssertEqual(stream.segments, ["movie", username, password, "5001.mp4"])
    }
}
