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
        let text = String(data: data, encoding: .utf8)
            ?? String(data: data, encoding: .isoLatin1)
            ?? ""
        return parse(text)
    }

    public static func parse(_ text: String) -> [SubtitleCue] {
        let normalized = text
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
            .replacingOccurrences(of: "\u{FEFF}", with: "")
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
