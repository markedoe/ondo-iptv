import XCTest
import IPTVCore
@testable import Ondo

/// Playlists, provider APIs, guides and logos are often plain HTTP: the app must allow it everywhere.
final class AppTransportSecurityTests: XCTestCase {
    private var ats: [String: Any] {
        Bundle(for: AppModel.self).object(forInfoDictionaryKey: "NSAppTransportSecurity") as? [String: Any] ?? [:]
    }

    func testAllowsHTTPForEveryRequest() {
        XCTAssertEqual(ats["NSAllowsArbitraryLoads"] as? Bool, true)
    }

    /// Any of these keys makes the system ignore NSAllowsArbitraryLoads (HTTP then only works for media).
    func testNoKeyOverridesArbitraryLoads() {
        for key in ["NSAllowsArbitraryLoadsForMedia", "NSAllowsArbitraryLoadsInWebContent", "NSAllowsLocalNetworking"] {
            XCTAssertNil(ats[key], "\(key) disables NSAllowsArbitraryLoads")
        }
    }

    /// The real thing: a plain-HTTP download through the client that adds playlists, inside the app
    /// (the only place App Transport Security applies). Only the ATS refusal fails; no network skips.
    func testPlainHTTPDownloadIsNotRefused() async throws {
        let url = URL(string: "http://example.com/")!
        do {
            _ = try await URLSessionHTTPClient(timeout: 20).data(from: url, headers: [:])
        } catch let error as URLError where error.code == .appTransportSecurityRequiresSecureConnection {
            XCTFail("App Transport Security refused \(url): \(error.localizedDescription)")
        } catch is HTTPClientError {
            return  // the server answered (an HTTP error): App Transport Security let the request through
        } catch {
            throw XCTSkip("No network to reach \(url): \(error.localizedDescription)")
        }
    }
}
