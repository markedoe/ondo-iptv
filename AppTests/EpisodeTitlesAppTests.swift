import XCTest
import IPTVCore
@testable import Ondo

/// An episode reads the same on every screen that names it: its own title (TMDb's name, else the provider's without
/// the series' name, tags and code, else "Episode N" in the app's language), never the provider's whole name, and its
/// still from TMDb where the provider has none. The surfaces: the series' page and the player's queue (Up next) read
/// `AppModel.details(for:)`, i.e. `shown(_:of:)`; Continue Watching and an episode resumed at once read
/// `episodeLine(of:)`; search lists no episode. Same cases as Android's `EpisodeSurfacesTest`.
@MainActor
final class EpisodeTitlesAppTests: XCTestCase {
    private let seriesName = "|EN| Tom Clancy's Jack Ryan"

    private func makeModel() throws -> AppModel {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoEpisodes-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: root) }
        return AppModel(storageRoot: root, secrets: InMemorySecretStore(), reminderScheduler: InMemoryReminderScheduler())
    }

    func testAnEpisodeReadsTheSameOnEveryScreen() throws {
        let model = try makeModel()
        let series = Series(id: "s1", name: seriesName, categoryID: "c", xtreamSeriesID: 1)
        let episodes = (1...5).map { number in
            Episode(
                id: "30\(number)", title: "\(seriesName) S03E0\(number)", season: 3, number: number,
                streamURL: URL(string: "http://panel.example.com/series/u/p/30\(number).mp4")!,
                posterURL: number == 1 || number == 4 ? URL(string: "http://img.example.com/provider-\(number).jpg") : nil
            )
        }
        let details = SeriesDetails(info: MediaDetails(title: seriesName), seasons: [Season(number: 3, name: "Season 3", episodes: episodes)])
        model.tmdbMetadata.seasons[series.id] = [3: [
            TMDbEpisode(number: 1, name: "Falcon", stillPath: "/tmdb-1.jpg"),
            TMDbEpisode(number: 2, name: "Paralysis", stillPath: "/tmdb-2.jpg"),
            TMDbEpisode(number: 3, name: "Episode 3", stillPath: "/tmdb-3.jpg"),
            TMDbEpisode(number: 4, name: "Ghosts", stillPath: "/tmdb-4.jpg"),
            TMDbEpisode(number: 5),
        ]]

        // The series' page and the player's queue.
        let shown = model.shown(details, of: series).seasons[0].episodes
        let english = PlaceholderNames.app()
        XCTAssertEqual(shown.map(\.title), ["Falcon", "Paralysis", english.episode(3), "Ghosts", english.episode(5)])
        XCTAssertEqual(
            shown.map { $0.posterURL?.absoluteString },
            ["http://img.example.com/provider-1.jpg", "https://image.tmdb.org/t/p/w300/tmdb-2.jpg", "https://image.tmdb.org/t/p/w300/tmdb-3.jpg",
             "http://img.example.com/provider-4.jpg", nil]
        )
        XCTAssertFalse(shown.contains { $0.title.contains("Jack Ryan") }, "never the series' name")

        // Continue Watching (and an episode resumed at once), a line kept before this change included.
        let entry = HistoryEntry(
            reference: MediaReference(playlistID: UUID(), type: .series, itemID: series.id, episodeID: "302"),
            title: seriesName, subtitle: "S03E02 · \(seriesName) S03E02"
        )
        XCTAssertEqual(model.episodeLine(of: entry), "S03E02 · " + english.episode(2))
        let film = HistoryEntry(reference: MediaReference(playlistID: UUID(), type: .movie, itemID: "m"), title: "Film", subtitle: "4K")
        XCTAssertEqual(model.episodeLine(of: film), "4K", "a film's line as it is")
    }
}
