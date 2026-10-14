import XCTest
@testable import IPTVCore

final class SearchTests: XCTestCase {
    private func makeContent() -> PlaylistContent {
        let url = URL(string: "http://x/1")!
        return PlaylistContent(
            channels: [
                Channel(id: "1", name: "FR: Télé Loisirs HD", categoryID: "a", streamURL: url),
                Channel(id: "2", name: "TV1", categoryID: "a", streamURL: url),
                Channel(id: "3", name: "TV1 Séries Films", categoryID: "a", streamURL: url),
                Channel(id: "4", name: "Hidden TV1", categoryID: "hidden", streamURL: url),
            ],
            movies: [Movie(id: "m", name: "Le Fabuleux Destin d'Amélie Lagarde", categoryID: "m", streamURL: url)],
            series: [Series(id: "s", name: "Les Voisins", categoryID: "s")]
        )
    }

    func testNormalisation() {
        XCTAssertEqual(TextNormalizer.normalize("  Télé-Loisirs  HD! "), "tele loisirs hd")
        XCTAssertEqual(TextNormalizer.channelMatchKey("FR: TV1 FHD"), "tv1")
        XCTAssertEqual(TextNormalizer.channelMatchKey("TV1 HD"), "tv1")
        XCTAssertEqual(TextNormalizer.channelMatchKey("Nation 2"), "nation2")
        XCTAssertEqual(TextNormalizer.channelMatchKey("FR"), "fr", "a lone country token is kept")
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
