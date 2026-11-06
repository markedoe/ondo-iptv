import XCTest
@testable import IPTVCore

/// Genres in any language as one genre (`fixtures/genres`, the same files and cases as the Kotlin `GenresTest`): the
/// fields of the cases give their keys, the tags of an earlier version read as this version's, and a selection made on
/// one device matches the titles of another whatever the language of their genres.
final class GenresTests: XCTestCase {
    private struct Cases: Decodable {
        struct Field: Decodable {
            var field: String
            var keys: [String]
        }
        struct Tag: Decodable {
            var tag: String
            var canonical: [String]
        }
        struct Entity: Decodable {
            var `in`: String
            var out: String
        }
        var fields: [Field]
        var tags: [Tag]
        var entities: [Entity]
    }

    private func fixture(_ name: String) throws -> Data {
        let file = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("fixtures/genres/" + name)
        return try Data(contentsOf: file)
    }

    private func cases() throws -> Cases { try JSONDecoder().decode(Cases.self, from: fixture("cases.json")) }

    private var englishNames: [String] {
        Genres.table.split(separator: "\n").map { String($0.split(separator: "|")[0]) }
    }

    func testTheTableIsTheSharedOne() throws {
        let shared = String(decoding: try fixture("table.txt"), as: UTF8.self)
        XCTAssertEqual(Genres.table.trimmingCharacters(in: .whitespacesAndNewlines), shared.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    func testEachCanonicalKeyIsItsEnglishNameNormalised() {
        // An earlier version keys a genre by its name normalised: an English name matches the same key there.
        XCTAssertEqual(englishNames.map(TextNormalizer.normalize), Genres.canonical)
        XCTAssertEqual(Genres.canonical.count, 25)
        XCTAssertTrue(Genres.isCanonical("science fiction"))
        XCTAssertFalse(Genres.isCanonical("aventure"))
    }

    func testEachNameMeansOneGenre() {
        var seen: [String: String] = [:]
        for line in Genres.table.split(separator: "\n") {
            let parts = line.split(separator: "|").map(String.init)
            for name in parts.dropFirst() {
                let key = TextNormalizer.normalize(name)
                if let previous = seen[key] { XCTAssertEqual(previous, parts[0], name) }
                seen[key] = parts[0]
            }
        }
    }

    func testFieldsGiveTheirKeys() throws {
        for test in try cases().fields {
            XCTAssertEqual(Genres.keys(test.field), test.keys, test.field)
        }
    }

    func testTagsOfAnEarlierVersionReadAsThisOnes() throws {
        for test in try cases().tags {
            XCTAssertEqual(Genres.canonicalTags(test.tag), test.canonical, test.tag)
        }
        XCTAssertEqual(Genres.canonicalTags(["genre:aventure", "genre:adventure", "genre:drame"]), ["genre:adventure", "genre:drama"])
    }

    func testHTMLReferencesAreDecoded() throws {
        for test in try cases().entities {
            XCTAssertEqual(HtmlEntities.decode(test.in), test.out, test.in)
        }
    }

    /// The bug: "Action &amp; Adventure" was split on its `&`, giving the genres "Action" and "amp; Adventure".
    func testAGenreFieldIsDecodedBeforeItIsSplit() {
        XCTAssertEqual(Genres.split("Action &amp; Adventure, Sci-Fi &amp; Fantasy"), ["Action", "Adventure", "Sci-Fi", "Fantasy"])
        XCTAssertEqual(Selections.genres("War &amp; Politics"), ["genre:war", "genre:politics"])
    }

    /// The bug, in the provider's lists: a genre's references reach the app decoded.
    func testXtreamGenresAreDecoded() throws {
        let series = #"[{"series_id":1,"genre":"Action &amp; Adventure, Sci-Fi &amp; Fantasy"}]"#
        let shows = try JSONDecoder().decode(LossyArray<XtreamSeriesDTO>.self, from: Data(series.utf8)).elements
        XCTAssertEqual(shows.first?.genre, "Action & Adventure, Sci-Fi & Fantasy")
        let info = try JSONDecoder().decode(XtreamInfoDTO.self, from: Data(#"{"genre":"War &amp; Politics, Com&eacute;die"}"#.utf8))
        XCTAssertEqual(info.genre, "War & Politics, Comédie")
    }

    /// The bug: the same genre in each language was its own tag ("Aventure", "Adventure", "Abenteuer").
    func testASelectionMatchesTitlesWhateverTheLanguageOfTheirGenres() throws {
        let french = Selections.Facts(genres: Set(Genres.keys("Aventure, Drame")))
        let german = Selections.Facts(genres: Set(Genres.keys("Abenteuer")))
        let english = Selections.Facts(genres: Set(Genres.keys("Action & Adventure")))
        let tag = try XCTUnwrap(Selections.genre("Adventure"))
        for facts in [french, german, english] { XCTAssertTrue(Selections.matches(tags: [tag], all: false, facts: facts)) }
        // A tag an earlier version saved from a French title still matches, in both modes.
        XCTAssertTrue(Selections.matches(tags: ["genre:aventure"], all: true, facts: german))
        XCTAssertTrue(Selections.matches(tags: ["genre:drame", "genre:aventure"], all: true, facts: french))
        // A tag of two genres (an earlier version's, from TMDb's "Action & Adventure") needs both.
        XCTAssertTrue(Selections.matches(tags: ["genre:action adventure"], all: false, facts: english))
        XCTAssertFalse(Selections.matches(tags: ["genre:action adventure"], all: false, facts: german))
    }

    /// A device of the earlier version keys its titles' genres by their names normalised: a tag this version saves
    /// (`genre:adventure`) matches its English-named titles there, as before.
    func testACanonicalTagMatchesEnglishNamesOnAnEarlierVersion() {
        func earlierFacts(_ field: String) -> Set<String> {
            Set(field.split(whereSeparator: { ",/|&".contains($0) }).map { TextNormalizer.normalize(String($0)) }.filter { !$0.isEmpty })
        }
        for (key, english) in zip(Genres.canonical, englishNames) {
            XCTAssertTrue(earlierFacts(english).contains(key), key)
        }
        XCTAssertTrue(earlierFacts("Action & Adventure").contains("adventure"))
        XCTAssertFalse(earlierFacts("Aventure").contains("adventure"))
    }
}
