import XCTest
import IPTVCore
@testable import Ondo

/// Home carousel and new releases, next episode, "More like this", My List and hiding watched movies.
@MainActor
final class ShowcaseTests: XCTestCase {
    private var root: URL!

    /// Release years are relative to today: "new releases" are this year's and last year's.
    private static let thisYear = Calendar.current.component(.year, from: .now)

    private static let catalogue = """
    #EXTM3U
    #EXTINF:-1 tvg-id="news.test" group-title="News",Test News
    http://127.0.0.1:9/news.m3u8
    #EXTINF:5400 group-title="Movies | Drama",Quiet Water (\(thisYear - 1))
    http://127.0.0.1:9/quiet.mp4
    #EXTINF:6000 group-title="Movies | Drama",Long Road (\(thisYear - 2))
    http://127.0.0.1:9/road.mp4
    #EXTINF:6000 group-title="Movies | Comedy",Bright Side (\(thisYear))
    http://127.0.0.1:9/bright.mp4
    #EXTINF:2700 group-title="Series | Drama",Harbor (\(thisYear)) S01E01
    http://127.0.0.1:9/h101.mp4
    #EXTINF:2700 group-title="Series | Drama",Harbor (\(thisYear)) S01E02
    http://127.0.0.1:9/h102.mp4
    #EXTINF:2700 group-title="Series | Drama",Harbor (\(thisYear)) S02E01
    http://127.0.0.1:9/h201.mp4
    """

    /// New releases against everything that must stay out of them.
    private static let releases = """
    #EXTM3U
    #EXTINF:6000 group-title="Movies | Drama",Old Classic (\(thisYear - 5))
    http://127.0.0.1:9/old.mp4
    #EXTINF:6000 group-title="Movies | Drama",Last Year Film (\(thisYear - 1))
    http://127.0.0.1:9/last.mp4
    #EXTINF:6000 group-title="Movies | Drama",This Year Film (\(thisYear))
    http://127.0.0.1:9/this.mp4
    #EXTINF:6000 group-title="Movies | Comedy",Undated Film
    http://127.0.0.1:9/undated.mp4
    #EXTINF:6000 group-title="Movies | Comedy",Coming Soon (\(thisYear + 1))
    http://127.0.0.1:9/soon.mp4
    #EXTINF:6000 group-title="Movies | Horror",Hidden New Film (\(thisYear))
    http://127.0.0.1:9/hidden.mp4
    #EXTINF:2700 group-title="Series | Drama",New Show (\(thisYear)) S01E01
    http://127.0.0.1:9/new101.mp4
    #EXTINF:2700 group-title="Series | Drama",Old Show (\(thisYear - 3)) S01E01
    http://127.0.0.1:9/old101.mp4
    """

