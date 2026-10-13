import XCTest
import IPTVCore
@testable import Ondo

/// Home carousel, next episode, "More like this" and My List.
@MainActor
final class ShowcaseTests: XCTestCase {
    private var root: URL!

    private static let catalogue = """
    #EXTM3U
    #EXTINF:-1 tvg-id="news.test" group-title="News",Test News
    http://127.0.0.1:9/news.m3u8
    #EXTINF:5400 group-title="Movies | Drama",Quiet Water (2021)
    http://127.0.0.1:9/quiet.mp4
    #EXTINF:6000 group-title="Movies | Drama",Long Road (2020)
    http://127.0.0.1:9/road.mp4
    #EXTINF:6000 group-title="Movies | Comedy",Bright Side (2022)
    http://127.0.0.1:9/bright.mp4
    #EXTINF:2700 group-title="Series | Drama",Harbor S01E01
    http://127.0.0.1:9/h101.mp4
    #EXTINF:2700 group-title="Series | Drama",Harbor S01E02
    http://127.0.0.1:9/h102.mp4
    #EXTINF:2700 group-title="Series | Drama",Harbor S02E01
    http://127.0.0.1:9/h201.mp4
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

    func testHeroPutsContinueWatchingFirstWithoutDuplicates() async throws {
        let model = try await model()
        let road = try movie("Long Road", in: model)
        try await watch(model.downloadItem(for: road), in: model, fraction: 0.2, duration: 6_000)

        let hero = model.heroItems(limit: 5)
        XCTAssertEqual(hero.first, .movie(road), "what is being watched comes first")
        XCTAssertEqual(Set(hero.map(\.id)).count, hero.count, "no title twice")
        XCTAssertEqual(hero.count, 4, "three movies and one series")
        XCTAssertEqual(model.heroItems(limit: 2).count, 2)
    }

    func testLiveOnlyPlaylistHasNoCarousel() async throws {
        let model = try await model(with: Self.liveOnly)
        XCTAssertTrue(model.heroItems().isEmpty, "the home screen falls back to live TV")
        XCTAssertTrue(model.featuredMovies().isEmpty)
        XCTAssertTrue(model.featuredSeries().isEmpty)
    }

    func testSimilarTitlesStayInTheCategoryAndSkipHiddenOnes() async throws {
        let model = try await model()
        let quiet = try movie("Quiet Water", in: model)
        XCTAssertEqual(model.similarMovies(to: quiet).map(\.name), ["Long Road"])

        let drama = try XCTUnwrap(model.content.movieCategories.first { $0.id == quiet.categoryID })
        model.setHidden(true, category: drama)
        XCTAssertTrue(model.similarMovies(to: quiet).isEmpty, "nothing from a hidden or locked category")
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
