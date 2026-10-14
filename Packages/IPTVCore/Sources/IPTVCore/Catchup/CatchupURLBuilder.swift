import Foundation

/// Builds replay URLs for past programmes, following the common M3U conventions (the
/// `catchup`, `catchup-source`, `catchup-days` attributes) and Xtream Codes `timeshift`.
public enum CatchupURLBuilder {
    public struct Context: Sendable {
        /// Credentials when the playlist is an Xtream account.
        public var xtream: XtreamCredentials?
        public var serverTimeZone: TimeZone?
        public var outputFormat: XtreamOutputFormat

        public init(xtream: XtreamCredentials? = nil, serverTimeZone: TimeZone? = nil, outputFormat: XtreamOutputFormat = .m3u8) {
            self.xtream = xtream
            self.serverTimeZone = serverTimeZone
            self.outputFormat = outputFormat
        }
    }

    /// Can `program` be replayed on `channel`?
    public static func isAvailable(channel: Channel, program: EPGProgram, now: Date = Date()) -> Bool {
        isAvailable(channel: channel, from: program.start, now: now)
    }

    /// Can `channel` be played from `start` (a past instant within its archive)?
    public static func isAvailable(channel: Channel, from start: Date, now: Date = Date()) -> Bool {
        guard let catchup = channel.catchup, catchup.days > 0 else { return false }
        guard start < now else { return false }
        return now.timeIntervalSince(start) <= Double(catchup.days) * 86_400 + 3600
    }

    /// Candidate URLs (best first) to replay the programme. Empty when the channel has no catch-up.
    public static func urls(channel: Channel, start: Date, end: Date, now: Date = Date(), context: Context = Context()) -> [URL] {
        guard let catchup = channel.catchup else { return [] }
        let duration = max(Int(end.timeIntervalSince(start)), 60)
        let live = channel.streamURL

        switch catchup.mode {
        case .xtream:
            return xtreamURLs(channel: channel, start: start, durationSeconds: duration, context: context)

        case .default:
            guard let source = catchup.source else {
                return shiftURL(live, start: start, now: now).map { [$0] } ?? []
            }
            let filled = fill(template: source, start: start, end: end, now: now)
            if let url = URL.lenient(filled), url.scheme != nil { return [url] }
            // Relative template: treat as append.
            return URL.lenient(live.absoluteString + filled).map { [$0] } ?? []

        case .append:
            let suffix = fill(template: catchup.source ?? "?utc={utc}&lutc={lutc}", start: start, end: end, now: now)
            var base = live.absoluteString
            if suffix.hasPrefix("?") && base.contains("?") {
                base += "&" + suffix.dropFirst()
            } else {
                base += suffix
            }
            return URL.lenient(base).map { [$0] } ?? []

        case .shift:
            return shiftURL(live, start: start, now: now).map { [$0] } ?? []

        case .flussonic:
            return flussonicURL(live, start: start, durationSeconds: duration).map { [$0] } ?? []
        }
    }

    // MARK: - Modes

    static func xtreamURLs(channel: Channel, start: Date, durationSeconds: Int, context: Context) -> [URL] {
        let minutes = Int((Double(durationSeconds) / 60).rounded(.up))
        var builder: XtreamURLBuilder?
        var streamID = channel.xtreamStreamID
        if let credentials = context.xtream {
            builder = XtreamURLBuilder(credentials: credentials)
        } else if let parsed = XtreamURLBuilder.parseLiveURL(channel.streamURL) {
            builder = XtreamURLBuilder(credentials: XtreamCredentials(server: parsed.server, username: parsed.username, password: parsed.password))
            streamID = streamID ?? parsed.streamID
        }
        guard let builder, let streamID else { return [] }
        let primaryFormat = context.outputFormat
        let secondaryFormat: XtreamOutputFormat = primaryFormat == .m3u8 ? .ts : .m3u8
        return [
            builder.timeshiftURL(streamID: streamID, start: start, durationMinutes: minutes, serverTimeZone: context.serverTimeZone, format: primaryFormat),
            builder.timeshiftURL(streamID: streamID, start: start, durationMinutes: minutes, serverTimeZone: context.serverTimeZone, format: secondaryFormat),
            builder.timeshiftPHPURL(streamID: streamID, start: start, durationMinutes: minutes, serverTimeZone: context.serverTimeZone),
        ]
    }

    static func shiftURL(_ live: URL, start: Date, now: Date) -> URL? {
        let separator = live.absoluteString.contains("?") ? "&" : "?"
        return URL(string: live.absoluteString + separator + "utc=\(Int(start.timeIntervalSince1970))&lutc=\(Int(now.timeIntervalSince1970))")
    }