    private static let liveOnly = """
    #EXTM3U
    #EXTINF:-1 tvg-id="news.test" group-title="News",Test News
    http://127.0.0.1:9/news.m3u8
    #EXTINF:-1 tvg-id="sport.test" group-title="Sports",Test Sport
    http://127.0.0.1:9/sport.m3u8
    """

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoShowcase-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: root)
    }

    private func model(with text: String = catalogue) async throws -> AppModel {
        let file = root.appendingPathComponent("list.m3u")
        try Data(text.utf8).write(to: file)
        let model = AppModel(storageRoot: root, secrets: InMemorySecretStore(), reminderScheduler: InMemoryReminderScheduler())
        await model.bootstrap()
        try await model.addPlaylist(Playlist(name: "Test", source: .remote(file), autoRefreshHours: 0))
        return model
    }

    private func watch(_ item: PlaybackItem?, in model: AppModel, fraction: Double, duration: Double) async throws {
        let item = try XCTUnwrap(item)
        // Distinct "last watched" dates.
        try await Task.sleep(for: .milliseconds(20))
        model.recordStart(item)
        model.recordProgress(item, position: duration * fraction, duration: duration)
    }

    private func movie(_ name: String, in model: AppModel) throws -> Movie {
        try XCTUnwrap(model.content.movies.first { $0.name == name })
    }

    func testNextEpisodeFollowsWhatWasWatched() async throws {
        let model = try await model()
        let series = try XCTUnwrap(model.content.series.first)
        let details = try await model.details(for: series)
        let episodes = details.seasons.flatMap(\.episodes)
        XCTAssertEqual(episodes.map(\.code), ["S01E01", "S01E02", "S02E01"])
        func next() -> String? { model.nextEpisode(in: series, details: details)?.episode.code }

        XCTAssertEqual(next(), "S01E01", "nothing watched: the first episode")
        try await watch(model.downloadItem(for: episodes[1], in: series), in: model, fraction: 0.3, duration: 2_700)
        XCTAssertEqual(next(), "S01E02", "an episode in progress is resumed")
        try await watch(model.downloadItem(for: episodes[1], in: series), in: model, fraction: 0.99, duration: 2_700)
        XCTAssertEqual(next(), "S02E01", "after the last episode of a season comes the next season")
        try await watch(model.downloadItem(for: episodes[2], in: series), in: model, fraction: 0.99, duration: 2_700)
        XCTAssertEqual(next(), "S02E01", "everything watched: the last episode stays")
        XCTAssertNil(model.progress(episode: episodes[0], in: series), "episodes keep their own progress")
    }

    /// An episode listed twice (the same address, as some M3U playlists list it): after it comes the next one, not its copy.
    func testAfterAnEpisodeListedTwiceComesTheNextOne() async throws {
        let twice = Self.catalogue.replacingOccurrences(
            of: "S01E01\nhttp://127.0.0.1:9/h101.mp4",
            with: "S01E01\nhttp://127.0.0.1:9/h101.mp4\n#EXTINF:2700 group-title=\"Series | Drama\",Harbor (\(Self.thisYear)) S01E01\nhttp://127.0.0.1:9/h101.mp4"
        )
        let model = try await model(with: twice)
        let series = try XCTUnwrap(model.content.series.first)
        let details = try await model.details(for: series)
        let episodes = details.seasons.flatMap(\.episodes)
        let first = try XCTUnwrap(episodes.first)
        try await watch(model.downloadItem(for: first, in: series), in: model, fraction: 0.99, duration: 2_700)
        XCTAssertEqual(model.nextEpisode(in: series, details: details)?.episode.code, "S01E02")
    }

    func testHeroPutsContinueWatchingFirstWithoutDuplicates() async throws {
        let model = try await model()
        let road = try movie("Long Road", in: model)
        try await watch(model.downloadItem(for: road), in: model, fraction: 0.2, duration: 6_000)

        let bright = try movie("Bright Side", in: model)
        let quiet = try movie("Quiet Water", in: model)
        let harbor = try XCTUnwrap(model.content.series.first)

        let hero = model.heroItems(limit: 5)
        XCTAssertEqual(hero, [.movie(road), .movie(bright), .series(harbor), .movie(quiet)],
                       "what is being watched comes first, then new releases (movies and series), never twice")
        XCTAssertEqual(model.heroItems(limit: 2).count, 2)
    }

    func testMoviesAndSeriesInProgressAreApart() async throws {
        let model = try await model()
        let road = try movie("Long Road", in: model)
        let harbor = try XCTUnwrap(model.content.series.first)
        let episodes = try await model.details(for: harbor).seasons.flatMap(\.episodes)
        try await watch(model.downloadItem(for: road), in: model, fraction: 0.2, duration: 6_000)
        try await watch(model.downloadItem(for: episodes[0], in: harbor), in: model, fraction: 0.3, duration: 2_700)

        XCTAssertEqual(model.continueWatching().count, 2)
        XCTAssertEqual(model.continueWatching(.movie).map(\.reference.itemID), [road.id], "Home's movies in progress")
        XCTAssertEqual(model.continueWatching(.series).map(\.reference.itemID), [harbor.id], "Home's series in progress")
    }

    func testNewMoviesAndSeriesGoByReleaseYear() async throws {
        let model = try await model(with: Self.releases)
        let horror = try XCTUnwrap(model.content.movieCategories.first { $0.name.contains("Horror") })
        model.setHidden(true, category: horror)

        XCTAssertEqual(model.newMovies().map(\.title), ["This Year Film", "Last Year Film"],
                       "this year's and last year's releases, newest first; not an old, undated, upcoming or hidden title")
        XCTAssertEqual(model.newSeries().map(\.title), ["New Show"], "series in their own row; not an old one")
        XCTAssertEqual(model.newMovies(limit: 1).map(\.title), ["This Year Film"])
        XCTAssertEqual(model.newReleases().map(\.title), ["This Year Film", "New Show", "Last Year Film"],
                       "the carousel alternates movies and series")

        let inThreeYears = try XCTUnwrap(Calendar.current.date(byAdding: .year, value: 3, to: .now))
        XCTAssertTrue(model.newMovies(now: inThreeYears).isEmpty, "the same titles are no longer new three years later")
        XCTAssertTrue(model.newSeries(now: inThreeYears).isEmpty)
    }

    func testLiveOnlyPlaylistHasNoCarousel() async throws {
        let model = try await model(with: Self.liveOnly)
        XCTAssertTrue(model.heroItems().isEmpty, "the home screen falls back to live TV")
        XCTAssertTrue(model.newMovies().isEmpty)
        XCTAssertTrue(model.newSeries().isEmpty)
    }

    func testSimilarTitlesStayInTheCategoryAndSkipHiddenOnes() async throws {
        let model = try await model()
        let quiet = try movie("Quiet Water", in: model)
        XCTAssertEqual(model.similarMovies(to: quiet).map(\.name), ["Long Road"])

        let drama = try XCTUnwrap(model.content.movieCategories.first { $0.id == quiet.categoryID })
        model.setHidden(true, category: drama)
        XCTAssertTrue(model.similarMovies(to: quiet).isEmpty, "nothing from a hidden or locked category")
    }

    func testWatchedMoviesCanBeHiddenWhereMoviesAreBrowsed() async throws {
        let model = try await model()
        let quiet = try movie("Quiet Water", in: model)
        let road = try movie("Long Road", in: model)
        let drama = try XCTUnwrap(model.content.movieCategories.first { $0.id == quiet.categoryID })
        try await watch(model.downloadItem(for: quiet), in: model, fraction: 0.99, duration: 5_400)
        try await watch(model.downloadItem(for: road), in: model, fraction: 0.5, duration: 6_000)

        // Off by default. These lists are kept in a cache from now on: hiding must still apply to them.
        XCTAssertEqual(model.movies(in: drama, sort: .name).map(\.name), ["Long Road", "Quiet Water"])
        XCTAssertTrue(model.newMovies().contains(.movie(quiet)))

        model.updateSettings { $0.hideWatchedMovies = true }
        XCTAssertEqual(model.movies(in: drama, sort: .name).map(\.name), ["Long Road"], "a category's row and grid; a movie in progress stays")
        XCTAssertEqual(model.movies(in: drama, sort: .playlist).map(\.name), ["Long Road"])
        XCTAssertFalse(model.movies(in: nil, sort: .name).contains(quiet), "all movies")
        XCTAssertFalse(model.newMovies().contains(.movie(quiet)), "Home's new movies")
        XCTAssertFalse(model.heroItems().contains(.movie(quiet)), "the carousel")
        XCTAssertTrue(model.similarMovies(to: road).isEmpty, "More Like This")
        let lastYear = Self.thisYear - 1
        XCTAssertFalse(model.titles(for: .year(lastYear)).movies.contains(quiet), "the page of its release year")

        XCTAssertTrue(model.search("Quiet Water").movies.contains(quiet), "search still finds it")
        XCTAssertTrue(model.search("", releasedIn: lastYear...lastYear).movies.contains(quiet), "and so do its filters")
        model.toggleFavorite(quiet)
        XCTAssertEqual(model.myList(), [.movie(quiet)], "My List still shows it")

        model.updateSettings { $0.hideWatchedMovies = false }
        XCTAssertEqual(model.movies(in: drama, sort: .name).map(\.name), ["Long Road", "Quiet Water"], "turned off: back everywhere")
        XCTAssertTrue(model.newMovies().contains(.movie(quiet)))
    }

    /// Settings saved before the option existed keep their values, and finished movies stay visible.
    func testSettingsSavedBeforeTheOptionShowWatchedMovies() throws {
        let saved = Data(#"{"showChannelNumbers": false, "posterSort": "name"}"#.utf8)
        let settings = try JSONDecoder().decode(AppSettings.self, from: saved)
        XCTAssertFalse(settings.hideWatchedMovies)
        XCTAssertFalse(settings.showChannelNumbers)
        XCTAssertEqual(settings.posterSort, .name)
    }

    func testMyListShowsTheLatestAdditionFirst() async throws {
        let model = try await model()
        let bright = try movie("Bright Side", in: model)
        let series = try XCTUnwrap(model.content.series.first)
        model.toggleFavorite(bright)
        try await Task.sleep(for: .milliseconds(20))
        model.toggleFavorite(series)

        XCTAssertEqual(model.myList(), [.series(series), .movie(bright)])
        model.toggleFavorite(bright)
        XCTAssertEqual(model.myList(), [.series(series)])
    }
}
