import XCTest
import IPTVCore
@testable import Ondo

/// The wide picture behind a page's title: a provider backdrop that does not load is not offered again (nor at the next
/// launch when the address itself is broken), and the one found on TMDb takes its place. TMDb's trailer and votes
/// come from the store kept between launches.
@MainActor
final class BackdropTests: XCTestCase {
    private var root: URL!

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: root)
    }

    private func makeModel() -> AppModel {
        AppModel(storageRoot: root, secrets: InMemorySecretStore(), reminderScheduler: InMemoryReminderScheduler())
    }

    private let movie = Movie(id: "m1", name: "Lighthouse", categoryID: "c", streamURL: URL(string: "http://127.0.0.1:9/movie.mp4")!)

    func testABackdropThatDoesNotLoadGivesWayToTMDbs() {
        let model = makeModel()
        let dead = URL(string: "http://img.example.com/dead.jpg")!
        model.movieDetailsCache[movie.id] = MediaDetails(title: "Lighthouse", backdropURL: dead)
        XCTAssertEqual(model.backdropURL(for: .movie(movie)), dead)

        model.backdropFailed(dead, for: .movie(movie))
        XCTAssertNil(model.backdropURL(for: .movie(movie)), "the page shows the blurred poster")

        let found = URL(string: "https://image.tmdb.org/t/p/w1280/lighthouse.jpg")!
        XCTAssertEqual(model.metadataKeys(for: .movie(movie)), ["movie:lighthouse:"])
        model.tmdbMetadata.titles["movie:lighthouse:"] = TitleMetadata(key: "movie:lighthouse:", kind: "movie", backdropURL: found, fetchedAt: Date())
        XCTAssertEqual(model.backdropURL(for: .movie(movie)), found)
    }

    func testABrokenBackdropIsRememberedAtTheNextLaunchButNotAnUnreachableOne() async throws {
        let first = makeModel()
        let broken = URL(string: "http://img.example.com/broken.jpg")!
        let unreachable = URL(string: "http://img.example.com/offline.jpg")!
        first.backdropFailed(broken, for: .movie(movie), permanently: true)
        first.backdropFailed(unreachable, for: .movie(movie), permanently: false)
        XCTAssertTrue(first.backdrops.dead.isSuperset(of: [broken, unreachable]), "neither is tried again this session")
        // The write happens in the background.
        let deadline = Date().addingTimeInterval(5)
        while !first.metadataStore.deadImages(since: .distantPast).contains(broken), Date() < deadline {
            try await Task.sleep(for: .milliseconds(20))
        }

        let next = makeModel()
        await next.bootstrap()
        XCTAssertTrue(next.backdrops.dead.contains(broken), "a broken address goes straight to TMDb's picture or the poster")
        XCTAssertFalse(next.backdrops.dead.contains(unreachable), "a network failure is not held against the address")
    }

    func testTMDbsTrailerAndVotesShowFromTheStore() async {
        let saved = makeModel()
        saved.metadataStore.save([TitleMetadata(key: "movie:lighthouse:", kind: "movie", tmdbID: 910001, trailerKey: "trailerEN01",
                                                voteAverage: 7.1, voteCount: 12_345, fetchedAt: Date())])

        let model = makeModel()
        XCTAssertNil(model.trailerYouTubeID(for: .movie(movie), provider: nil))
        await model.showStoredMetadata(for: .movie(movie))
        XCTAssertEqual(model.trailerYouTubeID(for: .movie(movie), provider: nil), "trailerEN01", "no trailer from the provider: TMDb's")
        XCTAssertEqual(model.trailerYouTubeID(for: .movie(movie), provider: "provider001"), "provider001", "the provider's first")
        XCTAssertEqual(model.ratingFact(for: .movie(movie), provider: 6.5),
                       "★ " + Formatting.rating(6.5) + " · TMDb " + Formatting.rating(7.1) + " (" + 12_345.formatted() + ")")
        XCTAssertEqual(model.ratingFact(for: .movie(movie), provider: nil), "TMDb " + Formatting.rating(7.1) + " (" + 12_345.formatted() + ")")
        let other = Movie(id: "m2", name: "Paper Trail", categoryID: "c", streamURL: URL(string: "http://127.0.0.1:9/paper.mp4")!)
        XCTAssertEqual(model.ratingFact(for: .movie(other), provider: 6.5), "★ " + Formatting.rating(6.5), "without TMDb's votes, the provider's rating")
        XCTAssertNil(model.ratingFact(for: .movie(other), provider: 0))
    }
}
