import Foundation

/// What the player's title data leaves out of a provider's text before showing it: addresses, logins and passwords,
/// query values, IP addresses and `host:port`, and the playlist's own login, password and server wherever they appear.
/// The same rules as Android's `Redaction`, tested on `fixtures/title-data/redaction.json`. A title's own colon or at
/// sign ("Copper Valley: Night @ Sea") stays.
public enum Redaction {
    public static let mask = "•••"

    // swiftlint:disable:next force_try
    private static let address = try! NSRegularExpression(pattern: #"[A-Za-z][A-Za-z0-9+.\-]*://\S+"#)
    // swiftlint:disable:next force_try
    private static let login = try! NSRegularExpression(pattern: #"[^\s/:@]+:[^\s/@]+@"#)
    // swiftlint:disable:next force_try
    private static let query = try! NSRegularExpression(pattern: #"([?&;][A-Za-z0-9_.\-]+=)[^&;#\s]+"#)
    // swiftlint:disable:next force_try
    private static let ipAddress = try! NSRegularExpression(pattern: #"\b\d{1,3}(?:\.\d{1,3}){3}(?::\d{1,5})?\b"#)
    // swiftlint:disable:next force_try
    private static let hostPort = try! NSRegularExpression(pattern: #"\b[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)+:\d{2,5}\b"#)

    /// `text` without anything that could be a credential or an address; `secrets` (shorter than 3 characters ignored)
    /// masked.
    public static func clean(_ text: String, secrets: [String] = []) -> String {
        var result = text
        let masked = secrets.map { $0.trimmingCharacters(in: .whitespaces) }.filter { $0.count >= 3 }.sorted { $0.count > $1.count }
        for secret in masked {
            result = result.replacingOccurrences(of: secret, with: mask, options: .caseInsensitive)
        }
        result = replace(address, in: result, with: mask)
        result = replace(login, in: result, with: mask + "@")
        result = replace(query, in: result, with: "$1" + mask)
        result = replace(ipAddress, in: result, with: mask)
        result = replace(hostPort, in: result, with: mask)
        return result
    }

    private static func replace(_ regex: NSRegularExpression, in text: String, with template: String) -> String {
        regex.stringByReplacingMatches(in: text, range: NSRange(text.startIndex..., in: text), withTemplate: template)
    }
}
