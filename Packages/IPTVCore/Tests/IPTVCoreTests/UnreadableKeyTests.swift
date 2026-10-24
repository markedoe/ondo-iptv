import Foundation
import XCTest
@testable import IPTVCore

/// A JSON object key that is not a readable string (not UTF-8, a control character, a bad escape) stopped the whole
/// app in Swift 6.0's JSONDecoder, through a `try!`: every decoding of outside data checks its keys first (`JSONKeys`).
final class UnreadableKeyTests: XCTestCase {
    private static func json(_ parts: Any...) -> Data {
        var data = Data()
        for part in parts {
            if let text = part as? String { data.append(contentsOf: Array(text.utf8)) } else if let raw = part as? [UInt8] { data.append(contentsOf: raw) }
        }
        return data
    }

    private let notUTF8: [UInt8] = [0xFF]
    private let credentials = XtreamCredentials(server: URL(string: "http://panel.example.com")!, username: "u", password: "p")

    private func client(_ routes: [String: Data]) -> XtreamClient {
        let http = MockHTTPClient()
        http.routes = routes
        return XtreamClient(credentials: credentials, http: http)
    }

    private func assertInvalidResponse<T>(_ load: () async throws -> T, file: StaticString = #filePath, line: UInt = #line) async {
        do {
            _ = try await load()
            XCTFail("expected a refused answer", file: file, line: line)
        } catch {
            XCTAssertEqual(error as? XtreamError, .invalidResponse, file: file, line: line)
        }
    }

    // MARK: Panel answers

    func testAFilmWithAnUnreadableKeyIsDroppedNotTheList() async throws {
        let badKey = Self.json("[{\"stream_id\":1,\"name\":\"A\",\"", notUTF8, "\":1},{\"stream_id\":2,\"name\":\"B\"}]")
        let movies = try await client(["api:get_vod_streams": badKey]).movies()
        XCTAssertEqual(movies.map(\.name), ["B"])
        let badEscape = Self.json("[{\"stream_id\":1,\"name\":\"A\",\"stream_\\d\":1},{\"stream_id\":2,\"name\":\"B\",\"plot\":\"\\d\"}]")
        let escaped = try await client(["api:get_vod_streams": badEscape]).movies()
        XCTAssertEqual(escaped.map(\.name), ["B"], "a bad escape in a value is only that value's loss")
        let nested = Self.json("[{\"stream_id\":1,\"name\":\"A\",\"info\":{\"a\tb\":1}},{\"stream_id\":2,\"name\":\"B\"}]")
        let deep = try await client(["api:get_vod_streams": nested]).movies()
        XCTAssertEqual(deep.map(\.name), ["B"], "a key is checked at any depth")
    }

    func testAListDecodedWholeWithAnUnreadableKeyIsRefused() async {
        let keyedByIndex = Self.json("{\"0\":{\"stream_id\":1,\"name\":\"A\",\"", notUTF8, "\":1},\"1\":{\"stream_id\":2,\"name\":\"B\"}}")
        await assertInvalidResponse { try await self.client(["api:get_vod_streams": keyedByIndex]).movies() }
    }

    func testAnAnswerWithAnUnreadableKeyIsRefused() async {
        let account = Self.json("{\"user_info\":{\"auth\":1,\"", notUTF8, "\":1}}")
        await assertInvalidResponse { try await self.client(["api:": account]).authenticate() }
        let seasons = Self.json("{\"info\":{\"name\":\"A\"},\"episodes\":{\"", notUTF8, "\":[{\"id\":\"1\"}]}}")
        await assertInvalidResponse { try await self.client(["api:get_series_info": seasons]).seriesDetails(seriesID: 1) }
        let guide = Self.json("{\"epg_listings\":[{\"title\":\"QQ==\",\"\\uD800\":1}]}")
        await assertInvalidResponse { try await self.client(["api:get_short_epg": guide]).shortEPG(streamID: 1, epgChannelID: "a") }
    }

    // MARK: Files and other services

    func testADamagedSavedFileIsSetAsideNotReadAtEveryStart() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("keys-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = FileStore(directory: directory)
        try Self.json("{\"version\":1,\"savedAt\":0,\"value\":[{\"id\":\"a\",\"", notUTF8, "\":1}]}").write(to: store.url(for: "playlists"))
        XCTAssertNil(store.load([Playlist].self, name: "playlists", now: Date(timeIntervalSince1970: 1_000)))
        XCTAssertFalse(FileManager.default.fileExists(atPath: store.url(for: "playlists").path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: directory.appendingPathComponent("playlists.json.corrupt-1000.json").path))
        XCTAssertThrowsError(try BackupArchive.decode(Self.json("{\"version\":1,\"", notUTF8, "\":1}")))
    }

