import Foundation

extension String {
    /// The parts of the string between spaces, as `split(separator: " ", omittingEmptySubsequences:)` gives them. An
    /// ASCII string (most titles) is cut on its bytes, without going through its characters one by one: every title of a
    /// catalogue is split this way (`FilmVersions`, `CatalogueMatcher.displayTitle`).
    func spaceSplit(omittingEmptySubsequences: Bool = true) -> [Substring] {
        let bytes = utf8
        guard bytes.allSatisfy({ $0 < 0x80 }) else { return split(separator: " ", omittingEmptySubsequences: omittingEmptySubsequences) }
        var parts: [Substring] = []
        var start = bytes.startIndex
        var index = bytes.startIndex
        while index != bytes.endIndex {
            if bytes[index] == 0x20 {
                if !omittingEmptySubsequences || start != index { parts.append(self[start..<index]) }
                start = bytes.index(after: index)
            }
            index = bytes.index(after: index)
        }
        if !omittingEmptySubsequences || start != index { parts.append(self[start..<index]) }
        return parts
    }
}

/// A count that any thread adds to (how many times something costly was done), read by the tests.
final class CallCount: @unchecked Sendable {
    private let lock = NSLock()
    private var value = 0

    func add() {
        lock.lock()
        value += 1
        lock.unlock()
    }

    var count: Int {
        lock.lock()
        defer { lock.unlock() }
        return value
    }
}
