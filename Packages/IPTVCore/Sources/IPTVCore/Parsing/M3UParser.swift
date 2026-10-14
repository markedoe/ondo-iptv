import Foundation

/// One `#EXTINF` entry of an extended M3U playlist.
public struct M3UEntry: Hashable, Sendable {
    public var duration: Double
    public var title: String
    /// Attributes of the `#EXTINF` line (`tvg-id`, `tvg-logo`, `group-title`, …), keys lowercased.
    public var attributes: [String: String]
    public var url: URL
    /// `#EXTGRP:` value when present.
    public var extGroup: String?
    /// Headers from `#EXTVLCOPT`, `#KODIPROP` and `url|User-Agent=…` suffixes.
    public var headers: HTTPHeaders

    public var groupTitle: String? { attributes["group-title"]?.nilIfBlank ?? extGroup?.nilIfBlank }
    public var tvgID: String? { attributes["tvg-id"]?.nilIfBlank }
    public var tvgName: String? { attributes["tvg-name"]?.nilIfBlank }
    public var logo: URL? { URL.lenient(attributes["tvg-logo"] ?? attributes["logo"]) }
    public var channelNumber: Int? {
        (attributes["tvg-chno"] ?? attributes["channel-number"] ?? attributes["tvg-num"]).flatMap { Int($0) }
    }
}

public struct M3UPlaylist: Hashable, Sendable {
    /// Attributes of the `#EXTM3U` header line (keys lowercased).
    public var headerAttributes: [String: String]
    public var entries: [M3UEntry]

    /// Guide URLs advertised in the header (`url-tvg`, `x-tvg-url`, `tvg-url`), comma separated.
    public var epgURLs: [URL] {
        let keys = ["url-tvg", "x-tvg-url", "tvg-url"]
        var urls: [URL] = []
        for key in keys {
            guard let value = headerAttributes[key] else { continue }
            for part in value.split(separator: ",") {
                if let url = URL.lenient(String(part)), !urls.contains(url) {
                    urls.append(url)
                }
            }
        }
        return urls
    }
}

public enum M3UParser {
    public static func parse(_ text: String) -> M3UPlaylist {
        parse(Data(text.utf8))
    }

    /// Byte level parser: large provider lists (100k+ lines) must parse in well under a second.
    public static func parse(_ data: Data) -> M3UPlaylist {
        data.withUnsafeBytes { raw -> M3UPlaylist in
            let bytes = raw.bindMemory(to: UInt8.self)
            return parse(bytes: bytes)
        }
    }

