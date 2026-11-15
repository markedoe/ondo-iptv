import XCTest
@testable import IPTVCore

/// A film's chips and what each says: the table written by hand in `fixtures/film-versions/labels.json`, the
/// same file Android's `FilmVersionsLabelTest` reads, so both apps name versions alike. Invented titles only.
final class FilmVersionsLabelTests: XCTestCase {
    private struct Table: Decodable {
        struct Row: Decodable {
            var name: String
            var category: String?
            var ext: String?

            enum CodingKeys: String, CodingKey {
                case name, category
                case ext = "extension"
            }
        }
        struct Case: Decodable {
            var name: String
            var rows: [Row]
            var chips: [String]
            /// Each chip's rows; nil: each row alone.
            var members: [[Int]]?
        }
        var cases: [Case]
    }

    private func versions(_ names: [String], categories: [String?]? = nil, extensions: [String?]? = nil) -> ([Movie], (String) -> String?) {
        let movies = names.enumerated().map { index, name in
            Movie(
                id: "v\(index)", name: name, categoryID: "c\(index)", streamURL: URL(string: "http://stream.example.com/\(index)")!,
                containerExtension: extensions?[index]
            )
        }
        let byID = Dictionary(uniqueKeysWithValues: names.indices.map { ("c\($0)", categories?[$0]) })
        return (movies, { byID[$0] ?? nil })
    }

    private func labels(_ names: [String], categories: [String?]? = nil, extensions: [String?]? = nil) -> [String] {
        let (movies, categoryName) = versions(names, categories: categories, extensions: extensions)
        return FilmVersions.labels(movies, categoryName: categoryName)
    }

    private func choices(_ names: [String], categories: [String?]? = nil, extensions: [String?]? = nil) -> [VersionChoice] {
        let (movies, categoryName) = versions(names, categories: categories, extensions: extensions)
        return FilmVersions.choices(movies, categoryName: categoryName)
    }

    private static let tagWords = [
        "vf", "vff", "vfq", "vfi", "vo", "vost", "vostfr", "vosta", "stfr", "subfr", "frsub", "truefrench", "french", "english",
        "subbed", "dubbed", "dub", "sub", "dual", "en", "eng", "fr", "es", "esp", "de", "ger", "ita", "pt", "br", "ar", "tr", "nl", "pl",
        "multi", "4k", "uhd", "fhd", "hd", "sd", "hdr", "dv", "hevc", "x265", "h265", "1080p", "720p", "2160p", "imax",
    ]

    func testEveryCaseReadsAsWritten() throws {
        let file = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("fixtures/film-versions/labels.json")
        let table = try JSONDecoder().decode(Table.self, from: Data(contentsOf: file))
        XCTAssertFalse(table.cases.isEmpty)
        for test in table.cases {
            let result = choices(test.rows.map(\.name), categories: test.rows.map(\.category), extensions: test.rows.map(\.ext))
            XCTAssertEqual(result.map(\.label), test.chips, test.name)
            let members = (test.members ?? test.rows.indices.map { [$0] }).map { $0.map { "v\($0)" } }
            XCTAssertEqual(result.map { $0.members.map(\.id) }, members, test.name)
        }
    }

    /// A chip plays the member in progress, else the one chosen last, else its first; the others are its fallbacks.
    func testAChipPlaysItsPreferredMemberAndKeepsTheOthers() {
        let (movies, categoryName) = versions(
            ["|FR| COPPER VALLÉE", "|FR| Copper Vallée 4K DV", "|FR| Copper Vallee"],
            categories: ["|FR| DRAME | THRILLER", "|FR| FILMS 4K", "|FR| AJOUTÉS"],
            extensions: ["mkv", "mkv", "mkv"]
        )
        let first = FilmVersions.choices(movies, categoryName: categoryName)[0]
        XCTAssertEqual(first.preferred.id, "v0")
        XCTAssertEqual(first.playOrder.map(\.id), ["v0", "v2"])
        let watching = FilmVersions.choices(movies, categoryName: categoryName, inProgress: ["v2"], chosen: ["v0"])[0]
        XCTAssertEqual(watching.preferred.id, "v2", "the one in progress")
        XCTAssertEqual(watching.playOrder.map(\.id), ["v2", "v0"])
        XCTAssertEqual(watching.id, "v0", "the chip is the same whichever plays")
        XCTAssertEqual(FilmVersions.choices(movies, categoryName: categoryName, chosen: ["v1", "v2"])[0].preferred.id, "v2", "the one chosen last")
        XCTAssertEqual(FilmVersions.choices(movies, categoryName: categoryName, inProgress: ["v2"]).last?.playOrder.map(\.id), ["v1"])
    }

