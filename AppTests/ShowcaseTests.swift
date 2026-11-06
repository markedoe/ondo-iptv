import XCTest
import IPTVCore
@testable import Ondo

/// Home carousel and new releases, next episode, "More like this", My List and hiding watched movies.
@MainActor
final class ShowcaseTests: XCTestCase {
    private var root: URL!

    /// Release years are relative to today: "new releases" are this year's and last year's.
    nonisolated private static let thisYear = Calendar.current.component(.year, from: .now)

    nonisolated private static let catalogue = """
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

    /// What TMDb said of a title, as the store keeps it: released `daysAgo` days ago (a series: its latest episode), with
    /// a backdrop and, when given, its votes. The carousel is picked again.
    private func release(_ item: ShowcaseItem, in model: AppModel, daysAgo: Int, votes: (Double, Int)? = nil, backdrop: Bool = true) {
        let date = RecentReleases.dayString(RecentReleases.today(.now) - daysAgo)
        for key in model.metadataKeys(for: item) {
            model.tmdbMetadata.titles[key] = TitleMetadata(
                key: key, kind: MetadataKey.kind(item.contentType), backdropURL: backdrop ? URL(string: "https://example.com/backdrop.jpg") : nil,
                voteAverage: votes?.0, voteCount: votes?.1, fetchedAt: .now, releaseDate: date
            )
        }
        model.tmdbMetadata.carouselRevision += 1
    }

    /// The carousel goes by release date, not by the day the provider added a title nor by what is being watched: the
    /// recent releases, best first by TMDb's votes weighted by their number (the rule itself is tested in IPTVCore).
    func testTheCarouselShowsTheBestRatedRecentReleases() async throws {
        let model = try await model()
        let road = try movie("Long Road", in: model)
        try await watch(model.downloadItem(for: road), in: model, fraction: 0.2, duration: 6_000)
        let bright = try movie("Bright Side", in: model)
        let quiet = try movie("Quiet Water", in: model)
        let harbor = try XCTUnwrap(model.content.series.first)
        XCTAssertTrue(model.heroItems(limit: 5).isEmpty, "no release date known: no carousel, rather than older titles")

        release(.movie(road), in: model, daysAgo: 800, votes: (9.5, 50_000))
        release(.movie(bright), in: model, daysAgo: 2, votes: (7.8, 5_000))
        release(.movie(quiet), in: model, daysAgo: 5, votes: (8.0, 300))
        release(.series(harbor), in: model, daysAgo: 1, votes: (7.0, 100))
        // Scores (rating for new titles + freshness): Bright Side 7.79 + 0.49, Quiet Water 7.79 + 0.47, Harbor 6.83 + 0.48.
        XCTAssertEqual(model.heroItems(limit: 5), [.movie(bright), .series(harbor), .movie(quiet)],
                       "by score, movies and series in turn; an old film, however well rated or being watched, stays out")
        XCTAssertEqual(model.heroItems(limit: 2), [.movie(bright), .series(harbor)])
    }

    /// One series, one card: listed again under other categories ("|FR| Harbor", "Harbor 4K"), each copy released
    /// lately, the carousel shows it once, dated by the latest. The same rule as Android's `ShowcaseTest` (a series listed
    /// once per new episode is tested in IPTVCore, `RecentReleasesTests`).
    func testASeriesListedSeveralTimesIsOneCard() async throws {
        let copies: String = Self.catalogue + """

