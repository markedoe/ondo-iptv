import Foundation
#if canImport(FoundationXML)
import FoundationXML
#endif

/// Restricts what is kept from a (potentially huge) XMLTV guide.
public struct EPGFilter: Sendable {
    /// Keep programmes overlapping this window. `nil` keeps everything.
    public var window: DateInterval?
    /// Guide ids referenced by the playlist (`tvg-id`), compared case-insensitively.
    public var channelIDs: Set<String>?
    /// `TextNormalizer.channelMatchKey` of playlist channel names, used when ids do not match.
    public var channelNameKeys: Set<String>?

    public init(window: DateInterval? = nil, channelIDs: Set<String>? = nil, channelNameKeys: Set<String>? = nil) {
        self.window = window
        self.channelIDs = channelIDs.map { Set($0.map { $0.lowercased() }) }
        self.channelNameKeys = channelNameKeys
    }

    public static let none = EPGFilter()

    /// Default window: archive days back, three days ahead.
    public static func standard(now: Date = Date(), daysBack: Int = 7, daysAhead: Int = 3) -> DateInterval {
        DateInterval(start: now.addingTimeInterval(-Double(daysBack) * 86_400), end: now.addingTimeInterval(Double(daysAhead) * 86_400))
    }

    var filtersChannels: Bool { channelIDs != nil || channelNameKeys != nil }
}

public enum XMLTVParser {
    public enum ParseError: Error, LocalizedError {
        case invalidXML(String)

        public var errorDescription: String? {
            switch self {
            case .invalidXML(let detail): return "The guide is not a valid XMLTV file (\(detail))."
            }
        }
    }

    public static func parse(_ data: Data, filter: EPGFilter = .none, timeShift: TimeInterval = 0) throws -> EPGData {
        let xml = try data.gunzippedIfNeeded()
        let delegate = XMLTVDelegate(filter: filter, timeShift: timeShift)
        let parser = XMLParser(data: xml)
        parser.shouldProcessNamespaces = false
        parser.shouldResolveExternalEntities = false
        parser.delegate = delegate
        // Some platforms report a premature end only through `parserError`, with `parse()` returning true.
        let failed = !parser.parse() || parser.parserError != nil
        // A guide cut short or damaged near its end keeps what was read before the damage. A file that yields
        // nothing is an error when it is broken or is not a guide at all: providers answer some refusals with an
        // HTML page and a 200, which must not pass for an empty guide.
        if delegate.programCount == 0, delegate.channels.isEmpty, failed || !delegate.sawGuideElement {
            throw ParseError.invalidXML(parser.parserError?.localizedDescription ?? "no <tv> element")
        }
        return delegate.result()
    }

    /// Parses `20240101203000 +0100`, `20240101203000+0100`, `20240101203000` (UTC), `202401012030`.
    public static func parseDate(_ string: String) -> Date? {
        let utf8 = Array(string.utf8)
        var index = 0
        func digits(_ count: Int) -> Int? {
            guard index + count <= utf8.count else { return nil }
            var value = 0
            for i in index..<(index + count) {
                let byte = utf8[i]
                guard byte >= 48 && byte <= 57 else { return nil }
                value = value * 10 + Int(byte - 48)
            }
            index += count
            return value
        }
        guard let year = digits(4), let month = digits(2), let day = digits(2) else { return nil }
        let hour = digits(2) ?? 0
        let minute = digits(2) ?? 0
        let second = digits(2) ?? 0

        // Optional timezone offset.
        while index < utf8.count, utf8[index] == 32 { index += 1 }
        var offsetSeconds = 0
        if index < utf8.count, utf8[index] == 43 || utf8[index] == 45 { // + / -
            let sign = utf8[index] == 45 ? -1 : 1
            index += 1
            if let hh = digits(2) {
                if index < utf8.count, utf8[index] == 58 { index += 1 } // "+01:00"
                let mm = digits(2) ?? 0
                offsetSeconds = sign * (hh * 3600 + mm * 60)
            }
        }

        guard (1...12).contains(month), (1...31).contains(day) else { return nil }
        let days = daysFromCivil(year: year, month: month, day: day)
        let seconds = days * 86_400 + hour * 3600 + minute * 60 + second - offsetSeconds
        return Date(timeIntervalSince1970: TimeInterval(seconds))
    }

    /// Days since 1970-01-01 (Howard Hinnant's algorithm) — avoids slow DateFormatter on millions of rows.
    static func daysFromCivil(year: Int, month: Int, day: Int) -> Int {
        let y = month <= 2 ? year - 1 : year
        let era = (y >= 0 ? y : y - 399) / 400
        let yoe = y - era * 400
        let mp = (month + 9) % 12
        let doy = (153 * mp + 2) / 5 + day - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }
}

private final class XMLTVDelegate: NSObject, XMLParserDelegate {
    let filter: EPGFilter
    let timeShift: TimeInterval

    var channels: [String: EPGChannel] = [:]
    var programs: [String: [EPGProgram]] = [:]
    var programCount = 0
    /// Whether the document has the `<tv>` element of an XMLTV guide.
    private(set) var sawGuideElement = false