    /// `http://host/ch/index.m3u8?token=x` -> `http://host/ch/index-{start}-{duration}.m3u8?token=x`
    /// `http://host/ch/mpegts?token=x`     -> `http://host/ch/timeshift_abs-{start}.ts?token=x`
    static func flussonicURL(_ live: URL, start: Date, durationSeconds: Int) -> URL? {
        guard var components = URLComponents(url: live, resolvingAgainstBaseURL: false) else { return nil }
        let startStamp = Int(start.timeIntervalSince1970)
        var path = components.path
        let lastComponent = (path as NSString).lastPathComponent
        let directory = (path as NSString).deletingLastPathComponent
        if lastComponent.hasSuffix(".m3u8") {
            let name = (lastComponent as NSString).deletingPathExtension
            path = (directory as NSString).appendingPathComponent("\(name)-\(startStamp)-\(durationSeconds).m3u8")
        } else if lastComponent == "mpegts" || lastComponent.hasSuffix(".ts") {
            path = (directory as NSString).appendingPathComponent("timeshift_abs-\(startStamp).ts")
        } else {
            path = (path as NSString).appendingPathComponent("timeshift_abs-\(startStamp).ts")
        }
        components.path = path
        return components.url
    }

    // MARK: - Templates

    // swiftlint:disable:next force_try
    private static let tokenRegex = try! NSRegularExpression(pattern: #"\$?\{([a-zA-Z_]+)(?::([^}]*))?\}"#)

    /// Replaces catch-up template tokens:
    /// `{utc}` `{start}` `${start}` start (unix) · `{utcend}` `{end}` `${end}` end · `{lutc}` `{now}` `${now}`
    /// `{timestamp}` now · `{duration}` seconds · `{duration:60}` minutes · `{offset}` / `{offset:60}` now - start ·
    /// `{Y}` `{m}` `{d}` `{H}` `{M}` `{S}` start components (UTC) · `{utc:Y-m-d:H-M}` `{end:YmdHMS}` formatted.
    public static func fill(template: String, start: Date, end: Date, now: Date) -> String {
        let startTS = Int(start.timeIntervalSince1970)
        let endTS = Int(end.timeIntervalSince1970)
        let nowTS = Int(now.timeIntervalSince1970)
        let duration = max(endTS - startTS, 0)
        let offset = max(nowTS - startTS, 0)

        let nsTemplate = template as NSString
        var result = ""
        var cursor = 0
        for match in tokenRegex.matches(in: template, range: NSRange(location: 0, length: nsTemplate.length)) {
            result += nsTemplate.substring(with: NSRange(location: cursor, length: match.range.location - cursor))
            let name = nsTemplate.substring(with: match.range(at: 1))
            let argument = match.range(at: 2).location != NSNotFound ? nsTemplate.substring(with: match.range(at: 2)) : nil
            let divider = argument.flatMap { Int($0) }.map { max($0, 1) }

            var replacement: String?
            switch name {
            case "utc", "start", "timestamp_start":
                replacement = argument != nil && divider == nil ? format(start, pattern: argument!) : String(startTS)
            case "utcend", "end":
                replacement = argument != nil && divider == nil ? format(end, pattern: argument!) : String(endTS)
            case "lutc", "now", "timestamp", "current":
                replacement = argument != nil && divider == nil ? format(now, pattern: argument!) : String(nowTS)
            case "duration":
                replacement = String(duration / (divider ?? 1))
            case "offset":
                replacement = String(offset / (divider ?? 1))
            case "Y", "m", "d", "H", "M", "S":
                replacement = format(start, pattern: name)
            default:
                replacement = nil
            }
            result += replacement ?? nsTemplate.substring(with: match.range)
            cursor = match.range.location + match.range.length
        }
        result += nsTemplate.substring(from: cursor)
        return result
    }

    /// Formats with strftime-like letters: Y m d H M S (UTC). Other characters are kept.
    static func format(_ date: Date, pattern: String) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        let c = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        var output = ""
        for char in pattern {
            switch char {
            case "Y": output += String(format: "%04d", c.year ?? 0)
            case "m": output += String(format: "%02d", c.month ?? 0)
            case "d": output += String(format: "%02d", c.day ?? 0)
            case "H": output += String(format: "%02d", c.hour ?? 0)
            case "M": output += String(format: "%02d", c.minute ?? 0)
            case "S": output += String(format: "%02d", c.second ?? 0)
            default: output.append(char)
            }
        }
        return output
    }
}
