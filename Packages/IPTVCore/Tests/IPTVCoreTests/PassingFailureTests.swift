import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import IPTVCore

/// TMDb busy for a moment (429, 5xx) is asked again the next time a season shows, as the network is: before, a season
/// TMDb answered 503 for kept its episodes without stills all session. Same rule as Android's `EpisodeSurfacesTest`.
final class PassingFailureTests: XCTestCase {
    func testTheNetworkAndABusyServerAreAskedAgain() {
        XCTAssertTrue(isPassingFailure(URLError(.timedOut)))
        XCTAssertTrue(isPassingFailure(URLError(.networkConnectionLost)))
        XCTAssertTrue(isPassingFailure(HTTPClientError.badStatus(503)))
        XCTAssertTrue(isPassingFailure(HTTPClientError.badStatus(500)))
        XCTAssertTrue(isPassingFailure(HTTPClientError.badStatus(429)))
    }

    func testARefusalOrAMissingSeasonIsNot() {
        XCTAssertFalse(isPassingFailure(HTTPClientError.badStatus(401)))
        XCTAssertFalse(isPassingFailure(HTTPClientError.badStatus(404)))
        XCTAssertFalse(isPassingFailure(HTTPClientError.emptyResponse))
        XCTAssertFalse(isPassingFailure(DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "not TMDb"))))
    }
}
