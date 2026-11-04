import Foundation

/// What a device sends to sync on the local network: its playlists, My List and favourite channels, the people kept,
/// lists, playback progress, the keys of outside services and the order of the rows of the Movies and Series tabs, and
/// what it deleted (docs/SYNC.md). Times are milliseconds since 1970.
///
/// The same document as the Kotlin `SyncSnapshot` (android/core): both read the examples of `fixtures/sync`.
public struct SyncSnapshot: Codable, Hashable, Sendable {
    public static let typeName = "ondo-sync"
    public static let currentVersion = 1
    public static let deletionsKeptDays = 180
    /// A device not synced with for this long no longer holds deletions back.
    public static let peersKeptDays = 365
    static let dayMilliseconds: Int64 = 86_400_000

    public var type: String
    public var version: Int
    public var device: String
    public var deviceName: String?
    public var playlists: [SyncPlaylist]
    public var favorites: [SyncFavorite]
    public var progress: [SyncProgress]
    public var lists: [SyncList]
    /// The actors and directors kept in My List (whatever the playlist); none from a device that does not keep any.
    public var people: [SyncPerson]
    /// The keys typed in Settings for outside services (the TMDb token); none from a device that does not sync them.
    public var keys: [SyncKey]
    /// The order of the rows of the Movies and Series tabs, and the rows hidden; none from a device that does not sync them.
    public var rows: [SyncRows]
    public var deleted: [String: Int64]
    /// Playlist IDs that became another playlist (the same playlist added on two devices): old ID → new ID.
    public var replaced: [String: String]
    /// The sender's clock when it sent the snapshot; receivers ignore it (docs/SYNC.md, "Clocks").
    public var clock: Int64?
    /// The sender's digest of its own data (docs/SYNC.md, "Transport"); none from an older version.
    public var digest: String?
    /// In a probe: the server's `digest` the client got at their last session.
    public var seen: String?
    /// A probe: no records, only `digest` and `seen`; a server answers `unchanged` when it has not changed since.
    public var probe: Bool?
    /// An answer to a probe: nothing changed on the server since the client's last session; nothing to merge.
    public var unchanged: Bool?

    public var isProbe: Bool { probe == true }
    public var isUnchanged: Bool { unchanged == true }

    public init(
        type: String = SyncSnapshot.typeName,
        version: Int = SyncSnapshot.currentVersion,
        device: String,
        deviceName: String? = nil,
        playlists: [SyncPlaylist] = [],
        favorites: [SyncFavorite] = [],
        progress: [SyncProgress] = [],
        lists: [SyncList] = [],
        people: [SyncPerson] = [],
        keys: [SyncKey] = [],
        rows: [SyncRows] = [],
        deleted: [String: Int64] = [:],
        replaced: [String: String] = [:],
        clock: Int64? = nil,
        digest: String? = nil,
        seen: String? = nil,
        probe: Bool? = nil,
        unchanged: Bool? = nil
    ) {
        self.type = type
        self.version = version
        self.device = device
        self.deviceName = deviceName
        self.playlists = playlists
        self.favorites = favorites
        self.progress = progress
        self.lists = lists
        self.people = people
        self.keys = keys
        self.rows = rows
        self.deleted = deleted
        self.replaced = replaced
        self.clock = clock
        self.digest = digest
        self.seen = seen
        self.probe = probe
        self.unchanged = unchanged
    }

    enum CodingKeys: String, CodingKey {
        case type, version, device, deviceName, playlists, favorites, progress, lists, people, keys, rows, deleted, replaced, clock
        case digest, seen, probe, unchanged
    }

