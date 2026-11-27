import Foundation

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