    private static func parse(bytes: UnsafeBufferPointer<UInt8>) -> M3UPlaylist {
        var header: [String: String] = [:]
        var entries: [M3UEntry] = []
        entries.reserveCapacity(max(bytes.count / 200, 16))

        var pendingInfo: (duration: Double, title: String, attributes: [String: String])?
        var pendingGroup: String?
        var pendingHeaders = HTTPHeaders()

        var lineStart = 0
        let count = bytes.count
        // Skip UTF-8 BOM.
        if count >= 3, bytes[0] == 0xEF, bytes[1] == 0xBB, bytes[2] == 0xBF { lineStart = 3 }

        while lineStart < count {
            var lineEnd = lineStart
            while lineEnd < count, bytes[lineEnd] != 0x0A, bytes[lineEnd] != 0x0D { lineEnd += 1 }
            let nextLine = lineEnd + 1
            // Trim spaces / tabs.
            var a = lineStart
            var b = lineEnd
            while a < b, bytes[a] == 0x20 || bytes[a] == 0x09 { a += 1 }
            while b > a, bytes[b - 1] == 0x20 || bytes[b - 1] == 0x09 { b -= 1 }
            lineStart = nextLine
            guard a < b else { continue }
            let line = UnsafeBufferPointer(rebasing: bytes[a..<b])

            if line[0] == 0x23 { // '#'
                if hasPrefix(line, "#EXTINF:") {
                    pendingInfo = parseExtInf(UnsafeBufferPointer(rebasing: line[8...]))
                } else if hasPrefix(line, "#EXTM3U") {
                    header.merge(parseAttributes(UnsafeBufferPointer(rebasing: line[7...]))) { _, new in new }
                } else if hasPrefix(line, "#EXTGRP:") {
                    pendingGroup = string(line, from: 8).trimmingCharacters(in: .whitespaces)
                } else if hasPrefix(line, "#EXTVLCOPT:") {
                    applyOption(string(line, from: 11), to: &pendingHeaders)
                } else if hasPrefix(line, "#KODIPROP:") {
                    applyKodiProp(string(line, from: 10), to: &pendingHeaders)
                } else if hasPrefix(line, "#EXTHTTP:") {
                    applyEXTHTTP(string(line, from: 9), to: &pendingHeaders)
                }
                continue
            }

            // URL line.
            var urlString = String(decoding: line, as: UTF8.self)
            var headers = pendingHeaders
            if let pipe = urlString.firstIndex(of: "|") {
                let options = urlString[urlString.index(after: pipe)...]
                urlString = String(urlString[..<pipe])
                applyKodiURLOptions(String(options), to: &headers)
            }
            guard let url = URL.lenient(urlString) else {
                pendingInfo = nil
                pendingGroup = nil
                pendingHeaders = HTTPHeaders()
                continue
            }
            let info = pendingInfo ?? (duration: -1, title: url.lastPathComponent, attributes: [:])
            if headers.userAgent == nil, let ua = info.attributes["user-agent"] ?? info.attributes["http-user-agent"] {
                headers.userAgent = ua
            }
            if headers.referrer == nil, let ref = info.attributes["http-referrer"] ?? info.attributes["referrer"] {
                headers.referrer = ref
            }
            let title = info.title.isEmpty ? (info.attributes["tvg-name"] ?? url.lastPathComponent) : info.title
            entries.append(M3UEntry(
                duration: info.duration,
                title: title,
                attributes: info.attributes,
                url: url,
                extGroup: pendingGroup,
                headers: headers
            ))
            pendingInfo = nil
            pendingGroup = nil
            pendingHeaders = HTTPHeaders()
        }
        return M3UPlaylist(headerAttributes: header, entries: entries)
    }

    /// Case-insensitive ASCII prefix test.
    private static func hasPrefix(_ line: UnsafeBufferPointer<UInt8>, _ prefix: StaticString) -> Bool {
        let length = prefix.utf8CodeUnitCount
        guard line.count >= length else { return false }
        let p = prefix.utf8Start
        for i in 0..<length {
            var c = line[i]
            if c >= 0x61 && c <= 0x7A { c -= 0x20 }
            if c != p[i] { return false }
        }
        return true
    }

    private static func string(_ line: UnsafeBufferPointer<UInt8>, from offset: Int) -> String {
        guard offset < line.count else { return "" }
        return String(decoding: UnsafeBufferPointer(rebasing: line[offset...]), as: UTF8.self)
    }

    static func parseExtInf(_ body: Substring) -> (duration: Double, title: String, attributes: [String: String]) {
        var copy = String(body)
        return copy.withUTF8 { parseExtInf($0) }
    }

    /// Parses `-1 tvg-id="x" group-title="A, B",Display Name`.
    static func parseExtInf(_ body: UnsafeBufferPointer<UInt8>) -> (duration: Double, title: String, attributes: [String: String]) {
        // The title starts after the first comma that is not inside a quoted attribute value.
        var quote: UInt8 = 0
        var comma = -1
        var i = 0
        while i < body.count {
            let c = body[i]
            if quote != 0 {
                if c == quote { quote = 0 }
            } else if (c == 0x22 || c == 0x27) && i > 0 && body[i - 1] == 0x3D { // " or ' after '='
                quote = c
            } else if c == 0x2C { // ','
                comma = i
                break
            }
            i += 1
        }
        let metaEnd = comma >= 0 ? comma : body.count
        var title = ""
        if comma >= 0 {
            var t0 = comma + 1
            var t1 = body.count
            while t0 < t1, body[t0] == 0x20 || body[t0] == 0x09 { t0 += 1 }
            while t1 > t0, body[t1 - 1] == 0x20 || body[t1 - 1] == 0x09 { t1 -= 1 }
            if t0 < t1 { title = String(decoding: UnsafeBufferPointer(rebasing: body[t0..<t1]), as: UTF8.self) }
        }
        // Duration is the first token.
        var a = 0
        while a < metaEnd, body[a] == 0x20 || body[a] == 0x09 { a += 1 }
        var d = a
        while d < metaEnd, body[d] != 0x20, body[d] != 0x09 { d += 1 }
        let duration = Double(String(decoding: UnsafeBufferPointer(rebasing: body[a..<d]), as: UTF8.self)) ?? -1
        let attributes = parseAttributes(UnsafeBufferPointer(rebasing: body[d..<metaEnd]))
        return (duration, title, attributes)
    }

