import XCTest
@testable import IPTVCore

final class SearchTests: XCTestCase {
    private func makeContent() -> PlaylistContent {
        let url = URL(string: "http://x/1")!
        return PlaylistContent(
            channels: [
                Channel(id: "1", name: "FR: Télé Évasion HD", categoryID: "a", streamURL: url),
                Channel(id: "2", name: "TV1", categoryID: "a", streamURL: url),
                Channel(id: "3", name: "TV1 Séries Films", categoryID: "a", streamURL: url),
                Channel(id: "4", name: "Hidden TV1", categoryID: "hidden", streamURL: url),
            ],
            movies: [Movie(id: "m", name: "Le Voyage d'Amélie Lagarde", categoryID: "m", streamURL: url)],
            series: [Series(id: "s", name: "Les Voisins", categoryID: "s")]
        )
    }

    func testNormalisation() {
        XCTAssertEqual(TextNormalizer.normalize("  Télé-Évasion  HD! "), "tele evasion hd")
        XCTAssertEqual(TextNormalizer.channelMatchKey("FR: TV1 FHD"), "tv1")
        XCTAssertEqual(TextNormalizer.channelMatchKey("TV1 HD"), "tv1")
        XCTAssertEqual(TextNormalizer.channelMatchKey("Nation 2"), "nation2")
        XCTAssertEqual(TextNormalizer.channelMatchKey("FR"), "fr", "a lone country token is kept")
    }

    /// The fast path for plain ASCII gives exactly what the general one gives, on any ASCII text; anything else
    /// takes the general path.
    func testTheASCIIPathNormalisesLikeTheGeneralOne() {
        var generator = SystemRandomNumberGenerator()
        let alphabet = Array(" \t-_.:|()[]!?'&+/#0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
        for _ in 0..<5_000 {
            let text = String((0..<Int.random(in: 0...24, using: &generator)).map { _ in alphabet.randomElement(using: &generator)! })
            XCTAssertEqual(TextNormalizer.normalizeASCII(text), TextNormalizer.normalizeUnicode(text), "\(text)")
        }
        for text in ["FR: TV1 HD", "  Night -- City (2024) ", "", "---", "A"] {
            XCTAssertEqual(TextNormalizer.normalizeASCII(text), TextNormalizer.normalizeUnicode(text))
        }
        XCTAssertNil(TextNormalizer.normalizeASCII("Télé"))
        XCTAssertNil(TextNormalizer.normalizeASCII("ＴＶ１"), "full-width letters are folded by the general path")
        XCTAssertEqual(TextNormalizer.normalize("ＴＶ１ Évasion"), "tv1 evasion")
    }

    /// Provider text is blank when it holds only spaces of any kind (no-break, ideographic…), not only ASCII ones.
    func testBlankTextCoversEveryKindOfSpace() {
        XCTAssertNil("\u{3000}".nilIfBlank, "ideographic space")
        XCTAssertNil(" \u{00A0}\u{2003}\n".nilIfBlank)
        XCTAssertNil("".nilIfBlank)
        XCTAssertEqual("\u{2003}TV1\u{3000}".nilIfBlank, "TV1")
        XCTAssertEqual("Télé à la carte".nilIfBlank, "Télé à la carte")
        XCTAssertEqual("\u{000B}TV1\u{000C}".trimmedFast, "TV1")
        XCTAssertEqual("\u{3000}TV1".trimmedFast, "TV1")
        XCTAssertEqual("voilà".trimmedFast, "voilà")
    }

    func testSearchIsAccentAndCaseInsensitiveAndRanked() {
        let engine = SearchEngine(content: makeContent())
        XCTAssertEqual(engine.search("tele").channels.map(\.id), ["1"])
        XCTAssertEqual(engine.search("amelie").movies.count, 1)
        XCTAssertEqual(engine.search("voisin").series.count, 1)
        XCTAssertEqual(engine.search("tv1").channels.map(\.id), ["2", "3", "4"], "exact first, then prefix, then contains")
        XCTAssertEqual(engine.search("tv1", excludedCategoryIDs: ["hidden"]).channels.map(\.id), ["2", "3"])
        XCTAssertEqual(engine.search("films tv1").channels.map(\.id), ["3"], "all words must match")
        XCTAssertTrue(engine.search("   ").isEmpty)
        XCTAssertEqual(engine.search("zzz").totalCount, 0)
    }

    func testStableHash() {
        XCTAssertEqual(StableHash.fnv1a(""), 0xcbf2_9ce4_8422_2325)
        XCTAssertEqual(StableHash.hex("http://x/1"), StableHash.hex("http://x/1"))
        XCTAssertNotEqual(StableHash.hex("http://x/1"), StableHash.hex("http://x/2"))
    }
}
