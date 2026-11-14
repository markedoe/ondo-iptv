import Foundation

/// The format of a stream as its address names it, shown on movie and series pages and in the player's statistics:
/// "MKV", "MP4", "HLS"… Nothing for an address that names no known video format (a `.php` link, no extension).
public enum MediaFormat {
    /// Video containers a viewer may want to know about (MKV plays differently on each device).
    static let containers: Set<String> = [
        "mkv", "mp4", "m4v", "mov", "avi", "ts", "m2ts", "mts", "webm", "wmv", "flv", "mpg", "mpeg", "vob", "ogv",
        "3gp", "divx", "asf", "rmvb",
    ]

    public static func label(for url: URL) -> String? {
        label(forExtension: url.pathExtension)
    }

    /// The format an extension names ("mkv", ".MKV"), as `label(for:)` says it; nil for one it does not know.
    public static func label(forExtension given: String) -> String? {
        var ext = given.trimmingCharacters(in: .whitespaces).lowercased()
        while ext.hasPrefix(".") { ext.removeFirst() }
        switch ext {
        case "m3u8": return "HLS"
        case "mpd": return "DASH"
        default: return containers.contains(ext) ? ext.uppercased() : nil
        }
    }

    /// The one format of a set of streams (the episodes of a series), or nil when there is none or several.
    public static func label(for urls: [URL]) -> String? {
        let labels = Set(urls.map { label(for: $0) })
        guard labels.count == 1, let only = labels.first else { return nil }
        return only
    }
}
