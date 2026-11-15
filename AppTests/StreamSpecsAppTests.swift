import XCTest
import IPTVCore
@testable import Ondo

/// What a film's version is made of, on its page: the facts line's badges after its format, its chip, and the title
/// data's line with where it comes from; measured by the player first, then the provider's details, then the name's
/// tags. Read from what is kept: browsing asks for nothing. The same as Android's `StreamSpecsAppTest` and
/// `VersionSpecsTest`. Invented names and example.com only.
@MainActor
final class StreamSpecsAppTests: XCTestCase {
    private var root: URL!

    // Two versions the names call the same, and a 4K Dolby Vision one.
    nonisolated private static let catalogue = """
    #EXTM3U
    #EXTINF:6000 group-title="Movies | Drama",Sand Harbor (2026)
    http://stream.example.com/sand-1.mkv
    #EXTINF:6000 group-title="Movies | Drama",Sand Harbor (2026)
    http://stream.example.com/sand-2.mkv
    #EXTINF:6000 group-title="Movies | Drama",Sand Harbor 4K DV (2026)
    http://stream.example.com/sand-dv.mkv
    """

    private let provider1080 = StreamSpecs(width: 1920, height: 802, range: .sdr, videoCodec: "H.264", audioCodec: "AAC", channels: 6)
    private let measured4K = StreamSpecs(
        width: 3840, height: 2160, range: .dolbyVision, videoCodec: "HEVC", audioCodec: "E-AC-3", channels: 6, atmos: true, source: .measured
    )

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoStreamSpecs-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: root)
    }

    private func model(adding: Bool = true) async throws -> AppModel {
        let model = AppModel(storageRoot: root, secrets: InMemorySecretStore(), reminderScheduler: InMemoryReminderScheduler())
        await model.bootstrap()
        if adding {
            let file = root.appendingPathComponent("list.m3u")
            try Data(Self.catalogue.utf8).write(to: file)
            try await model.addPlaylist(Playlist(name: "Test", source: .remote(file), autoRefreshHours: 0))
        }
        return model
    }

    private func movies(_ model: AppModel) throws -> (plain: Movie, again: Movie, dv: Movie) {
        let plain = model.content.movies.filter { $0.name == "Sand Harbor" }
        XCTAssertEqual(plain.count, 2)
        return (plain[0], plain[1], try XCTUnwrap(model.content.movies.first { $0.name == "Sand Harbor 4K DV" }))
    }

    /// Waits until `condition` holds (a task the model started), a few seconds at most.
    private func eventually(_ condition: () -> Bool) async {
        for _ in 0..<500 where !condition() { try? await Task.sleep(for: .milliseconds(10)) }
    }

    func testTheFactsLineShowsWhatTheVersionThatPlaysIsMadeOf() async throws {
        let model = try await model()
        let (plain, again, dv) = try movies(model)
        let versions = model.visibleVersions(of: plain)
        // Nothing known: the name's tags only (and nothing for a plain name).
        XCTAssertEqual(model.specBadges(for: dv, among: versions).badges, ["4K", "Dolby Vision"])
        XCTAssertEqual(model.specBadges(for: dv, among: versions).description, "4K, Dolby Vision")
        XCTAssertEqual(model.specBadges(for: plain, among: versions).badges, [])
        // The provider's details: a film in scope is 1080p.
        model.movieDetailsCache[plain.id] = MediaDetails(title: "Sand Harbor", streamSpecs: provider1080)
        XCTAssertEqual(model.specBadges(for: plain, among: versions).badges, ["1080p", "H.264", "5.1 AAC"])
        XCTAssertEqual(model.specBadges(for: plain, among: versions).description, "1080p, H.264, 5.1 surround sound, AAC")
        XCTAssertEqual(model.specSource(for: plain.id), .provider)
        XCTAssertNil(model.specSource(for: again.id))
    }

    func testTheChipsSayTheSpecsAndKeepVersionsWhoseSpecsDifferApart() async throws {
        let model = try await model()
        let (plain, again, dv) = try movies(model)
        let versions = model.visibleVersions(of: plain)
        XCTAssertEqual(model.versionChoices(versions).map(\.label), ["Standard", "4K Dolby Vision"])
        model.movieDetailsCache[plain.id] = MediaDetails(title: "Sand Harbor", streamSpecs: provider1080)
        model.recordMeasuredSpecs(measured4K, movieID: again.id, playlistID: try XCTUnwrap(model.activePlaylist).id)
        await eventually { model.specSource(for: again.id) == .measured }
        let choices = model.versionChoices(versions)
        XCTAssertEqual(choices.map(\.label), ["1080p · 5.1", "4K Dolby Vision · Atmos", "4K Dolby Vision"])
        XCTAssertEqual(choices.map { $0.members.map(\.id) }, [[plain.id], [again.id], [dv.id]])
    }

    func testTheTitleDataSaysEachVersionsSpecsAndWhereTheyComeFrom() async throws {
        let model = try await model()
        let (plain, again, _) = try movies(model)
        model.movieDetailsCache[plain.id] = MediaDetails(title: "Sand Harbor", streamSpecs: provider1080)
        model.recordMeasuredSpecs(measured4K, movieID: again.id, playlistID: try XCTUnwrap(model.activePlaylist).id)
        await eventually { model.specSource(for: again.id) == .measured }
        let item = try XCTUnwrap(model.downloadItem(for: plain))
        let maybe = await model.titleData(for: item)
        let data = try XCTUnwrap(maybe)
        XCTAssertEqual(data.versions.map(\.specs), [
            "1920×802 · 1080p · SDR · H.264 · 5.1 AAC", "3840×2160 · 4K · Dolby Vision · HEVC · 5.1 E-AC-3 · Atmos", "4K · Dolby Vision",
        ])
        XCTAssertEqual(data.versions.map(\.specSource), [.provider, .measured, nil])
        XCTAssertEqual(data.lines.first { $0.label == .specs(.provider) }?.value, "1920×802 · 1080p · SDR · H.264 · 5.1 AAC")
    }

    /// What the player measured is kept on the device by playlist and stream, and read back at the next start once a page
    /// needs it.
    func testWhatThePlayerMeasuredIsKeptAndReadBack() async throws {
        let first = try await model()
        let (plain, _, _) = try movies(first)
        first.recordMeasuredSpecs(StreamSpecs(width: 1920, height: 1080, range: .sdr, audioCodec: "AAC", channels: 2), movieID: plain.id, playlistID: try XCTUnwrap(first.activePlaylist).id)
        await eventually { first.specSource(for: plain.id) == .measured }
        await first.measuredSpecsWrite?.value
        let second = try await model(adding: false)
        await second.loadMeasuredSpecs()
        XCTAssertEqual(second.streamSpecs(for: plain.id)?.audioLabel, "2.0 AAC")
    }

    /// The chips are known by their first stream: a chip that splits as specs arrive changes no other chip's identity, so
    /// the one under the focus stays where it is. The same as Android's `versionKeys`.
    func testChipsKeepTheirIdentityWhenAnotherSplits() async throws {
        let model = try await model()
        let (plain, again, dv) = try movies(model)
        let versions = model.visibleVersions(of: plain)
        let before = VersionChips.keys(model.versionChoices(versions))
        XCTAssertEqual(before, ["version:\(plain.id)", "version:\(dv.id)"])
        model.movieDetailsCache[plain.id] = MediaDetails(title: "Sand Harbor", streamSpecs: provider1080)
        model.recordMeasuredSpecs(measured4K, movieID: again.id, playlistID: try XCTUnwrap(model.activePlaylist).id)
        await eventually { model.specSource(for: again.id) == .measured }
        let after = VersionChips.keys(model.versionChoices(versions))
        XCTAssertEqual(after, ["version:\(plain.id)", "version:\(again.id)", "version:\(dv.id)"])
        XCTAssertTrue(before.allSatisfy(after.contains), "the chips there before keep their identity")
    }

    /// Browsing (the chips, the badges, Continue Watching) asks the provider for nothing; a film's other versions are
    /// asked only from its page, never those without a provider's id or already known.
    func testBrowsingAsksForNoDetails() async throws {
        let model = try await model()
        let (plain, again, dv) = try movies(model)
        let before = model.movieDetailRequests
        let versions = model.visibleVersions(of: plain)
        _ = model.versionChoices(versions)
        _ = model.specBadges(for: dv, among: versions)
        _ = model.continueWatching(.movie)
        XCTAssertEqual(model.movieDetailRequests, before)
        // An M3U playlist's films have no provider's details to ask.
        let asked = await model.loadVersionDetails([again, dv])
        XCTAssertEqual(asked, 0)
        XCTAssertEqual(model.movieDetailRequests, before)
    }
}

