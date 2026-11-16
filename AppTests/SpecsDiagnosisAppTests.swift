import XCTest
import IPTVCore
@testable import Ondo

/// A film in two versions on an Xtream panel, a plain one and a "4K DV" one: details kept by an earlier version of the
/// app, which did not read the provider's technical blocks, are asked again; the title data says what each version's
/// details held (or how they failed) and whether they are kept, and is worked out again when a reading or details
/// arrive; VLC's reading of a film's picture is kept too. The same as Android's `SpecsDiagnosisTest`. A panel on
/// 127.0.0.1; invented names and ids only.
@MainActor
final class SpecsDiagnosisAppTests: XCTestCase {
    private var root: URL!
    /// Shared by the launches of a test: the next launch reads the password the first one kept, as on a device.
    private let secrets = InMemorySecretStore()

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoSpecsDiagnosis-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: root)
    }

    /// A launch; `adding` starts the panel and adds it (the first launch only: a listener is started once).
    private func model(_ panel: DiagnosisPanel, adding: Bool = true) async throws -> AppModel {
        let model = AppModel(storageRoot: root, secrets: secrets, reminderScheduler: InMemoryReminderScheduler())
        await model.bootstrap()
        if adding {
            let base = try await panel.server.start()
            try await model.addPlaylist(Playlist(
                name: "Panel", source: .xtream(XtreamCredentials(server: base, username: "viewer", password: "secret")), autoRefreshHours: 0
            ))
        }
        return model
    }

    private func films(_ model: AppModel) throws -> (plain: Movie, dv: Movie) {
        (try XCTUnwrap(model.content.movies.first { $0.xtreamStreamID == 700101 }), try XCTUnwrap(model.content.movies.first { $0.xtreamStreamID == 700102 }))
    }

    private func eventually(_ condition: () -> Bool) async {
        for _ in 0..<500 where !condition() { try? await Task.sleep(for: .milliseconds(10)) }
    }

    /// Details kept before the specs were read (no `providerInfo`) are asked again: the page's own, the other versions'.
    func testDetailsKeptBeforeTheSpecsWereReadAreAskedAgain() async throws {
        let panel = try DiagnosisPanel()
        let first = try await model(panel)
        let (plain, dv) = try films(first)
        let playlist = try XCTUnwrap(first.activePlaylist)
        first.flush()
        var archive = DetailsArchive()
        archive.movies[plain.id] = .init(value: MediaDetails(title: "Copper Lake", plot: "A lake."), fetchedAt: .now)
        archive.versions[dv.id] = .init(value: MediaDetails(title: "Copper Lake", plot: "A lake."), fetchedAt: .now)
        try first.cacheStore.save(archive, name: DetailsArchive.documentName(for: playlist.id), keepingPrevious: false)

        // `bootstrap()` opens the playlist, which starts reading its document: waiting for that read is enough.
        let second = try await model(panel, adding: false)
        await second.storedDetailsLoaded()
        XCTAssertEqual(second.activePlaylist?.id, playlist.id)
        XCTAssertEqual(panel.asked, [], "nothing asked at launch")
        XCTAssertNotNil(second.movieDetailsCache[plain.id], "the kept details are read back")
        XCTAssertNil(second.movieDetailsCache[plain.id]?.streamSpecs)
        // The other version asked again and saved: the page's own old details stay on disk, never lost.
        let asked = await second.loadVersionDetails([dv], gap: .milliseconds(1))
        XCTAssertEqual(asked, 1)
        XCTAssertEqual(panel.asked, ["700102"])
        second.flush()
        await second.storedDetailsWrite?.value
        let kept = try XCTUnwrap(second.cacheStore.load(DetailsArchive.self, name: DetailsArchive.documentName(for: playlist.id)))
        XCTAssertEqual(kept.movies[plain.id]?.value.plot, "A lake.")
        XCTAssertNil(kept.movies[plain.id]?.value.providerInfo)
        XCTAssertNotNil(kept.versions[dv.id]?.value.providerInfo)

        _ = await second.details(for: plain)
        XCTAssertEqual(panel.asked, ["700102", "700101"], "asked again")
        XCTAssertEqual(second.movieDetailsCache[plain.id]?.streamSpecs?.resolution, "1080p")
        // Read by this version: not asked again.
        let again = await second.loadVersionDetails([dv], gap: .milliseconds(1))
        XCTAssertEqual(again, 0)
    }

    /// The title data says what each version's details held, how they failed, which are kept; field names only.
    func testTheTitleDataSaysWhatTheProvidersDetailsHeld() async throws {
        let panel = try DiagnosisPanel()
        let model = try await model(panel)
        let (plain, dv) = try films(model)
        let item = try XCTUnwrap(model.downloadItem(for: plain))
        var maybe = await model.titleData(for: item)
        var data = try XCTUnwrap(maybe)
        XCTAssertEqual(data.versions.map(\.provider), [.notRequested, .notRequested])
        XCTAssertNotNil(data.screen)
        XCTAssertFalse(data.app.isEmpty)

        _ = await model.details(for: plain)
        panel.refuse("700102")
        _ = await model.loadVersionDetails([dv], gap: .milliseconds(1))
        maybe = await model.titleData(for: item)
        data = try XCTUnwrap(maybe)
        let shown = Dictionary(uniqueKeysWithValues: data.versions.map { ($0.id, $0) })
        XCTAssertEqual(shown[plain.id]?.provider, .videoAndAudio)
        XCTAssertEqual(shown[plain.id]?.providerKeys, ["audio", "name", "plot", "video"])
        XCTAssertEqual(shown[plain.id]?.detailsKept, true)
        XCTAssertEqual(shown[dv.id]?.provider, .failed)
        XCTAssertEqual(shown[dv.id]?.providerFailure, "HTTP 503")
        XCTAssertEqual(shown[dv.id]?.detailsKept, false)
        XCTAssertEqual(shown[dv.id]?.dolbyVision, true)
        XCTAssertNil(shown[dv.id]?.dolbyVisionProfile)
        for version in data.versions {
            for key in version.providerKeys { XCTAssertFalse(key.contains("example.com") || key.contains("/"), key) }
        }
    }

    /// What the player measured shows in the player's title data once recorded: its key changes with the reading.
    func testThePlayersTitleDataFollowsWhatIsMeasured() async throws {
        let panel = try DiagnosisPanel()
        let model = try await model(panel)
        let (plain, _) = try films(model)
        let key = { TitleDataKey(shown: true, item: "film", catalogue: 2, measured: model.measuredSpecsRevision, details: model.detailsRevision, failures: model.detailsFailures.count) }
        let before = key()
        model.recordMeasuredSpecs(StreamSpecs(width: 1920, height: 1080, videoCodec: "H.264"), movieID: plain.id, playlistID: try XCTUnwrap(model.activePlaylist).id)
        await eventually { model.specSource(for: plain.id) == .measured }
        XCTAssertNotEqual(key(), before)
        let item = try XCTUnwrap(model.downloadItem(for: plain))
        let maybe = await model.titleData(for: item)
        let data = try XCTUnwrap(maybe)
        XCTAssertEqual(data.versions.first { $0.id == plain.id }?.specSource, .measured)
    }

    /// A film VLC plays (an MKV on a Mac) is measured too: its picture's size, its own stream only.
    func testVLCsReadingOfAFilmIsKept() throws {
        let film = PlaybackItem(
            reference: MediaReference(playlistID: UUID(), type: .movie, itemID: "x700101"), title: "Copper Lake",
            streamURL: try XCTUnwrap(URL(string: "http://panel.example.com/movie/a/b/700101.mkv")), isLive: false
        )
        XCTAssertTrue(PlayerController.recordsSpecs(of: film, candidateIndex: 0))
        XCTAssertFalse(PlayerController.recordsSpecs(of: film, candidateIndex: 1), "a fallback's address is not this stream")
        XCTAssertEqual(PlayerController.vlcSpecs(videoSize: CGSize(width: 1920, height: 1080)), StreamSpecs(width: 1920, height: 1080, source: .measured))
        XCTAssertNil(PlayerController.vlcSpecs(videoSize: .zero))
    }
}

