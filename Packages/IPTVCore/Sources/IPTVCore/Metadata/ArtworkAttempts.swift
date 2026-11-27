import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Which picture a card asks for, and when, as its loads fail: the first of `urls` (the provider's), tried again a
/// moment later when the network failed (`maxRetries` times, waiting longer each time), then the next address (TMDb's
/// still for an episode), then nothing (the placeholder). Without Nuke types: the same rules as Android's
/// `ArtworkAttempts`. A card shown again starts over (`init`): a picture that failed is asked again.
public struct ArtworkAttempts: Hashable, Sendable {
    /// Tries again after a network error, before the next address: a picture's server busy for a moment.
    public static let maxRetries = 2
    /// How long each try again waits, in seconds.
    public static let retryDelays: [Double] = [1.5, 5]

    public let urls: [URL]
    /// The address asked now, in `urls`.
    public private(set) var index = 0
    /// How many times the address asked now was asked again.
    public private(set) var retries = 0
    /// How long to wait before asking `url` (again), in seconds: 0 for a first try.
    public private(set) var wait: Double = 0
    /// No address left: the placeholder shows.
    public private(set) var gaveUp = false

    /// The addresses of a picture in order, repeated ones left out.
    public init(_ url: URL?, fallbacks: [URL?] = []) {
        var urls: [URL] = []
        for candidate in ([url] + fallbacks).compactMap({ $0 }) where !urls.contains(candidate) { urls.append(candidate) }
        self.urls = urls
    }

    /// The address to ask, nil once every one failed.
    public var url: URL? { gaveUp || index >= urls.count ? nil : urls[index] }

    /// Changes with every request made: a new one starts when it does.
    public var attempt: Int { index * (Self.maxRetries + 1) + retries }

    /// After a failed load: `transient` when the network failed (the address may still be good).
    public func failed(transient: Bool) -> ArtworkAttempts {
        var next = self
        if transient && retries < Self.maxRetries {
            next.wait = Self.retryDelays[retries]
            next.retries += 1
        } else if index + 1 < urls.count {
            next.index += 1
            next.retries = 0
            next.wait = 0
        } else {
            next.gaveUp = true
            next.wait = 0
        }
        return next
    }
}

extension ArtworkAttempts {
    /// Whether a load that failed is worth another try of the same address: the server could not be reached for a
    /// moment (a connection refused or lost, a failed DNS lookup) or said it is busy (`status` 408, 429, 5xx). Not a
    /// picture that stopped arriving (a timeout: asked again, a slow picture would start over from nothing), not an
    /// address the server says does not exist (404), not something that is not a picture. Same rules as Android's.
    public static func isRetryable(urlError: URLError.Code?, status: Int?) -> Bool {
        if let status { return status == 408 || status == 429 || (500...599).contains(status) }
        guard let urlError else { return false }
        switch urlError {
        case .cannotConnectToHost, .networkConnectionLost, .notConnectedToInternet, .dnsLookupFailed, .cannotFindHost:
            return true
        default:
            return false
        }
    }

    /// `self` after a failed load of its address: another try when `isRetryable` and `breaker` allow it, else the next
    /// address, else the placeholder. The failure counts against the address' host.
    public func afterFailure(urlError: URLError.Code?, status: Int?, breaker: ArtworkBreaker = .shared) -> ArtworkAttempts {
        let host = url?.host?.lowercased()
        let retryable = Self.isRetryable(urlError: urlError, status: status)
        if retryable { breaker.failed(host) }
        return failed(transient: retryable && breaker.allowsRetry(host))
    }
}

/// Stops the tries again for a host that keeps failing: after `threshold` failures worth another try within `window`
/// seconds, none for `pause` seconds (a picture failing then goes to its next address, or its placeholder). A dead image
/// server then holds no connection with tries that cannot succeed; a picture that loads from it closes it again.
/// Thread-safe; `now` is the clock. Same rules as Android's `ArtworkBreaker`.
public final class ArtworkBreaker: @unchecked Sendable {
    /// The app's, shared by every card.
    public static let shared = ArtworkBreaker()

    private let threshold: Int
    private let window: TimeInterval
    private let pause: TimeInterval
    private let now: @Sendable () -> Date
    private let lock = NSLock()
    private var failures: [String: [Date]] = [:]
    private var pausedUntil: [String: Date] = [:]

    public init(threshold: Int = 5, window: TimeInterval = 30, pause: TimeInterval = 60, now: @escaping @Sendable () -> Date = Date.init) {
        self.threshold = threshold
        self.window = window
        self.pause = pause
        self.now = now
    }

    /// Whether `host` may be asked again now.
    public func allowsRetry(_ host: String?) -> Bool {
        guard let host else { return true }
        lock.lock()
        defer { lock.unlock() }
        guard let until = pausedUntil[host] else { return true }
        if now() < until { return false }
        pausedUntil[host] = nil
        return true
    }

    /// A failure worth another try (`ArtworkAttempts.isRetryable`) on `host`.
    public func failed(_ host: String?) {
        guard let host else { return }
        lock.lock()
        defer { lock.unlock() }
        let at = now()
        var recent = (failures[host] ?? []).filter { at.timeIntervalSince($0) <= window }
        recent.append(at)
        if recent.count >= threshold {
            pausedUntil[host] = at.addingTimeInterval(pause)
            recent = []
        }
        failures[host] = recent
    }

    /// The picture at `url` loaded: its host's failures are forgotten.
    public func succeeded(_ url: URL?) {
        guard let host = url?.host?.lowercased() else { return }
        lock.lock()
        defer { lock.unlock() }
        guard failures[host] != nil || pausedUntil[host] != nil else { return }
        failures[host] = nil
        pausedUntil[host] = nil
    }
}