    static func parseAttributes(_ text: Substring) -> [String: String] {
        var copy = String(text)
        return copy.withUTF8 { parseAttributes($0) }
    }

    /// Parses `key="value" key2='value' key3=value` pairs. Keys are lowercased.
    static func parseAttributes(_ text: UnsafeBufferPointer<UInt8>) -> [String: String] {
        var result: [String: String] = [:]
        var i = 0
        let n = text.count
        while i < n {
            while i < n, text[i] == 0x20 || text[i] == 0x09 { i += 1 }
            guard i < n else { break }
            let keyStart = i
            while i < n, text[i] != 0x3D, text[i] != 0x20, text[i] != 0x09 { i += 1 }
            let keyEnd = i
            guard i < n, text[i] == 0x3D else { continue } // bare token
            i += 1
            var valueStart = i
            var valueEnd = i
            if i < n, text[i] == 0x22 || text[i] == 0x27 {
                let quote = text[i]
                i += 1
                valueStart = i
                while i < n, text[i] != quote { i += 1 }
                valueEnd = i
                if i < n { i += 1 }
            } else {
                while i < n, text[i] != 0x20, text[i] != 0x09 { i += 1 }
                valueEnd = i
            }
            guard keyEnd > keyStart else { continue }
            var key = String(decoding: UnsafeBufferPointer(rebasing: text[keyStart..<keyEnd]), as: UTF8.self)
            if text[keyStart..<keyEnd].contains(where: { $0 >= 0x41 && $0 <= 0x5A }) { key = key.lowercased() }
            while valueStart < valueEnd, text[valueStart] == 0x20 { valueStart += 1 }
            while valueEnd > valueStart, text[valueEnd - 1] == 0x20 { valueEnd -= 1 }
            result[key] = String(decoding: UnsafeBufferPointer(rebasing: text[valueStart..<valueEnd]), as: UTF8.self)
        }
        return result
    }

    static func applyOption(_ option: String, to headers: inout HTTPHeaders) {
        guard let eq = option.firstIndex(of: "=") else { return }
        let key = option[..<eq].trimmingCharacters(in: .whitespaces).lowercased()
        let value = option[option.index(after: eq)...].trimmingCharacters(in: .whitespaces)
        switch key {
        case "http-user-agent": headers.userAgent = value
        case "http-referrer", "http-referer": headers.referrer = value
        case "http-origin": headers.extra["Origin"] = value
        case "http-cookie": headers.extra["Cookie"] = value
        default: break
        }
    }

    static func applyKodiProp(_ prop: String, to headers: inout HTTPHeaders) {
        // inputstream.adaptive.stream_headers=User-Agent=foo&Referer=bar
        guard let eq = prop.firstIndex(of: "=") else { return }
        let key = prop[..<eq].lowercased()
        let value = String(prop[prop.index(after: eq)...])
        if key.hasSuffix("stream_headers") || key.hasSuffix("manifest_headers") {
            applyKodiURLOptions(value, to: &headers)
        }
    }

    static func applyEXTHTTP(_ json: String, to headers: inout HTTPHeaders) {
        // #EXTHTTP:{"cookie":"…","User-Agent":"…"}
        guard let data = json.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
        for (key, value) in object {
            guard let value = value as? String else { continue }
            setHeader(key, value, on: &headers)
        }
    }

    /// `User-Agent=foo&Referer=http%3A%2F%2Fbar`
    static func applyKodiURLOptions(_ options: String, to headers: inout HTTPHeaders) {
        for pair in options.split(separator: "&") {
            guard let eq = pair.firstIndex(of: "=") else { continue }
            let key = String(pair[..<eq])
            let rawValue = String(pair[pair.index(after: eq)...])
            let value = rawValue.removingPercentEncoding ?? rawValue
            setHeader(key, value, on: &headers)
        }
    }

    private static func setHeader(_ key: String, _ value: String, on headers: inout HTTPHeaders) {
        switch key.lowercased() {
        case "user-agent": headers.userAgent = value
        case "referer", "referrer": headers.referrer = value
        default: headers.extra[key] = value
        }
    }
}