    // Channel being parsed.
    private var channelID: String?
    private var channelNames: [String] = []
    private var channelIcon: URL?

    // Programme being parsed.
    private var programme: EPGProgram?
    private var keepProgramme = false

    private var text = ""
    private var capturing = false

    /// Lowercased guide channel id -> keep?
    private var decisionCache: [String: Bool] = [:]

    init(filter: EPGFilter, timeShift: TimeInterval) {
        self.filter = filter
        self.timeShift = timeShift
    }

    func result() -> EPGData {
        var sorted: [String: [EPGProgram]] = [:]
        sorted.reserveCapacity(programs.count)
        for (id, list) in programs {
            sorted[id] = EPGData.onePerStartSecond(list.sorted { $0.start < $1.start })
        }
        return EPGData(channels: channels, programs: sorted)
    }

    private func shouldKeep(channel id: String) -> Bool {
        guard filter.filtersChannels else { return true }
        let key = id.lowercased()
        if let cached = decisionCache[key] { return cached }
        var keep = filter.channelIDs?.contains(key) ?? false
        if !keep, let nameKeys = filter.channelNameKeys {
            if nameKeys.contains(TextNormalizer.channelMatchKey(id)) {
                keep = true
            } else if let channel = channels[id] {
                keep = channel.displayNames.contains { nameKeys.contains(TextNormalizer.channelMatchKey($0)) }
            }
        }
        decisionCache[key] = keep
        return keep
    }

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes: [String: String] = [:]) {
        switch elementName {
        case "tv":
            sawGuideElement = true
        case "programme":
            guard let channel = attributes["channel"],
                  let start = attributes["start"].flatMap(XMLTVParser.parseDate) else {
                keepProgramme = false
                return
            }
            var stop = attributes["stop"].flatMap(XMLTVParser.parseDate) ?? start.addingTimeInterval(3600)
            if stop <= start { stop = start.addingTimeInterval(60) }
            let shiftedStart = start.addingTimeInterval(timeShift)
            let shiftedStop = stop.addingTimeInterval(timeShift)
            if let window = filter.window, shiftedStop < window.start || shiftedStart > window.end {
                keepProgramme = false
                return
            }
            keepProgramme = shouldKeep(channel: channel)
            if keepProgramme {
                programme = EPGProgram(channelID: channel, title: "", start: shiftedStart, end: shiftedStop)
            }
        case "channel":
            channelID = attributes["id"]
            channelNames = []
            channelIcon = nil
        case "icon":
            let url = URL.lenient(attributes["src"])
            if var current = programme, keepProgramme {
                if current.iconURL == nil { current.iconURL = url }
                programme = current
            } else if channelID != nil {
                channelIcon = channelIcon ?? url
            }
        case "title", "sub-title", "desc", "category", "episode-num", "display-name", "value":
            if (programme != nil && keepProgramme) || (channelID != nil && elementName == "display-name") {
                capturing = true
                text = ""
            }
        default:
            break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        if capturing { text += string }
    }

    func parser(_ parser: XMLParser, foundCDATA CDATABlock: Data) {
        if capturing, let string = String(data: CDATABlock, encoding: .utf8) { text += string }
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        switch elementName {
        case "programme":
            if keepProgramme, var current = programme {
                if current.title.isEmpty { current.title = "—" }
                programs[current.channelID, default: []].append(current)
                programCount += 1
            }
            programme = nil
            keepProgramme = false
        case "channel":
            if let id = channelID {
                channels[id] = EPGChannel(id: id, displayNames: channelNames, iconURL: channelIcon)
            }
            channelID = nil
        default:
            guard capturing else { return }
            capturing = false
            let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
            text = ""
            guard !value.isEmpty else { return }
            if var current = programme {
                Self.fill(&current, element: elementName, value: value)
                programme = current
            } else if elementName == "display-name" {
                channelNames.append(value)
            }
        }
    }

    /// Stores the text of a programme child element (the first value of each kind wins).
    private static func fill(_ program: inout EPGProgram, element: String, value: String) {
        switch element {
        case "title": if program.title.isEmpty { program.title = value }
        case "sub-title": if program.subtitle == nil { program.subtitle = value }
        case "desc": if program.summary == nil { program.summary = value }
        case "category": if program.category == nil { program.category = value }
        case "episode-num": if program.episode == nil { program.episode = formatEpisode(value) }
        case "value": if program.rating == nil { program.rating = value }
        default: break
        }
    }

    /// xmltv_ns "1.4.0/1" (zero based) -> "S02E05", onscreen values are kept as-is.
    static func formatEpisode(_ value: String) -> String {
        let parts = value.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count >= 2 else { return value }
        func number(_ part: Substring) -> Int? {
            Int(part.split(separator: "/").first?.trimmingCharacters(in: .whitespaces) ?? "")
        }
        guard let season = number(parts[0]), let episode = number(parts[1]) else { return value }
        // The guide's text: `Int.max + 1` would trap.
        guard season < Int.max, episode < Int.max else { return value }
        return String(format: "S%02dE%02d", season + 1, episode + 1)
    }
}
