import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import IPTVCore

/// Keys and accounts of the subtitle services are checked with each service when the viewer saves them: accepted,
/// refused, or not checked (the service could not be reached), in which case Settings saves nothing.
final class SubtitleCredentialTests: XCTestCase {
    private let userAgent = "Ondo v1.0"
    private func json(_ text: String) -> Data { Data(text.utf8) }

    func testOpenSubtitlesChecksTheKeyThenTheAccount() async {
        let http = MockHTTPClient()
        http.routes["https://api.opensubtitles.com/api/v1/infos/formats"] = json(#"{"data":{"output_formats":["srt"]}}"#)
        var check = await OpenSubtitlesClient(apiKey: "key-example-0001", userAgent: userAgent, http: http).checkCredentials()
        XCTAssertEqual(check, .accepted)
        XCTAssertEqual(http.sent.last?.headers["Api-Key"], "key-example-0001")
        XCTAssertFalse(http.sent.contains { $0.url.path.hasSuffix("/login") }, "no account, no sign-in")

        http.routes["POST https://api.opensubtitles.com/api/v1/login"] = json(#"{"token":"jwt-example","base_url":"api.opensubtitles.com"}"#)
        check = await OpenSubtitlesClient(apiKey: "key-example-0001", username: "viewer", password: "example", userAgent: userAgent, http: http)
            .checkCredentials()
        XCTAssertEqual(check, .accepted)
        XCTAssertTrue(http.sent.contains { $0.method == "POST" && $0.url.path.hasSuffix("/login") })

        http.failures["POST https://api.opensubtitles.com/api/v1/login"] = [HTTPClientError.badStatus(401)]
        check = await OpenSubtitlesClient(apiKey: "key-example-0001", username: "viewer", password: "wrong", userAgent: userAgent, http: http)
            .checkCredentials()
        XCTAssertEqual(check, .refused, "a refused account")
    }

    func testOpenSubtitlesRefusedKeyAndNoAnswer() async {
        let http = MockHTTPClient()
        http.failures["https://api.opensubtitles.com/api/v1/infos/formats"] = [HTTPClientError.badStatus(403), URLError(.notConnectedToInternet)]
        let client = OpenSubtitlesClient(apiKey: "key-example-0002", userAgent: userAgent, http: http)
        let refused = await client.checkCredentials()
        XCTAssertEqual(refused, .refused)
        let offline = await client.checkCredentials()
        XCTAssertEqual(offline, .unreachable)
    }

    func testSubDLChecksTheKeyWithASearch() async {
        let http = MockHTTPClient()
        http.routes["https://api.subdl.com/api/v1/subtitles?"] = json(#"{"status":false,"error":"can't find movie or tv"}"#)
        let found = await SubDLClient(apiKey: "key-example-0003", http: http).checkKey()
        XCTAssertEqual(found, .accepted, "no result is still an accepted key")
        XCTAssertTrue(http.sent.last?.url.query?.contains("api_key=key-example-0003") == true)

        http.routes["https://api.subdl.com/api/v1/subtitles?"] = json(#"{"status":false,"error":"Invalid API key"}"#)
        let refused = await SubDLClient(apiKey: "key-example-0004", http: http).checkKey()
        XCTAssertEqual(refused, .refused)

        http.failures["https://api.subdl.com/api/v1/subtitles?api_key=key-example-0004&film_name=The%20Matrix&type=movie&year=1999&languages=EN&subs_per_page=30"] =
            [HTTPClientError.badStatus(502)]
        let unreachable = await SubDLClient(apiKey: "key-example-0004", http: http).checkKey()
        XCTAssertEqual(unreachable, .unreachable, "an error of the service's own says nothing about the key")
    }

    func testSubSourceChecksTheKeyWithATitleSearch() async {
        let http = MockHTTPClient()
        http.routes["https://api.subsource.net/api/v1/movies/search?"] = json(#"{"success":true,"data":[]}"#)
        let accepted = await SubSourceClient(apiKey: "key-example-0005", http: http).checkKey()
        XCTAssertEqual(accepted, .accepted)
        XCTAssertEqual(http.sent.last?.headers["X-API-Key"], "key-example-0005")

        http.failures["https://api.subsource.net/api/v1/movies/search?searchType=text&q=The%20Matrix&type=movie"] = [HTTPClientError.badStatus(401)]
        let refused = await SubSourceClient(apiKey: "key-example-0006", http: http).checkKey()
        XCTAssertEqual(refused, .refused)
    }
}
