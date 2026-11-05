import Foundation

/// The last values used, by key, up to `capacity`: storing one more lets go of the one used longest ago, not of them all.
/// The apps keep the sorted lists of a large catalogue this way (the whole catalogue sorted by date for the Movies tab's
/// All row, a category's titles): the lists a screen asks for at every redraw stay, however many rows it shows.
public struct RecentCache<Value> {
    public let capacity: Int
    private var values: [String: Value] = [:]
    /// The keys, the one used longest ago first.
    private var order: [String] = []

    public init(capacity: Int) {
        precondition(capacity > 0)
        self.capacity = capacity
    }

    public var count: Int { values.count }

    /// The value kept under `key`, now the one used last.
    public mutating func value(_ key: String) -> Value? {
        guard let value = values[key] else { return nil }
        touch(key)
        return value
    }

    /// Keeps `value` under `key`, as the one used last; the one used longest ago goes when the cache is full.
    public mutating func store(_ value: Value, key: String) {
        if values.updateValue(value, forKey: key) != nil {
            touch(key)
            return
        }
        order.append(key)
        if order.count > capacity { values[order.removeFirst()] = nil }
    }

    public mutating func removeAll() {
        values.removeAll()
        order.removeAll()
    }

    private mutating func touch(_ key: String) {
        guard order.last != key, let index = order.firstIndex(of: key) else { return }
        order.remove(at: index)
        order.append(key)
    }
}
