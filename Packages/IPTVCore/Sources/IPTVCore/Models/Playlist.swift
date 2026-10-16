import Foundation

/// The three kinds of content an IPTV provider exposes.
public enum ContentType: String, Codable, CaseIterable, Hashable, Sendable {
    case live
    case movie
    case series
}

/// Extra HTTP headers some providers require (User-Agent / Referer locks).
public struct HTTPHeaders: Codable, Hashable, Sendable {
    public var userAgent: String?
    public var referrer: String?
    public var extra: [String: String]

    public init(userAgent: String? = nil, referrer: String? = nil, extra: [String: String] = [:]) {
        self.userAgent = userAgent
        self.referrer = referrer
        self.extra = extra
    }

    public static let none = HTTPHeaders()

    public var isEmpty: Bool {
        (userAgent?.isEmpty ?? true) && (referrer?.isEmpty ?? true) && extra.isEmpty
    }

    /// Header dictionary suitable for `URLRequest` / `AVURLAsset`.
    public var dictionary: [String: String] {
        var result = extra
        if let userAgent, !userAgent.isEmpty { result["User-Agent"] = userAgent }
        if let referrer, !referrer.isEmpty { result["Referer"] = referrer }
        return result
    }

    /// Item-level headers win over playlist-level ones.
    public func merging(_ other: HTTPHeaders?) -> HTTPHeaders {
        guard let other else { return self }
        var merged = self
        if let ua = other.userAgent, !ua.isEmpty { merged.userAgent = ua }
        if let ref = other.referrer, !ref.isEmpty { merged.referrer = ref }
        merged.extra.merge(other.extra) { _, new in new }
        return merged
    }
}

/// Login for an Xtream Codes compatible panel.
public struct XtreamCredentials: Codable, Hashable, Sendable {
    /// Base URL of the panel, e.g. `http://example.com:8080` (no trailing slash, no path).
    public var server: URL
    public var username: String
    public var password: String

    public init(server: URL, username: String, password: String) {
        self.server = server
        self.username = username
        self.password = password
    }

    /// Accepts what users typically paste: `host:port`, `http://host:port/`, or even a full
    /// `get.php?username=..&password=..` link.
    public static func normalizeServer(_ raw: String) -> URL? {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        if !text.lowercased().hasPrefix("http://") && !text.lowercased().hasPrefix("https://") {
            text = "http://" + text
        }
        guard var components = URLComponents(string: text), components.host != nil else { return nil }
        components.path = ""
        components.query = nil
        components.fragment = nil
        return components.url
    }

    /// Extracts credentials from an M3U link produced by an Xtream panel
    /// (`http://host:port/get.php?username=U&password=P&type=m3u_plus`).
    public static func fromM3ULink(_ url: URL) -> XtreamCredentials? {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              components.path.hasSuffix("get.php") || components.path.hasSuffix("player_api.php"),
              let items = components.queryItems,
              let user = items.first(where: { $0.name == "username" })?.value, !user.isEmpty,
              let pass = items.first(where: { $0.name == "password" })?.value, !pass.isEmpty,
              let server = normalizeServer(url.absoluteString)
        else { return nil }
        return XtreamCredentials(server: server, username: user, password: pass)
    }
}

/// Stream container the Xtream panel should serve live channels in.
/// AVPlayer can only play HLS (`m3u8`), so it is the default.
public enum XtreamOutputFormat: String, Codable, CaseIterable, Hashable, Sendable {
    case m3u8
    case ts
}

/// Where a playlist comes from.
public enum PlaylistSource: Codable, Hashable, Sendable {
    /// M3U / M3U8 / XSPF / JSON playlist downloaded from a URL.
    case remote(URL)
    /// A playlist file imported by the user and copied into the app container.
    case local(fileName: String)
    /// Xtream Codes API login.
    case xtream(XtreamCredentials)

    public var kindLabel: String {
        switch self {
        case .remote: return "M3U URL"
        case .local: return "File"
        case .xtream: return "Xtream Codes"
        }
    }

    /// Password / token that should be stored in the keychain rather than in plain files.
    public var secret: String? {
        switch self {
        case .xtream(let credentials): return credentials.password
        case .remote, .local: return nil
        }
    }

    public func withSecret(_ secret: String) -> PlaylistSource {
        switch self {
        case .xtream(var credentials):
            credentials.password = secret
            return .xtream(credentials)
        case .remote, .local:
            return self
        }
    }

    public var xtreamCredentials: XtreamCredentials? {
        if case .xtream(let credentials) = self { return credentials }
        return nil
    }
}

/// A user configured playlist (one IPTV subscription / source).
public struct Playlist: Identifiable, Codable, Hashable, Sendable {
    public var id: UUID
    public var name: String
    public var source: PlaylistSource
    /// Additional XMLTV sources added by the user.
    public var epgURLs: [URL]
    /// Use the guide advertised by the playlist itself (`url-tvg` header, Xtream `xmltv.php`).
    public var useProviderEPG: Bool
    /// Hours to shift guide times by (`tvg-shift`), for badly configured providers.
    public var epgTimeShiftHours: Double
    public var headers: HTTPHeaders
    public var xtreamOutput: XtreamOutputFormat
    /// Xtream accounts: load the `get.php` M3U export instead of the JSON API ("force M3U").
    public var xtreamForceM3U: Bool
    /// Re-download the playlist when it is older than this many hours (0 = manual only).
    public var autoRefreshHours: Int
    public var createdAt: Date
    public var lastRefreshedAt: Date?
    /// Guide channel chosen by the user for a playlist channel (`channelID -> guide id`);
    /// an empty guide id means "no guide" for that channel.
    public var epgChannelOverrides: [String: String]
    /// Names and numbers changed by the user, by channel id.
    public var channelEdits: [String: ChannelEdit]

