import Foundation

/// Stable pointer to a channel, movie, series or episode inside a playlist.
public struct MediaReference: Codable, Hashable, Sendable {
    public var playlistID: UUID
    public var type: ContentType
    /// Channel / movie / series id. For episodes this is the series id.
    public var itemID: String
    /// Set for series episodes.
    public var episodeID: String?

    public init(playlistID: UUID, type: ContentType, itemID: String, episodeID: String? = nil) {
        self.playlistID = playlistID
        self.type = type
        self.itemID = itemID
        self.episodeID = episodeID
    }

    /// Key that identifies the exact playable thing (episode level for series).
    public var playbackKey: String {
        [playlistID.uuidString, type.rawValue, itemID, episodeID ?? ""].joined(separator: "|")
    }

    /// Key that identifies the browsable item (series level for episodes).
    public var itemKey: String {
        [playlistID.uuidString, type.rawValue, itemID].joined(separator: "|")
    }

    /// Same browsable item (`itemKey`), compared field by field: lists compare it per cell, the keys are strings
    /// to build.
    public func isSameItem(as other: MediaReference) -> Bool {
        itemID == other.itemID && type == other.type && playlistID == other.playlistID
    }

    /// Same playable thing (`playbackKey`), compared field by field.
    public func isSamePlayback(as other: MediaReference) -> Bool {
        isSameItem(as: other) && (episodeID ?? "") == (other.episodeID ?? "")
    }
}

public struct FavoriteEntry: Identifiable, Codable, Hashable, Sendable {
    public var id: String { reference.itemKey }
    public var reference: MediaReference
    public var title: String
    public var imageURL: URL?
    public var addedAt: Date
    /// Manual ordering inside the favourites list.
    public var sortIndex: Int

    public init(reference: MediaReference, title: String, imageURL: URL?, addedAt: Date = Date(), sortIndex: Int = 0) {
        self.reference = reference
        self.title = title
        self.imageURL = imageURL
        self.addedAt = addedAt
        self.sortIndex = sortIndex
    }
}

/// A programme the user wants to be notified about.
public struct ProgramReminder: Identifiable, Codable, Hashable, Sendable {
    public var id: String { Self.id(playlistID: playlistID, channelID: channelID, start: start) }
    public var playlistID: UUID
    public var channelID: String
    public var channelName: String
    public var title: String
    public var start: Date
    public var end: Date
    public var createdAt: Date

    public init(playlistID: UUID, channelID: String, channelName: String, title: String, start: Date, end: Date, createdAt: Date = Date()) {
        self.playlistID = playlistID
        self.channelID = channelID
        self.channelName = channelName
        self.title = title
        self.start = start
        self.end = end
        self.createdAt = createdAt
    }

    public static func id(playlistID: UUID, channelID: String, start: Date) -> String {
        "\(playlistID.uuidString)|\(channelID)|\(start.wholeSecondsKey)"
    }
}

extension Date {
    /// Whole seconds since 1970 (`Int(_:)`'s truncation) as text, for identifiers. A date past Int's range (a backup's
    /// or a provider's) keeps its `Double` text: `Int(_:)` traps there.
    var wholeSecondsKey: String {
        let seconds = timeIntervalSince1970.rounded(.towardZero)
        return Int(exactly: seconds).map(String.init) ?? "\(seconds)"
    }
}

/// "Recently watched" / "Continue watching" entry.
public struct HistoryEntry: Identifiable, Codable, Hashable, Sendable {
    public var id: String { reference.playbackKey }
    public var reference: MediaReference
    public var title: String
    public var subtitle: String?
    public var imageURL: URL?
    public var lastWatchedAt: Date
    /// Seconds. Zero for live channels.
    public var position: Double
    public var duration: Double
    /// The container of an episode ("mkv"…): with the episode id, enough to play it again at once, before the
    /// season is fetched from the provider.
    public var streamExtension: String?

