import Foundation

/// Whether the data keeps up with the picture, for the playback log. The player reads ahead and then only as fast as
/// the picture uses the data, so a lower rate alone may be a quieter scene; data received more slowly than the picture
/// consumes it means the player lives on what it holds ahead, and freezes once that runs out. A report then says so
/// before the first freeze, and when the data catches up again.
public struct DataRateWatch: Sendable, Equatable {
    public enum Change: Equatable, Sendable {
        /// Bytes per second received, and consumed by the picture, over the last seconds.
        case fellBehind(received: Double, consumed: Double)
        case caughtUp(received: Double)
    }

    /// Below this share of what the picture consumes, the data falls behind.
    public static let behind = 0.8
    /// From this share on, it has caught up (a margin, so that a rate near the limit is not reported at every sample).
    public static let caughtUp = 0.95

    public private(set) var isBehind = false

    public init() {}

    /// A sample while the picture plays: bytes per second `received` and `consumed` over the same last seconds
    /// (`PlaybackHealth.receivedRate` and `consumedRate`). The change, if the data fell behind or caught up.
    public mutating func note(received: Double?, consumed: Double?) -> Change? {
        guard let received, let consumed, consumed > 0 else { return nil }
        if !isBehind, received < consumed * Self.behind {
            isBehind = true
            return .fellBehind(received: received, consumed: consumed)
        }
        if isBehind, received >= consumed * Self.caughtUp {
            isBehind = false
            return .caughtUp(received: received)
        }
        return nil
    }
}
