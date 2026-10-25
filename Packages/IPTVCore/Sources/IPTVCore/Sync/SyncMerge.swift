import Foundation

/// Merges two snapshots (docs/SYNC.md, "Merge"): the later change of each record wins, deletions win over records
/// not changed since, the same playlist added on two devices becomes one, and the records of a deleted playlist go
/// with it. The result does not depend on the order of the two snapshots, and merging it again changes nothing.
///
/// A line by line port of the Kotlin `SyncMerge` (android/core): both give the same result for the same snapshots.
/// Strings are compared as UTF-16 code units, as Kotlin compares them (Swift's `<` on strings does not).
public enum SyncMerge {
    static let maxReplacements = 10

    /// `device` and `deviceName` of the result are `a`'s: the device that merges answers with its own.
    public static func merge(_ a: SyncSnapshot, _ b: SyncSnapshot) -> SyncSnapshot {
        let left = a.normalized()
        let right = b.normalized()
        var deleted = left.deleted
        for (key, time) in right.deleted { deleted[key] = max(deleted[key] ?? time, time) }
        var replaced = left.replaced
        for (old, new) in right.replaced { replaced[old] = replaced[old].map { utf16Min($0, new) } ?? new }

        let playlists = pick(left.playlists + right.playlists, key: \.key, time: \.updatedAt, tie: playlistOrder, deleted: deleted)
        // The same playlist added twice: the oldest stays, the others become it.
        var kept: [SyncPlaylist] = []
        for same in grouped(playlists, by: { PlaylistIdentity($0) }) {
            guard let oldest = same.min(by: { before(($0.addedAt, $0.id), ($1.addedAt, $1.id)) }) else { continue }
            for dropped in same where dropped.id != oldest.id {
                deleted[dropped.key] = max(deleted[dropped.key] ?? dropped.updatedAt, dropped.updatedAt)
                replaced[dropped.id] = replaced[dropped.id].map { utf16Min($0, oldest.id) } ?? oldest.id
            }
            kept.append(oldest)
        }
        let keptIDs = Set(kept.map(\.id))
        let replacements = replaced
        func id(_ playlist: String) -> String {
            var current = playlist
            for _ in 0..<maxReplacements {
                guard let next = replacements[current] else { return current }
                current = next
            }
            return current
        }
        // A record of a deleted playlist goes with it; one of a playlist this device does not know stays.
        let removed = deleted
        func alive(_ playlist: String) -> Bool {
            keptIDs.contains(playlist) || removed["playlist:" + playlist] == nil
        }

        let favorites = pick(
            (left.favorites + right.favorites).map { favorite in
                var favorite = favorite
                favorite.playlist = id(favorite.playlist)
                return favorite
            },
            key: \.key, time: \.addedAt, tie: { $0.title.isBefore($1.title) }, deleted: deleted
        ).filter { alive($0.playlist) }
        let progress = pick(
            (left.progress + right.progress).map { progress in
                var progress = progress
                progress.playlist = id(progress.playlist)
                return progress
            },
            key: \.key, time: \.watchedAt, tie: progressOrder, deleted: deleted
        ).filter { alive($0.playlist) }
        let lists = pick(
            (left.lists + right.lists).map { list in
                var list = list
                list.items = list.items.map { item in
                    var item = item
                    item.playlist = id(item.playlist)
                    return item
                }
                return list
            },
            key: \.key, time: \.updatedAt, tie: { listTieString($0).isBefore(listTieString($1)) }, deleted: deleted
        ).map { list in
            var list = list
            list.items = list.items.filter { alive($0.playlist) }
            return list
        }
        // A side without keys (a device that does not sync them) removes none: only a deletion does.
        let keys = pick(left.keys + right.keys, key: \.key, time: \.updatedAt, tie: { $0.value.isBefore($1.value) }, deleted: deleted)

        var result = left
        result.playlists = kept.sorted { before(($0.addedAt, $0.id), ($1.addedAt, $1.id)) }
        result.favorites = favorites.sorted { before(($0.addedAt, $0.key), ($1.addedAt, $1.key)) }
        result.progress = progress.sorted { first, second in
            first.watchedAt != second.watchedAt ? first.watchedAt > second.watchedAt : first.key.isBefore(second.key)
        }
        result.lists = lists.sorted { $0.id.isBefore($1.id) }
        result.keys = keys.sorted { $0.service.isBefore($1.service) }
        result.deleted = deleted
        result.replaced = replaced
        return result
    }

