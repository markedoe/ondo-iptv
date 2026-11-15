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
        model.recordMeasuredSpecs(measured4K, movieID: again.id)
        await eventually { model.specSource(for: again.id) == .measured }
        let choices = model.versionChoices(versions)
        XCTAssertEqual(choices.map(\.label), ["1080p · 5.1", "4K Dolby Vision · Atmos", "4K Dolby Vision"])
        XCTAssertEqual(choices.map { $0.members.map(\.id) }, [[plain.id], [again.id], [dv.id]])
    }

    func testTheTitleDataSaysEachVersionsSpecsAndWhereTheyComeFrom() async throws {
        let model = try await model()
        let (plain, again, _) = try movies(model)
        model.movieDetailsCache[plain.id] = MediaDetails(title: "Sand Harbor", streamSpecs: provider1080)
        model.recordMeasuredSpecs(measured4K, movieID: again.id)
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
        first.recordMeasuredSpecs(StreamSpecs(width: 1920, height: 1080, range: .sdr, audioCodec: "AAC", channels: 2), movieID: plain.id)
        await eventually { first.specSource(for: plain.id) == .measured }
        await first.measuredSpecsWrite?.value
        let second = try await model(adding: false)
        await second.loadMeasuredSpecs()
        XCTAssertEqual(second.streamSpecs(for: plain.id)?.audioLabel, "2.0 AAC")
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
