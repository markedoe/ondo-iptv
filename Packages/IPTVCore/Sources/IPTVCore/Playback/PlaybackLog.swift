import Foundation

/// What happened during a playback, with the seconds since it was opened: the record of start and seek times, retries
/// and errors that a problem report carries. Every line goes through `PrivacyScrubber`: no address, login or title.
public struct PlaybackLog: Sendable, Equatable {
    public struct Entry: Sendable, Equatable {
        public var time: Double
        public var event: String
    }

    /// The oldest lines go first beyond this many.
    public static let limit = 200

    public private(set) var entries: [Entry] = []

    public init() {}

    public mutating func add(_ event: String, at time: Double) {
        entries.append(Entry(time: time, event: PrivacyScrubber.scrub(event)))
        if entries.count > Self.limit { entries.removeFirst(entries.count - Self.limit) }
    }
}

/// Removes from free text what could identify a viewer or their subscription: addresses (with any login in them),
/// e-mail addresses and IP addresses. Error messages from the system can quote the address that failed.
public enum PrivacyScrubber {
    private static let patterns: [(NSRegularExpression, String)] = [
        (#"\b[a-zA-Z][a-zA-Z0-9+.-]*://\S+"#, "<address>"),
        (#"\b[^\s@/:]+:[^\s@/]+@[^\s/]+"#, "<address>"),
        (#"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"#, "<e-mail>"),
        (#"\b(?:\d{1,3}\.){3}\d{1,3}(?::\d+)?\b"#, "<ip>"),
        (#"\b(?:[0-9A-Fa-f]{1,4}:){4,7}[0-9A-Fa-f]{1,4}\b"#, "<ip>"),
        // Shortened IPv6 (2001:db8::7), with its brackets and port when it has them ([2001:db8::1]:8080).
        (#"(?<![\w:])\[?(?:[0-9A-Fa-f]{1,4}:){1,7}:(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4}){0,6})?(?:%\w+)?(?:\](?::\d+)?)?(?![\w:])"#, "<ip>"),
        // Host names, whatever their top-level domain: its letters are all lowercase or all capitals. Type names
        // (java.net.UnknownHostException), codecs (avc1.64001F, mp4a.40.2) and versions (1.11.0) stay; so does a
        // dotted name that runs on (java.net in front of a type name).
        (#"(?<![\w.-])(?:[A-Za-z0-9-]+\.)+(?:[a-z]{2,63}|[A-Z]{2,63}|xn--[a-z0-9-]+)(?![\w-]|\.\w)"#, "<host>"),
    ].map { pattern, replacement in
        // The patterns are constants: a typo is a programming error caught by the tests.
        (try! NSRegularExpression(pattern: pattern), replacement)  // swiftlint:disable:this force_try
    }

    public static func scrub(_ text: String) -> String {
        patterns.reduce(text) { text, rule in
            rule.0.stringByReplacingMatches(in: text, range: NSRange(text.startIndex..., in: text), withTemplate: rule.1)
        }
    }
}