    /// A missing field takes its default; a field present with the wrong type, or `null` where a value is needed, makes
    /// the snapshot unreadable (as in Kotlin). Unknown fields are ignored.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        type = try c.value(.type, default: Self.typeName)
        version = try c.value(.version, default: Self.currentVersion)
        device = try c.decode(String.self, forKey: .device)
        deviceName = try c.decodeIfPresent(String.self, forKey: .deviceName)
        playlists = try c.value(.playlists, default: [])
        favorites = try c.value(.favorites, default: [])
        progress = try c.value(.progress, default: [])
        lists = try c.value(.lists, default: [])
        people = try c.value(.people, default: [])
        keys = try c.value(.keys, default: [])
        rows = try c.value(.rows, default: [])
        deleted = try c.value(.deleted, default: [:])
        replaced = try c.value(.replaced, default: [:])
        clock = try c.decodeIfPresent(Int64.self, forKey: .clock)
        digest = try c.decodeIfPresent(String.self, forKey: .digest)
        seen = try c.decodeIfPresent(String.self, forKey: .seen)
        probe = try c.decodeIfPresent(Bool.self, forKey: .probe)
        unchanged = try c.decodeIfPresent(Bool.self, forKey: .unchanged)
    }

    public func encoded() throws -> Data {
        try JSONEncoder().encode(self)
    }

    /// The records and deletions alone, encoded the same way each time (keys sorted), without who sends them or when,
    /// nor the progress records whose key is in `leavingOut` (an item playing, saved every few seconds): what a device
    /// hashes into its `digest` (the same data gives the same digest). A device compares a digest only with its own
    /// earlier ones and those a peer sent it.
    public func contentForDigest(leavingOut: Set<String> = []) throws -> Data {
        var content = self
        if !leavingOut.isEmpty { content.progress = progress.filter { !leavingOut.contains($0.key) } }
        content.device = ""
        content.deviceName = nil
        content.clock = nil
        content.digest = nil
        content.seen = nil
        content.probe = nil
        content.unchanged = nil
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(content)
    }

    /// What a client sends a server (docs/SYNC.md, "Transport"): a probe when its data is the same as in the snapshot it
    /// last sent (`sentDigest`) and the server answered with a digest (`answerDigest`); else this snapshot, with its
    /// `digest` (`hash` gives it from `contentForDigest(leavingOut:)`). `full`: the regular full session, never a probe.
    public func offer(sentDigest: String?, answerDigest: String?, full: Bool = false, leavingOut: Set<String> = [],
                      hash: (Data) -> String) throws -> SyncSnapshot {
        let digest = hash(try contentForDigest(leavingOut: leavingOut))
        if !full, let answerDigest, digest == sentDigest { return probe(digest: digest, seen: answerDigest) }
        var copy = self
        copy.digest = digest
        return copy
    }

    /// `offer` against `sent`, the snapshot last sent in full to that server (nil: none yet): its digest is the one for
    /// the same `leavingOut`, so that an item that started playing since (its progress saved every few seconds) does not
    /// make every offer a full one. Returns the offer, and what to keep as sent once the server has it: for a full offer
    /// the offer itself, for a probe `sent` with its digest for `leavingOut`.
    public func offer(sent: SentSnapshot?, answerDigest: String?, full: Bool = false, leavingOut: Set<String> = [],
                      hash: (Data) -> String) throws -> (snapshot: SyncSnapshot, sent: SentSnapshot?) {
        let now = try sent?.leaving(leavingOut, hash: hash)
        let offer = try offer(sentDigest: now?.digest, answerDigest: answerDigest, full: full, leavingOut: leavingOut, hash: hash)
        guard !offer.isProbe, let digest = offer.digest else { return (offer, now) }
        return (offer, SentSnapshot(snapshot: offer, leavingOut: leavingOut, digest: digest))
    }

    /// A probe from this snapshot's device (docs/SYNC.md, "Transport"): its `digest`, and the server's `seen` one.
    public func probe(digest: String, seen: String) -> SyncSnapshot {
        SyncSnapshot(device: device, deviceName: deviceName, clock: clock, digest: digest, seen: seen, probe: true)
    }

    /// Reads a snapshot, with its IDs in lowercase; nil when it is not one, or of another version (it is refused, not
    /// guessed).
    public static func decode(_ data: Data) -> SyncSnapshot? {
        guard let snapshot = try? JSONDecoder().decodeCheckingKeys(SyncSnapshot.self, from: data),
              snapshot.type == typeName, snapshot.version == currentVersion else { return nil }
        return snapshot.normalized()
    }

    /// The same snapshot without deletions older than `days` before `now` (and the replacements they ended), nor older
    /// than the last session with each device of `sessions` (when it last synced with this one), for the devices seen
    /// in the last `peersKeptDays` days: a deletion goes only once every such device has had it (docs/SYNC.md,
    /// "Deleted").
    public func pruned(now: Int64, days: Int = SyncSnapshot.deletionsKeptDays, sessions: [Int64] = [],
                       learned: [String: Int64] = [:]) -> SyncSnapshot {
        let recent = sessions.filter { $0 >= now - Int64(Self.peersKeptDays) * Self.dayMilliseconds }
        let oldest = min(now - Int64(days) * Self.dayMilliseconds, recent.min() ?? .max)
        // A deletion learned late (from a device away for months) counts from when this device learned it.
        let kept = deleted.filter { max($0.value, learned[$0.key] ?? $0.value) >= oldest }
        var copy = self
        copy.deleted = kept
        copy.replaced = replaced.filter { kept["playlist:" + $0.key] != nil }
        return copy
    }

    /// Every time of the snapshot: of the records, of the items and people of the lists, and of the deletions.
    public var times: [Int64] {
        var times: [Int64] = []
        for playlist in playlists { times += [playlist.addedAt, playlist.updatedAt] }
        times += favorites.map(\.addedAt)
        times += progress.map(\.watchedAt)
        for list in lists {
            times.append(list.updatedAt)
            if let renamedAt = list.renamedAt { times.append(renamedAt) }
            if let orderedAt = list.orderedAt { times.append(orderedAt) }
            times += list.items.compactMap(\.addedAt)
            times += list.people.map(\.addedAt)
        }
        times += people.map(\.addedAt)
        times += keys.map(\.updatedAt)
        times += rows.map(\.updatedAt)
        times += deleted.values
        return times
    }

    /// The latest of `times`; nil for a snapshot without any.
    public var latestTime: Int64? { times.max() }

    /// `a + b`, held at the ends of Int64's range instead of trapping.
    static func saturated(_ a: Int64, _ b: Int64) -> Int64 {
        let (sum, overflow) = a.addingReportingOverflow(b)
        return overflow ? (b < 0 ? .min : .max) : sum
    }

    /// The same snapshot without the records and deletions dated after `latest` (`SyncClock.receive`, docs/SYNC.md,
    /// "Clocks"): one device whose clock is far ahead cannot date its changes in the future and win over every later
    /// one, nor push the others' clocks there. They are left out, not moved: the device that made them keeps them, and
    /// they travel once their time has come. Applied by the device that receives a snapshot, before the merge.
    public func withoutRecordsAfter(_ latest: Int64) -> SyncSnapshot {
        guard latest != .max else { return self }
        func past(_ time: Int64) -> Bool { time <= latest }
        var copy = self
        copy.playlists = playlists.filter { past($0.addedAt) && past($0.updatedAt) }
        copy.favorites = favorites.filter { past($0.addedAt) }
        copy.progress = progress.filter { past($0.watchedAt) }
        copy.lists = lists.filter { past($0.updatedAt) && past($0.renamedAt ?? $0.updatedAt) && past($0.orderedAt ?? $0.updatedAt) }.map { list in
            var list = list
            list.items = list.items.filter { past($0.addedAt ?? list.updatedAt) }
            list.people = list.people.filter { past($0.addedAt) }
            return list
        }
        copy.people = people.filter { past($0.addedAt) }
        copy.keys = keys.filter { past($0.updatedAt) }
        copy.rows = rows.filter { past($0.updatedAt) }
        copy.deleted = deleted.filter { past($0.value) }
        return copy
    }

    /// Playlist and list IDs in lowercase, as every device writes them, in the records and in the keys of what was
    /// deleted (two keys that become one keep the later time).
    public func normalized() -> SyncSnapshot {
        var copy = self
        copy.playlists = playlists.map { playlist in
            var playlist = playlist
            playlist.id = playlist.id.lowercased()
            return playlist
        }
        copy.favorites = favorites.map { favorite in
            var favorite = favorite
            favorite.playlist = favorite.playlist.lowercased()
            return favorite
        }
        copy.progress = progress.map { progress in
            var progress = progress
            progress.playlist = progress.playlist.lowercased()
            return progress
        }
        copy.lists = lists.map { list in
            var list = list
            list.id = list.id.lowercased()
            list.items = list.items.map { item in
                var item = item
                item.playlist = item.playlist.lowercased()
                return item
            }
            list.order = list.order?.map { SyncListItem.withPlaylist($0) { $0.lowercased() } }
            return list
        }
        copy.rows = SyncRows.capped(rows.map { $0.normalized() })
        copy.deleted = Dictionary(deleted.map { (Self.lowercasingID(ofDeleted: $0.key), $0.value) }, uniquingKeysWith: max)
        copy.replaced = Dictionary(replaced.map { ($0.key.lowercased(), $0.value.lowercased()) }, uniquingKeysWith: { _, last in last })
        return copy
    }

    /// The key of a deleted record with its playlist or list ID in lowercase: `playlist:<id>`, `list:<id>`, and the
    /// playlist of `favorite:<playlist>|…` and `progress:<playlist>|…`; the name of `person:<name>` with A to Z in
    /// lowercase. The rest (the provider's item IDs, the service of `key:`) keeps its case.
    static func lowercasingID(ofDeleted key: String) -> String {
        let scalars = key.unicodeScalars
        if scalars.starts(with: SyncPerson.keyPrefix.unicodeScalars) {
            return SyncPerson.keyPrefix + SyncPerson.folded(String(scalars.dropFirst(SyncPerson.keyPrefix.unicodeScalars.count)))
        }
        if scalars.starts(with: SyncList.itemPrefix.unicodeScalars) {
            let rest = scalars.dropFirst(SyncList.itemPrefix.unicodeScalars.count)
            guard let listEnd = rest.firstIndex(of: "|") else { return key.lowercased() }
            let list = SyncList.itemPrefix + String(rest[..<listEnd]).lowercased()
            let item = String(rest[rest.index(after: listEnd)...])
            if item.unicodeScalars.starts(with: SyncPerson.keyPrefix.unicodeScalars) { return list + "|" + lowercasingID(ofDeleted: item) }
            guard let bar = item.unicodeScalars.firstIndex(of: "|") else { return list + "|" + item.lowercased() }
            return list + "|" + String(item.unicodeScalars[..<bar]).lowercased() + String(item.unicodeScalars[bar...])
        }
        for prefix in ["playlist:", "list:", "favorite:", "progress:"] where scalars.starts(with: prefix.unicodeScalars) {
            let rest = String(scalars.dropFirst(prefix.unicodeScalars.count))
            guard prefix == "favorite:" || prefix == "progress:", let bar = rest.unicodeScalars.firstIndex(of: "|") else {
                return prefix + rest.lowercased()
            }
            return prefix + String(rest.unicodeScalars[..<bar]).lowercased() + String(rest.unicodeScalars[bar...])
        }
        return key
    }
}

