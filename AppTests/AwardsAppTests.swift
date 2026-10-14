import XCTest
import IPTVCore
@testable import Ondo

/// The awards bundled with the app are read, and a playlist's movies get theirs (no network involved).
@MainActor
final class AwardsAppTests: XCTestCase {
    private var root: URL!

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: root)
    }

    func testTheBundledAwardsAreRead() {
        let catalogue = AwardsCatalogue.bundled
        XCTAssertNotNil(catalogue.award("palme-dor"))
        XCTAssertGreaterThan(catalogue.films.count, 1000)
        XCTAssertTrue(catalogue.groups.contains("oscars"))
    }

    func testAMovieOfThePlaylistGetsItsAwards() async throws {
        let file = root.appendingPathComponent("list.m3u")
        try Data("""
        #EXTM3U
        #EXTINF:5400 group-title="Movies | Drama",Pulp Fiction (1994)
        http://127.0.0.1:9/movie/pulp.mp4
        #EXTINF:5400 group-title="Movies | Drama",Northern Lights (2025)
        http://127.0.0.1:9/movie/northern.mp4
        """.utf8).write(to: file)
        let model = AppModel(storageRoot: root, secrets: InMemorySecretStore(), reminderScheduler: InMemoryReminderScheduler())
        await model.bootstrap()
        try await model.addPlaylist(Playlist(name: "Test", source: .remote(file), autoRefreshHours: 0))
        await model.awardsTask?.value  // matched after the catalogue is shown

        let pulp = try XCTUnwrap(model.content.movies.first { $0.name == "Pulp Fiction" })
        let awards = model.awards(for: pulp)
        XCTAssertEqual(awards.first?.won, true)
        XCTAssertTrue(awards.contains { $0.year == 1994 && $0.won && $0.isFestival }, "Palme d'Or 1994")
        XCTAssertEqual(model.awardWinners().map(\.name), ["Pulp Fiction"])
        XCTAssertTrue(model.awardGroups().contains("cannes"))
        XCTAssertEqual(model.search("", releasedIn: nil, awardGroup: "cannes", winnersOnly: true).movies.map(\.name), ["Pulp Fiction"])
    }
}