    public init(
        id: UUID = UUID(),
        name: String,
        source: PlaylistSource,
        epgURLs: [URL] = [],
        useProviderEPG: Bool = true,
        epgTimeShiftHours: Double = 0,
        headers: HTTPHeaders = .none,
        xtreamOutput: XtreamOutputFormat = .m3u8,
        xtreamForceM3U: Bool = false,
        autoRefreshHours: Int = 24,
        createdAt: Date = Date(),
        lastRefreshedAt: Date? = nil,
        epgChannelOverrides: [String: String] = [:],
        channelEdits: [String: ChannelEdit] = [:]
    ) {
        self.id = id
        self.name = name
        self.source = source
        self.epgURLs = epgURLs
        self.useProviderEPG = useProviderEPG
        self.epgTimeShiftHours = epgTimeShiftHours
        self.headers = headers
        self.xtreamOutput = xtreamOutput
        self.xtreamForceM3U = xtreamForceM3U
        self.autoRefreshHours = autoRefreshHours
        self.createdAt = createdAt
        self.lastRefreshedAt = lastRefreshedAt
        self.epgChannelOverrides = epgChannelOverrides
        self.channelEdits = channelEdits
    }

    enum CodingKeys: String, CodingKey {
        case id, name, source, epgURLs, useProviderEPG, epgTimeShiftHours, headers, xtreamOutput, xtreamForceM3U
        case autoRefreshHours, createdAt, lastRefreshedAt, epgChannelOverrides, channelEdits
    }

    /// Tolerant decoding: fields added in later versions get defaults instead of failing,
    /// so an update can never wipe the user's playlists.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        source = try c.decode(PlaylistSource.self, forKey: .source)
        epgURLs = (try? c.decodeIfPresent([URL].self, forKey: .epgURLs)) ?? []
        useProviderEPG = (try? c.decodeIfPresent(Bool.self, forKey: .useProviderEPG)) ?? true
        epgTimeShiftHours = (try? c.decodeIfPresent(Double.self, forKey: .epgTimeShiftHours)) ?? 0
        headers = (try? c.decodeIfPresent(HTTPHeaders.self, forKey: .headers)) ?? .none
        xtreamOutput = (try? c.decodeIfPresent(XtreamOutputFormat.self, forKey: .xtreamOutput)) ?? .m3u8
        xtreamForceM3U = (try? c.decodeIfPresent(Bool.self, forKey: .xtreamForceM3U)) ?? false
        autoRefreshHours = (try? c.decodeIfPresent(Int.self, forKey: .autoRefreshHours)) ?? 24
        createdAt = (try? c.decodeIfPresent(Date.self, forKey: .createdAt)) ?? Date()
        lastRefreshedAt = try? c.decodeIfPresent(Date.self, forKey: .lastRefreshedAt)
        epgChannelOverrides = (try? c.decodeIfPresent([String: String].self, forKey: .epgChannelOverrides)) ?? [:]
        channelEdits = (try? c.decodeIfPresent([String: ChannelEdit].self, forKey: .channelEdits)) ?? [:]
    }

    public func needsRefresh(now: Date = Date()) -> Bool {
        guard let lastRefreshedAt else { return true }
        guard autoRefreshHours > 0 else { return false }
        return now.timeIntervalSince(lastRefreshedAt) > Double(autoRefreshHours) * 3600
    }
}

/// Xtream account status returned by `player_api.php`.
public struct XtreamAccountInfo: Codable, Hashable, Sendable {
    public var username: String
    public var status: String
    public var isTrial: Bool
    public var expiresAt: Date?
    public var createdAt: Date?
    public var activeConnections: Int?
    public var maxConnections: Int?
    public var allowedOutputFormats: [String]
    public var message: String?
    public var serverTimeZone: String?
    public var serverURL: String?

    public init(
        username: String,
        status: String,
        isTrial: Bool = false,
        expiresAt: Date? = nil,
        createdAt: Date? = nil,
        activeConnections: Int? = nil,
        maxConnections: Int? = nil,
        allowedOutputFormats: [String] = [],
        message: String? = nil,
        serverTimeZone: String? = nil,
        serverURL: String? = nil
    ) {
        self.username = username
        self.status = status
        self.isTrial = isTrial
        self.expiresAt = expiresAt
        self.createdAt = createdAt
        self.activeConnections = activeConnections
        self.maxConnections = maxConnections
        self.allowedOutputFormats = allowedOutputFormats
        self.message = message
        self.serverTimeZone = serverTimeZone
        self.serverURL = serverURL
    }

    public var isActive: Bool { status.lowercased() == "active" }
}
