import Foundation

/// The speed of a download and the time it has left, from the bytes received over time (seconds on any clock that
/// goes forward).
public struct TransferMeter: Sendable, Equatable {
    public struct Point: Sendable, Equatable {
        public var time: Double
        public var bytes: Int64
    }

    /// The measures of the last two minutes, oldest first.
    public private(set) var points: [Point] = []
    /// The first measure, for the average.
    public private(set) var first: Point?
    /// When bytes last arrived.
    public private(set) var lastArrival: Double?

    public init() {}

    /// The bytes received so far, at `time`. A smaller count is ignored: a download that starts again from the
    /// beginning gets a new meter.
    public mutating func note(bytes: Int64, at time: Double) {
        if let last = points.last {
            guard time > last.time, bytes >= last.bytes else { return }
            if bytes > last.bytes { lastArrival = time }
        }
        let point = Point(time: time, bytes: bytes)
        if first == nil { first = point }
        points.append(point)
        while let oldest = points.first, oldest.time < time - 120 { points.removeFirst() }
    }

    /// Bytes per second over the last `seconds`, once two seconds were measured.
    public func rate(over seconds: Double = 10) -> Double? {
        guard let last = points.last, let start = points.first(where: { $0.time >= last.time - seconds }),
              last.time - start.time >= 2 else { return nil }
        return Double(last.bytes - start.bytes) / (last.time - start.time)
    }

    /// Bytes per second since the first measure.
    public var averageRate: Double? {
        guard let first, let last = points.last, last.time - first.time >= 2 else { return nil }
        return Double(last.bytes - first.bytes) / (last.time - first.time)
    }

    /// Seconds left for `total` bytes at the recent speed (the average until it is known); nil while nothing comes.
    public func remaining(total: Int64) -> Double? {
        guard total > 0, let last = points.last, let rate = rate() ?? averageRate, rate > 0 else { return nil }
        return Double(max(total - last.bytes, 0)) / rate
    }
}
