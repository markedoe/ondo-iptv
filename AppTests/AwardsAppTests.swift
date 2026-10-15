import XCTest
import IPTVCore
@testable import Ondo

/// The awards bundled with the app are read, a playlist's movies get theirs, and a newer file replaces them (read from
/// a local file: no network involved).
@MainActor
final class AwardsAppTests: XCTestCase {
    private var root: URL!

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: root)
        AwardsCatalogue.current = .bundled
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

    func testTheValuesOfAPageOpenTheTitlesSharingThem() async throws {
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
        await model.awardsTask?.value
        let pulp = try XCTUnwrap(model.content.movies.first { $0.name == "Pulp Fiction" })

        XCTAssertEqual(model.titles(for: .award("palme-dor")).movies.map(\.name), ["Pulp Fiction"])
        XCTAssertEqual(model.entry(of: pulp, for: .award("palme-dor"))?.won, true)
        XCTAssertEqual(model.titles(for: .festival("cannes")).movies.map(\.name), ["Pulp Fiction"])
        XCTAssertEqual(model.titles(for: .year(2025)).movies.map(\.name), ["Northern Lights"])

        // A movie's genre is known once its page was opened.
        XCTAssertTrue(model.titles(for: .genre("Crime")).isEmpty)
        model.movieDetailsCache[pulp.id] = MediaDetails(title: "Pulp Fiction", genre: "Crime, Drama")
        XCTAssertEqual(model.titles(for: .genre("crime")).movies.map(\.name), ["Pulp Fiction"])
        XCTAssertEqual(AppModel.genres(in: "Action / Adventure, Sci-Fi"), ["Action", "Adventure", "Sci-Fi"])
    }

    /// A newer file than the bundled one: "Northern Lights" won the Golden Bear in 2099, and a thousand other films.
    private func newerAwards(generated: String, films: Int = 1_000) throws -> URL {
        var list: [[String: Any]] = [["titles": ["Northern Lights"], "year": 2025, "awards": [["golden-bear", 2099, 1]]]]
        for index in 0..<films { list.append(["titles": ["Film \(index)"], "year": 1990, "awards": [["golden-bear", 1990, 0]]]) }
        let document: [String: Any] = [
            "generated": generated,
            "awards": [["id": "golden-bear", "group": "berlin", "names": ["en": "Golden Bear"]]],
            "films": list,
        ]
        let url = root.appendingPathComponent("awards-\(generated).json")
        try JSONSerialization.data(withJSONObject: document).write(to: url)
        return url
    }

    func testANewerFileReplacesTheBundledAwardsOnceAWeek() async throws {
        let file = root.appendingPathComponent("list.m3u")
        try Data("#EXTM3U\n#EXTINF:5400 group-title=\"Movies\",Northern Lights (2025)\nhttp://127.0.0.1:9/movie/northern.mp4\n".utf8).write(to: file)
        let model = AppModel(storageRoot: root, secrets: InMemorySecretStore(), reminderScheduler: InMemoryReminderScheduler())
        await model.bootstrap()
        try await model.addPlaylist(Playlist(name: "Test", source: .remote(file), autoRefreshHours: 0))
        let now = Date()

        await model.updateAwardsIfNeeded(from: try newerAwards(generated: "2099-01-01"), now: now)
        await model.awardsTask?.value
        XCTAssertEqual(AwardsCatalogue.current.generated, "2099-01-01")
        let northern = try XCTUnwrap(model.content.movies.first { $0.name == "Northern Lights" })
        XCTAssertEqual(model.awards(for: northern).map(\.year), [2099])

        // Within the week, nothing is asked; a partial file or an older one is never used.
        await model.updateAwardsIfNeeded(from: try newerAwards(generated: "2099-06-01"), now: now.addingTimeInterval(3600))
        XCTAssertEqual(AwardsCatalogue.current.generated, "2099-01-01")
        await model.updateAwardsIfNeeded(from: try newerAwards(generated: "2099-07-01", films: 10), now: now.addingTimeInterval(8 * 86_400))
        XCTAssertEqual(AwardsCatalogue.current.generated, "2099-01-01")

        // Turned off: no download.
        model.updateSettings { $0.updateAwards = false }
        await model.updateAwardsIfNeeded(from: try newerAwards(generated: "2099-08-01"), now: now.addingTimeInterval(30 * 86_400))
        XCTAssertEqual(AwardsCatalogue.current.generated, "2099-01-01")

        // The download is kept: the next launch starts from it.
        AwardsCatalogue.current = .bundled
        model.loadDownloadedAwards()
        XCTAssertEqual(AwardsCatalogue.current.generated, "2099-01-01")
    }
}