public struct SyncPlaylist: Codable, Hashable, Sendable {
    public static let m3u = "m3u"
    public static let xtream = "xtream"

    public var id: String
    public var name: String
    /// `m3u` or `xtream`.
    public var kind: String
    /// The M3U address, or the Xtream server.
    public var url: String
    public var username: String?
    public var password: String?
    public var epgUrl: String?
    public var addedAt: Int64
    public var updatedAt: Int64

    public init(id: String, name: String, kind: String, url: String, username: String? = nil, password: String? = nil,
                epgUrl: String? = nil, addedAt: Int64, updatedAt: Int64) {
        self.id = id
        self.name = name
        self.kind = kind
        self.url = url
        self.username = username
        self.password = password
        self.epgUrl = epgUrl
        self.addedAt = addedAt
        self.updatedAt = updatedAt
    }

    public var key: String { "playlist:" + id }
}

/// My List (movies, series) and favourite channels (`live`).
public struct SyncFavorite: Codable, Hashable, Sendable {
    public var playlist: String
    /// `live`, `movie` or `series`.
    public var kind: String
    public var item: String
    public var title: String
    public var image: String?
    public var addedAt: Int64

    public init(playlist: String, kind: String, item: String, title: String, image: String? = nil, addedAt: Int64) {
        self.playlist = playlist
        self.kind = kind
        self.item = item
        self.title = title
        self.image = image
        self.addedAt = addedAt
    }