    /// A copy filmed in a cinema (its category "Cinema CAM", no tag in its name) was one chip with the proper copy once
    /// categories stopped counting, and could play first: its own chip now, after the proper one, and never played first.
    func testACinemaCopyIsItsOwnChipAndNeverPlaysFirst() throws {
        let (movies, categoryName) = versions(["Sand Harbor", "Sand Harbor"], categories: ["Cinema CAM", "Movies"], extensions: ["mkv", "mkv"])
        XCTAssertEqual(FilmVersions.choices(movies, categoryName: categoryName).map(\.label), ["Standard", "Cinema copy (CAM)"])
        for support in [PictureSupport(), PictureSupport(hdr: true, dolbyVision: true, uhd: true)] {
            let choice = try XCTUnwrap(FilmVersions.defaultChoice(movies, inProgress: nil, chosen: [], support: support, categoryName: categoryName))
            XCTAssertEqual(choice.movie.id, "v1")
            XCTAssertEqual(choice.reason, .bestForScreen)
        }
        // Even next to a 4K Dolby Vision copy filmed in a cinema.
        let (others, names) = versions(["Sand Harbor 4K DV HDCAM", "Sand Harbor"])
        let best = FilmVersions.defaultVersion(
            others, inProgress: nil, chosen: [], support: PictureSupport(hdr: true, dolbyVision: true, uhd: true), categoryName: names
        )
        XCTAssertEqual(best?.id, "v1")
        XCTAssertEqual(FilmVersions.tags(name: "Sand Harbor", categoryName: "Cinema CAM"), [.cam])
        XCTAssertEqual(FilmVersions.tags(name: "The Night Cam", categoryName: "Cam Corner"), [], "a word of a title, not a tag")
    }

    /// Whatever a film's versions are, no two chips read the same, none is empty, and every version is on one.
    func testTwoChipsNeverReadTheSame() {
        let pool = [
            "Copper Valley", "Copper Valley", "Copper Valley 4K", "Copper Valley 4K", "Copper Valley 4K DV", "Copper Valley HD",
            "Copper Valley VOSTFR", "FR - Copper Valley", "EN - Copper Valley", "Copper Valley MULTI", "Copper Valley Standard",
            "Copper Valley 4K 2", "Copper Valley Version 1", "Copper Valley Extended", "Copper Valley 3D", "COPPER VALLEY (2026)",
        ]
        for a in pool {
            for b in pool {
                for c in pool {
                    for names in [[a, b], [a, b, c]] {
                        let result = choices(names)
                        let labels = result.map(\.label)
                        XCTAssertEqual(Set(labels).count, labels.count, "\(names) -> \(labels)")
                        XCTAssertFalse(labels.contains { $0.trimmingCharacters(in: .whitespaces).isEmpty }, "\(names) -> \(labels)")
                        XCTAssertEqual(result.flatMap { $0.members.map(\.id) }.sorted(), names.indices.map { "v\($0)" }.sorted(), "\(names)")
                    }
                }
            }
        }
    }

    /// A provider's word the chips have no name for, a tag, a language's code: never collapsed with the plain version.
    func testATagWordAlwaysKeepsItsOwnChip() {
        for word in Self.tagWords {
            XCTAssertEqual(choices(["Copper Valley \(word.uppercased())", "Copper Valley"]).count, 2, word)
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
    /// version next to a plain one; those that say nothing of the copy ("DUB": dubbed into what?) or name a language the
    /// apps have no word for are shown as the name writes them.
    func testEveryTagWordOfTheGroupingNamesItsVersion() {
        let unnamed: Set<String> = ["dub", "dubbed", "sub", "tr", "nl", "pl"]
        for word in Self.tagWords {
            let tag = word.uppercased()
            XCTAssertTrue(CatalogueMatcher.isTagWord(word), word)
            let result = labels(["Copper Valley \(tag)", "Copper Valley"])
            if unnamed.contains(word) {
                XCTAssertEqual(result, [tag, "Standard"], word)
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
