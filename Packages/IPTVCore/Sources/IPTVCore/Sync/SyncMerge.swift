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
            key: \.key, time: \.addedAt, tie: { tie($0.title, optional($0.image)).isBefore(tie($1.title, optional($1.image))) },
            deleted: deleted
        ).filter { alive($0.playlist) }
        let progress = pick(
            (left.progress + right.progress).map { progress in
                var progress = progress
                progress.playlist = id(progress.playlist)
                return progress
            },
            key: \.key, time: \.watchedAt, tie: progressOrder, deleted: deleted
        ).filter { alive($0.playlist) }
        let lists = mergeLists(
            (left.lists + right.lists).map { list in
                var list = list
                list.items = list.items.map { item in
                    var item = item
                    item.playlist = id(item.playlist)
                    return item
                }
                list.order = list.order?.map { SyncListItem.withPlaylist($0, id) }
                return list
            },
            deleted: deleted
        ).map { list in
            var list = list
            list.items = list.items.filter { alive($0.playlist) }
            return list
        }
        // A side without people (a device that does not keep them) removes none: only a deletion does.
        let people = pick(left.people + right.people, key: \.key, time: \.addedAt, tie: personOrder, deleted: deleted)
        // A side without keys (a device that does not sync them) removes none: only a deletion does.
        let keys = pick(left.keys + right.keys, key: \.key, time: \.updatedAt, tie: { $0.value.isBefore($1.value) }, deleted: deleted)
        // Likewise for the order of the rows: a side without it (an older version) changes none.
        let rows = pick(left.rows + right.rows, key: \.key, time: \.updatedAt, tie: { $0.tie.isBefore($1.tie) }, deleted: deleted)
        // Profiles: the later change wins; a deleted profile goes with its data (the main one is never deleted). A side
        // without profiles (an older version) changes none.
        let profileDeletions = deleted.filter { $0.key != SyncProfile.keyPrefix + SyncProfile.main }
        let profiles = SyncProfile.capped(
            pick(left.profiles + right.profiles, key: \.key, time: \.updatedAt, tie: { $0.tie.isBefore($1.tie) }, deleted: profileDeletions)
        ).sorted { $0.id.isBefore($1.id) }
        let keptPlaylists = kept.sorted { before(($0.addedAt, $0.id), ($1.addedAt, $1.id)) }
        let profileData = mergeProfileData(left.profileData + right.profileData, profiles: profiles, playlists: keptPlaylists,
                                           deleted: deleted, replaced: replaced)

        var result = left
        result.playlists = keptPlaylists
        result.favorites = favorites.sorted { before(($0.addedAt, $0.key), ($1.addedAt, $1.key)) }
        result.progress = progress.sorted { first, second in
            first.watchedAt != second.watchedAt ? first.watchedAt > second.watchedAt : first.key.isBefore(second.key)
        }
        result.lists = lists.sorted { $0.id.isBefore($1.id) }
        result.people = people.sorted { before(($0.addedAt, $0.key), ($1.addedAt, $1.key)) }
        result.keys = keys.sorted { $0.service.isBefore($1.service) }
        result.rows = SyncRows.capped(rows).sorted { $0.tab.isBefore($1.tab) }
        result.profiles = profiles
        result.profileData = profileData
        result.deleted = deleted
        result.replaced = replaced
        return result
    }

    /// The data of each profile kept, other than the main one (docs/SYNC.md, "Profiles"): merged as a snapshot of its own
    /// by the same rules, with the playlists as merged (a favourite of a deleted playlist goes, a replaced playlist's
    /// become the one kept). A profile deleted, or not kept, goes with its data.
    private static func mergeProfileData(_ buckets: [SyncProfileData], profiles: [SyncProfile], playlists: [SyncPlaylist],
                                         deleted: [String: Int64], replaced: [String: String]) -> [SyncProfileData] {
        let kept = Set(profiles.map(\.id))
        let deletedPlaylists = deleted.filter { $0.key.hasPrefix("playlist:") }
        var byProfile: [String: [SyncProfileData]] = [:]
        var order: [String] = []
        for bucket in buckets where bucket.profile != SyncProfile.main && kept.contains(bucket.profile) {
            if byProfile[bucket.profile] == nil { order.append(bucket.profile) }
            byProfile[bucket.profile, default: []].append(bucket)
        }
        return order.map { profile in
            // Merged with an empty side first: one side's bucket alone gets the playlists' outcome too.
            var merged = SyncProfileData(profile: profile).records(playlists: playlists, deletedPlaylists: deletedPlaylists, replaced: replaced)
            for bucket in byProfile[profile] ?? [] {
                merged = merge(merged, bucket.records(playlists: playlists, deletedPlaylists: deletedPlaylists, replaced: replaced))
            }
            return SyncProfileData(profile: profile, records: merged)
        }.sorted { $0.profile.isBefore($1.profile) }
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
        tie(playlist.name, playlist.kind, playlist.url, optional(playlist.username), optional(playlist.password),
            optional(playlist.epgUrl), String(playlist.addedAt))
    }

    /// The larger position wins, then the longer duration, then the smaller title, subtitle and image (none first).
    private static func progressOrder(_ first: SyncProgress, _ second: SyncProgress) -> Bool {
        let positions = kotlinCompare(first.position, second.position)
        if positions != 0 { return positions > 0 }
        let durations = kotlinCompare(first.duration, second.duration)
        if durations != 0 { return durations > 0 }
        return tie(first.title, optional(first.subtitle), optional(first.image))
            .isBefore(tie(second.title, optional(second.subtitle), optional(second.image)))
    }

    /// A tie string (docs/SYNC.md, "Merge", rule 2): the fields joined with a line feed.
    private static func tie(_ fields: String...) -> String {
        fields.joined(separator: "\n")
    }

    /// An optional field in a tie string: `0` when missing, else `1` then its value (none comes before any).
    private static func optional(_ value: String?) -> String {
        value.map { "1" + $0 } ?? "0"
    }

    /// One list per key, merged field by field (docs/SYNC.md, "Merge", rule 7): it stays unless deleted after its last
    /// change; its name is the one set last; its items and people are those of either side, each at its own time,
    /// without those removed since; their order is the one set last, then the others by the time they were added.
    private static func mergeLists(_ lists: [SyncList], deleted: [String: Int64]) -> [SyncList] {
        grouped(lists, by: { Array($0.key.utf16) }).compactMap { same -> SyncList? in
            guard let first = same.first else { return nil }
            let updatedAt = same.map(\.updatedAt).max() ?? first.updatedAt
            if let removed = deleted[first.key], updatedAt <= removed { return nil }
            guard let named = same.min(by: { $0.nameTime != $1.nameTime ? $0.nameTime > $1.nameTime : $0.name.isBefore($1.name) }),
                  let ordered = same.min(by: { a, b in
                      a.orderTime != b.orderTime ? a.orderTime > b.orderTime : orderString(a).isBefore(orderString(b))
                  }) else { return nil }
            func kept(_ item: String, _ time: Int64) -> Bool { deleted[first.itemKey(item)].map { time > $0 } ?? true }
            let allItems = same.flatMap { list in
                list.items.map { item in
                    var item = item
                    item.addedAt = item.addedAt ?? list.updatedAt
                    return item
                }
            }
            let items = grouped(allItems, by: { Array($0.key.utf16) })
                .compactMap { $0.min(by: itemOrder) }
                .filter { kept($0.key, $0.addedAt ?? 0) }
            let people = grouped(same.flatMap(\.people), by: { Array($0.key.utf16) })
                .compactMap { $0.min(by: { $0.addedAt != $1.addedAt ? $0.addedAt > $1.addedAt : personOrder($0, $1) }) }
                .filter { kept($0.key, $0.addedAt) }
            // The order set last is kept as it was set (an order that changes as it is merged would depend on the order of
            // the merges); the items it does not name come after it.
            let order = ordered.itemOrder
            var position: [[UInt16]: Int] = [:]
            for (index, key) in order.enumerated() where position[Array(key.utf16)] == nil {
                position[Array(key.utf16)] = index
            }
            var list = first
            list.name = named.name
            list.items = items.sorted { a, b in
                let first = position[Array(a.key.utf16)] ?? .max, second = position[Array(b.key.utf16)] ?? .max
                if first != second { return first < second }
                return before((a.addedAt ?? 0, a.key), (b.addedAt ?? 0, b.key))
            }
            list.people = people.sorted { before(($0.addedAt, $0.key), ($1.addedAt, $1.key)) }
            list.updatedAt = updatedAt
            list.renamedAt = named.nameTime
            list.orderedAt = ordered.orderTime
            list.order = order
            return list
        }
    }

    private static func orderString(_ list: SyncList) -> String {
        list.itemOrder.joined(separator: "\n")
    }

    /// The later addition wins, then the smaller title, then no image, then the smaller image address.
    private static func itemOrder(_ first: SyncListItem, _ second: SyncListItem) -> Bool {
        let firstTime = first.addedAt ?? 0, secondTime = second.addedAt ?? 0
        if firstTime != secondTime { return firstTime > secondTime }
        return tie(first.title, optional(first.image)).isBefore(tie(second.title, optional(second.image)))
    }

    /// The person with a photo wins, then the smaller name, then the smaller photo address.
    private static func personOrder(_ first: SyncPerson, _ second: SyncPerson) -> Bool {
        if (first.image == nil) != (second.image == nil) { return first.image != nil }
        if !first.name.utf16.elementsEqual(second.name.utf16) { return first.name.isBefore(second.name) }
        return (first.image ?? "").isBefore(second.image ?? "")
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
