import XCTest
import IPTVCore
@testable import Ondo

/// Categories hidden by language or country tag ("|PT| Filmes"), the rule kept for categories added later, and
/// the one-time suggestion to keep only the viewer's languages.
@MainActor
final class CategoryFilterTests: XCTestCase {
    private var root: URL!

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: root)
    }

    private static func playlist(_ groups: [String]) -> String {
        var lines = ["#EXTM3U"]
        for (index, group) in groups.enumerated() {
            lines.append(#"#EXTINF:5400 group-title="\#(group)",Title \#(index) (2020)"#)
            lines.append("http://127.0.0.1:9/movie/\(index).mp4")
        }
        return lines.joined(separator: "\n")
    }

    private func model(groups: [String]) async throws -> (AppModel, Playlist, URL) {
        let file = root.appendingPathComponent("list.m3u")
        try Data(Self.playlist(groups).utf8).write(to: file)
        let model = AppModel(storageRoot: root, secrets: InMemorySecretStore(), reminderScheduler: InMemoryReminderScheduler())
        await model.bootstrap()
        let playlist = Playlist(name: "Test", source: .remote(file), autoRefreshHours: 0)
        try await model.addPlaylist(playlist)
        return (model, try XCTUnwrap(model.activePlaylist), file)
    }

    private static let groups = ["|PT| Filmes", "|PT| Ação", "|FR| Films", "|FR| Action", "|EN| Movies", "|EN| Drama",
                                 "|DE| Filme", "|DE| Action", "Classics"]

    func testHidingATagHidesItsCategoriesEverywhere() async throws {
        let (model, _, _) = try await model(groups: Self.groups)
        XCTAssertEqual(Set(model.categoryTags.categories.keys), ["PT", "FR", "EN", "DE"])

        model.setHidden(true, tag: "PT")
        let shown = model.categories(for: .movie).map(\.name)
        XCTAssertFalse(shown.contains("|PT| Filmes"))
        XCTAssertTrue(shown.contains("|FR| Films"))
        XCTAssertTrue(shown.contains("Classics"), "a category without a tag stays")
        XCTAssertEqual(model.hiddenCategoryCount(.movie), 2)
        XCTAssertFalse(model.movies(in: nil, sort: .playlist).contains { $0.name == "Title 0" }, "its movies leave All Movies")
        XCTAssertTrue(model.search("title 0").movies.isEmpty, "and the search")
    }

    func testTheRuleCoversCategoriesTheProviderAddsLater() async throws {
        let (model, playlist, file) = try await model(groups: Self.groups)
        model.setHidden(true, tag: "PT")

        try Data(Self.playlist(Self.groups + ["|PT| Novidades"]).utf8).write(to: file)
        await model.activate(playlist, forceRefresh: true)
        let added = try XCTUnwrap(model.content.movieCategories.first { $0.name == "|PT| Novidades" })
        XCTAssertTrue(model.isHidden(added))
        XCTAssertEqual(model.hiddenTag(of: added), "PT")
    }

    func testTheSuggestionKeepsTheViewersLanguagesOnce() async throws {
        let (model, _, _) = try await model(groups: Self.groups)
        let suggestion = try XCTUnwrap(model.tagSuggestion(viewerCodes: ["FR", "EN"]))
        XCTAssertEqual(Set(suggestion.kept), ["FR", "EN"])
        XCTAssertEqual(Set(suggestion.hidden), ["PT", "DE"])

        model.accept(suggestion)
        XCTAssertTrue(model.isHidden(tag: "PT"))
        XCTAssertTrue(model.isHidden(tag: "DE"))
        XCTAssertFalse(model.isHidden(tag: "FR"))
        XCTAssertNil(model.tagSuggestion(viewerCodes: ["FR", "EN"]), "answered: not offered again")
    }

    func testNoSuggestionWithoutEnoughLanguagesOrNoneOfTheViewers() async throws {
        let (few, _, _) = try await model(groups: ["|PT| Filmes", "|PT| Ação", "|FR| Films", "|FR| Action", "Classics"])
        XCTAssertNil(few.tagSuggestion(viewerCodes: ["FR"]), "two languages: nothing to suggest")

        try FileManager.default.removeItem(at: root)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let (other, _, _) = try await model(groups: Self.groups)
        XCTAssertNil(other.tagSuggestion(viewerCodes: ["JA"]), "none of the viewer's languages: no guess")
        other.dismissTagSuggestion()
        XCTAssertNil(other.tagSuggestion(viewerCodes: ["FR"]), "dismissed")
    }

    func testTagNamesAndViewerCodes() {
        let english = Locale(identifier: "en")
        XCTAssertEqual(CategoryTagName.name(of: "PT", locale: english), "Portuguese")
        XCTAssertEqual(CategoryTagName.name(of: "AR", locale: english), "Arabic", "AR is Arabic, not Argentina")
        XCTAssertEqual(CategoryTagName.name(of: "UK", locale: english), "United Kingdom")
        XCTAssertEqual(CategoryTagName.name(of: "BR", locale: english), "Brazil")
        XCTAssertNil(CategoryTagName.name(of: "EX-YU", locale: english))
        XCTAssertEqual(CategoryTagName.viewerCodes(preferredLanguages: ["fr-FR", "en-GB"], current: Locale(identifier: "fr_FR")),
                       ["FR", "EN", "GB", "UK"])
    }
}
