import XCTest
import IPTVCore
@testable import Ondo

/// Regression guard for hidden languages, on every list of titles at once (the Android `noListShowsTheTitlesOfAHiddenLanguage`).
/// A language hidden as a whole leaves its categories, and every title and channel whose own name carries it ("|PT| …",
/// "PT - …", "[PT] …") whatever its category, out of:
///  - Home: carousel, new movies, new series, recently added;
///  - Movies, Series and Live TV: every category's list and the lists of all of them;
///  - Search (movies, series, channels) and the search by release year (a year's titles);
///  - similar titles, award winners;
///  - My List and favourite channels.
/// The one exception, the product rule: Continue watching keeps a title the viewer already started.
/// A new screen that lists titles is added to `surfaces` here.
@MainActor
final class HiddenLanguageTests: XCTestCase {
    private var root: URL!

    nonisolated private static let thisYear = Calendar.current.component(.year, from: .now)

    /// Titles of the language's categories hold "Saudade"; titles named with the language in categories of no language
    /// ("Concerts", "Clássicos", "Rádio") hold "Carreira". "Portugal: The Man" and "PTSD" only look like a tag.
    nonisolated private static let catalogue = """
    #EXTM3U
    #EXTINF:-1 tvg-type="live" group-title="News",Test News
    http://127.0.0.1:9/news.m3u8
    #EXTINF:-1 tvg-type="live" group-title="|PT| Canais",|PT| Saudade TV
    http://127.0.0.1:9/pt-tv.m3u8
    #EXTINF:-1 tvg-type="live" group-title="Rádio",[PT] Carreira Rádio
    http://127.0.0.1:9/pt-radio.m3u8
    #EXTINF:6000 tvg-type="movie" group-title="Movies | Drama",Quiet Water (\(thisYear))
    http://127.0.0.1:9/quiet.mp4
    #EXTINF:6000 tvg-type="movie" group-title="|PT| Filmes",|PT| Saudade do Mar (\(thisYear))
    http://127.0.0.1:9/saudade.mp4
    #EXTINF:6000 tvg-type="movie" group-title="Concerts",|PT| Tony Carreira – Ao Vivo em Lisboa (\(thisYear))
    http://127.0.0.1:9/carreira.mp4
    #EXTINF:6000 tvg-type="movie" group-title="Concerts",Portugal: The Man – Live (\(thisYear))
    http://127.0.0.1:9/portugal.mp4
    #EXTINF:6000 tvg-type="movie" group-title="Concerts",PTSD Stories (\(thisYear))
    http://127.0.0.1:9/ptsd.mp4
    #EXTINF:2700 tvg-type="series" group-title="Series | Drama",Harbor (\(thisYear)) S01E01
    http://127.0.0.1:9/harbor101.mp4
    #EXTINF:2700 tvg-type="series" group-title="|PT| Séries",|PT| Saudade Nocturna (\(thisYear)) S01E01
    http://127.0.0.1:9/nocturna101.mp4
    #EXTINF:2700 tvg-type="series" group-title="Clássicos",PT - Carreira Antiga (\(thisYear)) S01E01
    http://127.0.0.1:9/antiga101.mp4
    #EXTINF:2700 tvg-type="series" group-title="Clássicos",Classic Days (\(thisYear)) S01E01
    http://127.0.0.1:9/classic101.mp4
    """

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoHiddenLanguage-\(UUID().uuidString)")
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

    /// Every list of titles, by its name: the names it shows.
    private func surfaces(_ model: AppModel) -> [String: [String]] {
        func names(_ items: [ShowcaseItem]) -> [String] { items.map(\.title) }
        let movieCategories = model.content.movieCategories.map { Optional($0) } + [nil]
        let seriesCategories = model.content.seriesCategories.map { Optional($0) } + [nil]
        let liveCategories = model.content.liveCategories.map { Optional($0) } + [nil]
        let anyMovie = model.content.movies.first { $0.name.hasPrefix("Portugal") }
        let anySeries = model.content.series.first { $0.name == "Classic Days" }
        var searched: [String] = []
        for query in ["a", "o", "saudade", "carreira", "tony"] {
            let found = model.search(query)
            searched += found.movies.map(\.name) + found.series.map(\.name) + found.channels.map(\.name)
        }
        let year = model.search("", releasedIn: Self.thisYear...Self.thisYear)
        return [
            "carousel": names(model.heroItems(limit: 20)),
            "new movies": names(model.newMovies(limit: 50)),
            "new series": names(model.newSeries(limit: 50)),
            "recently added": names(model.recentlyAdded()),
            "movies": movieCategories.flatMap { model.movies(in: $0, sort: .name).map(\.name) },
            "series": seriesCategories.flatMap { model.series(in: $0, sort: .name).map(\.name) },
            "live": liveCategories.flatMap { model.channels(in: $0).map(\.name) },
            "search": searched,
            "year": year.movies.map(\.name) + year.series.map(\.name),
            "similar": (anyMovie.map { model.similarMovies(to: $0).map(\.name) } ?? [])
                + (anySeries.map { model.similarSeries(to: $0).map(\.name) } ?? []),
            "award winners": model.awardWinners().map(\.name),
            "my list": names(model.myList()),
            "favourite channels": model.favoriteChannels().map(\.name),
        ]
    }

