import XCTest
@testable import IPTVCore

/// Every title of a catalogue is cleaned when it arrives (`FilmVersions`, the awards): 50,000 titles took about 1.4 s on
/// Linux, most of it compiling the same regular expressions again for each title and searching each one through
/// `NSString`. The patterns are compiled once, and an ASCII title is searched byte by byte. Counted, not timed.
final class CatalogueCleaningCostTests: XCTestCase {
    private func titles(_ count: Int) -> [String] {
        let tags = ["|FR| %@", "FR - %@ (2021)", "%@ (2019) 4K", "[MULTI] %@ VOSTFR", "%@ (VOSTFR 4K)", "EN | %@  HD", "%@"]
        return (0..<count).map { String(format: tags[$0 % tags.count], "Film \($0 % 500) Part \($0 % 7)") }
    }

    func testTheCatalogueIsCleanedWithoutCompilingAPatternPerTitle() {
        let names = titles(3_000)
        _ = CatalogueMatcher.displayTitle("warm up (2020) [x] |y|")
        let compiled = CatalogueMatcher.compiledPatterns.count
        let searched = CatalogueMatcher.foundationSearches.count
        let versions = FilmVersions.of(names.enumerated().map { index, name in
            Movie(id: "\(index)", name: name, categoryID: "c", streamURL: URL(string: "http://example.com/\(index).mkv")!)
        })
        XCTAssertGreaterThan(versions.groupCount, 0)
        for name in names { _ = CatalogueMatcher.displayTitle(name) }
        XCTAssertEqual(CatalogueMatcher.compiledPatterns.count, compiled, "patterns compiled once, not per title")
        XCTAssertLessThanOrEqual(compiled, 6)
        XCTAssertEqual(CatalogueMatcher.foundationSearches.count, searched, "ASCII titles searched byte by byte")
        // A title that is not ASCII is still searched as Foundation does (combining accents, other scripts).
        XCTAssertEqual(CatalogueMatcher.displayTitle("|FR| Le Rêve (MULTI)"), "Le Rêve")
        XCTAssertGreaterThan(CatalogueMatcher.foundationSearches.count, searched)
    }

    /// The fast paths give what Foundation gives: spaces, tabs, separators at the ends, tags, words found inside others.
    func testASCIITitlesCleanAsBefore() {
        let cases: [(String, String)] = [
            ("|FR| Le Transport", "Le Transport"),
            ("[FR] Le Transport", "Le Transport"),
            ("FR - Le Transport", "Le Transport"),
            ("STFR| Le Transport", "Le Transport"),
            ("Le Transport VFF", "Le Transport"),
            ("Le Transport (MULTI) (2021)", "Le Transport"),
            ("  Dune\t\tPart Two  ", "Dune Part Two"),
            ("-- Matrix --", "Matrix"),
            ("4K", "4K"),
        ]
        for (title, expected) in cases { XCTAssertEqual(CatalogueMatcher.displayTitle(title), expected, title) }
    }
}
