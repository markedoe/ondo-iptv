import XCTest
@testable import IPTVCore

/// The player's title data never shows an address, a login, a password or a server: the table of
/// `fixtures/title-data/redaction.json`, the same file Android's `RedactionTest` reads. Invented values only.
final class RedactionTests: XCTestCase {
    private struct Table: Decodable {
        struct Case: Decodable {
            var name: String
            var input: String
            var out: String
            var secrets: [String]?

            enum CodingKeys: String, CodingKey {
                case name, out, secrets
                case input = "in"
            }
        }
        var cases: [Case]
    }

    func testEveryCaseReadsAsWritten() throws {
        let file = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("fixtures/title-data/redaction.json")
        let table = try JSONDecoder().decode(Table.self, from: Data(contentsOf: file))
        XCTAssertFalse(table.cases.isEmpty)
        for test in table.cases {
            XCTAssertEqual(Redaction.clean(test.input, secrets: test.secrets ?? []), test.out, test.name)
        }
    }

    /// Whatever surrounds them, the playlist's password and server never come out.
    func testTheLoginNeverComesOut() {
        let secrets = ["demouser", "s3cret-pass", "panel.example.com"]
        for text in [
            "http://panel.example.com:8080/movie/demouser/s3cret-pass/12.mkv",
            "Copper Valley [demouser:s3cret-pass@panel.example.com]",
            "get.php?username=demouser&password=s3cret-pass",
            "PANEL.EXAMPLE.COM Copper Valley",
        ] {
            let shown = Redaction.clean(text, secrets: secrets).lowercased()
            for secret in secrets {
                XCTAssertFalse(shown.contains(secret), "\(secret) in \(shown)")
            }
        }
    }
}
