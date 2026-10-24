import XCTest
@testable import IPTVCore

/// Serves files: `download` copies them, as a streaming download writes its file; `data(from:)` is refused, so a test
/// fails if a large guide or list would be kept whole in memory.
final class FileHTTPClient: HTTPClient, @unchecked Sendable {
    let routes: [String: URL]

    init(_ routes: [String: URL]) {
        self.routes = routes
    }

    func data(from url: URL, headers: [String: String]) async throws -> Data {
        throw HTTPClientError.unsupportedRequest
    }

    func download(from url: URL, headers: [String: String], to file: URL) async throws {
        let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let action = components?.queryItems?.first(where: { $0.name == "action" })?.value
        let key = url.path.hasSuffix("player_api.php") ? "api:\(action ?? "")" : url.absoluteString
        guard let source = routes[key] else { throw HTTPClientError.badStatus(404) }
        try? FileManager.default.removeItem(at: file)
        try FileManager.default.copyItem(at: source, to: file)
    }
}

final class GuideLoadingTests: XCTestCase {
    private var folder: URL!

    override func setUpWithError() throws {
        folder = FileManager.default.temporaryDirectory.appendingPathComponent("ondo-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: folder)
    }

    private func fixtureFile(_ name: String) throws -> URL {
        let file = folder.appendingPathComponent(name)
        try Fixture.data(name).write(to: file)
        return file
    }

    private let content = PlaylistContent(channels: [
        Channel(id: "c1", name: "TV1", categoryID: "live-1", streamURL: URL(string: "http://stream.example.com/1")!, epgID: "TV1.fr"),
        Channel(id: "c2", name: "Nation 2", categoryID: "live-1", streamURL: URL(string: "http://stream.example.com/2")!),
    ])

    /// Guides are downloaded to files and read from them, plain or gzip; a missing one is reported, the others kept.
    func testGuidesAreReadFromDownloadedFiles() async throws {
        let plain = URL(string: "http://epg.example.com/guide.xml")!
        let gzip = URL(string: "http://epg.example.com/guide.xml.gz")!
        let missing = URL(string: "http://epg.example.com/missing.xml")!
        let http = FileHTTPClient([plain.absoluteString: try fixtureFile("epg.xml"), gzip.absoluteString: try fixtureFile("epg.xml.gz")])
        let loader = PlaylistLoader(http: http, localFileURL: { URL(fileURLWithPath: $0) })
        let playlist = Playlist(name: "G", source: .remote(URL(string: "http://provider.example.com/list.m3u")!),
                                epgURLs: [missing, gzip, plain], useProviderEPG: false)
        let result = await loader.loadEPG(for: playlist, content: content, window: DateInterval(start: .utc(2025, 10, 9, 0), duration: 86_400))
        XCTAssertEqual(result.data.programs["TV1.fr"]?.map(\.title), ["Le JT", "Film du soir"])
        XCTAssertEqual(result.data.programs["nation2"]?.map(\.title), ["Chantez maintenant"], "matched by name")
        XCTAssertNil(result.data.programs["unused.fr"])
        XCTAssertEqual(result.failures.keys.map(\.absoluteString), [missing.absoluteString])
        XCTAssertEqual(result.failures[missing] as? HTTPClientError, .badStatus(404))
    }
}
