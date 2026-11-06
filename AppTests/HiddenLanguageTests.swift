import XCTest
import IPTVCore
@testable import Ondo

/// Regression guard for hidden languages, on every list of titles at once (the Android `noListShowsTheTitlesOfAHiddenLanguage`).
/// A language hidden as a whole leaves its categories, and every title and channel whose own name carries it ("|PT| …",
/// "PT - …", "[PT] …") whatever its category, out of:
///  - Home: carousel, new movies, new series, recently added, trending;
///  - Movies, Series and Live TV: every category's list and the lists of all of them;
///  - Search (movies, series, channels) and the search by release year (a year's titles);
///  - similar titles, award winners;
///  - My List and favourite channels;
///  - a selection's row (Home) and its See All page, and the tags a new selection offers.
/// The one exception, the product rule: Continue watching keeps a title the viewer already started.
/// A new screen that lists titles is added to `surfaces` here.
@MainActor
final class HiddenLanguageTests: XCTestCase {
    private var root: URL!

    nonisolated private static let thisYear = Calendar.current.component(.year, from: .now)

    /// Titles of the language's categories hold "Saudade"; titles named with the language, at the start or at the end
    /// ("… (PT)", "… [PT]"), in categories of no language ("Concerts", "Clássicos", "Rádio") hold "Carreira".
    /// "Portugal: The Man", "PTSD" and "Quiet Office (UK)" only look like a tag.
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
    #EXTINF:6000 tvg-type="movie" group-title="Concerts",Tony Carreira – Ao Vivo no Porto (PT) (\(thisYear))
    http://127.0.0.1:9/porto.mp4
    #EXTINF:6000 tvg-type="movie" group-title="Concerts",Portugal: The Man – Live (\(thisYear))
    http://127.0.0.1:9/portugal.mp4
    #EXTINF:6000 tvg-type="movie" group-title="Concerts",Quiet Office (UK) (\(thisYear))
    http://127.0.0.1:9/office.mp4
    #EXTINF:6000 tvg-type="movie" group-title="Concerts",PTSD Stories (\(thisYear))
    http://127.0.0.1:9/ptsd.mp4
    #EXTINF:2700 tvg-type="series" group-title="Series | Drama",Harbor (\(thisYear)) S01E01
    http://127.0.0.1:9/harbor101.mp4
    #EXTINF:2700 tvg-type="series" group-title="|PT| Séries",|PT| Saudade Nocturna (\(thisYear)) S01E01
    http://127.0.0.1:9/nocturna101.mp4
    #EXTINF:2700 tvg-type="series" group-title="Clássicos",PT - Carreira Antiga (\(thisYear)) S01E01
    http://127.0.0.1:9/antiga101.mp4
    #EXTINF:2700 tvg-type="series" group-title="Clássicos",Carreira Velha [PT] (\(thisYear)) S01E01
    http://127.0.0.1:9/velha101.mp4
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

