import Foundation

/// The rows of the Home, Movies or Series tab in the order the viewer chose, and those they hid (Settings → Row order).
/// A row is known by a key that survives a playlist refresh: a built-in row by its name (`all`, `continueWatching`…), a
/// provider category by its ID and its name (`categoryKey`), so that a category the provider numbers anew is still
/// found by its name. The same rules as the Kotlin `RowOrder` (android/core); the order syncs (docs/SYNC.md, "Rows").
public enum RowOrder {
    /// "All movies" / "All series": the latest additions, then See All the catalogue.
    public static let all = "all"
    public static let continueWatching = "continue"
    public static let myList = "mylist"
    public static let newReleases = "new"
    public static let trending = "trending"
    /// Movies only.
    public static let awardWinners = "awards"
    public static let categoryPrefix = "cat:"

    /// Home's rows (below its carousel, which stays at the top): movies and series in progress, …
    public static let homeContinueMovies = "continue-movies"
    public static let homeContinueSeries = "continue-series"
    public static let homeNewMovies = "new-movies"
    public static let homeNewSeries = "new-series"
    /// Live TV: the channels in My List, or those watched lately.
    public static let homeChannels = "channels"
    /// The viewer's own lists (Apple devices only).
    public static let homeCollections = "collections"

    /// Home's rows in their default order: movies and series in progress, My List, the new movies and series,
    /// Trending, the collections (`collections`: the platforms that show them), the channels last; on a playlist
    /// without movies or series (`hasVOD` false), the channels right after My List.
    public static func homeBuiltIns(hasVOD: Bool, collections: Bool) -> [String] {
        var keys = [homeContinueMovies, homeContinueSeries, myList]
        if !hasVOD { keys.append(homeChannels) }
        keys += [homeNewMovies, homeNewSeries, trending]
        if collections { keys.append(homeCollections) }
        if hasVOD { keys.append(homeChannels) }
        return keys
    }

    /// The built-in rows of a tab, in their default order, above the categories.
    public static func builtIns(series: Bool) -> [String] {
        series ? [all, continueWatching, myList, newReleases, trending]
            : [all, continueWatching, myList, newReleases, trending, awardWinners]
    }

    /// The key of a provider category: `cat:<id>|<name>`, its name as `normalizedName` gives it.
    public static func categoryKey(id: String, name: String) -> String {
        categoryPrefix + clean(id) + "|" + normalizedName(name)
    }

    /// A category name as a key holds it: without control characters, its spaces trimmed and runs of them made one,
    /// A to Z in lowercase (the same on every platform, whatever its case rules); other letters keep their case.
    public static func normalizedName(_ name: String) -> String {
        var units: [UInt16] = []
        units.reserveCapacity(name.utf16.count)
        var space = false
        for unit in name.utf16 {
            if unit == 0x20 || unit < 0x20 || unit == 0x7F {
                space = !units.isEmpty
            } else {
                if space { units.append(0x20) }
                space = false
                units.append((0x41...0x5A).contains(unit) ? unit + 32 : unit)
            }
        }
        return String(decoding: units, as: UTF16.self)
    }

    /// An ID without control characters (a key holds no line feed, so that the sync tie strings are unambiguous).
    private static func clean(_ id: String) -> String {
        String(decoding: id.utf16.filter { $0 >= 0x20 && $0 != 0x7F && $0 != 0x7C }, as: UTF16.self)
    }

    /// A key as sync accepts it: not blank, no control characters.
    public static func isValidKey(_ key: String) -> Bool { SyncRows.isValid(key) }

    /// A row of the tab: its key, and whether the viewer hid it.
    public struct Row: Hashable, Sendable {
        public let key: String
        public let hidden: Bool

        public init(key: String, hidden: Bool) {
            self.key = key
            self.hidden = hidden
        }
    }

