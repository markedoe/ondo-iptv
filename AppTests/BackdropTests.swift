import XCTest
import IPTVCore
@testable import Ondo

/// The wide picture behind a page's title: a provider backdrop that does not load is not offered again, and the one
/// found on TMDb takes its place.
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

    func testABackdropThatDoesNotLoadGivesWayToTMDbs() {
        let model = AppModel(storageRoot: root, secrets: InMemorySecretStore(), reminderScheduler: InMemoryReminderScheduler())
        let movie = Movie(id: "m1", name: "Lighthouse", categoryID: "c", streamURL: URL(string: "http://127.0.0.1:9/movie.mp4")!)
        let dead = URL(string: "http://img.example.com/dead.jpg")!
        model.movieDetailsCache[movie.id] = MediaDetails(title: "Lighthouse", backdropURL: dead)
        XCTAssertEqual(model.backdropURL(for: .movie(movie)), dead)

        model.backdropFailed(dead, for: .movie(movie))
        XCTAssertNil(model.backdropURL(for: .movie(movie)), "the page shows the blurred poster")

        let found = URL(string: "https://image.tmdb.org/t/p/w1280/lighthouse.jpg")!
        model.backdrops.fromTMDb[ShowcaseItem.movie(movie).id] = found
        XCTAssertEqual(model.backdropURL(for: .movie(movie)), found)
    }
}
