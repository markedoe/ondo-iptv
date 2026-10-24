#if canImport(Darwin)
import Foundation
#if canImport(FoundationXML)
import FoundationXML
#endif
import XCTest
@testable import IPTVCore

/// Probe only (not for main): what this Apple system's JSONDecoder and XMLParser do with a key or a name that is not
/// valid UTF-8. Runs only with ONDO_DECODER_PROBE set, one test per process (`swift test --parallel`): a test that
/// stops the process shows as crashed, any other ends with XCTFail naming its outcome so the log shows it.
final class AppleDecoderProbeTests: XCTestCase {
    private struct Item: Decodable { var id: Int? }

    private final class Names: NSObject, XMLParserDelegate {
        var names: [String] = []
        func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes: [String: String] = [:]) {
            names.append(elementName + attributes.keys.sorted().joined(separator: ","))
        }
        func parser(_ parser: XMLParser, foundComment comment: String) { names.append("comment") }
    }

    override func setUpWithError() throws {
        try XCTSkipIf(ProcessInfo.processInfo.environment["ONDO_DECODER_PROBE"] == nil, "probe only")
    }

    private static func bytes(_ parts: Any...) -> Data {
        var data = Data()
        for part in parts {
            if let text = part as? String { data.append(contentsOf: Array(text.utf8)) } else if let raw = part as? [UInt8] { data.append(contentsOf: raw) }
        }
        return data
    }

    private let ff: [UInt8] = [0xFF]

    private func outcome(_ name: String, _ body: () async throws -> Any) async {
        do {
            let value = try await body()
            XCTFail("PROBE \(name): no crash, value \(String(describing: value).prefix(120))")
        } catch {
            XCTFail("PROBE \(name): no crash, error \(String(describing: error).prefix(160))")
        }
    }

    // MARK: Apple's own decoders

    func testFoundationJSONKeyNotUTF8() async {
        await outcome("JSONDecoder key 0xFF") { try JSONDecoder().decode([Item].self, from: Self.bytes("[{\"id\":1,\"", self.ff, "\":1}]")) }
    }

    func testFoundationJSONKeyBadEscape() async {
        await outcome("JSONDecoder key \\d") { try JSONDecoder().decode([Item].self, from: Self.bytes("[{\"id\":1,\"stream_\\d\":1}]")) }
    }

    func testFoundationJSONKeyRawTab() async {
        await outcome("JSONDecoder key tab") { try JSONDecoder().decode([Item].self, from: Self.bytes("[{\"id\":1,\"a\tb\":1}]")) }
    }

    func testFoundationJSONKeyLoneSurrogate() async {
        await outcome("JSONDecoder key \\uD800") { try JSONDecoder().decode([Item].self, from: Self.bytes("[{\"id\":1,\"\\uD800\":1}]")) }
    }

    func testFoundationJSONDictionaryKeyNotUTF8() async {
        await outcome("JSONDecoder [String: Int] key 0xFF") { try JSONDecoder().decode([String: Int].self, from: Self.bytes("{\"", self.ff, "\":1}")) }
    }

    private func parseXML(_ data: Data, stream: Bool) -> Any {
        let parser = stream ? XMLParser(stream: InputStream(data: data)) : XMLParser(data: data)
        let names = Names()
        parser.delegate = names
        let parsed = parser.parse()
        return "parsed \(parsed), \(names.names), error \(String(describing: parser.parserError))"
    }

    func testFoundationXMLAttributeNameNotUTF8Data() async {
        await outcome("XMLParser(data:) attribute name 0xFF") { self.parseXML(Self.bytes("<tv><programme a", self.ff, "=\"1\" channel=\"a\"/><b/></tv>"), stream: false) }
    }

    func testFoundationXMLAttributeNameNotUTF8Stream() async {
        await outcome("XMLParser(stream:) attribute name 0xFF") { self.parseXML(Self.bytes("<tv><programme a", self.ff, "=\"1\" channel=\"a\"/><b/></tv>"), stream: true) }
    }

    func testFoundationXMLElementNameNotUTF8() async {
        await outcome("XMLParser element name 0xFF") { self.parseXML(Self.bytes("<tv><x", self.ff, "/><b/></tv>"), stream: true) }
    }

    func testFoundationXMLCommentNotUTF8() async {
        await outcome("XMLParser comment 0xFF") { self.parseXML(Self.bytes("<tv><!-- a", self.ff, " --><b/></tv>"), stream: true) }
    }

    func testFoundationXMLProcessingInstructionNotUTF8() async {
        await outcome("XMLParser PI target 0xFF") { self.parseXML(Self.bytes("<tv><?x", self.ff, " d?><b/></tv>"), stream: true) }
    }

    func testFoundationXMLTextNotUTF8() async {
        await outcome("XMLParser text 0xFF") { self.parseXML(Self.bytes("<tv><title>T", self.ff, "</title><b/></tv>"), stream: true) }
    }

    // MARK: Ondo's entry points

    private let credentials = XtreamCredentials(server: URL(string: "http://panel.example.com")!, username: "u", password: "p")

    private func client(_ routes: [String: Data]) -> XtreamClient {
        let http = MockHTTPClient()
        http.routes = routes
        return XtreamClient(credentials: credentials, http: http)
    }

    func testXtreamMoviesKeyNotUTF8() async {
        let answer = Self.bytes("[{\"stream_id\":1,\"name\":\"A\",\"", ff, "\":1},{\"stream_id\":2,\"name\":\"B\"}]")
        await outcome("XtreamClient.movies element key 0xFF") { try await self.client(["api:get_vod_streams": answer]).movies().map(\.name) }
    }

    func testXtreamMoviesKeyBadEscape() async {
        let answer = Self.bytes("[{\"stream_id\":1,\"name\":\"A\",\"stream_\\d\":1},{\"stream_id\":2,\"name\":\"B\"}]")
        await outcome("XtreamClient.movies element key \\d") { try await self.client(["api:get_vod_streams": answer]).movies().map(\.name) }
    }

    func testXtreamMoviesWholeKeyNotUTF8() async {
        let answer = Self.bytes("{\"0\":{\"stream_id\":1,\"name\":\"A\",\"", ff, "\":1},\"1\":{\"stream_id\":2,\"name\":\"B\"}}")
        await outcome("XtreamClient.movies whole key 0xFF") { try await self.client(["api:get_vod_streams": answer]).movies().map(\.name) }
    }

    func testXtreamAuthenticateKeyNotUTF8() async {
        let answer = Self.bytes("{\"user_info\":{\"auth\":1,\"", ff, "\":1}}")
        await outcome("XtreamClient.authenticate key 0xFF") { try await self.client(["api:": answer]).authenticate().status }
    }

    func testXtreamSeriesDetailsKeyNotUTF8() async {
        let answer = Self.bytes("{\"info\":{\"name\":\"A\"},\"episodes\":{\"", ff, "\":[{\"id\":\"1\"}]}}")
        await outcome("XtreamClient.seriesDetails key 0xFF") { try await self.client(["api:get_series_info": answer]).seriesDetails(seriesID: 1).seasons.count }
    }

    private static let guide = bytes(
        "<?xml version=\"1.0\"?><tv><channel id=\"a\"><display-name>A</display-name></channel><programme a", [UInt8(0xFF)],
        "=\"1\" start=\"20250101000000 +0000\" stop=\"20250101010000 +0000\" channel=\"a\"><title>T</title></programme></tv>"
    )

    func testGuideDataAttributeNameNotUTF8() async {
        await outcome("XMLTVParser.parse(_:) attribute name 0xFF") { try XMLTVParser.parse(Self.guide).programs.count }
    }

    func testGuideFileAttributeNameNotUTF8() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("probe-\(UUID().uuidString).xml")
        defer { try? FileManager.default.removeItem(at: file) }
        try Self.guide.write(to: file)
        await outcome("XMLTVParser.parse(contentsOf:) attribute name 0xFF") { try XMLTVParser.parse(contentsOf: file).programs.count }
    }

    func testXSPFAttributeNameNotUTF8() async {
        let data = Self.bytes("<?xml version=\"1.0\"?><playlist><trackList><track a", ff, "=\"1\"><location>http://example.com/a</location></track></trackList></playlist>")
        await outcome("XSPFParser attribute name 0xFF") { XSPFParser.parse(data).entries.count }
    }

    func testFileStoreKeyNotUTF8() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("probe-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = FileStore(directory: directory)
        try Self.bytes("{\"version\":1,\"savedAt\":0,\"value\":[{\"id\":\"a\",\"", ff, "\":1}]}").write(to: store.url(for: "playlists"))
        await outcome("FileStore.load key 0xFF") { store.load([Playlist].self, name: "playlists") as Any }
    }

    func testSyncSnapshotKeyNotUTF8() async {
        await outcome("SyncSnapshot.decode key 0xFF") { SyncSnapshot.decode(Self.bytes("{\"type\":\"ondo-sync\",\"version\":1,\"device\":\"d\",\"", self.ff, "\":1}")) as Any }
    }

    func testAwardsKeyNotUTF8() async {
        await outcome("AwardsCatalogue key 0xFF") { try AwardsCatalogue(data: Self.bytes("{\"awards\":[],\"films\":[],\"", self.ff, "\":1}")).films.count }
    }
}
#endif
