import Foundation

public struct SubtitleCue: Hashable, Sendable {
    public var start: Double
    public var end: Double
    public var text: String

    public init(start: Double, end: Double, text: String) {
        self.start = start
        self.end = end
        self.text = text
    }
}

/// External subtitles (SRT and WebVTT) rendered by the app on top of the video.
public enum SubtitleParser {
    public static func parse(_ data: Data) -> [SubtitleCue] {
        parse(decode(data))
    }

    /// Subtitle files come in UTF-8 or, very often for French and other Western languages, Windows-1252:
    /// read as Latin-1 its curly apostrophes and "œ" turn into control characters. Decoded here rather than
    /// by Foundation, which does not on every platform. Some editors save UTF-16, always with a byte order mark.
    public static func decode(_ data: Data) -> String {
        if data.starts(with: [0xFF, 0xFE]), let text = String(data: data.dropFirst(2), encoding: .utf16LittleEndian) { return text }
        if data.starts(with: [0xFE, 0xFF]), let text = String(data: data.dropFirst(2), encoding: .utf16BigEndian) { return text }
        if let text = String(data: data, encoding: .utf8) { return text }
        var scalars = String.UnicodeScalarView()
        for byte in data {
            let value = (0x80...0x9F).contains(byte) ? windows1252High[Int(byte) - 0x80] : UInt32(byte)
            scalars.append(Unicode.Scalar(value) ?? "\u{FFFD}")
        }
        return String(scalars)
    }

    /// Characters of bytes 0x80–0x9F in Windows-1252 (the rest matches Latin-1).
    private static let windows1252High: [UInt32] = [
        0x20AC, 0xFFFD, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021, 0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0xFFFD, 0x017D, 0xFFFD,
        0xFFFD, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014, 0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0xFFFD, 0x017E, 0x0178,
    ]

    public static func parse(_ text: String) -> [SubtitleCue] {
        let normalized = text
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
            .replacingOccurrences(of: "\u{FEFF}", with: "")
            // A line holding only spaces still separates two cues.
            .components(separatedBy: "\n")
            .map { $0.trimmingCharacters(in: .whitespaces).isEmpty ? "" : $0 }
            .joined(separator: "\n")
        var cues: [SubtitleCue] = []
        for block in normalized.components(separatedBy: "\n\n") {
            let lines = block.split(separator: "\n", omittingEmptySubsequences: true).map(String.init)
            guard let timingIndex = lines.firstIndex(where: { $0.contains("-->") }) else { continue }
            let parts = lines[timingIndex].components(separatedBy: "-->")
            guard parts.count == 2,
                  let start = parseTime(parts[0]),
                  let end = parseTime(parts[1].split(separator: " ", omittingEmptySubsequences: true).first.map(String.init) ?? "")
            else { continue }
            let body = lines[(timingIndex + 1)...].joined(separator: "\n")
            let clean = stripTags(body).trimmingCharacters(in: .whitespacesAndNewlines)
            guard !clean.isEmpty else { continue }
            cues.append(SubtitleCue(start: start, end: end, text: clean))
        }
        return cues.sorted { $0.start < $1.start }
    }

    /// "00:01:02,500", "00:01:02.500", "01:02.500" -> seconds.
    static func parseTime(_ raw: String) -> Double? {
        let value = raw.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        let components = value.split(separator: ":").map(String.init)
        guard components.count == 2 || components.count == 3 else { return nil }
        var seconds = 0.0
        for component in components {
            guard let number = Double(component) else { return nil }
            seconds = seconds * 60 + number
        }
        return seconds
    }

    private static func stripTags(_ text: String) -> String {
        var result = ""
        var inTag = false
        for char in text {
            if char == "<" || char == "{" { inTag = true; continue }
            if char == ">" || char == "}" { inTag = false; continue }
            if !inTag { result.append(char) }
        }
        return result
    }

    /// Cue displayed at `time` (binary search, cues sorted by start).
    public static func cue(at time: Double, in cues: [SubtitleCue]) -> SubtitleCue? {
        var low = 0
        var high = cues.count - 1
        var candidate: Int?
        while low <= high {
            let mid = (low + high) / 2
            if cues[mid].start <= time {
                candidate = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        guard let index = candidate else { return nil }
        // Overlapping cues: walk back a little to find one still on screen.
        var i = index
        while i >= 0 && i >= index - 3 {
            if cues[i].start <= time && time < cues[i].end { return cues[i] }
            i -= 1
        }
        return nil
    }
}