    /// Playlists that are the same subscription: same kind, same address (trimmed, ignoring case), same login.
    private struct PlaylistIdentity: Hashable {
        let kind: [UInt16]
        let url: [UInt16]
        let username: [UInt16]

        init(_ playlist: SyncPlaylist) {
            kind = Array(playlist.kind.utf16)
            url = Array(kotlinTrimmed(playlist.url).lowercased().utf16)
            username = Array((playlist.username ?? "").utf16)
        }
    }

    private static func playlistOrder(_ first: SyncPlaylist, _ second: SyncPlaylist) -> Bool {
        playlistTieString(first).isBefore(playlistTieString(second))
    }

    private static func playlistTieString(_ playlist: SyncPlaylist) -> String {
        [playlist.name, playlist.url, playlist.username ?? "", playlist.password ?? "", playlist.epgUrl ?? ""].joined(separator: "\n")
    }

    /// The larger position wins, then the smaller title.
    private static func progressOrder(_ first: SyncProgress, _ second: SyncProgress) -> Bool {
        let positions = kotlinCompare(first.position, second.position)
        if positions != 0 { return positions > 0 }
        return first.title.isBefore(second.title)
    }

    private static func listTieString(_ list: SyncList) -> String {
        ([list.name] + list.items.map(\.key)).joined(separator: "\n")
    }

    /// One record per key: the later time wins, then the one `tie` puts first (the first one met on a full tie, as
    /// Kotlin's `minWith`); a record not changed after its key's deletion is dropped.
    private static func pick<T>(_ records: [T], key: (T) -> String, time: (T) -> Int64, tie: (T, T) -> Bool,
                                deleted: [String: Int64]) -> [T] {
        grouped(records, by: { Array(key($0).utf16) }).compactMap { same in
            guard let winner = same.min(by: { first, second in
                time(first) != time(second) ? time(first) > time(second) : tie(first, second)
            }) else { return nil }
            if let removed = deleted[key(winner)], time(winner) <= removed { return nil }
            return winner
        }
    }

    /// The records grouped by key, each group in the order the records come (groups in the order of their first record).
    private static func grouped<T, K: Hashable>(_ records: [T], by key: (T) -> K) -> [[T]] {
        var positions: [K: Int] = [:]
        var groups: [[T]] = []
        for record in records {
            let value = key(record)
            if let position = positions[value] {
                groups[position].append(record)
            } else {
                positions[value] = groups.count
                groups.append([record])
            }
        }
        return groups
    }

    /// By time, then by string (as UTF-16 code units).
    private static func before(_ first: (Int64, String), _ second: (Int64, String)) -> Bool {
        first.0 != second.0 ? first.0 < second.0 : first.1.isBefore(second.1)
    }

    private static func utf16Min(_ first: String, _ second: String) -> String {
        second.isBefore(first) ? second : first
    }

    /// Kotlin's `Double.compareTo` (Java's `Double.compare`): -0.0 before 0.0, NaN after everything.
    static func kotlinCompare(_ first: Double, _ second: Double) -> Int {
        if first < second { return -1 }
        if first > second { return 1 }
        let firstBits = Int64(bitPattern: (first.isNaN ? Double.nan : first).bitPattern)
        let secondBits = Int64(bitPattern: (second.isNaN ? Double.nan : second).bitPattern)
        return firstBits == secondBits ? 0 : (firstBits < secondBits ? -1 : 1)
    }

    /// Kotlin's `String.trim()`: drops the characters `Char.isWhitespace` accepts at both ends (Unicode space, line and
    /// paragraph separators, U+0009 to U+000D and U+001C to U+001F).
    static func kotlinTrimmed(_ text: String) -> String {
        let scalars = text.unicodeScalars
        guard let start = scalars.firstIndex(where: { !isKotlinWhitespace($0) }),
              let end = scalars.lastIndex(where: { !isKotlinWhitespace($0) }) else { return "" }
        return String(scalars[start...end])
    }

    private static func isKotlinWhitespace(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 0x09...0x0D, 0x1C...0x1F: return true
        default:
            switch scalar.properties.generalCategory {
            case .spaceSeparator, .lineSeparator, .paragraphSeparator: return true
            default: return false
            }
        }
    }
}

extension String {
    /// Whether this string comes before `other` compared as UTF-16 code units, as Kotlin and Java compare strings.
    func isBefore(_ other: String) -> Bool {
        utf16.lexicographicallyPrecedes(other.utf16)
    }
}
