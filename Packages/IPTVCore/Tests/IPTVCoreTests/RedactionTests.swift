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
            var playlist: PlaylistParts?

            enum CodingKeys: String, CodingKey {
                case name, out, secrets, playlist
                case input = "in"
            }
        }
        struct PlaylistParts: Decodable {
            var addresses: [String]?
            var login: [String]?
        }
        struct IDRow: Decodable {
            var id: String
            var shown: Bool
        }
        var cases: [Case]
        var ids: [IDRow]
    }

    private func loadTable() throws -> Table {
        let file = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("fixtures/title-data/redaction.json")
        return try JSONDecoder().decode(Table.self, from: Data(contentsOf: file))
    }

    func testEveryCaseReadsAsWritten() throws {
        let table = try loadTable()
        XCTAssertFalse(table.cases.isEmpty)
        for test in table.cases {
            let secrets = (test.secrets ?? []) + Redaction.secrets(addresses: test.playlist?.addresses ?? [], login: test.playlist?.login ?? [])
            XCTAssertEqual(Redaction.clean(test.input, secrets: secrets), test.out, test.name)
        }
    }

    /// A provider's number shows; the M3U reader's hash of an address (its login and password in it) never does.
    func testOnlyAProvidersIDShows() throws {
        let ids = try loadTable().ids
        XCTAssertFalse(ids.isEmpty)
        for row in ids {
            XCTAssertEqual(Redaction.shownID(row.id), row.shown ? row.id : nil, row.id)
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