/// A panel with one film in two versions; it refuses the details of the ones named.
private final class DiagnosisPanel: @unchecked Sendable {
    private let lock = NSLock()
    private var refused: Set<String> = []
    private(set) var server: LocalHTTPServer!

    init() throws {
        server = try LocalHTTPServer { [unowned self] request in self.answer(request) }
    }

    func refuse(_ id: String) { lock.withLock { _ = refused.insert(id) } }

    /// The versions whose details were asked, in order.
    var asked: [String] {
        server.requests.filter { $0.query["action"] == "get_vod_info" }.compactMap { $0.query["vod_id"] }
    }

    private func answer(_ request: LocalHTTPServer.Request) -> LocalHTTPServer.Response {
        guard request.path == "/player_api.php" else { return .notFound }
        switch request.query["action"] ?? "" {
        case "":
            return .json("""
            {"user_info":{"username":"viewer","auth":1,"status":"Active","exp_date":"1924992000","max_connections":"1",\
            "allowed_output_formats":["m3u8","ts"]},"server_info":{"url":"127.0.0.1","server_protocol":"http"}}
            """)
        case "get_vod_categories":
            return .json(#"[{"category_id":"31","category_name":"|FR| NEW","parent_id":0},{"category_id":"32","category_name":"|FR| 4K DOLBY VISION","parent_id":0}]"#)
        case "get_vod_streams":
            return .json(#"""
            [{"num":1,"name":"|FR| Copper Lake","stream_type":"movie","stream_id":700101,"category_id":"31","container_extension":"mkv","tmdb":"4343"},\#
            {"num":2,"name":"|FR-4K DV| Copper Lake","stream_type":"movie","stream_id":700102,"category_id":"32","container_extension":"mkv","tmdb":"4343"}]
            """#)
        case "get_series_categories", "get_series": return .json("[]")
        case "get_vod_info":
            let id = request.query["vod_id"] ?? ""
            if lock.withLock({ refused.contains(id) }) { return LocalHTTPServer.Response(status: 503) }
            if id == "700101" {
                return .json(
                    #"{"info":{"name":"Copper Lake","plot":"A lake.","video":{"codec_name":"h264","width":1920,"height":1080},"#
                        + #""audio":{"codec_name":"ac3","channels":6},"http://bad.example.com/key":"x"},"movie_data":{"stream_id":700101}}"#
                )
            }
            return .json(#"{"info":{"name":"Copper Lake","plot":"A lake.","video":[],"audio":[]},"movie_data":{"stream_id":700102}}"#)
        default: return .json("[]")
        }
    }
}
