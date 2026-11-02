import Foundation

/// What a device sends to sync on the local network: its playlists, My List and favourite channels, the people kept,
/// lists, playback progress and the keys of outside services, and what it deleted (docs/SYNC.md). Times are milliseconds since 1970.
///
/// The same document as the Kotlin `SyncSnapshot` (android/core): both read the examples of `fixtures/sync`.
public struct SyncSnapshot: Codable, Hashable, Sendable {
    public static let typeName = "ondo-sync"
    public static let currentVersion = 1
    public static let deletionsKeptDays = 180
    /// How far ahead of the receiving device's clock a time may be (docs/SYNC.md, "Clocks").
    public static let futureMarginMilliseconds: Int64 = 5 * 60_000
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
    public var deleted: [String: Int64]
    /// Playlist IDs that became another playlist (the same playlist added on two devices): old ID → new ID.
    public var replaced: [String: String]

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
        deleted: [String: Int64] = [:],
        replaced: [String: String] = [:]
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
        self.deleted = deleted
        self.replaced = replaced
    }

    enum CodingKeys: String, CodingKey {
        case type, version, device, deviceName, playlists, favorites, progress, lists, people, keys, deleted, replaced
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
        deleted = try c.value(.deleted, default: [:])
        replaced = try c.value(.replaced, default: [:])
    }

    public func encoded() throws -> Data {
        try JSONEncoder().encode(self)
    }

    /// Reads a snapshot, with its IDs in lowercase; nil when it is not one, or of another version (it is refused, not
    /// guessed).
    public static func decode(_ data: Data) -> SyncSnapshot? {
        guard let snapshot = try? JSONDecoder().decodeCheckingKeys(SyncSnapshot.self, from: data),
              snapshot.type == typeName, snapshot.version == currentVersion else { return nil }
        return snapshot.normalized()
    }

    /// The same snapshot without deletions older than `days` before `now` (and the replacements they ended).
    public func pruned(now: Int64, days: Int = SyncSnapshot.deletionsKeptDays) -> SyncSnapshot {
        let oldest = now - Int64(days) * Self.dayMilliseconds
        let kept = deleted.filter { $0.value >= oldest }
        var copy = self
        copy.deleted = kept
        copy.replaced = replaced.filter { kept["playlist:" + $0.key] != nil }
        return copy
    }

    /// The same snapshot without the records and deletions dated later than `now` plus `margin`: a device whose clock
    /// is ahead (a box before it reaches a time server) cannot date a change in the future and win over every later
    /// one. They are left out, not moved: the device that made them keeps them, and they travel once their time has
    /// come. Applied by the device that receives a snapshot, to the snapshot it receives, before the merge.
    public func withoutFutureRecords(now: Int64, margin: Int64 = SyncSnapshot.futureMarginMilliseconds) -> SyncSnapshot {
        let latest = now.addingReportingOverflow(margin).overflow ? Int64.max : now + margin
        func past(_ time: Int64) -> Bool { time <= latest }
        var copy = self
        copy.playlists = playlists.filter { past($0.addedAt) && past($0.updatedAt) }
        copy.favorites = favorites.filter { past($0.addedAt) }
        copy.progress = progress.filter { past($0.watchedAt) }
        copy.lists = lists.filter { past($0.updatedAt) }
        copy.people = people.filter { past($0.addedAt) }
        copy.keys = keys.filter { past($0.updatedAt) }
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
            return list
        }
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

    public init(id: String, name: String, items: [SyncListItem] = [], people: [SyncPerson] = [], updatedAt: Int64) {
        self.id = id
        self.name = name
        self.items = items
        self.people = people
        self.updatedAt = updatedAt
    }

    enum CodingKeys: String, CodingKey { case id, name, items, people, updatedAt }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        items = try c.value(.items, default: [])
        people = try c.value(.people, default: [])
        updatedAt = try c.decode(Int64.self, forKey: .updatedAt)
    }

    public var key: String { "list:" + id }
}

public struct SyncListItem: Codable, Hashable, Sendable {
    public var playlist: String
    public var kind: String
    public var item: String
    public var title: String
    public var image: String?

    public init(playlist: String, kind: String, item: String, title: String, image: String? = nil) {
        self.playlist = playlist
        self.kind = kind
        self.item = item
        self.title = title
        self.image = image
    }

    public var key: String { "\(playlist)|\(kind)|\(item)" }
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

private extension KeyedDecodingContainer {
    /// The value of `key`, or `fallback` when the key is absent (an explicit `null` is an error, as in Kotlin).
    func value<T: Decodable>(_ key: Key, default fallback: T) throws -> T {
        guard contains(key) else { return fallback }
        return try decode(T.self, forKey: key)
    }
}