        #EXTINF:2700 group-title="|FR| Séries",|FR| Harbor (\(Self.thisYear)) S01E01
        http://127.0.0.1:9/fr101.mp4
        #EXTINF:2700 group-title="4K Series",Harbor 4K (\(Self.thisYear)) S01E01
        http://127.0.0.1:9/k101.mp4
        """
        let model = try await model(with: copies)
        let harbors: [Series] = model.content.series.filter { $0.name.contains("Harbor") }
        XCTAssertEqual(harbors.count, 3, harbors.map(\.name).joined(separator: ", "))
        for (index, show) in harbors.enumerated() {
            release(.series(show), in: model, daysAgo: index + 1, votes: (7.5, 500))
        }
        let shown: [ShowcaseItem] = model.heroItems(limit: 20).filter { $0.title.contains("Harbor") }
        XCTAssertEqual(shown.count, 1, shown.map(\.title).joined(separator: ", "))
    }

    func testAnOldSeriesWithANewSeasonIsInAndOneWithNothingNewIsOut() async throws {
        let model = try await model(with: Self.releases)
        let horror = try XCTUnwrap(model.content.movieCategories.first { $0.name.contains("Horror") })
        model.setHidden(true, category: horror)
        func series(_ name: String) throws -> ShowcaseItem { .series(try XCTUnwrap(model.content.series.first { $0.name == name })) }
        try release(series("Old Show"), in: model, daysAgo: 3)
        try release(series("New Show"), in: model, daysAgo: 90)
        release(.movie(try movie("This Year Film", in: model)), in: model, daysAgo: 2)
        release(.movie(try movie("Coming Soon", in: model)), in: model, daysAgo: -30)
        release(.movie(try movie("Hidden New Film", in: model)), in: model, daysAgo: 1)
        release(.movie(try movie("Old Classic", in: model)), in: model, daysAgo: 1, backdrop: false)
        XCTAssertEqual(model.heroItems(limit: 5).map(\.title), ["This Year Film", "Old Show"],
                       "the newest by date (no votes): a series from three years ago with a new season is in; one with nothing new for three months, a release to come, a hidden category and a title without artwork are out")
    }

    func testTheProvidersFullReleaseDateCountsAndAYearAloneDoesNot() async throws {
        let model = try await model()
        let bright = try movie("Bright Side", in: model)
        let quiet = try movie("Quiet Water", in: model)
        let backdrop = URL(string: "https://example.com/backdrop.jpg")
        model.movieDetailsCache[bright.id] = MediaDetails(title: bright.name, releaseDate: RecentReleases.dayString(RecentReleases.today(.now) - 1), backdropURL: backdrop)
        model.movieDetailsCache[quiet.id] = MediaDetails(title: quiet.name, releaseDate: String(Self.thisYear), backdropURL: backdrop)
        model.tmdbMetadata.carouselRevision += 1
        XCTAssertEqual(model.heroItems(limit: 5), [.movie(bright)])
    }

    /// Old films with posters, nothing released lately: no carousel (Home starts with its rows).
    private static let oldWithPosters = """
    #EXTM3U
    #EXTINF:5400 tvg-logo="http://127.0.0.1:9/a.png" group-title="Movies | Drama",Old Film A (2010)
    http://127.0.0.1:9/a.mp4
    #EXTINF:5400 tvg-logo="http://127.0.0.1:9/b.png" group-title="Movies | Drama",Old Film B (2011)
    http://127.0.0.1:9/b.mp4
    """

    func testACatalogueWithoutRecentReleasesHasNoCarousel() async throws {
        let model = try await model(with: Self.oldWithPosters)
        XCTAssertTrue(model.heroItems(limit: 5).isEmpty)
        release(.movie(try movie("Old Film B", in: model)), in: model, daysAgo: 400, votes: (9, 20_000))
        XCTAssertTrue(model.heroItems(limit: 5).isEmpty, "released over 90 days ago")
        XCTAssertTrue(model.releasesToAsk().isEmpty, "old titles nobody changed lately are not asked about")
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
        release(.movie(quiet), in: model, daysAgo: 3)
        let road = try movie("Long Road", in: model)
        let drama = try XCTUnwrap(model.content.movieCategories.first { $0.id == quiet.categoryID })
        try await watch(model.downloadItem(for: quiet), in: model, fraction: 0.99, duration: 5_400)
        try await watch(model.downloadItem(for: road), in: model, fraction: 0.5, duration: 6_000)

        // Off by default. These lists are kept in a cache from now on: hiding must still apply to them.
        XCTAssertEqual(model.movies(in: drama, sort: .name).map(\.name), ["Long Road", "Quiet Water"])
        XCTAssertTrue(model.newMovies().contains(.movie(quiet)))
        XCTAssertTrue(model.heroItems().contains(.movie(quiet)))

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

    /// The appearance choice is gone (the app is always dark): settings saved with it still decode, the rest kept.
    func testSettingsSavedWithTheFormerAppearanceChoiceStillDecode() throws {
        let saved = Data(#"{"appearance": "light", "showChannelNumbers": false, "posterSort": "name"}"#.utf8)
        let settings = try JSONDecoder().decode(AppSettings.self, from: saved)
        XCTAssertFalse(settings.showChannelNumbers)
        XCTAssertEqual(settings.posterSort, .name)
        let reencoded = try JSONEncoder().encode(settings)
        XCTAssertFalse(String(decoding: reencoded, as: UTF8.self).contains("appearance"), "no longer written back")
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

    /// The hero and the movie and series pages showed the provider's raw title ("|FR| Le Transport VFF").
    func testHeroAndDetailTitlesAreShownWithoutTheProvidersTags() {
        let movie = Movie(id: "m", name: "|FR| Le Transport VFF", categoryID: "c", streamURL: URL(string: "http://stream.example.com/m.mp4")!)
        let series = Series(id: "s", name: "[FR] Harbor (MULTI)", categoryID: "c")
        XCTAssertEqual(ShowcaseItem.movie(movie).displayTitle, "Le Transport")
        XCTAssertEqual(ShowcaseItem.series(series).displayTitle, "Harbor")
        XCTAssertEqual(ShowcaseItem.movie(movie).title, "|FR| Le Transport VFF", "the stored title stays the provider's")
    }
}