    public init(
        reference: MediaReference,
        title: String,
        subtitle: String? = nil,
        imageURL: URL? = nil,
        lastWatchedAt: Date = Date(),
        position: Double = 0,
        duration: Double = 0,
        streamExtension: String? = nil
    ) {
        self.reference = reference
        self.title = title
        self.subtitle = subtitle
        self.imageURL = imageURL
        self.lastWatchedAt = lastWatchedAt
        self.position = position
        self.duration = duration
        self.streamExtension = streamExtension
    }

    public var progress: Double {
        guard duration > 0 else { return 0 }
        return min(max(position / duration, 0), 1)
    }

    /// Worth offering "resume": started, not (almost) finished.
    public var isResumable: Bool {
        reference.type != .live && position > 30 && progress < 0.95
    }

    public var isFinished: Bool { reference.type != .live && progress >= 0.95 }
}

/// Everything the player needs to start a stream. `Codable` so macOS can open it in its own window.
public struct PlaybackItem: Identifiable, Codable, Hashable, Sendable {
    public var id: String { reference.playbackKey }
    public var reference: MediaReference
    public var title: String
    public var subtitle: String?
    public var artworkURL: URL?
    public var streamURL: URL
    /// Tried in order when `streamURL` fails (e.g. `.m3u8` variant of a `.ts` stream).
    public var fallbackURLs: [URL]
    public var headers: HTTPHeaders
    public var isLive: Bool
    /// Catch-up playback of a past programme of a live channel.
    public var isCatchup: Bool
    public var startPosition: Double?

    public init(
        reference: MediaReference,
        title: String,
        subtitle: String? = nil,
        artworkURL: URL? = nil,
        streamURL: URL,
        fallbackURLs: [URL] = [],
        headers: HTTPHeaders = .none,
        isLive: Bool,
        isCatchup: Bool = false,
        startPosition: Double? = nil
    ) {
        self.reference = reference
        self.title = title
        self.subtitle = subtitle
        self.artworkURL = artworkURL
        self.streamURL = streamURL
        self.fallbackURLs = fallbackURLs
        self.headers = headers
        self.isLive = isLive
        self.isCatchup = isCatchup
        self.startPosition = startPosition
    }

    /// Candidate URLs in the order the player should try them.
    public var candidateURLs: [URL] {
        var seen = Set<URL>()
        return ([streamURL] + fallbackURLs).filter { seen.insert($0).inserted }
    }
}

/// A viewer profile: own favourites, history, lists. Kids profiles only see unlocked content.
public struct Profile: Identifiable, Codable, Hashable, Sendable {
    /// The profile created automatically; its library keeps the historical file name.
    public static let defaultID = UUID(uuidString: "00000000-0000-0000-0000-000000000001")!

    public var id: UUID
    public var name: String
    /// Index in the app's colour palette.
    public var colorIndex: Int
    public var symbol: String
    public var isKids: Bool
    /// SHA-256 of the profile PIN, `nil` when the profile is not protected.
    public var pinHash: String?

    public init(id: UUID = UUID(), name: String, colorIndex: Int = 0, symbol: String = "person.fill", isKids: Bool = false, pinHash: String? = nil) {
        self.id = id
        self.name = name
        self.colorIndex = colorIndex
        self.symbol = symbol
        self.isKids = isKids
        self.pinHash = pinHash
    }

    enum CodingKeys: String, CodingKey { case id, name, colorIndex, symbol, isKids, pinHash }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        name = (try? c.decodeIfPresent(String.self, forKey: .name)) ?? ""
        colorIndex = (try? c.decodeIfPresent(Int.self, forKey: .colorIndex)) ?? 0
        symbol = (try? c.decodeIfPresent(String.self, forKey: .symbol)) ?? "person.fill"
        isKids = (try? c.decodeIfPresent(Bool.self, forKey: .isKids)) ?? false
        pinHash = try? c.decodeIfPresent(String.self, forKey: .pinHash)
    }

    public var isProtected: Bool { pinHash != nil }

    /// Name of the library document of this profile.
    public var libraryDocumentName: String {
        id == Self.defaultID ? "library" : "library-\(id.uuidString)"
    }
}