    /// The rows of `current` (their keys, in their default order) in the `order` saved, then those it does not name in
    /// their default order (a category new to the playlist goes to the end), each hidden when `hidden` names it. A
    /// saved key no row has (a category the provider removed) is skipped and kept for when it comes back.
    public static func arrange(current: [String], order: [String], hidden: [String]) -> [Row] {
        let placed = matches(current: current, saved: order)
        let hiddenRows = Set(matches(current: current, saved: hidden).values)
        var seen = Set<String>()
        var result: [Row] = []
        result.reserveCapacity(current.count)
        for index in order.indices {
            guard let key = placed[index], seen.insert(key).inserted else { continue }
            result.append(Row(key: key, hidden: hiddenRows.contains(key)))
        }
        for key in current where seen.insert(key).inserted {
            result.append(Row(key: key, hidden: hiddenRows.contains(key)))
        }
        return result
    }

    /// The order to save once the viewer put the rows of the tab in `arranged` (current keys): those keys, with each key
    /// of `previous` that no current row has (a category gone for now) kept after the row it followed, so that it comes
    /// back at its place.
    public static func saving(arranged: [String], previous: [String]) -> [String] {
        let placed = matches(current: arranged, saved: previous)
        var after: [String: [String]] = [:]
        var first: [String] = []
        var anchor: String?
        for (index, key) in previous.enumerated() {
            if let row = placed[index] {
                anchor = row
            } else if isValidKey(key) {
                if let anchor { after[anchor, default: []].append(key) } else { first.append(key) }
            }
        }
        var seen = Set<String>()
        var result: [String] = []
        func add(_ key: String) { if seen.insert(key).inserted { result.append(key) } }
        first.forEach(add)
        for key in arranged {
            add(key)
            after[key]?.forEach(add)
        }
        return result
    }

    /// The keys to save as hidden: the current rows hidden (`hiddenNow`), and the saved ones (`previous`) that no
    /// current row has (a category gone for now, hidden again when it comes back), sorted and each once.
    public static func savingHidden(current: [String], hiddenNow: [String], previous: [String]) -> [String] {
        let placed = matches(current: current, saved: previous)
        let gone = previous.enumerated().filter { placed[$0.offset] == nil && isValidKey($0.element) }.map(\.element)
        return Array(Set(hiddenNow + gone)).sorted { $0.isBefore($1) }
    }

    /// Which current row each saved key names, by its index in `saved`: the same key first, then a category of the same
    /// ID, then one of the same name; each row named once at most.
    private static func matches(current: [String], saved: [String]) -> [Int: String] {
        var result: [Int: String] = [:]
        var used = Set<String>()
        let exact = Set(current)
        for (index, key) in saved.enumerated() where exact.contains(key) && used.insert(key).inserted {
            result[index] = key
        }
        var byID: [String: String] = [:]
        var byName: [String: String] = [:]
        for key in current {
            guard let (id, name) = category(key) else { continue }
            if byID[id] == nil { byID[id] = key }
            if byName[name] == nil { byName[name] = key }
        }
        for pass in 0...1 {
            for (index, key) in saved.enumerated() where result[index] == nil {
                guard let (id, name) = category(key) else { continue }
                let row = pass == 0 ? byID[id] : (name.isEmpty ? nil : byName[name])
                if let row, used.insert(row).inserted { result[index] = row }
            }
        }
        return result
    }

    /// The ID and the name of a category key; nil for a built-in row.
    private static func category(_ key: String) -> (String, String)? {
        let units = Array(key.utf16)
        let prefix = Array(categoryPrefix.utf16)
        guard units.starts(with: prefix) else { return nil }
        let rest = units[prefix.count...]
        guard let bar = rest.firstIndex(of: 0x7C) else { return (String(decoding: rest, as: UTF16.self), "") }
        return (String(decoding: rest[..<bar], as: UTF16.self), String(decoding: rest[(bar + 1)...], as: UTF16.self))
    }
}
