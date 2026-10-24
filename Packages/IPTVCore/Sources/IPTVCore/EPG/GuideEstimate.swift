import Foundation

/// What a guide would keep in memory, by hour of programme start relative to `now`, counted while reading it without
/// keeping anything (`XMLTVParser.estimatePlainXML`). A guide larger than the memory it may take is read again within
/// the window `window(budget:full:)` picks: around a large panel's thousands of channels, a week of programmes takes
/// hundreds of megabytes, more than an Apple TV or an older iPhone lets an app use.
struct GuideEstimate: Sendable {
    let now: Date
    let bytesByHour: [Int: Int]

    var totalBytes: Int { bytesByHour.values.reduce(0, +) }

    private func bytes(_ hour: Int) -> Int { bytesByHour[hour] ?? 0 }

    /// The hour, relative to `now`, that `date` falls in.
    static func hour(of date: Date, around now: Date) -> Int {
        Int((date.timeIntervalSince(now) / 3_600).rounded(.down))
    }

    /// The widest window around `now` whose programmes take at most `budget` bytes, within `full`: what is on and what
    /// comes next first, two hours ahead for each hour back. Always at least the current hour and the next.
    func window(budget: Int, full: DateInterval?) -> (window: DateInterval, bytes: Int) {
        let first = full.map { Self.hour(of: $0.start, around: now) } ?? (bytesByHour.keys.min() ?? 0)
        let last = full.map { Self.hour(of: $0.end, around: now) } ?? (bytesByHour.keys.max() ?? 0)
        // Hours [from, until) relative to now; a programme that began in the hour before is usually still on.
        var from = 0
        var until = 2
        var used = bytes(-1) + bytes(0) + bytes(1)
        while true {
            var grew = false
            for _ in 0..<2 where until <= last && used + bytes(until) <= budget {
                used += bytes(until)
                until += 1
                grew = true
            }
            if from > first, used + bytes(from - 2) <= budget {
                from -= 1
                used += bytes(from - 1)
                grew = true
            }
            if !grew { break }
        }
        var start = now.addingTimeInterval(Double(from) * 3_600)
        var end = now.addingTimeInterval(Double(until) * 3_600)
        if let full {
            start = max(start, full.start)
            end = max(start, min(end, full.end))
        }
        return (DateInterval(start: start, end: end), used)
    }

    /// Memory a kept programme takes, measured on large guides (790 bytes for one with a title, a subtitle and a
    /// 340-character description): the programme in its channel's list, and each text too long to be stored inline
    /// (more than 15 bytes) on its own.
    static func bytes(of program: EPGProgram) -> Int {
        var total = 340
        for text in [program.channelID, program.title, program.subtitle, program.summary, program.category, program.episode, program.rating] {
            if let length = text?.utf8.count, length > 15 { total += 48 + length }
        }
        if let icon = program.iconURL { total += 128 + icon.absoluteString.utf8.count }
        return total
    }

    /// Memory the programmes of a guide take, by the same measure.
    static func bytes(of guide: EPGData) -> Int {
        guide.programs.values.reduce(0) { sum, list in list.reduce(sum) { $0 + bytes(of: $1) } }
    }
}
