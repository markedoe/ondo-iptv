import XCTest
@testable import IPTVCore

/// What a film's versions say on their chips: the table written by hand in `fixtures/film-versions/labels.json`, the
/// same file Android's `FilmVersionsLabelTest` reads, so both apps name versions alike. Invented titles only.
final class FilmVersionsLabelTests: XCTestCase {
    private struct Table: Decodable {
        struct Row: Decodable {
            var name: String
            var category: String?
        }
        struct Case: Decodable {
            var name: String
            var rows: [Row]
            var labels: [String]
        }
        var cases: [Case]
    }

    private func labels(_ names: [String], categories: [String?]? = nil) -> [String] {
        let movies = names.enumerated().map { index, name in
            Movie(id: "v\(index)", name: name, categoryID: "c\(index)", streamURL: URL(string: "http://stream.example.com/\(index)")!)
        }
        let byID = Dictionary(uniqueKeysWithValues: names.indices.map { ("c\($0)", categories?[$0]) })
        return FilmVersions.labels(movies, categoryName: { byID[$0] ?? nil })
    }

    func testEveryCaseReadsAsWritten() throws {
        let file = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("fixtures/film-versions/labels.json")
        let table = try JSONDecoder().decode(Table.self, from: Data(contentsOf: file))
        XCTAssertFalse(table.cases.isEmpty)
        for test in table.cases {
            XCTAssertEqual(labels(test.rows.map(\.name), categories: test.rows.map(\.category)), test.labels, test.name)
        }
    }

    /// Whatever a film's versions are, two never read the same, and none is empty.
    func testTwoVersionsNeverReadTheSame() {
        let pool = [
            "Copper Valley", "Copper Valley", "Copper Valley 4K", "Copper Valley 4K", "Copper Valley 4K DV", "Copper Valley HD",
            "Copper Valley VOSTFR", "FR - Copper Valley", "EN - Copper Valley", "Copper Valley MULTI", "Copper Valley Standard",
            "Copper Valley 4K 2", "Copper Valley Version 1", "Copper Valley Extended", "Copper Valley 3D",
        ]
        for a in pool {
            for b in pool {
                for c in pool {
                    for names in [[a, b], [a, b, c]] {
                        let result = labels(names)
                        XCTAssertEqual(Set(result).count, result.count, "\(names) -> \(result)")
                        XCTAssertFalse(result.contains { $0.trimmingCharacters(in: .whitespaces).isEmpty }, "\(names) -> \(result)")
                    }
                }
            }
        }
    }

    /// Every word the grouping reads as a provider's tag (`CatalogueMatcher`'s version and quality words) names its
    /// version next to a plain one, but those that say nothing of the copy ("DUB": dubbed into what?) or name a language
    /// the apps have no word for.
    func testEveryTagWordOfTheGroupingNamesItsVersion() {
        let unnamed: Set<String> = ["dub", "dubbed", "sub", "tr", "nl", "pl"]
        let words = [
            "vf", "vff", "vfq", "vfi", "vo", "vost", "vostfr", "vosta", "stfr", "subfr", "frsub", "truefrench", "french", "english",
            "subbed", "dubbed", "dub", "sub", "dual", "en", "eng", "fr", "es", "esp", "de", "ger", "ita", "pt", "br", "ar", "tr", "nl", "pl",
            "multi", "4k", "uhd", "fhd", "hd", "sd", "hdr", "dv", "hevc", "x265", "h265", "1080p", "720p", "2160p", "imax",
        ]
        for word in words {
            let tag = word.uppercased()
            XCTAssertTrue(CatalogueMatcher.isTagWord(word), word)
            let result = labels(["Copper Valley \(tag)", "Copper Valley"])
            if unnamed.contains(word) {
                XCTAssertEqual(result, ["Version 1", "Version 2"], word)
            } else {
                XCTAssertNotEqual(result[0], "Standard", "\(tag) -> \(result)")
                XCTAssertFalse(result[0].hasPrefix("Version"), "\(tag) -> \(result)")
            }
        }
    }

    /// The chip's tags read in words: no "DV", no "VOSTFR" left.
    func testAbbreviationsAreSpelledOut() {
        let result = labels(["Copper Valley 4K DV", "Copper Valley VOSTFR", "Copper Valley MULTI VFF"])
        for abbreviation in ["DV", "VOSTFR", "MULTI", "VFF"] {
            XCTAssertFalse(result.contains { $0.split(whereSeparator: { $0 == " " || $0 == "·" }).contains(Substring(abbreviation)) }, "\(abbreviation) in \(result)")
        }
    }
}
