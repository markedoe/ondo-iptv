import Foundation

/// Deterministic hash (FNV-1a 64-bit). Swift's `Hasher` is seeded per process,
/// so it cannot be used for ids that are persisted (favourites, history…).
public enum StableHash {
    public static func fnv1a(_ string: String) -> UInt64 {
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        for byte in string.utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 0x0000_0100_0000_01B3
        }
        return hash
    }

    public static func hex(_ string: String) -> String {
        String(fnv1a(string), radix: 16)
    }
}

public enum TextNormalizer {
    /// Lowercased, accent-free, alphanumerics separated by single spaces.
    /// "Télé-Évasion  HD" -> "tele evasion hd"
    public static func normalize(_ text: String) -> String {
        let folded = text.folding(options: [.diacriticInsensitive, .caseInsensitive, .widthInsensitive], locale: nil)
        var result = ""
        result.reserveCapacity(folded.count)
        var lastWasSpace = true
        for scalar in folded.unicodeScalars {
            if CharacterSet.alphanumerics.contains(scalar) {
                result.unicodeScalars.append(scalar)
                lastWasSpace = false
            } else if !lastWasSpace {
                result.append(" ")
                lastWasSpace = true
            }
        }
        if result.hasSuffix(" ") { result.removeLast() }
        return result.lowercased()
    }

    private static let qualityTokens: Set<String> = [
        "hd", "fhd", "uhd", "sd", "hq", "lq", "4k", "8k", "hevc", "h265", "h264", "1080p", "720p", "2160p",
        "50fps", "60fps", "backup", "raw", "vip", "multi", "plus",
    ]

    private static let countryPrefixes: Set<String> = [
        "fr", "uk", "us", "usa", "de", "es", "it", "be", "ch", "ca", "nl", "pt", "ar", "tr", "pl", "ro",
        "ma", "dz", "tn", "af", "ex", "int",
    ]

    /// Quality or country tag that does not identify a channel ("hd", "fr"…).
    public static func isChannelTag(_ word: String) -> Bool {
        qualityTokens.contains(word) || countryPrefixes.contains(word)
    }

    /// Key used to match a playlist channel to a guide channel when ids differ:
    /// "FR: TV1 FHD" / "TV1 HD" / "tv1" -> "tv1"
    public static func channelMatchKey(_ name: String) -> String {
        var tokens = normalize(name).split(separator: " ").map(String.init)
        // Leading "FR", "UK"... country tags ("FR: TV1", "FR | TV1").
        if tokens.count > 1, let first = tokens.first, countryPrefixes.contains(first) {
            tokens.removeFirst()
        }
        tokens.removeAll { qualityTokens.contains($0) }
        return tokens.joined()
    }

    /// Does `text` (already normalised) contain every word of the (normalised) query, anywhere in it?
    /// Checking word prefixes first is only the fast path: "match" finds "rematch" too.
    public static func matches(normalizedText: String, queryTokens: [Substring]) -> Bool {
        guard !queryTokens.isEmpty else { return true }
        let words = normalizedText.split(separator: " ")
        for token in queryTokens {
            if !words.contains(where: { $0.hasPrefix(token) }) && !normalizedText.contains(token) {
                return false
            }
        }
        return true
    }
}

extension String {
    var nilIfBlank: String? {
        guard let first = utf8.first, let last = utf8.last else { return nil }
        // Fast path (hot in parsers): nothing to trim.
        if !Self.mayBeWhitespace(first) && !Self.mayBeWhitespace(last) { return self }
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    var trimmedFast: String {
        guard let first = utf8.first, let last = utf8.last else { return self }
        if !Self.mayBeWhitespace(first) && !Self.mayBeWhitespace(last) { return self }
        return trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Whether a first or last UTF-8 byte can belong to whitespace: ASCII spaces and controls, or any non-ASCII
    /// byte (no-break and ideographic spaces…), which the slow path then checks for real.
    private static func mayBeWhitespace(_ byte: UInt8) -> Bool {
        byte <= 0x20 || byte >= 0x7F
    }
}

extension URL {
    /// Lenient URL creation for provider data (spaces, unicode in paths…).
    static func lenient(_ string: String?) -> URL? {
        guard let raw = string?.trimmedFast, !raw.isEmpty else { return nil }
        if let url = URL(string: raw), url.scheme != nil { return url }
        let allowed = CharacterSet.urlQueryAllowed.union(CharacterSet(charactersIn: "#%"))
        if let escaped = raw.addingPercentEncoding(withAllowedCharacters: allowed),
           let url = URL(string: escaped), url.scheme != nil {
            return url
        }
        return nil
    }

    /// Lowercased path extension without query, e.g. "m3u8".
    var lowercasedExtension: String { pathExtension.lowercased() }
}
