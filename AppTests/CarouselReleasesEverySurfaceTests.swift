import XCTest
import IPTVCore
@testable import Ondo

/// Regression guard for the carousel, on every surface that shows it: it goes by release date (a movie's release, a
/// series' latest episode), not by the day the provider added a title. An old series with a new season is in; an old
/// series with nothing new, an old film (however well rated, trending or being watched), a release to come and a hidden
/// category are out; the best weighted rating comes first. The Android apps have the same test
/// (`CarouselReleasesEverySurfaceTest`, phone and TV). A new screen that shows the carousel is added to `surfaces` here.
@MainActor
final class CarouselReleasesEverySurfaceTests: XCTestCase {
    private var root: URL!

    nonisolated private static let thisYear = Calendar.current.component(.year, from: .now)

    nonisolated private static let catalogue = """
    #EXTM3U
    #EXTINF:6000 tvg-logo="http://127.0.0.1:9/signal.png" group-title="Movies | Science Fiction",The Last Signal (\(thisYear))
    http://127.0.0.1:9/signal.mp4
    #EXTINF:6000 tvg-logo="http://127.0.0.1:9/lights.png" group-title="Movies | Drama",Northern Lights (\(thisYear))
    http://127.0.0.1:9/lights.mp4
    #EXTINF:6000 tvg-logo="http://127.0.0.1:9/glass.png" group-title="Movies | Science Fiction",Glass City (\(thisYear))
    http://127.0.0.1:9/glass.mp4
    #EXTINF:6000 tvg-logo="http://127.0.0.1:9/road.png" group-title="Movies | Drama",The Long Road (\(thisYear - 4))
    http://127.0.0.1:9/road.mp4
    #EXTINF:6000 tvg-logo="http://127.0.0.1:9/orchard.png" group-title="Movies | Thriller",Midnight Orchard (\(thisYear))
    http://127.0.0.1:9/orchard.mp4
    #EXTINF:6000 tvg-logo="http://127.0.0.1:9/late.png" group-title="Movies | Late",Late Show (\(thisYear))
    http://127.0.0.1:9/late.mp4
    #EXTINF:2700 tvg-logo="http://127.0.0.1:9/horizon.png" group-title="Series | Mystery",Blue Horizon (\(thisYear - 12)) S01E01
    http://127.0.0.1:9/h101.mp4
    #EXTINF:2700 tvg-logo="http://127.0.0.1:9/archive.png" group-title="Series | Mystery",The Archive (\(thisYear - 12)) S01E01
    http://127.0.0.1:9/a101.mp4
    """

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoCarousel-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: root)
    }

    func testEveryCarouselGoesByReleaseDate() async throws {
        let file = root.appendingPathComponent("list.m3u")
        try Data(Self.catalogue.utf8).write(to: file)
        let model = AppModel(storageRoot: root, secrets: InMemorySecretStore(), reminderScheduler: InMemoryReminderScheduler())
        await model.bootstrap()
        try await model.addPlaylist(Playlist(name: "Test", source: .remote(file), autoRefreshHours: 0))
        let late = try XCTUnwrap(model.content.movieCategories.first { $0.name.contains("Late") })
        model.setHidden(true, category: late)
        func item(_ name: String) throws -> ShowcaseItem {
            if let movie = model.content.movies.first(where: { $0.name == name }) { return .movie(movie) }
            return .series(try XCTUnwrap(model.content.series.first { $0.name == name }))
        }
        // What TMDb said, as the store keeps it: days since the release (a series: its latest episode) and the votes.
        let today = RecentReleases.today(.now)
        let released: [(String, Int, Double, Int)] = [
            ("The Last Signal", 2, 8.0, 4_000), ("Blue Horizon", 4, 7.9, 800), ("Northern Lights", 3, 7.3, 12_345), ("Glass City", 5, 10, 10),
            ("The Long Road", 1_500, 9.5, 50_000), ("The Archive", 700, 9.5, 50_000), ("Midnight Orchard", -10, 9, 9_000), ("Late Show", 1, 9, 9_000),
        ]
        for (name, days, average, votes) in released {
            let title = try item(name)
            for key in model.metadataKeys(for: title) {
                model.tmdbMetadata.titles[key] = TitleMetadata(key: key, kind: MetadataKey.kind(title.contentType), voteAverage: average, voteCount: votes,
                                                               fetchedAt: .now, releaseDate: RecentReleases.dayString(today - days))
            }
        }
        model.tmdbMetadata.carouselRevision += 1
        let longRoad = try XCTUnwrap(model.content.movies.first { $0.name == "The Long Road" })
        let road = try XCTUnwrap(model.downloadItem(for: longRoad))
        model.recordStart(road)
        model.recordProgress(road, position: 600, duration: 6_000)

        // Its facts say when a title came out in place of its year, and why a series is there (its latest episode).
        let signal = try XCTUnwrap(model.carouselRelease(of: try item("The Last Signal")))
        XCTAssertEqual(signal.count, 1)
        XCTAssertFalse(signal[0].contains(String(Self.thisYear)), "a day and a month: \(signal)")
        XCTAssertEqual(model.carouselRelease(of: try item("Blue Horizon"))?.first, "New episode")

        /// Every screen that shows Home's carousel: Home on iPhone, iPad and Mac (`HomeView`).
        let surfaces: [String: [ShowcaseItem]] = [
            "Home": model.heroItems(),
        ]
        for (surface, carousel) in surfaces {
            XCTAssertEqual(carousel.map(\.title), ["The Last Signal", "Blue Horizon", "Northern Lights", "Glass City"],
                           "\(surface): the recent releases by weighted rating; a 12-year-old series with a new season is in")
            for name in ["The Long Road", "The Archive", "Midnight Orchard", "Late Show"] {
                XCTAssertFalse(carousel.map(\.title).contains(name), "\(surface) shows \(name)")
            }
        }
    }
}
