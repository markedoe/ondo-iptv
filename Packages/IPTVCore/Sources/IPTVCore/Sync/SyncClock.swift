import Foundation

/// This device's hybrid logical clock (docs/SYNC.md, "Clocks"): every time it gives a change of its own is later than
/// every time it gave or saw before, whatever its wall clock says. A box whose clock reads 1970, or hours behind, thus
/// dates a change it makes after what it has seen; and sync never moves a time once it is stored.
///
/// `last` is the latest time given or seen; `stamp(wall:)` gives the next one, `observe` takes in the times received.
/// The wall clock is passed in each time (milliseconds since 1970). The same clock as the Kotlin `SyncClock`.
public struct SyncClock: Hashable, Sendable {
    /// How far after the receiver's wall clock a time received may be (docs/SYNC.md, "Clocks"): one day.
    public static let maxAheadMilliseconds: Int64 = 86_400_000
    /// 2024-01-01 (UTC): a wall clock before this is not set (a box before it reaches a time server, at 1970).
    public static let earliestWallMilliseconds: Int64 = 1_704_067_200_000
    /// 2029-01-01 (UTC): the latest time a device whose clock is not set takes in, unless it holds later ones.
    public static let unsetLatestMilliseconds: Int64 = 1_861_920_000_000
    /// Two years: how far after the latest time it holds a device whose clock is not set takes times in.
    public static let unsetAheadMilliseconds: Int64 = 2 * 365 * maxAheadMilliseconds

    public private(set) var last: Int64

    public init(last: Int64 = .min) {
        self.last = last
    }

    public static func isSet(wall: Int64) -> Bool { wall >= earliestWallMilliseconds }

    /// The time of a change made now: the wall clock, or just after `last` when that is later.
    public mutating func stamp(wall: Int64) -> Int64 {
        last = max(wall, SyncSnapshot.saturated(last, 1))
        return last
    }

    /// Now on this clock, without giving a time: the later of the wall clock and `last`, but never more than
    /// `maxAheadMilliseconds` after a wall clock that is set, nor after `earliestWallMilliseconds` when it is not. What
    /// prunes deletions and dates sessions and learned deletions: one time far ahead cannot make every deletion look old
    /// at once.
    public func now(wall: Int64) -> Int64 {
        max(wall, min(last, Self.isSet(wall: wall) ? SyncSnapshot.saturated(wall, Self.maxAheadMilliseconds) : Self.earliestWallMilliseconds))
    }

    /// Takes in a time stored here before (this device's own data, read at launch): no later than it from now on.
    public mutating func advance(to time: Int64?) {
        if let time, time > last { last = time }
    }

    /// The latest time this device takes from another one (docs/SYNC.md, "Clocks"): `maxAheadMilliseconds` after its
    /// wall clock when that is set; else `unsetLatestMilliseconds`, or `unsetAheadMilliseconds` after `last` when that
    /// is later.
    public func latestAccepted(wall: Int64) -> Int64 {
        Self.isSet(wall: wall) ? SyncSnapshot.saturated(wall, Self.maxAheadMilliseconds)
            : max(Self.unsetLatestMilliseconds, SyncSnapshot.saturated(last, Self.unsetAheadMilliseconds))
    }

    /// Takes in a time received from another device, unless it is after `latestAccepted`.
    public mutating func observe(_ time: Int64?, wall: Int64) {
        if let time, time <= latestAccepted(wall: wall) { advance(to: time) }
    }

    /// What this device takes of a snapshot received: without the records and deletions dated after `latestAccepted`
    /// (`SyncSnapshot.withoutRecordsAfter`); the latest time left is taken in. No time is moved.
    public mutating func receive(_ snapshot: SyncSnapshot, wall: Int64) -> SyncSnapshot {
        let taken = snapshot.withoutRecordsAfter(latestAccepted(wall: wall))
        advance(to: taken.latestTime)
        return taken
    }
}
