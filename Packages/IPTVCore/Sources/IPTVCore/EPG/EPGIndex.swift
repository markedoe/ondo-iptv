import Foundation

/// Fast guide lookups for playlist channels.
///
/// Matching order: the user's manual choice, exact `tvg-id`, case-insensitive `tvg-id`, then normalised
/// names (`tvg-name` / channel name vs. XMLTV `display-name`, then vs. guide id). When several guide channels
/// fit equally, the smallest id wins, so a channel gets the same guide at every launch.
public struct EPGIndex: Sendable {
    public private(set) var data: EPGData
    /// Playlist channel id -> guide channel id chosen by the user ("" = no guide).
    public var overrides: [String: String]
    /// Lowercased guide id -> guide id.
    private var lowercasedIDs: [String: String] = [:]
    /// Name key -> guide id, from the display names of guide channels that have programmes.
    private var displayNameKeys: [String: String] = [:]
    /// Name key -> guide id, from the guide ids themselves (used when no display name matches).
    private var idNameKeys: [String: String] = [:]
    /// Guide id -> each programme's normalised title and subtitle, in `data.programs` order: normalised once here
    /// (in the background, with the rest of the index) rather than for every programme at every search.
    private var searchTexts: [String: [String]] = [:]

    public init(data: EPGData, overrides: [String: String] = [:]) {
        self.data = data
        self.overrides = overrides
        rebuildLookups()
    }

    public static let empty = EPGIndex(data: .empty)

    public var isEmpty: Bool { data.programs.isEmpty }

    private mutating func rebuildLookups() {
        lowercasedIDs = [:]
        displayNameKeys = [:]
        idNameKeys = [:]
        searchTexts = [:]
        for id in data.programs.keys { addLookups(for: id) }
        for id in data.channels.keys where data.programs[id] == nil { addLookups(for: id) }
    }

    /// Registers one guide id. A key several ids claim goes to the smallest, whatever order they come in:
    /// dictionaries iterate in a different order at every launch, and this also lets `insert` update the
    /// lookups for one id instead of rebuilding them.
    private mutating func addLookups(for id: String) {
        Self.claim(&lowercasedIDs, id.lowercased(), for: id)
        Self.claim(&idNameKeys, TextNormalizer.channelMatchKey(id), for: id)
        if let programs = data.programs[id] {
            searchTexts[id] = programs.map { TextNormalizer.normalize($0.title + " " + ($0.subtitle ?? "")) }
        }
        // Display names count only for guide channels with programmes: matching a name to a channel with
        // nothing to show would hide one that has some. `suggestions(for:)` still offers it.
        guard data.programs[id] != nil, let channel = data.channels[id] else { return }
        for name in channel.displayNames {
            Self.claim(&displayNameKeys, TextNormalizer.channelMatchKey(name), for: id)
        }
    }

    private static func claim(_ table: inout [String: String], _ key: String, for id: String) {
        guard !key.isEmpty else { return }
        if let holder = table[key], holder <= id { return }
        table[key] = id
    }

    /// Guide channel id for a playlist channel, if any.
    public func guideID(for channel: Channel) -> String? {
        if let manual = overrides[channel.id] {
            return manual.isEmpty ? nil : manual
        }
        return automaticGuideID(for: channel)
    }

    /// The match found without the user's override.
    public func automaticGuideID(for channel: Channel) -> String? {
        if let id = channel.epgID {
            if data.programs[id] != nil || data.channels[id] != nil { return id }
            if let match = lowercasedIDs[id.lowercased()] { return match }
        }
        for candidate in [channel.epgName, channel.name, channel.epgID].compactMap({ $0 }) {
            let key = TextNormalizer.channelMatchKey(candidate)
            if !key.isEmpty, let id = displayNameKeys[key] ?? idNameKeys[key] { return id }
        }
        return nil
    }

    public func programs(for channel: Channel) -> [EPGProgram] {
        guard let id = guideID(for: channel) else { return [] }
        return data.programs[id] ?? []
    }

    /// Logo from the guide, used when the playlist has none.
    public func iconURL(for channel: Channel) -> URL? {
        guard let id = guideID(for: channel) else { return nil }
        return data.channels[id]?.iconURL
    }

    public func current(for channel: Channel, at date: Date = Date()) -> EPGProgram? {
        let list = programs(for: channel)
        guard let index = Self.indexOfProgram(in: list, at: date) else { return nil }
        return list[index]
    }

    /// Current programme followed by the next `count - 1` ones.
    public func upcoming(for channel: Channel, at date: Date = Date(), count: Int = 2) -> [EPGProgram] {
        let list = programs(for: channel)
        guard !list.isEmpty else { return [] }
        let start = Self.indexOfProgram(in: list, at: date) ?? Self.firstIndex(in: list, startingAfter: date)
        guard let start, start < list.count else { return [] }
        return Array(list[start..<min(start + count, list.count)])
    }

    public func programs(for channel: Channel, in interval: DateInterval) -> [EPGProgram] {
        programs(for: channel).filter { $0.end > interval.start && $0.start < interval.end }
    }