    func testNoListShowsTheTitlesOfAHiddenLanguage() async throws {
        let model = try await model()
        let concert = try XCTUnwrap(model.content.movies.first { $0.name.contains("Carreira") })
        let station = try XCTUnwrap(model.content.channels.first { $0.name.contains("Carreira") })
        // Saved and started while the language is shown.
        model.toggleFavorite(concert)
        model.toggleFavorite(station)
        let item = try XCTUnwrap(model.downloadItem(for: concert))
        model.recordStart(item)
        model.recordProgress(item, position: 1_200, duration: 6_000)

        // Shown: the lists that can list them do, so the checks below prove something.
        let shown = surfaces(model)
        for list in ["movies", "series", "live", "search", "year", "my list", "favourite channels"] {
            XCTAssertTrue(shown[list, default: []].contains { $0.contains("Saudade") || $0.contains("Carreira") }, "\(list) lists the language while it is shown")
        }
        for list in ["movies", "series", "live", "search", "year"] {
            XCTAssertTrue(shown[list, default: []].contains { $0.contains("Carreira") }, "\(list) lists the titles named with the language while it is shown")
        }

        model.setHidden(true, tag: "PT")
        let hidden = surfaces(model)
        for (list, names) in hidden {
            XCTAssertFalse(names.contains { $0.contains("Saudade") || $0.contains("Carreira") }, "\(list) still shows the hidden language: \(names)")
        }
        // Names that only start like a tag stay.
        for name in ["Portugal: The Man – Live", "PTSD Stories"] {
            for list in ["movies", "search", "year"] {
                XCTAssertTrue(hidden[list, default: []].contains(name), "\(name) stays in \(list)")
            }
        }
        // The exception: Continue watching keeps the title already started.
        XCTAssertEqual(model.continueWatching().map(\.reference.itemID), [concert.id], "Continue watching keeps it")

        // Shown again.
        model.setHidden(false, tag: "PT")
        XCTAssertTrue(surfaces(model)["movies", default: []].contains { $0.contains("Carreira") })
    }

    /// The same lists, once the language is hidden on another device in this profile and sync applies it (docs/SYNC.md,
    /// "Hidden categories"): as a language, or its categories one by one; then shown again there.
    func testNoListShowsTheTitlesOfALanguageHiddenOnAnotherDevice() async throws {
        let model = try await model()
        let playlist = try XCTUnwrap(model.activePlaylist)
        let concert = try XCTUnwrap(model.content.movies.first { $0.name.contains("Carreira") })
        model.toggleFavorite(concert)
        /// What another device in the same profile sends: this device's data with `change` made there.
        func elsewhere(_ change: (inout SyncSnapshot) -> Void) -> SyncSnapshot {
            var snapshot = model.syncSnapshot()
            snapshot.device = "other"
            change(&snapshot)
            return snapshot
        }
        let languageCategories = (model.content.movieCategories + model.content.seriesCategories + model.content.liveCategories)
            .filter { $0.name.contains("|PT|") }
        XCTAssertFalse(languageCategories.isEmpty)
        let ways: [(String, [SyncHidden], (Set<String>) -> Bool)] = [
            ("as a language", [SyncHidden(kind: SyncHidden.tag, value: "PT", hiddenAt: model.syncStamp().syncMilliseconds)],
             { names in names.contains { $0.contains("Saudade") || $0.contains("Carreira") } }),
            ("one by one", languageCategories.map {
                SyncHidden.forCategory(playlistID: playlist.id.uuidString, categoryID: $0.id, hiddenAt: model.syncStamp().syncMilliseconds)
            }, { names in names.contains { $0.contains("Saudade") } }),
        ]
        for (how, records, shows) in ways {
            await model.applySync(elsewhere { $0.hiddenCategories = records })
            for (list, names) in surfaces(model) {
                XCTAssertFalse(shows(Set(names)), "hidden \(how) on another device, \(list) still shows it: \(names)")
            }
            // Shown again there: every list shows it again.
            let shownAt = model.syncStamp().syncMilliseconds
            await model.applySync(elsewhere { snapshot in
                snapshot.hiddenCategories = []
                for record in records { snapshot.deleted[record.key] = shownAt }
            })
            let again = surfaces(model)
            for list in ["movies", "series", "live", "search", "year"] {
                XCTAssertTrue(again[list, default: []].contains { $0.contains("Saudade") }, "\(list) shows it again, shown again \(how)")
            }
        }
    }
}
