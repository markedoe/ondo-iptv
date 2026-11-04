import XCTest
import IPTVCore
@testable import Ondo

/// Every row of titles that can hold more than it shows has "See All", on every surface (Home, Movies, Series), and it
/// opens the grid of that row: all its titles, those of the row first and in the row's order. The rows without one are
/// named with the reason. `Surface` lists every surface: a new one is added here.
@MainActor
final class SeeAllTests: XCTestCase {
    private var root: URL!

    nonisolated private static let thisYear = Calendar.current.component(.year, from: .now)

    /// New movies and series, a movie with an award (Pulp Fiction, in the bundled awards), and a hidden category's new movie.
    nonisolated private static let catalogue = """
    #EXTM3U
    #EXTINF:-1 tvg-id="news.test" group-title="News",Test News
    http://127.0.0.1:9/news.m3u8
    #EXTINF:5400 group-title="Movies | Drama",Pulp Fiction (1994)
    http://127.0.0.1:9/pulp.mp4
    #EXTINF:5400 group-title="Movies | Drama",Quiet Water (\(thisYear - 1))
    http://127.0.0.1:9/quiet.mp4
    #EXTINF:6000 group-title="Movies | Comedy",Bright Side (\(thisYear))
    http://127.0.0.1:9/bright.mp4
    #EXTINF:6000 group-title="Movies | Horror",Hidden New Film (\(thisYear))
    http://127.0.0.1:9/hidden.mp4
    #EXTINF:2700 group-title="Series | Drama",Harbor (\(thisYear)) S01E01
    http://127.0.0.1:9/h101.mp4
    #EXTINF:2700 group-title="Series | Drama",Old Show (\(thisYear - 4)) S01E01
    http://127.0.0.1:9/old101.mp4
    """

    private enum Surface: CaseIterable { case home, movies, series }

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoSeeAll-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: root)
    }

    func testEveryRowThatCanHoldMoreOpensItsGridFromSeeAll() async throws {
        let file = root.appendingPathComponent("list.m3u")
        try Data(Self.catalogue.utf8).write(to: file)
        let model = AppModel(storageRoot: root, secrets: InMemorySecretStore(), reminderScheduler: InMemoryReminderScheduler())
        await model.bootstrap()
        try await model.addPlaylist(Playlist(name: "Test", source: .remote(file), autoRefreshHours: 0))
        await model.awardsTask?.value
        let horror = try XCTUnwrap(model.categories(for: .movie).first { $0.name.contains("Horror") })
        model.setHidden(true, category: horror)

        for surface in Surface.allCases {
            switch surface {
            case .home:
                for row in HomeView.Row.allCases {
                    let route = HomeView.seeAll(row)
                    switch row {
                    case .moviesInProgress, .seriesInProgress, .trending:
                        XCTAssertNil(route, "Home \(row): no See All")
                    case .newMovies:
                        try assertGrid(route, shows: model.newMovies(), type: .movie, model: model, "Home \(row)")
                    case .newSeries:
                        try assertGrid(route, shows: model.newSeries(), type: .series, model: model, "Home \(row)")
                    case .myList:
                        XCTAssertEqual(route, .favorites(FavoritesRoute(type: .movie)), "Home \(row)")
                    case .collections:
                        XCTAssertEqual(route, .favorites(FavoritesRoute(type: nil)), "Home \(row)")
                    case .channels:
                        XCTAssertEqual(route, .section(.live), "Home \(row)")
                    }
                }
            case .movies, .series:
                let type: ContentType = surface == .series ? .series : .movie
                let categories = model.categories(for: type)
                XCTAssertFalse(categories.isEmpty)
                let keys = RowOrder.builtIns(series: type == .series) + categories.map { RowOrder.categoryKey(id: $0.id, name: $0.name) }
                for key in keys {
                    let place = "\(surface) \(key)"
                    let category = categories.first { RowOrder.categoryKey(id: $0.id, name: $0.name) == key }
                    let route = VODBrowser.seeAll(key, type: type, category: category)
                    switch key {
                    case RowOrder.all:
                        XCTAssertEqual(route, .category(PosterCategoryRoute(category: PseudoCategory.all(type))), place)
                    case RowOrder.continueWatching, RowOrder.trending:
                        XCTAssertNil(route, place)
                    case RowOrder.myList:
                        XCTAssertEqual(route, .favorites(FavoritesRoute(type: type)), place)
                    case RowOrder.newReleases:
                        try assertGrid(route, shows: model.rowTitles(PseudoCategory.newReleases, type: type, limit: 20), type: type, model: model, place)
                    case RowOrder.awardWinners:
                        try assertGrid(route, shows: model.rowTitles(PseudoCategory.awardWinners, type: type, limit: 20), type: type, model: model, place)
                    default:
                        let category = try XCTUnwrap(category, place)
                        try assertGrid(route, shows: VODBrowser.items(in: category, type: type, model: model), type: type, model: model, place)
                    }
                }
            }
        }

        // The hidden category's new movie is in neither the row nor its grid.
        let newGrid = MovieGridView.movies(PseudoCategory.newReleases(.movie), model: model).map(\.name)
        XCTAssertEqual(newGrid, ["Bright Side", "Quiet Water"])
        XCTAssertEqual(MovieGridView.movies(PseudoCategory.awardWinners(.movie), model: model).map(\.name), ["Pulp Fiction"])
        XCTAssertEqual(SeriesGridView.series(PseudoCategory.newReleases(.series), model: model).map(\.name), ["Harbor"])
    }

    /// `route` opens a grid whose first titles are the row's (`row`, not empty), in its order.
    private func assertGrid(_ route: AnyHashableRoute?, shows row: [ShowcaseItem], type: ContentType, model: AppModel, _ place: String) throws {
        guard case .category(let poster) = try XCTUnwrap(route, "\(place): See All") else {
            return XCTFail("\(place): See All opens a grid")
        }
        XCTAssertEqual(poster.category.type, type, place)
        let grid: [ShowcaseItem] = type == .series
            ? SeriesGridView.series(poster.category, model: model).map(ShowcaseItem.series)
            : MovieGridView.movies(poster.category, model: model).map(ShowcaseItem.movie)
        XCTAssertFalse(row.isEmpty, "\(place): the row has titles")
        XCTAssertEqual(grid.prefix(row.count).map(\.id), row.map(\.id), "\(place): the grid starts with the row's titles, in its order")
    }
}