    /// Every list of titles, by its name: the names it shows. The tabs list the categories shown (`categories(for:)`),
    /// as the screens do, and all of them: a category hidden on its own is not one of the tab's lists.
    private func surfaces(_ model: AppModel) -> [String: [String]] {
        func names(_ items: [ShowcaseItem]) -> [String] { items.map(\.title) }
        let movieCategories = model.categories(for: .movie).map { Optional($0) } + [nil]
        let seriesCategories = model.categories(for: .series).map { Optional($0) } + [nil]
        let liveCategories = model.categories(for: .live).map { Optional($0) } + [nil]
        let anyMovie = model.content.movies.first { $0.name.hasPrefix("Portugal") }
        let anySeries = model.content.series.first { $0.name == "Classic Days" }
        var searched: [String] = []
        for query in ["a", "o", "saudade", "carreira", "tony"] {
            let found = model.search(query)
            searched += found.movies.map(\.name) + found.series.map(\.name) + found.channels.map(\.name)
        }
        let year = model.search("", releasedIn: Self.thisYear...Self.thisYear)
        // A selection of this decade, on Home: its row and its See All page; and the genres a new selection offers.
        var selectionRow: [String] = []
        var selectionPage: [String] = []
        if let selection = model.profileSelections.first {
            selectionRow = names(model.selectionTitles(selection, tab: SyncRows.home))
            let page: SearchResults = model.titles(for: .selection(selection.id))
            selectionPage = page.movies.map(\.name) + page.series.map(\.name)
        }
        let selectionTags: [String] = model.selectionChoices().genres.map(\.name)
        return [
            "carousel": names(model.heroItems(limit: 20)),
            "new movies": names(model.newMovies(limit: 50)),
            "new series": names(model.newSeries(limit: 50)),
            "carousel candidates": names(model.releasesToAsk()),
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
            "trending": model.trendingTitles().map { (match: TrendingMatch) -> String in
                switch match {
                case .movie(let movie, _): return movie.name
                case .series(let series, _): return series.name
                }
            },
            "selection row": selectionRow,
            "selection page": selectionPage,
            "selection tags": selectionTags,
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
        // Every title released yesterday, with artwork: the carousel would feature them all.
        let yesterday = RecentReleases.dayString(RecentReleases.today(.now) - 1)
        for title in model.content.movies.map(ShowcaseItem.movie) + model.content.series.map(ShowcaseItem.series) {
            for key in model.metadataKeys(for: title) {
                model.tmdbMetadata.titles[key] = TitleMetadata(key: key, kind: MetadataKey.kind(title.contentType),
                                                               backdropURL: URL(string: "https://example.com/backdrop.jpg"), fetchedAt: .now, releaseDate: yesterday)
            }
        }
        model.tmdbMetadata.carouselRevision += 1
        // A selection of the decade they came out in; a genre only the titles of the language have, one named with it.
        XCTAssertNotNil(model.saveSelection(id: nil, name: "Decade", tags: [Selections.decade(Self.thisYear)], all: false, tabs: [SyncRows.home]))
        model.movieDetailsCache[concert.id] = MediaDetails(title: concert.name, genre: "Carreira")
        if let saudade = model.content.movies.first(where: { $0.name.contains("Saudade") }) {
            model.movieDetailsCache[saudade.id] = MediaDetails(title: saudade.name, genre: "Saudade")
        }

        // Shown: the lists that can list them do, so the checks below prove something.
        let shown = surfaces(model)
        for ending in ["Ao Vivo no Porto", "Carreira Velha"] {
            for list in ["movies", "series", "search", "year"] where list != (ending.hasPrefix("Ao") ? "series" : "movies") {
                XCTAssertTrue(shown[list, default: []].contains { $0.contains(ending) }, "\(list) lists \(ending) while the language is shown")
            }
        }
        for list in ["carousel", "carousel candidates", "movies", "series", "live", "search", "year", "my list", "favourite channels",
                     "selection row", "selection page", "selection tags"] {
            XCTAssertTrue(shown[list, default: []].contains { $0.contains("Saudade") || $0.contains("Carreira") }, "\(list) lists the language while it is shown")
        }
        for list in ["movies", "series", "live", "search", "year", "selection row", "selection page", "selection tags"] {
            XCTAssertTrue(shown[list, default: []].contains { $0.contains("Carreira") }, "\(list) lists the titles named with the language while it is shown")
        }

        model.setHidden(true, tag: "PT")
        let hidden = surfaces(model)
        for (list, names) in hidden {
            XCTAssertFalse(names.contains { $0.contains("Saudade") || $0.contains("Carreira") }, "\(list) still shows the hidden language: \(names)")
        }
        // Names that only start or end like a tag stay.
        for name in ["Portugal: The Man – Live", "PTSD Stories", "Quiet Office"] {
            for list in ["movies", "year"] {
                XCTAssertTrue(hidden[list, default: []].contains { $0.hasPrefix(name) }, "\(name) stays in \(list)")
            }
        }
        for name in ["Portugal: The Man – Live", "PTSD Stories"] {
            XCTAssertTrue(hidden["search", default: []].contains(name), "\(name) stays in search")
        }
        // The exception: Continue watching keeps the title already started.
        XCTAssertEqual(model.continueWatching().map(\.reference.itemID), [concert.id], "Continue watching keeps it")

        // Shown again.
        model.setHidden(false, tag: "PT")
        XCTAssertTrue(surfaces(model)["movies", default: []].contains { $0.contains("Carreira") })
    }

    /// The same lists, for the titles of categories of no language whose provider's description is in the hidden
    /// language (an Xtream panel's lists carry descriptions; an M3U playlist's do not): a Portuguese series of "Netflix"
    /// leaves every list, the carousel first, once Portuguese is hidden; a title of a category of a language the viewer
    /// keeps ("|FR| Films") stays, whatever its description.
    func testNoListShowsTheTitlesDescribedInAHiddenLanguage() async throws {
        let panel = try DescribedPanel(year: Self.thisYear)
        let base = try await panel.server.start()
        let model = AppModel(storageRoot: root, secrets: InMemorySecretStore(), reminderScheduler: InMemoryReminderScheduler())
        await model.bootstrap()
        try await model.addPlaylist(Playlist(
            name: "Panel", source: .xtream(XtreamCredentials(server: base, username: "viewer", password: "secret")), autoRefreshHours: 0
        ))
        XCTAssertEqual(model.content.series.first { $0.name == "Carreira Nova" }?.plot, DescribedPanel.portuguese, "the list's description")
        let yesterday = RecentReleases.dayString(RecentReleases.today(.now) - 1)
        for title in model.content.movies.map(ShowcaseItem.movie) + model.content.series.map(ShowcaseItem.series) {
            for key in model.metadataKeys(for: title) {
                model.tmdbMetadata.titles[key] = TitleMetadata(key: key, kind: MetadataKey.kind(title.contentType),
                                                               backdropURL: URL(string: "https://example.com/backdrop.jpg"), fetchedAt: .now, releaseDate: yesterday)
            }
        }
        model.tmdbMetadata.carouselRevision += 1

        let shown = surfaces(model)
        for list in ["carousel", "movies", "series", "search", "year"] {
            XCTAssertTrue(shown[list, default: []].contains { $0.contains("Carreira") }, "\(list) lists the described titles while the language is shown")
        }
        model.setHidden(true, tag: "PT")
        // The descriptions are read off the main actor once a hidden language needs them; the lists follow.
        _ = model.hiddenByName
        await model.plotLanguagesTask?.value
        let hidden = surfaces(model)
        for (list, names) in hidden {
            XCTAssertFalse(names.contains { $0.contains("Carreira") }, "\(list) still shows a title described in the hidden language: \(names)")
        }
        for list in ["movies", "search", "year"] {
            XCTAssertTrue(hidden[list, default: []].contains("Les Amis du Port"), "a kept language's category keeps its title in \(list)")
            XCTAssertTrue(hidden[list, default: []].contains("Quiet Water"), "a title described in English stays in \(list)")
        }
        XCTAssertTrue(hidden["series", default: []].contains("Harbor"))
    }

    /// Regression guard: a language hides by description only when none of its tags in the catalogue is kept, and
    /// English never: with "|PT|" categories kept, hiding BR (as "Show only your languages?" can) leaves the titles
    /// described in Portuguese everywhere, and neither BR nor EN reads a single description (no scan, no wait).
    func testAKeptTagOfTheLanguageOrEnglishHidesNoDescription() async throws {
        let panel = try DescribedPanel(year: Self.thisYear, portugueseCategories: true)
        let base = try await panel.server.start()
        let model = AppModel(storageRoot: root, secrets: InMemorySecretStore(), reminderScheduler: InMemoryReminderScheduler())
        await model.bootstrap()
        try await model.addPlaylist(Playlist(
            name: "Panel", source: .xtream(XtreamCredentials(server: base, username: "viewer", password: "secret")), autoRefreshHours: 0
        ))
        XCTAssertNotNil(model.categoryTags.categories["PT"], "the catalogue's categories carry PT")
        model.setHidden(true, tags: ["BR", "EN", "UK", "US"])
        let kept = surfaces(model)
        XCTAssertNil(model.plotLanguagesTask, "no description read while no hidden language hides by description")
        for list in ["movies", "series", "search", "year"] {
            XCTAssertTrue(kept[list, default: []].contains { $0.contains("Carreira") }, "\(list) keeps the Portuguese descriptions while PT is kept")
            XCTAssertTrue(kept[list, default: []].contains { $0 == "Quiet Water" || $0 == "Harbor" }, "\(list) keeps the English descriptions")
        }
        model.setHidden(true, tag: "PT")
        _ = model.hiddenByName
        await model.plotLanguagesTask?.value
        for (list, names) in surfaces(model) {
            XCTAssertFalse(names.contains { $0.contains("Carreira") }, "\(list) still shows a Portuguese description once PT is hidden too: \(names)")
        }
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

/// An Xtream panel whose lists carry descriptions: in Portuguese ("Carreira …") in categories of no language and in a
/// French one, in English otherwise.
private final class DescribedPanel: @unchecked Sendable {
    static let portuguese = "Depois da morte da mãe, Luísa herda uma casa antiga no litoral. Mas os vizinhos não querem que ela fique, e logo ela descobre o motivo."
    static let english = "After the death of her mother, Lucy inherits an old house on the coast. But the neighbours do not want her to stay, and she soon finds out why."
    private(set) var server: LocalHTTPServer!
    private let year: Int
    /// "|PT| Filmes" and "|PT| Séries" besides, each with a title: PT is then a tag of the catalogue.
    private let portugueseCategories: Bool

    init(year: Int, portugueseCategories: Bool = false) throws {
        self.year = year
        self.portugueseCategories = portugueseCategories
        server = try LocalHTTPServer { [unowned self] request in self.answer(request) }
    }

    private func movie(_ id: Int, _ name: String, category: String, plot: String) -> String {
        #"{"num":\#(id),"name":"\#(name)","stream_type":"movie","stream_id":\#(id),"category_id":"\#(category)","container_extension":"mp4","#
            + #""year":"\#(year)","plot":"\#(plot)"}"#
    }

    private func series(_ id: Int, _ name: String, category: String = "20", plot: String) -> String {
        #"{"num":\#(id),"name":"\#(name)","series_id":\#(id),"category_id":"\#(category)","year":"\#(year)","plot":"\#(plot)"}"#
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
            let extra: String = portugueseCategories ? #",{"category_id":"12","category_name":"|PT| Filmes","parent_id":0}"# : ""
            return .json(#"[{"category_id":"10","category_name":"Concerts","parent_id":0},{"category_id":"11","category_name":"|FR| Films","parent_id":0}"#
                + extra + "]")
        case "get_vod_streams":
            var movies: [String] = [
                movie(5001, "Carreira no Coliseu", category: "10", plot: Self.portuguese),
                movie(5002, "Les Amis du Port", category: "11", plot: Self.portuguese),
                movie(5003, "Quiet Water", category: "10", plot: Self.english),
            ]
            if portugueseCategories { movies.append(movie(5004, "Saudade do Mar", category: "12", plot: Self.portuguese)) }
            return .json("[" + movies.joined(separator: ",") + "]")
        case "get_series_categories":
            let extra: String = portugueseCategories ? #",{"category_id":"21","category_name":"|PT| Séries","parent_id":0}"# : ""
            return .json(#"[{"category_id":"20","category_name":"Netflix","parent_id":0}"# + extra + "]")
        case "get_series":
            var shows: [String] = [series(9001, "Carreira Nova", plot: Self.portuguese), series(9002, "Harbor", plot: Self.english)]
            if portugueseCategories { shows.append(series(9003, "Saudade da Serra", category: "21", plot: Self.portuguese)) }
            return .json("[" + shows.joined(separator: ",") + "]")
        default: return .json("[]")
        }
    }
}