    /// Binary search for the programme airing at `date` (lists are sorted by start).
    static func indexOfProgram(in list: [EPGProgram], at date: Date) -> Int? {
        var low = 0
        var high = list.count - 1
        while low <= high {
            let mid = (low + high) / 2
            let program = list[mid]
            if program.end <= date {
                low = mid + 1
            } else if program.start > date {
                high = mid - 1
            } else {
                return mid
            }
        }
        return nil
    }

    static func firstIndex(in list: [EPGProgram], startingAfter date: Date) -> Int? {
        list.firstIndex { $0.start > date }
    }

    // MARK: - Manual matching

    /// A guide channel the user can pick for a playlist channel.
    public struct GuideChannelOption: Identifiable, Hashable, Sendable {
        public var id: String
        public var name: String
        public var iconURL: URL?
        public var programCount: Int
    }

    /// Every channel the guide describes, sorted by name. Channels the playlist did not match have no
    /// programmes loaded yet (the loader skips them); they are fetched once picked.
    public func guideChannelOptions() -> [GuideChannelOption] {
        allGuideIDs.map(option(for:))
            .sorted { ($0.name.localizedLowercase, $0.id) < ($1.name.localizedLowercase, $1.id) }
    }

    private var allGuideIDs: Set<String> {
        Set(data.programs.keys).union(data.channels.keys)
    }

    public func option(for guideID: String) -> GuideChannelOption {
        let channel = data.channels[guideID]
        return GuideChannelOption(
            id: guideID,
            name: channel?.displayNames.first ?? guideID,
            iconURL: channel?.iconURL,
            programCount: data.programs[guideID]?.count ?? 0
        )
    }

    /// Programme airing on a guide channel (used to preview a guide channel before picking it).
    public func current(guideID: String, at date: Date = Date()) -> EPGProgram? {
        guard let list = data.programs[guideID], let index = Self.indexOfProgram(in: list, at: date) else { return nil }
        return list[index]
    }

    /// Guide channels whose names look like the playlist channel's, best first.
    public func suggestions(for channel: Channel, limit: Int = 8) -> [GuideChannelOption] {
        let wanted = Set([channel.name, channel.epgName, channel.epgID].compactMap { $0 }.flatMap(Self.words))
        guard !wanted.isEmpty else { return [] }
        let wantedKey = TextNormalizer.channelMatchKey(channel.name)
        var scored: [(score: Double, option: GuideChannelOption)] = []
        for id in allGuideIDs {
            let names = (data.channels[id]?.displayNames ?? []) + [id]
            var best = 0.0
            for name in names {
                let words = Set(Self.words(name))
                guard !words.isEmpty else { continue }
                var score = Double(wanted.intersection(words).count) / Double(wanted.union(words).count)
                let key = TextNormalizer.channelMatchKey(name)
                if !key.isEmpty, !wantedKey.isEmpty, key.hasPrefix(wantedKey) || wantedKey.hasPrefix(key) { score += 0.5 }
                best = max(best, score)
            }
            if best > 0.2 { scored.append((best, option(for: id))) }
        }
        return scored
            .sorted { ($0.score, $1.option.name) > ($1.score, $0.option.name) }
            .prefix(limit)
            .map(\.option)
    }

    /// Words of a channel name without quality / country tags ("FR: TV1 HD" -> ["tv1"]).
    static func words(_ name: String) -> [String] {
        TextNormalizer.normalize(name)
            .split(separator: " ")
            .map(String.init)
            .filter { !TextNormalizer.isChannelTag($0) }
    }

    // MARK: - Programme search

    public struct ProgramMatch: Hashable, Sendable {
        public var guideID: String
        public var program: EPGProgram
    }

    /// Programmes whose title (or subtitle) contains every word of `query`, within `interval`, soonest first.
    public func searchPrograms(_ query: String, in interval: DateInterval, limit: Int = 200) -> [ProgramMatch] {
        let tokens = TextNormalizer.normalize(query).split(separator: " ")
        guard !tokens.isEmpty else { return [] }
        var matches: [ProgramMatch] = []
        for (id, programs) in data.programs {
            let texts = searchTexts[id].flatMap { $0.count == programs.count ? $0 : nil }
            for (position, program) in programs.enumerated() where program.end > interval.start && program.start < interval.end {
                let text = texts?[position] ?? TextNormalizer.normalize(program.title + " " + (program.subtitle ?? ""))
                if TextNormalizer.matches(normalizedText: text, queryTokens: tokens) {
                    matches.append(ProgramMatch(guideID: id, program: program))
                }
            }
        }
        return Array(matches.sorted { ($0.program.start, $0.guideID) < ($1.program.start, $1.guideID) }.prefix(limit))
    }

    /// Adds programmes fetched on demand (Xtream short EPG) for a channel.
    public mutating func insert(_ programs: [EPGProgram], for channel: Channel) {
        guard let first = programs.first else { return }
        let id = guideID(for: channel) ?? first.channelID
        var addition = EPGData()
        addition.programs[id] = programs.map { var p = $0; p.channelID = id; return p }
        data.merge(addition)
        // Only `id` changed: rebuilding every lookup here would normalise the whole guide on each insert.
        addLookups(for: id)
    }
}