    func testOtherAnswersWithAnUnreadableKeyAreRefused() async throws {
        XCTAssertNil(SyncSnapshot.decode(Self.json("{\"type\":\"ondo-sync\",\"version\":1,\"device\":\"d\",\"", notUTF8, "\":1}")))
        XCTAssertThrowsError(try AwardsCatalogue(data: Self.json("{\"awards\":[],\"films\":[],\"", notUTF8, "\":1}")))
        let http = MockHTTPClient()
        http.routes["https://api.themoviedb.org/3/trending/all/week?language=en-US&api_key=KEY"] = Self.json("{\"results\":[{\"id\":1,\"", notUTF8, "\":1}]}")
        do {
            _ = try await TMDbClient(credential: "KEY", http: http).trending()
            XCTFail("expected a refused answer")
        } catch {
            XCTAssertTrue(error is DecodingError, "\(error)")
        }
        http.routes["https://api.opensubtitles.com/api/v1/subtitles?"] = Self.json("{\"data\":[{\"", notUTF8, "\":1}]}")
        let subtitles = OpenSubtitlesClient(apiKey: "key-example-0001", userAgent: "Ondo", http: http)
        do {
            _ = try await subtitles.search(SubtitleQuery(title: "Quiet Water", languages: ["en"]))
            XCTFail("expected a refused answer")
        } catch {
            XCTAssertTrue(error is DecodingError, "\(error)")
        }
    }

    // MARK: The check itself

    func testOnlyKeysAreChecked() {
        XCTAssertTrue(JSONKeys.areReadable(Self.json("{\"name\":\"", notUTF8, "\\d\",\"list\":[\"\\uD800\",\"a\tb\"]}")), "values are the decoder's to refuse")
        XCTAssertTrue(JSONKeys.areReadable(Self.json("[\"key\" , \"b\"]")))
        XCTAssertFalse(JSONKeys.areReadable(Self.json("{\"a\":{\"b\":[{\"c\\x\" : 1}]}}")))
        XCTAssertFalse(JSONKeys.areReadable(Self.json([0xEF, 0xBB, 0xBF], "{\"", notUTF8, "\":1}")), "after a byte order mark too")
        XCTAssertTrue(JSONKeys.areReadable(Self.json("{\"say \\\"hi\\\" \\\\ \\/ \\b\\f\\n\\r\\t \\u00e9 \\u0000 \\uD83D\\uDE00 \u{7F} é 😀\":1}")))
        // UTF-16: the decoder converts the document first, and refuses it if that fails.
        XCTAssertFalse(JSONKeys.areReadable("{\"a\\d\":1}".data(using: .utf16LittleEndian)!))
        XCTAssertFalse(JSONKeys.areReadable("{\"a\\d\":1}".data(using: .utf16)!), "with its byte order mark")
        XCTAssertTrue(JSONKeys.areReadable("{\"é\":1}".data(using: .utf16BigEndian)!))
        XCTAssertTrue(JSONKeys.areReadable(Self.json("{\"cut short")))
        XCTAssertTrue(JSONKeys.areReadable(Data()))
    }

    /// The check says of a string what the decoder does: `JSONDecoder` reads a value with the same code as a key, and a
    /// value it cannot read throws instead of stopping.
    func testTheCheckAgreesWithTheDecoder() throws {
        var strings: [[UInt8]] = [
            Array("plain".utf8), Array("é😀".utf8), [0x7F], [0x09], [0x00], [0x1F], [0xC0, 0x80], [0xC1, 0xBF], [0xC2, 0x80],
            [0xE0, 0x9F, 0x80], [0xE0, 0xA0, 0x80], [0xED, 0x9F, 0xBF], [0xED, 0xA0, 0x80], [0xEF, 0xBF, 0xBF],
            [0xF0, 0x8F, 0x80, 0x80], [0xF0, 0x90, 0x80, 0x80], [0xF4, 0x8F, 0xBF, 0xBF], [0xF4, 0x90, 0x80, 0x80], [0xF5, 0x80, 0x80, 0x80],
            [0xE2, 0x82], [0xE2, 0x82, 0x41], [0x80], [0xBF], [0xFE], [0xFF],
        ]
        for escape in ["\\\"", "\\\\", "\\/", "\\b", "\\f", "\\n", "\\r", "\\t", "\\a", "\\d", "\\x41", "\\'", "\\u0041", "\\u00e9",
                       "\\u00E9", "\\u0000", "\\u12G4", "\\uD800", "\\uDBFF\\uDFFF", "\\uD800\\uDC00", "\\uD800\\u0041", "\\uD800\\uD800",
                       "\\uDC00", "\\uD800x", "a\\uD83D\\uDE00b", "\\u", "\\u12"] {
            strings.append(Array(escape.utf8))
        }
        var generator = SystemRandomNumberGenerator()
        let alphabet: [UInt8] = Array("\\u0aDF/bnx".utf8) + [0x00, 0x09, 0x7F, 0x80, 0xBF, 0xC2, 0xE0, 0xED, 0xF0, 0xF4, 0xFF]
        for _ in 0..<3_000 {
            strings.append((0..<Int.random(in: 1...8, using: &generator)).map { _ in alphabet.randomElement(using: &generator)! })
        }
        let decoder = JSONDecoder()
        var compared = 0
        for string in strings where !string.contains(UInt8(ascii: "\"")) {
            let document = Data(Array("[\"".utf8) + string + Array("\"]".utf8))
            // A string the decoder's scanner refuses (`\u12`, a final `\`) is not a document: nothing to compare.
            guard (try? decoder.decode([AnyJSONValue].self, from: document)) != nil else { continue }
            let decoded = (try? decoder.decode([String].self, from: document)) != nil
            let checked = string.withUnsafeBytes { JSONKeys.isReadable($0) }
            XCTAssertEqual(checked, decoded, "\(string.map { String(format: "%02X", $0) }.joined(separator: " "))")
            compared += 1
        }
        XCTAssertGreaterThan(compared, 1_000)
    }
}
