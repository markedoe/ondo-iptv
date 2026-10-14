import Foundation
#if canImport(FoundationXML)
import FoundationXML
#endif

public enum PlaylistFormat: String, Sendable {
    case m3u
    case xspf
    case json
    /// One URL per line without `#EXTM3U`.
    case plainList

    public static func detect(_ data: Data) -> PlaylistFormat? {
        let prefix = String(decoding: data.prefix(4096), as: UTF8.self)
            .trimmingCharacters(in: CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: "\u{FEFF}")))
        if prefix.uppercased().hasPrefix("#EXTM3U") || prefix.contains("#EXTINF") { return .m3u }
        if prefix.hasPrefix("<") && prefix.lowercased().contains("<playlist") { return .xspf }
        if prefix.hasPrefix("[") || prefix.hasPrefix("{") { return .json }
        if let firstLine = prefix.split(whereSeparator: \.isNewline).first,
           firstLine.lowercased().hasPrefix("http") || firstLine.lowercased().hasPrefix("rtmp") || firstLine.lowercased().hasPrefix("rtsp") {
            return .plainList
        }
        return nil
    }
}

/// XSPF (`<playlist><trackList><track>`) → M3U entries.
public enum XSPFParser {
    public static func parse(_ data: Data) -> M3UPlaylist {
        let delegate = XSPFDelegate()
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        _ = parser.parse()  // a damaged file keeps the tracks read before the damage
        return M3UPlaylist(headerAttributes: [:], entries: delegate.entries)
    }

    private final class XSPFDelegate: NSObject, XMLParserDelegate {
        var entries: [M3UEntry] = []
        private var inTrack = false
        private var location: String?
        private var title: String?
        private var image: String?
        private var album: String?
        private var duration: Double = -1
        private var text = ""

        func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes: [String: String] = [:]) {
            if elementName == "track" {
                inTrack = true
                location = nil
                title = nil
                image = nil
                album = nil
                duration = -1
            }
            text = ""
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) {
            text += string
        }

        func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
            let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
            text = ""
            guard inTrack else { return }
            switch elementName {
            case "location": location = location ?? value
            case "title": title = value
            case "image": image = value
            case "album": album = value
            case "duration": duration = (Double(value) ?? -1000) / 1000
            case "track":
                inTrack = false
                guard let url = URL.lenient(location) else { return }
                var attributes: [String: String] = [:]
                if let image { attributes["tvg-logo"] = image }
                if let album { attributes["group-title"] = album }
                entries.append(M3UEntry(
                    duration: duration,
                    title: title ?? url.lastPathComponent,
                    attributes: attributes,
                    url: url,
                    extGroup: nil,
                    headers: .none
                ))
            default: break
            }
        }
    }
}

/// Generic JSON playlists: an array (or `{channels|items|streams|playlist: [...]}`) of objects with
/// `name|title`, `url|link|stream|src`, `logo|icon|image`, `group|category|genre`, `tvg_id|epg_id`.
public enum JSONPlaylistParser {
    public static func parse(_ data: Data) -> M3UPlaylist {
        guard let object = try? JSONSerialization.jsonObject(with: data) else {
            return M3UPlaylist(headerAttributes: [:], entries: [])
        }
        // Objects of the list; a stray `null` or string among them is skipped rather than failing the whole cast.
        func objects(_ value: Any?) -> [[String: Any]]? {
            (value as? [Any]).map { $0.compactMap { $0 as? [String: Any] } }
        }
        var items: [[String: Any]] = []
        if let array = objects(object) {
            items = array
        } else if let dictionary = object as? [String: Any] {
            for key in ["channels", "items", "streams", "playlist", "data", "list"] {
                if let array = objects(dictionary[key]) {
                    items = array
                    break
                }
            }
        }

        func string(_ item: [String: Any], _ keys: [String]) -> String? {
            for key in keys {
                if let value = item[key] as? String, !value.isEmpty { return value }
                if let value = item[key] as? NSNumber { return value.stringValue }
            }
            return nil
        }

        let entries: [M3UEntry] = items.compactMap { item in
            guard let url = URL.lenient(string(item, ["url", "link", "stream", "stream_url", "src", "source", "file"])) else { return nil }
            var attributes: [String: String] = [:]
            if let logo = string(item, ["logo", "icon", "image", "tvg_logo", "tvg-logo", "poster"]) { attributes["tvg-logo"] = logo }
            if let group = string(item, ["group", "category", "genre", "group_title", "group-title"]) { attributes["group-title"] = group }
            if let id = string(item, ["tvg_id", "tvg-id", "epg_id", "epg"]) { attributes["tvg-id"] = id }
            if let type = string(item, ["type", "tvg-type"]) { attributes["tvg-type"] = type }
            var headers = HTTPHeaders()
            headers.userAgent = string(item, ["user_agent", "user-agent", "userAgent"])
            headers.referrer = string(item, ["referrer", "referer"])
            return M3UEntry(
                duration: -1,
                title: string(item, ["name", "title", "channel"]) ?? url.lastPathComponent,
                attributes: attributes,
                url: url,
                extGroup: nil,
                headers: headers
            )
        }
        return M3UPlaylist(headerAttributes: [:], entries: entries)
    }
}

public enum PlainListParser {
    public static func parse(_ data: Data) -> M3UPlaylist {
        let text = String(decoding: data, as: UTF8.self)
        let entries: [M3UEntry] = text.split(whereSeparator: \.isNewline).compactMap { line in
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            guard !trimmed.hasPrefix("#"), let url = URL.lenient(trimmed) else { return nil }
            return M3UEntry(duration: -1, title: url.lastPathComponent, attributes: [:], url: url, extGroup: nil, headers: .none)
        }
        return M3UPlaylist(headerAttributes: [:], entries: entries)
    }
}