    public var key: String { "favorite:\(playlist)|\(kind)|\(item)" }
}

/// Where a movie or an episode was left.
public struct SyncProgress: Codable, Hashable, Sendable {
    public var playlist: String
    /// `movie` or `series`.
    public var kind: String
    /// The movie, or the series.
    public var item: String
    public var episode: String?
    public var title: String
    public var subtitle: String?
    public var image: String?
    /// Seconds.
    public var position: Double
    public var duration: Double
    public var watchedAt: Int64

    public init(playlist: String, kind: String, item: String, episode: String? = nil, title: String, subtitle: String? = nil,
                image: String? = nil, position: Double, duration: Double, watchedAt: Int64) {
        self.playlist = playlist
        self.kind = kind
        self.item = item
        self.episode = episode
        self.title = title
        self.subtitle = subtitle
        self.image = image
        self.position = position
        self.duration = duration
        self.watchedAt = watchedAt
    }

    public var key: String { "progress:\(playlist)|\(kind)|\(item)|\(episode ?? "")" }
}

public struct SyncList: Codable, Hashable, Sendable {
    public var id: String
    public var name: String
    public var items: [SyncListItem]
    /// The actors and directors in the list; none from a device that does not keep any.
    public var people: [SyncPerson]
    public var updatedAt: Int64
    /// When the name was last set; `updatedAt` when absent (an older version).
    public var renamedAt: Int64?
    /// When the items were last put in order; `updatedAt` when absent (an older version).
    public var orderedAt: Int64?
    /// The order of the items set at `orderedAt`, by `SyncListItem.key`; the order of `items` when absent.
    public var order: [String]?

