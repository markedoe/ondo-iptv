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
    /// 2024-01-01 (UTC): a wall clock before this is not set (a box before it reaches a time server, at 1970). Such a
    /// device has no idea of the time, so it leaves nothing out of what it receives.
    public static let earliestWallMilliseconds: Int64 = 1_704_067_200_000

    public private(set) var last: Int64

    public init(last: Int64 = .min) {
        self.last = last
    }

    public static func isSet(wall: Int64) -> Bool { wall >= earliestWallMilliseconds }

    /// The latest time a device whose wall clock reads `wall` takes from another one; any time when its clock is not set.
    public static func latestAccepted(wall: Int64) -> Int64 {
        isSet(wall: wall) ? SyncSnapshot.saturated(wall, maxAheadMilliseconds) : .max
    }

    /// The time of a change made now: the wall clock, or just after `last` when that is later.
    public mutating func stamp(wall: Int64) -> Int64 {
        last = max(wall, SyncSnapshot.saturated(last, 1))
        return last
    }

    /// Now on this clock, without giving a time: the later of the wall clock and `last` (to compare with, prune by).
    public func now(wall: Int64) -> Int64 { max(wall, last) }

    /// Takes in a time stored here before (this device's own data, read at launch): no later than it from now on.
    public mutating func advance(to time: Int64?) {
        if let time, time > last { last = time }
    }

    /// Takes in a time received from another device, unless it is more than `maxAheadMilliseconds` after `wall` (then
    /// the record is left out too, `SyncSnapshot.withoutRecordsAhead`): one device far ahead does not carry the others
    /// along.
    public mutating func observe(_ time: Int64?, wall: Int64) {
        if let time, time <= Self.latestAccepted(wall: wall) { advance(to: time) }
    }

    /// `observe` for each time of `snapshot`: call it with what is left of a received snapshot once filtered.
    public mutating func observe(_ snapshot: SyncSnapshot, wall: Int64) {
        let latest = Self.latestAccepted(wall: wall)
        observe(snapshot.times.filter { $0 <= latest }.max(), wall: wall)
    }
}
