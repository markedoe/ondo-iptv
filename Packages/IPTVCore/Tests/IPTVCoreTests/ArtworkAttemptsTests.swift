import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
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

    func testOnlyAServerOutOfReachOrBusyIsAskedAgain() {
        XCTAssertTrue(ArtworkAttempts.isRetryable(urlError: .cannotConnectToHost, status: nil))
        XCTAssertTrue(ArtworkAttempts.isRetryable(urlError: .networkConnectionLost, status: nil))
        XCTAssertTrue(ArtworkAttempts.isRetryable(urlError: .dnsLookupFailed, status: nil))
        XCTAssertTrue(ArtworkAttempts.isRetryable(urlError: nil, status: 503))
        XCTAssertTrue(ArtworkAttempts.isRetryable(urlError: nil, status: 429))
        XCTAssertTrue(ArtworkAttempts.isRetryable(urlError: nil, status: 408))
        XCTAssertFalse(ArtworkAttempts.isRetryable(urlError: .timedOut, status: nil), "a slow picture is not started over")
        XCTAssertFalse(ArtworkAttempts.isRetryable(urlError: nil, status: 404))
        XCTAssertFalse(ArtworkAttempts.isRetryable(urlError: .cancelled, status: nil))
        XCTAssertFalse(ArtworkAttempts.isRetryable(urlError: nil, status: nil), "not a picture")
    }

    func testAHostThatKeepsFailingIsNotAskedAgainForAWhile() {
        final class Clock: @unchecked Sendable { var time = Date(timeIntervalSince1970: 1_000_000) }
        let clock = Clock()
        let breaker = ArtworkBreaker(threshold: 5, window: 30, pause: 60, now: { clock.time })
        let dead = ArtworkAttempts(URL(string: "http://dead.example.com/a.jpg")!, fallbacks: [still])
        for _ in 0..<4 { XCTAssertEqual(dead.afterFailure(urlError: .cannotConnectToHost, status: nil, breaker: breaker).retries, 1) }
        XCTAssertEqual(dead.afterFailure(urlError: .cannotConnectToHost, status: nil, breaker: breaker).url, still, "paused: the still at once")
        XCTAssertFalse(breaker.allowsRetry("dead.example.com"))
        XCTAssertTrue(breaker.allowsRetry("panel.example.com"), "other hosts are not paused")
        clock.time += 61
        XCTAssertTrue(breaker.allowsRetry("dead.example.com"), "asked again once the pause is over")
    }

    func testFailuresFarApartOrFollowedByAPictureDoNotPauseTheHost() {
        final class Clock: @unchecked Sendable { var time = Date(timeIntervalSince1970: 0) }
        let clock = Clock()
        let breaker = ArtworkBreaker(threshold: 5, window: 30, pause: 60, now: { clock.time })
        for _ in 0..<10 {
            breaker.failed("slow.example.com")
            clock.time += 10
        }
        XCTAssertTrue(breaker.allowsRetry("slow.example.com"), "never five within 30 s")
        for _ in 0..<4 { breaker.failed("panel.example.com") }
        breaker.succeeded(URL(string: "http://panel.example.com/images/e1.jpg"))
        breaker.failed("panel.example.com")
        XCTAssertTrue(breaker.allowsRetry("panel.example.com"), "a picture that loaded forgets the failures")
        let missing = ArtworkAttempts(URL(string: "http://cdn.example.com/a.jpg")!)
        for _ in 0..<10 { _ = missing.afterFailure(urlError: nil, status: 404, breaker: breaker) }
        XCTAssertTrue(breaker.allowsRetry("cdn.example.com"), "a 404 does not count")
    }
}