/// A film's other versions, asked from its page: a few, paced, the first refusal stopping the round and remembered for the
/// session, nothing while the player is on top, kept apart from the pages viewers opened. The same as Android's
/// `VersionSpecsTest`. A panel on 127.0.0.1; invented names only.
@MainActor
final class VersionDetailsAppTests: XCTestCase {
    private var root: URL!

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoVersionDetails-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: root)
    }

    private func model(_ panel: VersionPanel) async throws -> AppModel {
        let base = try await panel.server.start()
        let model = AppModel(storageRoot: root, secrets: InMemorySecretStore(), reminderScheduler: InMemoryReminderScheduler())
        await model.bootstrap()
        try await model.addPlaylist(Playlist(
            name: "Panel", source: .xtream(XtreamCredentials(server: base, username: "viewer", password: "secret")), autoRefreshHours: 0
        ))
        return model
    }

    private func others(_ model: AppModel) throws -> [Movie] {
        let movies = model.content.movies.sorted { ($0.xtreamStreamID ?? 0) < ($1.xtreamStreamID ?? 0) }
        XCTAssertEqual(movies.count, VersionPanel.count)
        return Array(movies.dropFirst())
    }

    func testAPageAsksAFewVersionsPaced() async throws {
        let panel = try VersionPanel()
        let model = try await model(panel)
        let versions = try others(model)
        let start = Date()
        let asked = await model.loadVersionDetails(versions, gap: .milliseconds(50))
        XCTAssertEqual(asked, AppModel.versionsPerPage)
        XCTAssertEqual(panel.asked.count, 6)
        XCTAssertGreaterThanOrEqual(Date().timeIntervalSince(start), 0.25, "paced")
        // Opened again: the ones left.
        let more = await model.loadVersionDetails(versions, gap: .milliseconds(1))
        XCTAssertEqual(more, VersionPanel.count - 1 - 6)
    }

    func testARefusalStopsAndIsRemembered() async throws {
        let panel = try VersionPanel()
        let model = try await model(panel)
        let versions = try others(model)
        let refused = String(versions[1].xtreamStreamID ?? 0)
        panel.refuse(refused)
        let first = await model.loadVersionDetails(versions, gap: .milliseconds(1))
        XCTAssertEqual(first, 2)
        var seen = Set<String>()
        XCTAssertEqual(panel.asked.filter { seen.insert($0).inserted }, [String(versions[0].xtreamStreamID ?? 0), refused])
        let refusals = panel.asked.filter { $0 == refused }.count
        let second = await model.loadVersionDetails(versions, gap: .milliseconds(1), limit: 2)
        XCTAssertEqual(second, 2)
        XCTAssertEqual(panel.asked.filter { $0 == refused }.count, refusals, "not asked again this session")
    }

    func testNothingIsAskedWhileThePlayerIsOnTop() async throws {
        let panel = try VersionPanel()
        let model = try await model(panel)
        let versions = try others(model)
        model.isMultiviewPresented = true
        let round = Task { await model.loadVersionDetails(versions, gap: .milliseconds(1), limit: 2) }
        try await Task.sleep(for: .milliseconds(700))
        XCTAssertEqual(panel.asked, [], "nothing while the player is on top")
        model.isMultiviewPresented = false
        let asked = await round.value
        XCTAssertEqual(asked, 2, "the page goes on once back")
    }

    func testVersionsAreKeptApartFromThePagesOpened() async throws {
        let panel = try VersionPanel()
        let model = try await model(panel)
        let movies = model.content.movies.sorted { ($0.xtreamStreamID ?? 0) < ($1.xtreamStreamID ?? 0) }
        _ = await model.details(for: movies[0])
        await model.loadVersionDetails(Array(movies[1...2]), gap: .milliseconds(1))
        model.flush()
        await model.storedDetailsWrite?.value
        let playlist = try XCTUnwrap(model.activePlaylist)
        let archive = try XCTUnwrap(model.cacheStore.load(DetailsArchive.self, name: DetailsArchive.documentName(for: playlist.id)))
        XCTAssertEqual(Set(archive.movies.keys), [movies[0].id])
        XCTAssertEqual(Set(archive.versions.keys), [movies[1].id, movies[2].id])
    }
}

/// A panel with one film in `count` versions; it refuses the details of the ones named.
private final class VersionPanel: @unchecked Sendable {
    static let count = 9
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
        case "get_vod_categories": return .json(#"[{"category_id":"10","category_name":"Drama","parent_id":0}]"#)
        case "get_vod_streams":
            let streams = (1...Self.count).map { i in
                #"{"num":\#(i),"name":"Sand Harbor","stream_type":"movie","stream_id":\#(5000 + i),"category_id":"10","container_extension":"mkv","tmdb":"4242"}"#
            }
            return .json("[" + streams.joined(separator: ",") + "]")
        case "get_series_categories", "get_series": return .json("[]")
        case "get_vod_info":
            let id = request.query["vod_id"] ?? ""
            if lock.withLock({ refused.contains(id) }) { return LocalHTTPServer.Response(status: 503) }
            return .json(#"{"info":{"name":"Sand Harbor","video":{"codec_name":"h264","width":1920,"height":1080},"audio":{"codec_name":"aac","channels":2}},"movie_data":{"stream_id":"# + id + "}}")
        default: return .json("[]")
        }
    }
}