    public static let itemPrefix = "listitem:"

    public init(id: String, name: String, items: [SyncListItem] = [], people: [SyncPerson] = [], updatedAt: Int64,
                renamedAt: Int64? = nil, orderedAt: Int64? = nil, order: [String]? = nil) {
        self.id = id
        self.name = name
        self.items = items
        self.people = people
        self.updatedAt = updatedAt
        self.renamedAt = renamedAt
        self.orderedAt = orderedAt
        self.order = order
    }

    enum CodingKeys: String, CodingKey { case id, name, items, people, updatedAt, renamedAt, orderedAt, order }

    public var nameTime: Int64 { renamedAt ?? updatedAt }
    public var orderTime: Int64 { orderedAt ?? updatedAt }
    public var itemOrder: [String] { order ?? items.map(\.key) }

    /// The key of the removal of an item or a person (`SyncListItem.key`, `SyncPerson.key`) from this list.
    public func itemKey(_ item: String) -> String { Self.itemPrefix + id + "|" + item }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        items = try c.value(.items, default: [])
        people = try c.value(.people, default: [])
        updatedAt = try c.decode(Int64.self, forKey: .updatedAt)
        renamedAt = try c.decodeIfPresent(Int64.self, forKey: .renamedAt)
        orderedAt = try c.decodeIfPresent(Int64.self, forKey: .orderedAt)
        order = try c.decodeIfPresent([String].self, forKey: .order)
    }

    public var key: String { "list:" + id }
}

public struct SyncListItem: Codable, Hashable, Sendable {
    public var playlist: String
    public var kind: String
    public var item: String
    public var title: String
    public var image: String?
    /// When it was put in the list; the list's `updatedAt` when absent (an older version).
    public var addedAt: Int64?

    public init(playlist: String, kind: String, item: String, title: String, image: String? = nil, addedAt: Int64? = nil) {
        self.playlist = playlist
        self.kind = kind
        self.item = item
        self.title = title
        self.image = image
        self.addedAt = addedAt
    }

    public var key: String { "\(playlist)|\(kind)|\(item)" }

    /// An item `key` with its playlist ID through `playlist`.
    static func withPlaylist(_ key: String, _ playlist: (String) -> String) -> String {
        guard let bar = key.unicodeScalars.firstIndex(of: "|") else { return key }
        return playlist(String(key.unicodeScalars[..<bar])) + String(key.unicodeScalars[bar...])
    }
}

/// An actor or a director kept in My List or in a list, by name, whatever the playlist.
public struct SyncPerson: Codable, Hashable, Sendable {
    static let keyPrefix = "person:"

    public var name: String
    /// Their photo (a web address).
    public var image: String?
    public var addedAt: Int64

    public init(name: String, image: String? = nil, addedAt: Int64) {
        self.name = name
        self.image = image
        self.addedAt = addedAt
    }

    /// `person:<name>` with only A to Z in lowercase: the same on every platform, whatever its case rules.
    public var key: String { Self.keyPrefix + Self.folded(name) }

    static func folded(_ name: String) -> String {
        String(String.UnicodeScalarView(name.unicodeScalars.map { scalar in
            ("A"..."Z").contains(scalar) ? Unicode.Scalar(scalar.value + 32)! : scalar
        }))
    }
}

/// The key or token of an outside service, typed in Settings: `service` `tmdb` for TMDb's token (or API Key).
public struct SyncKey: Codable, Hashable, Sendable {
    public static let tmdb = "tmdb"

    public var service: String
    public var value: String
    public var updatedAt: Int64

    public init(service: String, value: String, updatedAt: Int64) {
        self.service = service
        self.value = value
        self.updatedAt = updatedAt
    }

    public var key: String { "key:" + service }
}

/// The rows of a tab of the Movies or Series tabs (`tab` `movie` or `series`) in the order the viewer set (`order`, keys
/// of `RowOrder`: `all`, `continue`, … and `cat:<id>|<name>` for a category), and the rows they hid (`hidden`). One
/// record per tab, the whole of it set at once: the later `updatedAt` wins (docs/SYNC.md, "Rows").
public struct SyncRows: Codable, Hashable, Sendable {
    public static let movies = "movie"
    public static let series = "series"

