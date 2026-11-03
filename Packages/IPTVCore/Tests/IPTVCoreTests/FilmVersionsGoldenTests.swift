import XCTest
@testable import IPTVCore

/// The groups written by hand in `fixtures/film-versions/golden.json`, the same file Android's `FilmVersionsGoldenTest`
/// reads: both cores make the same groups of the same catalogues, and the rules are judged by the expected groups, not
/// by the code that makes them.
final class FilmVersionsGoldenTests: XCTestCase {
    private struct Golden: Decodable {
        struct Row: Decodable {
            var id: String
            var name: String
            var category: String
            var year: Int?
            var tmdb: Int?
        }
        struct Sublist: Decodable {
            var category: String
            var distinct: [Int]
        }
        struct Case: Decodable {
            var name: String
            var rows: [Row]
            var groups: [[Int]]
            var sublists: [Sublist]?
            var diagnostics: [String: Int]?
        }
        var cases: [Case]
    }

    func testEveryCaseMakesItsExpectedGroups() throws {
        let file = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("fixtures/film-versions/golden.json")
        let golden = try JSONDecoder().decode(Golden.self, from: Data(contentsOf: file))
        XCTAssertFalse(golden.cases.isEmpty)
        for test in golden.cases {
            let rows = test.rows.enumerated().map { index, row in
                Movie(
                    id: row.id, name: row.name, categoryID: row.category, streamURL: URL(string: "http://stream.example.com/\(index)")!,
                    year: row.year, tmdbID: row.tmdb
                )
            }
            func row(_ movie: Movie) -> Int { rows.firstIndex(of: movie) ?? -1 }
            let versions = FilmVersions.of(rows)
            let groups = (0..<versions.groupCount).map { versions.versions(group: $0).map(row) }
            XCTAssertEqual(groups, test.groups, test.name)
            for sublist in test.sublists ?? [] {
                let shown = versions.distinct(rows.filter { $0.categoryID == sublist.category }).map(row)
                XCTAssertEqual(shown, sublist.distinct, "\(test.name), category \(sublist.category)")
            }
            for (key, value) in test.diagnostics ?? [:] {
                let actual: Int
                switch key {
                case "idTitleMismatches": actual = versions.diagnostics.idTitleMismatches
                case "prefixWithoutYears": actual = versions.diagnostics.prefixWithoutYears
                case "ambiguousTitles": actual = versions.diagnostics.ambiguousTitles
                default: XCTFail("unknown diagnostic \(key)"); continue
                }
                XCTAssertEqual(actual, value, "\(test.name), \(key)")
            }
            XCTAssertEqual(groups.flatMap { $0 }.sorted(), Array(rows.indices), test.name)
        }
    }
}
