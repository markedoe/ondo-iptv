import Foundation

/// Times how long the viewer waits: from opening, or from a seek, until the position moves forward again (the
/// picture plays). Fed with the player's half-second samples, so a wait is known to about half a second.
public struct MovementWatch: Sendable, Equatable {
    /// When the wait started (sample clock), nil when nothing is awaited.
    public private(set) var since: Double?
    private var target: Double?
    private var origin: Double?
    private var base: Double?

    public init() {}

    /// Waits for the picture to move, after opening (`target` nil) or after a seek from `origin` to `target` seconds.
    public mutating func start(at clock: Double, target: Double? = nil, from origin: Double? = nil) {
        since = clock
        self.target = target
        self.origin = origin
        base = nil
    }

    public mutating func cancel() {
        since = nil
    }

    /// A sample: the seconds waited once the position moved on from where it landed, else nil.
    public mutating func sample(clock: Double, position: Double?) -> Double? {
        guard let since, let position else { return nil }
        // After a seek, the position first has to land near the target (a key frame up to 10 s before it), away from
        // where it was: until then the old picture may still be playing.
        if let target, base == nil {
            let near = (target - 11...target + 2).contains(position)
            let moved = origin.map { abs(position - $0) > 1.5 || abs(target - $0) <= 1.5 } ?? true
            if !near || !moved { return nil }
        }
        guard let base else {
            self.base = position
            return nil
        }
        if position > base + 0.1 {
            self.since = nil
            return clock - since
        }
        if position < base { self.base = position }
        return nil
    }
}
