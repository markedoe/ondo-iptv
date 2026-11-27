import XCTest
@testable import IPTVCore

/// A card's picture is not given up at its first failed load: a network failure is asked again a moment later, then the
/// next address (TMDb's still of an episode) is tried, and only then the placeholder shows. Same cases as Android's
/// `ArtworkAttemptsTest`.
final class ArtworkAttemptsTests: XCTestCase {
    private let provider = URL(string: "http://panel.example.com/images/e1.jpg")!
    private let still = URL(string: "https://image.tmdb.org/t/p/w300/e1.jpg")!

    func testATimeoutIsAskedAgainThenTheStillThenThePlaceholder() {
        let start = ArtworkAttempts(provider, fallbacks: [still])
        XCTAssertEqual(start.url, provider)
        var seen = [start.attempt]

        let again = start.failed(transient: true)
        XCTAssertEqual(again.url, provider, "the same address, asked again")
        XCTAssertGreaterThan(again.wait, 0, "after a wait")
        seen.append(again.attempt)

        let third = again.failed(transient: true)
        XCTAssertEqual(third.url, provider)
        XCTAssertGreaterThan(third.wait, again.wait, "waiting longer")
        seen.append(third.attempt)

        let fallback = third.failed(transient: true)
        XCTAssertEqual(fallback.url, still, "then TMDb's still, at once")
        XCTAssertEqual(fallback.wait, 0)
        seen.append(fallback.attempt)

        let broken = fallback.failed(transient: false)
        XCTAssertTrue(broken.gaveUp, "nothing left: the placeholder")
        XCTAssertNil(broken.url)
        XCTAssertEqual(Set(seen).count, seen.count, "every request is a new one")
    }

    func testAnAddressThatLeadsNowhereGoesStraightToTheNext() {
        let next = ArtworkAttempts(provider, fallbacks: [still]).failed(transient: false)
        XCTAssertEqual(next.url, still)
        XCTAssertEqual(next.retries, 0)
        XCTAssertTrue(ArtworkAttempts(provider).failed(transient: false).gaveUp)
    }

    func testACardShownAgainStartsOver() {
        var attempts = ArtworkAttempts(provider)
        while !attempts.gaveUp { attempts = attempts.failed(transient: true) }
        let shownAgain = ArtworkAttempts(provider)
        XCTAssertEqual(shownAgain.url, provider)
        XCTAssertFalse(shownAgain.gaveUp)
    }

    func testRepeatedAndMissingAddressesAreLeftOut() {
        XCTAssertEqual(ArtworkAttempts(provider, fallbacks: [nil, provider]).urls, [provider])
        XCTAssertTrue(ArtworkAttempts(nil).urls.isEmpty)
        XCTAssertEqual(ArtworkAttempts(nil, fallbacks: [still]).url, still)
    }
}
