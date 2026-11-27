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

    func testABusyServiceIsLeftAloneForAMinute() {
        let at = Date(timeIntervalSince1970: 1_000_000)
        var pause = BusyPause()
        XCTAssertFalse(pause.isBusy(at: at))
        pause.note(HTTPClientError.badStatus(404), at: at)
        XCTAssertFalse(pause.isBusy(at: at), "a missing season is no reason to wait")
        pause.note(URLError(.timedOut), at: at)
        XCTAssertFalse(pause.isBusy(at: at), "out of reach: asked again at the next show")
        pause.note(HTTPClientError.badStatus(429), at: at)
        XCTAssertTrue(pause.isBusy(at: at.addingTimeInterval(59)), "not asked at each show")
        XCTAssertFalse(pause.isBusy(at: at.addingTimeInterval(61)), "asked again after a minute")
        pause.note(HTTPClientError.badStatus(503), at: at.addingTimeInterval(100))
        XCTAssertTrue(pause.isBusy(at: at.addingTimeInterval(130)))
    }
}
