import XCTest
import IPTVCore
@testable import Ondo

/// TMDb's score on the posters and Continue Watching cards (`AppModel.posterScores`): the title page's rating
/// (`ratingFact`), from what the device kept as soon as the catalogue shows, none when unknown or without votes, written
/// in the app's language, worked out again when what is known changes, in few builds. Android has the same tests
/// (`PosterScoreTest.kt`).
@MainActor
final class PosterScoreTests: XCTestCase {
    private var root: URL!
    private var secrets: InMemorySecretStore!

    private static let catalogue = """
    #EXTM3U
    #EXTINF:5400 group-title="Movies | Drama",Quiet Water (2019)
    https://stream.example.com/quiet.mp4
    #EXTINF:6000 group-title="Movies | Drama",Long Road (2020)
    https://stream.example.com/road.mp4
    #EXTINF:6000 group-title="Movies | Drama",Bright Side (2021)
    https://stream.example.com/bright.mp4
    #EXTINF:6000 group-title="Movies | Drama",Open Sky (2022)
    https://stream.example.com/sky.mp4
    #EXTINF:2700 group-title="Series | Drama",Harbor (2022) S01E01
    https://stream.example.com/h101.mp4
    #EXTINF:2700 group-title="Series | Drama",Lantern (2023) S01E01
    https://stream.example.com/l101.mp4
    """

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoPosterScore-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        secrets = InMemorySecretStore()
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: root)
    }

    private func makeModel() -> AppModel {
        AppModel(storageRoot: root, secrets: secrets, reminderScheduler: InMemoryReminderScheduler())
    }

    private func model() async throws -> AppModel {
        let file = root.appendingPathComponent("list.m3u")
        try Data(Self.catalogue.utf8).write(to: file)
        let model = makeModel()
        await model.bootstrap()
        try await model.addPlaylist(Playlist(name: "Test", source: .remote(file), autoRefreshHours: 0))
        await model.posterScoresSettled()
        return model
    }

    /// The next launch: the catalogue kept, and what the device kept of TMDb's answers.
    private func relaunched() async -> AppModel {
        let model = makeModel()
        await model.bootstrap()
        await model.posterScoresSettled()
        return model
    }

    private func item(_ name: String, in model: AppModel) throws -> ShowcaseItem {
        if let movie = model.content.movies.first(where: { $0.name == name }) { return .movie(movie) }
        return .series(try XCTUnwrap(model.content.series.first { $0.name == name }, name))
    }

    /// TMDb's answer about a title, as the device keeps it; nil votes: TMDb did not know it.
    private func row(_ key: String, _ item: ShowcaseItem, average: Double?, votes: Int?) -> TitleMetadata {
        TitleMetadata(key: key, kind: MetadataKey.kind(item.contentType), tmdbID: votes == nil ? nil : 1, voteAverage: average,
                      voteCount: votes, fetchedAt: Date(), status: votes == nil ? .notFound : .found)
    }

    private func keep(_ item: ShowcaseItem, average: Double?, votes: Int?, in model: AppModel) {
        model.metadataStore.save(model.metadataKeys(for: item).map { row($0, item, average: average, votes: votes) })
    }

    /// Every title's card says what its page says, from what the device kept, as soon as the catalogue shows at the
    /// next launch (no page opened, nothing asked of TMDb); none when TMDb did not know it, or nobody voted.
    func testTheCardSaysWhatTheTitlePageSays() async throws {
        let first = try await model()
        keep(try item("Quiet Water", in: first), average: 7.84, votes: 5_000, in: first)
        keep(try item("Long Road", in: first), average: nil, votes: nil, in: first)
        keep(try item("Bright Side", in: first), average: 6.0, votes: 0, in: first)
        keep(try item("Harbor", in: first), average: 8.04, votes: 120, in: first)
        keep(try item("Lantern", in: first), average: 0, votes: 10, in: first)

        let model = await relaunched()
        XCTAssertFalse(model.content.movies.isEmpty, "the catalogue kept")
        XCTAssertEqual(model.posterScores[try item("Quiet Water", in: model).id], Formatting.rating(7.84))
        XCTAssertEqual(model.posterScores[try item("Harbor", in: model).id], Formatting.rating(8.04))
        XCTAssertEqual(model.posterScores.count, 2, "none for an unknown title, one without votes, nor one at 0: \(model.posterScores)")

        // Each the same as its page, once the page read what the device kept.
        for name in ["Quiet Water", "Long Road", "Bright Side", "Open Sky", "Harbor", "Lantern"] {
            let title = try item(name, in: model)
            await model.showStoredMetadata(for: title)
            XCTAssertEqual(model.posterScores[title.id], model.tmdbScore(for: title), name)
            let page = model.ratingFact(for: title, provider: nil)
            if let score = model.posterScores[title.id] {
                XCTAssertTrue(page?.contains("TMDb " + score) == true, "\(name): \(page ?? "nil")")
            } else {
                XCTAssertFalse(page?.contains("TMDb") == true, "\(name): \(page ?? "nil")")
            }
        }
    }

    /// TMDb answers this session (a page, the prefetch, a grid's fill), a TMDb id from the details, Clear Cache: the map
    /// follows each, the same as the page.
    func testTheScoresFollowWhatIsKnown() async throws {
        let first = try await model()
        keep(try item("Quiet Water", in: first), average: 7.84, votes: 5_000, in: first)
        let model = await relaunched()
        let quiet = try item("Quiet Water", in: model)
        let sky = try item("Open Sky", in: model)
        XCTAssertEqual(model.posterScores[quiet.id], Formatting.rating(7.84))
        XCTAssertNil(model.posterScores[sky.id])

        // TMDb answers about a title: its card shows it, as its page.
        for key in model.metadataKeys(for: sky) { model.tmdbMetadata.titles[key] = row(key, sky, average: 6.46, votes: 300) }
        model.updatePosterScores()
        await model.posterScoresSettled()
        XCTAssertEqual(model.posterScores[sky.id], Formatting.rating(6.46))
        XCTAssertEqual(model.posterScores[sky.id], model.tmdbScore(for: sky))

        // A newer answer this session comes before what the device kept, as on the page.
        for key in model.metadataKeys(for: quiet) { model.tmdbMetadata.titles[key] = row(key, quiet, average: 9.1, votes: 40) }
        model.updatePosterScores()
        await model.posterScoresSettled()
        XCTAssertEqual(model.posterScores[quiet.id], Formatting.rating(9.1))
        XCTAssertEqual(model.posterScores[quiet.id], model.tmdbScore(for: quiet))

        // The details give the title's TMDb id: its row under that id comes first, on the card as on the page.
        guard case .movie(let skyMovie) = sky else { return XCTFail("a movie") }
        let byID = try XCTUnwrap(MetadataKey.tmdb(.movie, id: "77"))
        model.metadataStore.save([row(byID, sky, average: 5.5, votes: 900)])
        model.movieDetailsCache[skyMovie.id] = MediaDetails(title: "Open Sky", tmdbID: "77")
        model.detailsRevision += 1
        model.updatePosterScores()
        await model.posterScoresSettled()
        await model.showStoredMetadata(for: sky)
        XCTAssertEqual(model.metadataKeys(for: sky).first, byID)
        XCTAssertEqual(model.posterScores[sky.id], Formatting.rating(5.5))
        XCTAssertEqual(model.posterScores[sky.id], model.tmdbScore(for: sky))
    }

    /// Clear Cache: what the device kept leaves the cards with the store; what this session's pages show stays.
    func testClearCacheTakesTheKeptScoresAway() async throws {
        let first = try await model()
        keep(try item("Quiet Water", in: first), average: 7.84, votes: 5_000, in: first)
        keep(try item("Harbor", in: first), average: 8.04, votes: 120, in: first)
        let model = await relaunched()
        let quiet = try item("Quiet Water", in: model)
        let harbor = try item("Harbor", in: model)
        // Harbor's page was opened: it shows TMDb's rating, and keeps it.
        await model.showStoredMetadata(for: harbor)
        XCTAssertEqual(model.posterScores.count, 2)

        await model.forgetTMDbMetadata()
        await model.posterScoresSettled()
        XCTAssertNil(model.posterScores[quiet.id])
        XCTAssertNil(model.tmdbScore(for: quiet))
        XCTAssertEqual(model.posterScores[harbor.id], Formatting.rating(8.04))
        XCTAssertEqual(model.posterScores[harbor.id], model.tmdbScore(for: harbor))
    }

    /// The score is written in the app's language, the same as the page's (`Formatting.rating`): "7,8" in French.
    func testTheScoreIsWrittenInTheAppsLanguage() throws {
        let titles = [(id: "m-1", keys: ["a"]), (id: "m-2", keys: ["b"])]
        let stored: [String: PosterScoreRating] = ["a": .rated(7.84), "b": .rated(7.84)]
        let english = PosterScoreBuild.scores(titles, session: [:], stored: stored, locale: Locale(identifier: "en_US"))
        XCTAssertEqual(english, ["m-1": "7.8", "m-2": "7.8"])
        let french = PosterScoreBuild.scores(titles, session: [:], stored: stored, locale: Locale(identifier: "fr_FR"))
        XCTAssertEqual(french["m-1"], "7,8")
        for identifier in ["fr_FR", "ar", "ar_SA", "hi_IN", "pt_BR", "es_ES", "zh_Hans_CN"] {
            let locale = Locale(identifier: identifier)
            let score = try XCTUnwrap(PosterScoreBuild.scores(titles, session: [:], stored: stored, locale: locale)["m-1"], identifier)
            XCTAssertEqual(score, Formatting.rating(7.84, locale: locale), identifier)
            // One decimal, read back as the same number in that language.
            XCTAssertEqual(try Double(score, format: .number.locale(locale)), 7.8, accuracy: 0.0001, identifier)
        }
    }

    /// The first key with a row decides, as `tmdbTitle(for:)`: a row without votes there means no score, even if a later
    /// key has one; this session's row comes before the store's.
    func testTheFirstKeptRowDecides() {
        let stored: [String: PosterScoreRating] = ["id": .unrated, "name": .rated(8.0)]
        XCTAssertNil(PosterScoreBuild.rating(keys: ["id", "name"], session: [:], stored: stored))
        XCTAssertEqual(PosterScoreBuild.rating(keys: ["missing", "name"], session: [:], stored: stored), 8.0)
        XCTAssertEqual(PosterScoreBuild.rating(keys: ["id", "name"], session: ["id": .rated(6.2)], stored: stored), 6.2)
        XCTAssertNil(PosterScoreBuild.rating(keys: ["name"], session: ["name": .rated(0)], stored: stored))
        XCTAssertNil(PosterScoreBuild.rating(keys: [], session: [:], stored: stored))
    }

    /// Answers arriving together (the prefetch, a grid's fill) make one build, not one each: never per card or per answer.
    func testABurstOfChangesMakesOneBuild() async throws {
        let model = try await model()
        let before = model.posterScoreState.builds
        for _ in 0..<50 { model.updatePosterScores() }
        await model.posterScoresSettled()
        XCTAssertLessThanOrEqual(model.posterScoreState.builds - before, 2)
        XCTAssertGreaterThanOrEqual(model.posterScoreState.builds - before, 1)
    }
}
