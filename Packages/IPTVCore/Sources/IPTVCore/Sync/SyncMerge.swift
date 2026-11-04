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

        let profiles = mergeProfiles(left, right, deleted: deleted)
        // Lists shared with every profile (rule 9): a list is in one space only, the household's or a profile's.
        let mapped = { (list: SyncList) in withPlaylists(list, id) }
        let keptProfiles = Set(profiles.map(\.id)).subtracting([SyncProfile.main])
        let spaces = sharedSpaces(left, right, keptProfiles: keptProfiles, mainDeleted: deleted, mapped: mapped)
        let shared = sharedLists(spaces)
        for (key, time) in shared.raised[.main] ?? [:] { deleted[key] = max(deleted[key] ?? time, time) }
        func outside(_ lists: [SyncList]) -> [SyncList] { lists.filter { !shared.resolved.contains(Array($0.key.utf16)) } }

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
            outside((left.lists + right.lists).map(mapped)) + (shared.lists[.main] ?? []),
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
        let keptPlaylists = kept.sorted { before(($0.addedAt, $0.id), ($1.addedAt, $1.id)) }
        // The profiles' buckets without the lists rule 9 placed, then those it placed in each, with its deletions.
        let buckets = (left.profileData + right.profileData).map { bucket in
            var bucket = bucket
            bucket.lists = outside(bucket.lists.map(mapped))
            return bucket
        } + keptProfiles.sorted { $0.isBefore($1) }.compactMap { profile -> SyncProfileData? in
            let lists = shared.lists[.profile(profile)] ?? [], raised = shared.raised[.profile(profile)] ?? [:]
            return lists.isEmpty && raised.isEmpty ? nil : SyncProfileData(profile: profile, lists: lists, deleted: raised)
        }
        let profileData = mergeProfileData(buckets, profiles: profiles, playlists: keptPlaylists, deleted: deleted, replaced: replaced)
        // The household: its lists merged by the same rules, as a space of their own.
        let household = mergeHousehold(left.household, right.household, placed: shared.lists[.household],
                                       playlists: keptPlaylists, deleted: deleted, replaced: replaced) { outside($0.map(mapped)) }

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
        result.household = household
        result.deleted = deleted
        result.replaced = replaced
        return result
    }

    /// Rule 8: the profiles of both sides, the later change winning; a profile's deletion wins only at or after its latest
    /// time (its `updatedAt` and every time of its data); the main profile is never deleted.
    private static func mergeProfiles(_ left: SyncSnapshot, _ right: SyncSnapshot, deleted: [String: Int64]) -> [SyncProfile] {
        // Profiles: the later change wins; a deleted profile goes with its data (the main one is never deleted). A side
        // without profiles (an older version) changes none.
        // A profile's deletion loses to its data changed after it, as a list's does (rule 7): data added on a device that
        // had not heard of the deletion brings the profile back, never goes with it.
        var activity: [String: Int64] = [:]
        for bucket in left.profileData + right.profileData {
            if let latest = bucket.records().latestTime { activity[bucket.profile] = max(activity[bucket.profile] ?? latest, latest) }
        }
        let profileDeletions = deleted.filter { key, time in
            guard key != SyncProfile.keyPrefix + SyncProfile.main else { return false }
            guard key.hasPrefix(SyncProfile.keyPrefix) else { return true }
            return (activity[String(key.dropFirst(SyncProfile.keyPrefix.count))] ?? .min) <= time
        }
        return SyncProfile.capped(
            pick(left.profiles + right.profiles, key: \.key, time: \.updatedAt, tie: { $0.tie.isBefore($1.tie) }, deleted: profileDeletions)
        ).sorted { $0.id.isBefore($1.id) }
    }

    /// `list` with the playlist of each item, and of each key of its order, through `id` (rule 1).
    private static func withPlaylists(_ list: SyncList, _ id: (String) -> String) -> SyncList {
        var list = list
        list.items = list.items.map { item in
            var item = item
            item.playlist = id(item.playlist)
            return item
        }
        list.order = list.order?.map { SyncListItem.withPlaylist($0, id) }
        return list
    }

    /// The spaces a list can be in (rule 9), each with its lists from both sides (playlists replaced by `mapped`) and its
    /// deletions: the household, the main profile (whose deletions are `mainDeleted`), and each profile of `keptProfiles`.
    private static func sharedSpaces(_ left: SyncSnapshot, _ right: SyncSnapshot, keptProfiles: Set<String>, mainDeleted: [String: Int64],
                                     mapped: (SyncList) -> SyncList) -> [Space] {
        let leftHousehold = left.household ?? SyncHousehold(), rightHousehold = right.household ?? SyncHousehold()
        var spaces = [Space(id: .household, lists: (leftHousehold.lists + rightHousehold.lists).map(mapped),
                            deleted: leftHousehold.deleted.merging(rightHousehold.deleted, uniquingKeysWith: max)),
                      Space(id: .main, lists: (left.lists + right.lists).map(mapped), deleted: mainDeleted)]
        var profileSpaces: [String: Int] = [:]
        for bucket in left.profileData + right.profileData where keptProfiles.contains(bucket.profile) {
            if let index = profileSpaces[bucket.profile] {
                spaces[index].lists += bucket.lists.map(mapped)
                spaces[index].deleted.merge(bucket.deleted, uniquingKeysWith: max)
            } else {
                profileSpaces[bucket.profile] = spaces.count
                spaces.append(Space(id: .profile(bucket.profile), lists: bucket.lists.map(mapped), deleted: bucket.deleted))
            }
        }
        return spaces
    }

    /// The household of the merge: both sides' lists (without those rule 9 placed: `outside`), then those it placed there,
    /// merged by rules 2 to 7 as a snapshot of their own whose playlists are the merged ones. None when neither side has
    /// one (no list shared, or an older version) and rule 9 placed none there.
    private static func mergeHousehold(_ left: SyncHousehold?, _ right: SyncHousehold?, placed: [SyncList]?, playlists: [SyncPlaylist],
                                       deleted: [String: Int64], replaced: [String: String],
                                       outside: ([SyncList]) -> [SyncList]) -> SyncHousehold? {
        guard left != nil || right != nil || placed != nil else { return nil }
        let deletedPlaylists = deleted.filter { $0.key.hasPrefix("playlist:") }
        let sides = [left, right].compactMap { $0 }.map { side -> SyncHousehold in
            var side = side
            side.lists = outside(side.lists)
            return side
        } + [SyncHousehold(lists: placed ?? [])]
        // Merged with an empty side first, as a profile's data: one side alone gets the playlists' outcome too.
        let records = sides.reduce(SyncHousehold().records(playlists: playlists, deletedPlaylists: deletedPlaylists, replaced: replaced)) { result, side in
            merge(result, side.records(playlists: playlists, deletedPlaylists: deletedPlaylists, replaced: replaced))
        }
        let household = SyncHousehold(records: records)
        return household.isEmpty ? nil : household
    }

    /// Where a list is: the household (shared with every profile), the main profile, or another profile. Ties between
    /// spaces go in this order (the household, the main profile, then the others by ID).
    enum SpaceID: Hashable {
        case household
        case main
        case profile(String)

        func isBefore(_ other: SpaceID) -> Bool {
            switch (self, other) {
            case (.household, .household), (.main, .main): return false
            case (.household, _): return true
            case (_, .household): return false
            case (.main, _): return true
            case (_, .main): return false
            case let (.profile(first), .profile(second)): return first.isBefore(second)
            }
        }
    }

    /// The lists of one space from both sides (playlists already replaced), and its deletions.
    struct Space {
        let id: SpaceID
        var lists: [SyncList]
        var deleted: [String: Int64]
    }

    /// What rule 9 decides: the lists it resolved (by key, as UTF-16), each placed in one space, and the deletions it adds.
    struct SharedOutcome {
        var resolved: Set<[UInt16]> = []
        var lists: [SpaceID: [SyncList]] = [:]
        var raised: [SpaceID: [String: Int64]] = [:]
    }

    /// Rule 9 of the merge (docs/SYNC.md, "Shared lists"): a list found in two spaces or more (moved between a profile and
    /// the household on one device while another still had it where it was), or found outside the household while the
    /// household deleted it, ends in one space. A space keeps its copies when their latest `updatedAt` is after its own
    /// deletion of the list and, outside the household, after the household's (a list deleted for every profile goes from
    /// every profile). Of the spaces that keep it, the one it was last moved into wins (the latest `placedAt`, then the
    /// household, the main profile, the other profiles by ID); the list there is every kept copy merged by rule 7, without
    /// the items taken out in any space. Every other profile space that had a copy gets the list's deletion at that copy's
    /// latest time, so that a device that does not share lists drops it too. Without any space keeping it, the list goes.
    static func sharedLists(_ spaces: [Space]) -> SharedOutcome {
        var outcome = SharedOutcome()
        guard let household = spaces.first(where: { $0.id == .household }) else { return outcome }
        var copies: [[UInt16]: [SpaceID: [SyncList]]] = [:]
        var keys: [[UInt16]] = []
        for space in spaces {
            for list in space.lists {
                let key = Array(list.key.utf16)
                if copies[key] == nil { keys.append(key) }
                copies[key, default: [:]][space.id, default: []].append(list)
            }
        }
        for key in keys {
            guard let bySpace = copies[key], let first = bySpace.values.first?.first else { continue }
            let removedForAll = household.deleted[first.key]
            guard bySpace.count >= 2 || (removedForAll != nil && bySpace.keys.contains { $0 != .household }) else { continue }
            outcome.resolved.insert(key)
            let ordered = spaces.filter { bySpace[$0.id] != nil }
            func latest(_ space: Space) -> Int64 { bySpace[space.id]?.map(\.updatedAt).max() ?? .min }
            let keeping = ordered.filter { space in
                let time = latest(space)
                if let removed = space.deleted[first.key], time <= removed { return false }
                if space.id != .household, let removedForAll, time <= removedForAll { return false }
                return true
            }
            func placed(_ space: Space) -> Int64 { bySpace[space.id]?.compactMap(\.placedAt).max() ?? 0 }
            let winner = keeping.min { a, b in placed(a) != placed(b) ? placed(a) > placed(b) : a.id.isBefore(b.id) }
            for space in ordered where space.id != winner?.id && space.id != .household {
                let time = latest(space)
                if space.deleted[first.key].map({ time > $0 }) ?? true {
                    outcome.raised[space.id, default: [:]][first.key] = time
                }
            }
            guard let winner else { continue }
            // The items taken out of the list in any space (its ID is in their keys).
            let itemPrefix = SyncList.itemPrefix + first.id + "|"
            var itemDeletions: [String: Int64] = [:]
            for space in spaces {
                for (deletion, time) in space.deleted where deletion.utf16.starts(with: itemPrefix.utf16) {
                    itemDeletions[deletion] = max(itemDeletions[deletion] ?? time, time)
                }
            }
            let merged = mergeLists(keeping.flatMap { bySpace[$0.id] ?? [] }, deleted: itemDeletions)
            outcome.lists[winner.id, default: []] += merged
        }
        return outcome
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
            // When it was last moved into this space, if ever (rule 9).
            list.placedAt = same.compactMap(\.placedAt).max()
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
