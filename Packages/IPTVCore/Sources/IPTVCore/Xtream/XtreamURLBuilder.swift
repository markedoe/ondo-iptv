import Foundation

/// Builds the URLs an Xtream Codes panel serves.
public struct XtreamURLBuilder: Sendable {
    public var credentials: XtreamCredentials

    public init(credentials: XtreamCredentials) {
        self.credentials = credentials
    }

    private var base: String {
        var string = credentials.server.absoluteString
        while string.hasSuffix("/") { string.removeLast() }
        return string
    }

    private var user: String { encodePathComponent(credentials.username) }
    private var pass: String { encodePathComponent(credentials.password) }

    private func encodePathComponent(_ value: String) -> String {
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove(charactersIn: "/?#")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }

    public func apiURL(action: String? = nil, parameters: [String: String] = [:]) -> URL {
        var items = [
            URLQueryItem(name: "username", value: credentials.username),
            URLQueryItem(name: "password", value: credentials.password),
        ]
        if let action { items.append(URLQueryItem(name: "action", value: action)) }
        for key in parameters.keys.sorted() {
            items.append(URLQueryItem(name: key, value: parameters[key]))
        }
        return Self.url(URLComponents(string: base + "/player_api.php")!, items)
    }

    /// `+` in passwords must be escaped: URLComponents leaves it alone and PHP panels read it as a space.
    private static func url(_ base: URLComponents, _ items: [URLQueryItem]) -> URL {
        var components = base
        components.queryItems = items
        components.percentEncodedQuery = components.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        return components.url!
    }

    public func liveURL(streamID: Int, format: XtreamOutputFormat) -> URL {
        URL(string: "\(base)/live/\(user)/\(pass)/\(streamID).\(format.rawValue)")!
    }

    public func movieURL(streamID: Int, containerExtension: String?) -> URL {
        URL(string: "\(base)/movie/\(user)/\(pass)/\(streamID).\(containerExtension ?? "mp4")")!
    }

    public func episodeURL(episodeID: String, containerExtension: String?) -> URL {
        URL(string: "\(base)/series/\(user)/\(pass)/\(encodePathComponent(episodeID)).\(containerExtension ?? "mp4")")!
    }

    /// `/timeshift/{user}/{pass}/{minutes}/{yyyy-MM-dd:HH-mm}/{id}.{ext}` — start time in the server's time zone.
    public func timeshiftURL(streamID: Int, start: Date, durationMinutes: Int, serverTimeZone: TimeZone?, format: XtreamOutputFormat) -> URL {
        let stamp = Self.timeshiftStamp(start, timeZone: serverTimeZone ?? TimeZone(identifier: "UTC")!)
        return URL(string: "\(base)/timeshift/\(user)/\(pass)/\(max(durationMinutes, 1))/\(stamp)/\(streamID).\(format.rawValue)")!
    }

    /// Alternative endpoint some panels expose instead of the path form.
    public func timeshiftPHPURL(streamID: Int, start: Date, durationMinutes: Int, serverTimeZone: TimeZone?) -> URL {
        let stamp = Self.timeshiftStamp(start, timeZone: serverTimeZone ?? TimeZone(identifier: "UTC")!)
        return Self.url(URLComponents(string: base + "/streaming/timeshift.php")!, [
            URLQueryItem(name: "username", value: credentials.username),
            URLQueryItem(name: "password", value: credentials.password),
            URLQueryItem(name: "stream", value: String(streamID)),
            URLQueryItem(name: "start", value: stamp),
            URLQueryItem(name: "duration", value: String(max(durationMinutes, 1))),
        ])
    }

    public var xmltvURL: URL {
        Self.url(URLComponents(string: base + "/xmltv.php")!, [
            URLQueryItem(name: "username", value: credentials.username),
            URLQueryItem(name: "password", value: credentials.password),
        ])
    }

    public func m3uURL(format: XtreamOutputFormat) -> URL {
        Self.url(URLComponents(string: base + "/get.php")!, [
            URLQueryItem(name: "username", value: credentials.username),
            URLQueryItem(name: "password", value: credentials.password),
            URLQueryItem(name: "type", value: "m3u_plus"),
            URLQueryItem(name: "output", value: format.rawValue),
        ])
    }

    static func timeshiftStamp(_ date: Date, timeZone: TimeZone) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let c = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: date)
        return String(format: "%04d-%02d-%02d:%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0, c.hour ?? 0, c.minute ?? 0)
    }

    // MARK: - Recognising Xtream URLs inside plain M3U playlists

    public struct ParsedLiveURL: Hashable, Sendable {
        public var server: URL
        public var username: String
        public var password: String
        public var streamID: Int
        public var fileExtension: String
    }

    /// Recognises `http://host:port/live/user/pass/123.ts` and `http://host:port/user/pass/123`.
    public static func parseLiveURL(_ url: URL) -> ParsedLiveURL? {
        // Fast reject: the last path component must start with a digit.
        let string = url.absoluteString
        guard let lastSlash = string.lastIndex(of: "/"),
              let first = string[string.index(after: lastSlash)...].first, first.isASCII, first.isNumber
        else { return nil }
        guard let scheme = url.scheme, let host = url.host else { return nil }
        var parts = url.path.split(separator: "/").map(String.init)
        if parts.first == "live" { parts.removeFirst() }
        guard parts.count == 3 else { return nil }
        let last = parts[2]
        let idPart = last.split(separator: ".").first.map(String.init) ?? last
        guard let id = Int(idPart) else { return nil }
        let ext = last.contains(".") ? String(last.split(separator: ".").last!) : ""
        var serverString = "\(scheme)://\(host)"
        if let port = url.port { serverString += ":\(port)" }
        guard let server = URL(string: serverString) else { return nil }
        return ParsedLiveURL(
            server: server,
            username: parts[0].removingPercentEncoding ?? parts[0],
            password: parts[1].removingPercentEncoding ?? parts[1],
            streamID: id,
            fileExtension: ext
        )
    }

    public static func streamID(fromLiveURL url: URL) -> Int? {
        parseLiveURL(url)?.streamID
    }

    /// For a `.ts` live URL of an Xtream panel, the `.m3u8` variant AVPlayer can play (and vice versa).
    public static func alternateFormatURL(for url: URL) -> URL? {
        guard let parsed = parseLiveURL(url) else { return nil }
        let builder = XtreamURLBuilder(credentials: XtreamCredentials(server: parsed.server, username: parsed.username, password: parsed.password))
        switch parsed.fileExtension.lowercased() {
        case "m3u8": return builder.liveURL(streamID: parsed.streamID, format: .ts)
        default: return builder.liveURL(streamID: parsed.streamID, format: .m3u8)
        }
    }
}