    public var tab: String
    public var order: [String]
    public var hidden: [String]
    public var updatedAt: Int64

    public init(tab: String, order: [String] = [], hidden: [String] = [], updatedAt: Int64) {
        self.tab = tab
        self.order = order
        self.hidden = hidden
        self.updatedAt = updatedAt
    }

    enum CodingKeys: String, CodingKey { case tab, order, hidden, updatedAt }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        tab = try c.decode(String.self, forKey: .tab)
        order = try c.value(.order, default: [])
        hidden = try c.value(.hidden, default: [])
        updatedAt = try c.decode(Int64.self, forKey: .updatedAt)
    }

    public var key: String { "rows:" + tab }

    /// The tie string of the merge (docs/SYNC.md, "Merge", rule 2): unambiguous, as no key holds a line feed.
    var tie: String { "\(order.count)\n" + (order + hidden).joined(separator: "\n") }

    /// As every device keeps it: keys without control characters and not blank, `order` each once (the first),
    /// `hidden` each once and sorted (UTF-16 code units). Keys are the same when their UTF-16 code units are, as in
    /// Kotlin (Swift's `==` would also join canonically equivalent strings).
    public func normalized() -> SyncRows {
        var copy = self
        var seen = Set<[UInt16]>()
        copy.order = Array(order.filter { Self.isValid($0) && seen.insert(Array($0.utf16)).inserted }.prefix(Self.maxKeys))
        var seenHidden = Set<[UInt16]>()
        copy.hidden = Array(hidden.filter { Self.isValid($0) && seenHidden.insert(Array($0.utf16)).inserted }
            .sorted { $0.isBefore($1) }.prefix(Self.maxKeys))
        return copy
    }

    /// At most this many tabs in a snapshot, keys in `order` and in `hidden`, and UTF-16 units in a key or a tab.
    public static let maxTabs = 8
    public static let maxKeys = 2000
    public static let maxKeyLength = 256

    /// Not longer than `maxKeyLength` UTF-16 units, not blank (as Kotlin's `isNotBlank`), and no control characters
    /// (below U+0020, and U+007F).
    static func isValid(_ key: String) -> Bool {
        key.utf16.count <= maxKeyLength && !key.utf16.contains { $0 < 0x20 || $0 == 0x7F } && !SyncMerge.kotlinTrimmed(key).isEmpty
    }

    /// `rows` without the records of a tab that is not a valid key, and of the tabs past the `maxTabs` first (`movie`
    /// and `series` first, then the others by UTF-16 code units); the records kept stay in their order.
    static func capped(_ rows: [SyncRows]) -> [SyncRows] {
        let valid = rows.filter { isValid($0.tab) }
        var tabs: [String] = []
        var seen = Set<[UInt16]>()
        for row in valid where seen.insert(Array(row.tab.utf16)).inserted { tabs.append(row.tab) }
        guard tabs.count > maxTabs else { return valid }
        let known = [movies, series].filter { tab in tabs.contains { $0.utf16.elementsEqual(tab.utf16) } }
        let others = tabs.filter { tab in !known.contains { $0.utf16.elementsEqual(tab.utf16) } }.sorted { $0.isBefore($1) }
        let kept = Set((known + others).prefix(maxTabs).map { Array($0.utf16) })
        return valid.filter { kept.contains(Array($0.tab.utf16)) }
    }
}

private extension KeyedDecodingContainer {
    /// The value of `key`, or `fallback` when the key is absent (an explicit `null` is an error, as in Kotlin).
    func value<T: Decodable>(_ key: Key, default fallback: T) throws -> T {
        guard contains(key) else { return fallback }
        return try decode(T.self, forKey: key)
    }
}

/// A snapshot a client sent a server in full, with its `digest` leaving out the progress of `leavingOut` (docs/SYNC.md,
/// "Transport").
public struct SentSnapshot: Sendable {
    public let snapshot: SyncSnapshot
    public let leavingOut: Set<String>
    public let digest: String

    public init(snapshot: SyncSnapshot, leavingOut: Set<String>, digest: String) {
        self.snapshot = snapshot
        self.leavingOut = leavingOut
        self.digest = digest
    }

    /// The same snapshot with its digest leaving out `others` instead: worked out again only when they differ.
    public func leaving(_ others: Set<String>, hash: (Data) -> String) throws -> SentSnapshot {
        others == leavingOut ? self
            : SentSnapshot(snapshot: snapshot, leavingOut: others, digest: hash(try snapshot.contentForDigest(leavingOut: others)))
    }
}
