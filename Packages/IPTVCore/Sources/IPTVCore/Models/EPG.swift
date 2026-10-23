import Foundation

/// One TV programme from the electronic programme guide.
public struct EPGProgram: Identifiable, Codable, Hashable, Sendable {
    public var id: String { "\(channelID)|\(start.wholeSecondsKey)" }
    /// XMLTV channel id the programme belongs to.
    public var channelID: String
    public var title: String
    public var subtitle: String?
    public var summary: String?
    public var start: Date
    public var end: Date
    public var category: String?
    public var iconURL: URL?
    public var episode: String?
    public var rating: String?

    public init(
        channelID: String,
        title: String,
        subtitle: String? = nil,
        summary: String? = nil,
        start: Date,
        end: Date,
        category: String? = nil,
        iconURL: URL? = nil,
        episode: String? = nil,
        rating: String? = nil
    ) {
        self.channelID = channelID
        self.title = title
        self.subtitle = subtitle
        self.summary = summary
        self.start = start
        self.end = end
        self.category = category
        self.iconURL = iconURL
        self.episode = episode
        self.rating = rating
    }

    public var duration: TimeInterval { end.timeIntervalSince(start) }

    public func isLive(at date: Date = Date()) -> Bool { start <= date && date < end }

    public func isPast(at date: Date = Date()) -> Bool { end <= date }

    /// 0...1 progress of the programme at `date`.
    public func progress(at date: Date = Date()) -> Double {
        guard duration > 0 else { return 0 }
        return min(max(date.timeIntervalSince(start) / duration, 0), 1)
    }

    func shifted(by interval: TimeInterval) -> EPGProgram {
        var copy = self
        copy.start = start.addingTimeInterval(interval)
        copy.end = end.addingTimeInterval(interval)
        return copy
    }
}

/// A `<channel>` entry of an XMLTV file.
public struct EPGChannel: Codable, Hashable, Sendable {
    public var id: String
    public var displayNames: [String]
    public var iconURL: URL?

    public init(id: String, displayNames: [String], iconURL: URL? = nil) {
        self.id = id
        self.displayNames = displayNames
        self.iconURL = iconURL
    }
}

/// Parsed guide data: channels and their programmes sorted by start date.
public struct EPGData: Codable, Hashable, Sendable {
    public var channels: [String: EPGChannel]
    public var programs: [String: [EPGProgram]]
    public var fetchedAt: Date

    public init(channels: [String: EPGChannel] = [:], programs: [String: [EPGProgram]] = [:], fetchedAt: Date = Date()) {
        self.channels = channels
        self.programs = programs
        self.fetchedAt = fetchedAt
    }

    public static let empty = EPGData()

    public var programCount: Int { programs.values.reduce(0) { $0 + $1.count } }

    /// Merges another guide into this one. Programmes overlapping an existing slot are ignored,
    /// so the first source added wins (user sources are merged before provider ones).
    public mutating func merge(_ other: EPGData) {
        for (id, channel) in other.channels where channels[id] == nil {
            channels[id] = channel
        }
        for (id, incoming) in other.programs {
            guard let existing = programs[id], !existing.isEmpty else {
                programs[id] = Self.onePerStartSecond(incoming.sorted { $0.start < $1.start })
                continue
            }
            let taken = TakenSlots(existing)
            var combined = existing
            for program in incoming where !taken.overlaps(program) {
                combined.append(program)
            }
            combined.sort { $0.start < $1.start }
            programs[id] = Self.onePerStartSecond(combined)
        }
    }

    /// The first programme of each start second, from a channel's list sorted by start: `EPGProgram.id` is the
    /// channel and that second, and a guide that lists a programme twice would give a SwiftUI list two rows with one ID.
    static func onePerStartSecond(_ sorted: [EPGProgram]) -> [EPGProgram] {
        var result: [EPGProgram] = []
        result.reserveCapacity(sorted.count)
        var previous: Double?
        for program in sorted {
            let second = program.start.timeIntervalSince1970.rounded(.towardZero)
            if second == previous { continue }
            previous = second
            result.append(program)
        }
        return result
    }

    /// The time a channel's programmes take, to tell in O(log n) whether another programme overlaps one of
    /// them: scanning the list for each programme of a second guide took seconds on full-size guides.
    private struct TakenSlots {
        let starts: [Date]
        /// Latest end among the programmes up to each index (programmes can overlap, so ends are not sorted).
        let latestEnds: [Date]

        init(_ programs: [EPGProgram]) {
            let sorted = programs.sorted { $0.start < $1.start }
            starts = sorted.map(\.start)
            var latest = Date.distantPast
            latestEnds = sorted.map { program in
                latest = max(latest, program.end)
                return latest
            }
        }

        /// Whether one of the programmes starting before `program` ends is still on when it starts.
        func overlaps(_ program: EPGProgram) -> Bool {
            var low = 0
            var high = starts.count
            while low < high {
                let mid = (low + high) / 2
                if starts[mid] < program.end { low = mid + 1 } else { high = mid }
            }
            return low > 0 && latestEnds[low - 1] > program.start
        }
    }

    /// Drops programmes that ended before `date` (keeps the archive window small on disk).
    public mutating func prune(before date: Date) {
        for (id, list) in programs {
            let kept = list.filter { $0.end >= date }
            programs[id] = kept.isEmpty ? nil : kept
        }
    }
}
