import XCTest
import IPTVCore
@testable import Ondo

/// The title data of More, Stream Statistics: a film's raw names, categories and tags, each version's chip, the one
/// playing and the one played first. The same as Android's `TitleDataTest`. Invented names and example.com only.
@MainActor
final class TitleDataAppTests: XCTestCase {
    private var root: URL!

    // The case of the TV: a plain version, a 4K Dolby Vision one and another plain one, only their containers differ.
    nonisolated private static let catalogue = """
    #EXTM3U
    #EXTINF:6000 group-title="Movies | Drama",Copper Valley (2026)
    http://stream.example.com/copper.mp4
    #EXTINF:6000 group-title="Movies | Drama",Copper Valley 4K DV (2026)
    http://stream.example.com/copper-dv.mkv
    #EXTINF:6000 group-title="Movies | Drama",Copper Valley (2026)
    http://stream.example.com/copper-2.mkv
    """

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoTitleData-\(UUID().uuidString)")
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

    func testAFilmShowsItsVersionsAndTheirChips() async throws {
        let model = try await model()
        let dv = try XCTUnwrap(model.content.movies.first { $0.name == "Copper Valley 4K DV" })
        let item = try XCTUnwrap(model.downloadItem(for: dv))
        let data = try XCTUnwrap(model.titleData(for: item))
        XCTAssertEqual(data.lines.first { $0.label == .raw("name") }?.value, "Copper Valley 4K DV")
        XCTAssertEqual(data.lines.first { $0.label == .raw("year") }?.value, "2026")
        XCTAssertEqual(data.lines.first { $0.label == .raw("container_extension") }?.value, "mkv → MKV")
        XCTAssertEqual(data.lines.first { $0.label == .tags }?.value, "uhd, dolbyVision")
        XCTAssertEqual(data.versions.map(\.name), ["Copper Valley", "Copper Valley 4K DV", "Copper Valley"])
        XCTAssertEqual(data.versions.map(\.chip), ["Standard · MP4", "4K Dolby Vision", "Standard · MKV"])
        XCTAssertEqual(data.versions.map(\.playing), [false, true, false])
        XCTAssertEqual(data.versions.filter(\.first).count, 1)
        XCTAssertNotNil(data.reason)
        // Never an address.
        let texts = data.lines.map(\.value) + data.versions.flatMap { [$0.chip, $0.name, $0.category, $0.id, $0.container, $0.tags].compactMap { $0 } }
        XCTAssertFalse(texts.contains { $0.contains("http") || $0.contains("example.com") }, "\(texts)")
    }
}
