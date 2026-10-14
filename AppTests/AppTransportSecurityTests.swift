import XCTest
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
}
