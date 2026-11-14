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
        var seen = Set<String>()
        let masked = secrets.map { $0.trimmingCharacters(in: .whitespaces) }.filter { $0.count >= 3 && seen.insert($0).inserted }.sorted { $0.count > $1.count }
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

    // swiftlint:disable:next force_try
    private static let providerID = try! NSRegularExpression(pattern: #"^[A-Za-z]{0,2}-?[0-9]{1,12}(?:-[0-9]{1,4})?$"#)

    /// `id` when it is a provider's number ("x1234", "98765"), else nil. The M3U reader's ids are a hash of
    /// the stream's address, login and password included: a photo of one would let anyone check guesses offline, so
    /// they never show.
    public static func shownID(_ id: String) -> String? {
        providerID.firstMatch(in: id, range: NSRange(id.startIndex..., in: id)) == nil ? nil : id
    }

    /// What a playlist's `addresses` (its link or server, its guides) and `login` are made of, to mask with `clean`:
    /// the login and password, each address's host (with or without a scheme), its user part, and its query's values,
    /// as written and percent-decoded.
    public static func secrets(addresses: [String], login: [String?] = []) -> [String] {
        var result = login.compactMap { $0 }
        func add(_ value: String) {
            result.append(value)
            result.append(value.removingPercentEncoding ?? value)
        }
        for address in addresses {
            var text = address.trimmingCharacters(in: .whitespaces)
            guard !text.isEmpty else { continue }
            if !text.contains("://") { text = "http://" + text }
            guard let scheme = text.range(of: "://") else { continue }
            let rest = text[scheme.upperBound...]
            let authority = rest.prefix { $0 != "/" && $0 != "?" && $0 != "#" }
            var hostPort = authority[...]
            if let at = authority.lastIndex(of: "@") {
                authority[..<at].split(separator: ":").forEach { add(String($0)) }
                hostPort = authority[authority.index(after: at)...]
            }
            result.append(hostPort.hasPrefix("[") ? String(hostPort.dropFirst().prefix { $0 != "]" }) : String(hostPort.prefix { $0 != ":" }))
            if let mark = rest.firstIndex(of: "?") {
                for pair in rest[rest.index(after: mark)...].prefix(while: { $0 != "#" }).split(separator: "&", omittingEmptySubsequences: false) {
                    if let equals = pair.firstIndex(of: "=") { add(String(pair[pair.index(after: equals)...])) }
                }
            }
        }
        var seen = Set<String>()
        return result.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty && seen.insert($0).inserted }
    }

    private static func replace(_ regex: NSRegularExpression, in text: String, with template: String) -> String {
        regex.stringByReplacingMatches(in: text, range: NSRange(text.startIndex..., in: text), withTemplate: template)
    }
}
