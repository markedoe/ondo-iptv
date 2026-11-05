import XCTest
import IPTVCore
@testable import Ondo

/// The grids sorted by rating: TMDb's weighted rating first with the viewer's token, the provider's otherwise. The
/// order itself is tested in IPTVCore (`WeightedRatingTests`), the background fill there too (`RatingFillTests`).
/// The Android app has the same tests (`RatingSortTest.kt`).
@MainActor
final class RatingSortTests: XCTestCase {
    private var root: URL!

    private static let catalogue = """
    #EXTM3U
    #EXTINF:5400 group-title="Movies | Drama",Quiet Water (2019)
    https://stream.example.com/quiet.mp4
    #EXTINF:6000 group-title="Movies | Drama",Long Road (2020)
    https://stream.example.com/road.mp4
    #EXTINF:6000 group-title="Movies | Drama",Bright Side (2021)
    https://stream.example.com/bright.mp4
    #EXTINF:2700 group-title="Series | Drama",Harbor (2022) S01E01
    https://stream.example.com/h101.mp4
    #EXTINF:2700 group-title="Series | Drama",Lantern (2023) S01E01
    https://stream.example.com/l101.mp4
    """

    /// New titles, for the rows' grids (New Movies, New Series): released this year or last year.
    private static let thisYear = Calendar.current.component(.year, from: .now)
    private static let newTitles = """
    #EXTM3U
    #EXTINF:5400 group-title="Movies | Drama",Fresh One (\(thisYear))
    https://stream.example.com/fresh1.mp4
    #EXTINF:5400 group-title="Movies | Drama",Fresh Two (\(thisYear - 1))
    https://stream.example.com/fresh2.mp4
    #EXTINF:2700 group-title="Series | Drama",New Show (\(thisYear)) S01E01
    https://stream.example.com/n101.mp4
    #EXTINF:2700 group-title="Series | Drama",Newer Show (\(thisYear)) S01E01
    https://stream.example.com/w101.mp4
    """

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoRatingSort-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: root)
    }

    private func model(token: Bool, catalogue: String = RatingSortTests.catalogue) async throws -> AppModel {
        let file = root.appendingPathComponent("list.m3u")
        try Data(catalogue.utf8).write(to: file)
        let secrets = InMemorySecretStore()
        if token { _ = secrets.set("example-tmdb-token", for: Keychain.tmdbKey) }
        let model = AppModel(storageRoot: root, secrets: secrets, reminderScheduler: InMemoryReminderScheduler())
        await model.bootstrap()
        try await model.addPlaylist(Playlist(name: "Test", source: .remote(file), autoRefreshHours: 0))
        return model
    }

    /// Keeps TMDb's votes of a title in the store, as an earlier answer would have; nil votes: TMDb did not know it.
    /// Every title of a test is kept: the fill then asks TMDb nothing.
    private func keep(_ item: ShowcaseItem, average: Double? = nil, votes: Int? = nil, in model: AppModel) {
        let keys = MetadataKey.all(item.contentType, tmdbID: nil, name: item.title, year: item.year)
        model.metadataStore.save(keys.map {
            TitleMetadata(key: $0, kind: MetadataKey.kind(item.contentType), tmdbID: votes == nil ? nil : 1, voteAverage: average,
                          voteCount: votes, fetchedAt: Date(), status: votes == nil ? .notFound : .found)
        })
    }

    /// Every place a list is sorted by rating: each follows TMDb's order once the store's votes are read. A new place
    /// sorted by rating is added here.
    func testEverySurfaceSortedByRatingUsesTMDb() async throws {
        let model = try await model(token: true)
        let drama = try XCTUnwrap(model.content.movieCategories.first)
        let seriesDrama = try XCTUnwrap(model.content.seriesCategories.first)
        func movies(_ name: String) throws -> ShowcaseItem { .movie(try XCTUnwrap(model.content.movies.first { $0.name == name })) }
        func series(_ name: String) throws -> ShowcaseItem { .series(try XCTUnwrap(model.content.series.first { $0.name == name })) }
        // Four votes do not top the list; Quiet Water is not known to TMDb and comes last.
        keep(try movies("Bright Side"), average: 9.8, votes: 4, in: model)
        keep(try movies("Long Road"), average: 7.8, votes: 5_000, in: model)
        keep(try series("Lantern"), average: 8.1, votes: 900, in: model)
        keep(try movies("Quiet Water"), in: model)
        keep(try series("Harbor"), in: model)

        let surfaces: [(String, () -> [String])] = [
            ("All Movies", { model.movies(in: nil, sort: .rating).map(\.name) }),
            ("a movie category", { model.movies(in: drama, sort: .rating).map(\.name) }),
            ("All Series", { model.series(in: nil, sort: .rating).map(\.name) }),
            ("a series category", { model.series(in: seriesDrama, sort: .rating).map(\.name) }),
        ]
        // What a grid sorted by rating does as it appears.
        await model.fillRatingSort(model.movies(in: nil, sort: .rating).map(ShowcaseItem.movie))
        await model.fillRatingSort(model.series(in: nil, sort: .rating).map(ShowcaseItem.series))
        let firstOfEach = surfaces.map { $0.1().first ?? "" }
        XCTAssertEqual(firstOfEach, ["Long Road", "Long Road", "Lantern", "Lantern"], "\(surfaces.map(\.0))")
        XCTAssertEqual(surfaces[0].1(), ["Long Road", "Bright Side", "Quiet Water"])
        XCTAssertEqual(surfaces[1].1(), ["Long Road", "Bright Side", "Quiet Water"])
        XCTAssertEqual(surfaces[2].1(), ["Lantern", "Harbor"])
        XCTAssertEqual(surfaces[3].1(), ["Lantern", "Harbor"])
    }

    /// The fill's answers re-sort the grid (its cached order is not kept), only when a vote changed.
    func testNewVotesSortTheGridAgain() async throws {
        let model = try await model(token: true)
        let quiet = ShowcaseItem.movie(try XCTUnwrap(model.content.movies.first { $0.name == "Quiet Water" }))
        let road = ShowcaseItem.movie(try XCTUnwrap(model.content.movies.first { $0.name == "Long Road" }))
        keep(road, average: 7.0, votes: 1_000, in: model)
        keep(quiet, in: model)
        keep(.movie(try XCTUnwrap(model.content.movies.first { $0.name == "Bright Side" })), in: model)
        let items = model.movies(in: nil, sort: .rating).map(ShowcaseItem.movie)
        await model.fillRatingSort(items)
        XCTAssertEqual(model.movies(in: nil, sort: .rating).first?.name, "Long Road")
        let revision = model.tmdbMetadata.sortRevision
        model.updateSortVotes(items)
        XCTAssertEqual(model.tmdbMetadata.sortRevision, revision, "nothing new: the grid does not move")
        // An answer arrives for Quiet Water.
        for key in model.metadataKeys(for: quiet) {
            model.tmdbMetadata.titles[key] = TitleMetadata(key: key, kind: "movie", voteAverage: 8.5, voteCount: 20_000, fetchedAt: Date())
        }
        XCTAssertEqual(model.movies(in: nil, sort: .rating).first?.name, "Long Road", "not at each answer")
        model.updateSortVotes(items)
        XCTAssertEqual(model.tmdbMetadata.sortRevision, revision + 1)
        XCTAssertEqual(model.movies(in: nil, sort: .rating).map(\.name).prefix(2), ["Quiet Water", "Long Road"])
    }

    /// Without a TMDb token: the provider's rating, as before, whatever the store kept.
    func testWithoutATokenTheProvidersRatingAsBefore() async throws {
        let model = try await model(token: false)
        let road = ShowcaseItem.movie(try XCTUnwrap(model.content.movies.first { $0.name == "Long Road" }))
        keep(road, average: 9.0, votes: 10_000, in: model)
        await model.fillRatingSort(model.movies(in: nil, sort: .rating).map(ShowcaseItem.movie))
        XCTAssertNil(model.ratingSortVotes(.rating))
        XCTAssertEqual(model.ratingSortKey(.rating), "")
        // No provider rating in an M3U list: the playlist's order stays.
        XCTAssertEqual(model.movies(in: nil, sort: .rating).map(\.name), ["Quiet Water", "Long Road", "Bright Side"])

        let url = URL(string: "https://stream.example.com/x.mp4")!
        let list = [
            Movie(id: "1", name: "Low", categoryID: "c", streamURL: url, rating: 5),
            Movie(id: "2", name: "None", categoryID: "c", streamURL: url),
            Movie(id: "3", name: "High", categoryID: "c", streamURL: url, rating: 9),
            Movie(id: "4", name: "Also Low", categoryID: "c", streamURL: url, rating: 5),
        ]
        XCTAssertEqual(AppModel.sorted(list, by: .rating).map(\.name), ["High", "Low", "Also Low", "None"], "ties keep the list's order")
        // With a token, TMDb's first, then the provider's, ties by title.
        let votes: [String: (average: Double, count: Int)] = [ShowcaseItem.movie(list[1]).id: (7.0, 500)]
        XCTAssertEqual(AppModel.sorted(list, by: .rating, votes: votes).map(\.name), ["None", "High", "Also Low", "Low"])
        let shows = [Series(id: "a", name: "Plain", categoryID: "c", rating: 9), Series(id: "b", name: "Rated", categoryID: "c", rating: 1)]
        XCTAssertEqual(AppModel.sorted(shows, by: .rating, votes: [ShowcaseItem.series(shows[1]).id: (6.0, 50)]).map(\.name), ["Rated", "Plain"])
    }

    /// A row's grid (See All of New Movies, New Series; Award Winners sorts the same way): the row's own order until
    /// Rating is chosen there, then TMDb's weighted rating first.
    func testARowsGridSortedByRatingUsesTMDbAndKeepsTheRowsOrderOtherwise() async throws {
        let model = try await model(token: true, catalogue: Self.newTitles)
        let newMovies = PseudoCategory.newReleases(.movie), newSeries = PseudoCategory.newReleases(.series)
        func movie(_ name: String) throws -> ShowcaseItem { .movie(try XCTUnwrap(model.content.movies.first { $0.name == name })) }
        func show(_ name: String) throws -> ShowcaseItem { .series(try XCTUnwrap(model.content.series.first { $0.name == name })) }
        keep(try movie("Fresh One"), in: model)
        keep(try movie("Fresh Two"), average: 8.0, votes: 5_000, in: model)
        keep(try show("New Show"), in: model)
        keep(try show("Newer Show"), average: 7.9, votes: 800, in: model)

        XCTAssertEqual(MovieGridView.movies(newMovies, model: model).map(\.name), ["Fresh One", "Fresh Two"], "the row's order: newest first")
        XCTAssertEqual(SeriesGridView.series(newSeries, model: model).map(\.name), ["New Show", "Newer Show"])
        await model.fillRatingSort(MovieGridView.movies(newMovies, model: model, rowSort: .rating).map(ShowcaseItem.movie))
        await model.fillRatingSort(SeriesGridView.series(newSeries, model: model, rowSort: .rating).map(ShowcaseItem.series))
        XCTAssertEqual(MovieGridView.movies(newMovies, model: model, rowSort: .rating).map(\.name), ["Fresh Two", "Fresh One"])
        XCTAssertEqual(SeriesGridView.series(newSeries, model: model, rowSort: .rating).map(\.name), ["Newer Show", "New Show"])
        XCTAssertEqual(MovieGridView.movies(newMovies, model: model).map(\.name), ["Fresh One", "Fresh Two"], "the default stays the row's order")
    }
}
