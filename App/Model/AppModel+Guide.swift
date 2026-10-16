import Foundation
import IPTVCore

/// A programme found by the guide search, with the playlist channel that airs it.
struct ProgramSearchResult: Identifiable, Hashable {
    let channel: Channel
    let program: EPGProgram
    var id: String { channel.id + "|" + program.id }
}

/// How a channel gets its guide.
enum GuideMatch: Equatable {
    /// Matched by id or name (nil when nothing matched).
    case automatic(String?)
    /// Picked by the user.
    case manual(String)
    /// The user turned the guide off for this channel.
    case none
}

/// Manual guide matching and programme search.
extension AppModel {
    func guideMatch(for channel: Channel) -> GuideMatch {
        switch activePlaylist?.epgChannelOverrides[channel.id] {
        case .some(let id) where id.isEmpty: return .none
        case .some(let id): return .manual(id)
        case nil: return .automatic(epg.automaticGuideID(for: channel))
        }
    }

    /// `nil` restores automatic matching, `""` turns the guide off for the channel.
    func setGuideMatch(_ guideID: String?, for channel: Channel) async {
        guard var playlist = activePlaylist else { return }
        playlist.epgChannelOverrides[channel.id] = guideID
        await updatePlaylist(playlist)
    }

    /// Programmes matching `query` from the archive (when replayable) to the end of the guide.
    func searchPrograms(_ query: String, now: Date = Date(), limit: Int = 100) -> [ProgramSearchResult] {
        let window = DateInterval(start: now.addingTimeInterval(-7 * 86_400), end: now.addingTimeInterval(14 * 86_400))
        return programResults(epg.searchPrograms(query, in: window, limit: limit * 4), now: now, limit: limit)
    }

    /// `searchPrograms` with the guide scanned on a background task.
    func searchProgramsInBackground(_ query: String, now: Date = Date(), limit: Int = 100) async -> [ProgramSearchResult] {
        let window = DateInterval(start: now.addingTimeInterval(-7 * 86_400), end: now.addingTimeInterval(14 * 86_400))
        let guide = epg
        let matches = await Task.detached(priority: .userInitiated) { guide.searchPrograms(query, in: window, limit: limit * 4) }.value
        return programResults(matches, now: now, limit: limit)
    }

    private func programResults(_ matches: [EPGIndex.ProgramMatch], now: Date, limit: Int) -> [ProgramSearchResult] {
        let channelsByGuideID = channelsByGuideID()
        let excluded = excludedCategoryIDs
        let hidden = hiddenChannelIDs
        var results: [ProgramSearchResult] = []
        for match in matches {
            // Hidden, locked and (for kids) adult categories stay out of search, like everywhere else.
            guard let channel = channelsByGuideID[match.guideID]?.first(where: { !excluded.contains($0.categoryID) && !hidden.contains($0.id) }) else { continue }
            if match.program.end <= now && !canReplay(match.program, on: channel) { continue }
            results.append(ProgramSearchResult(channel: channel, program: match.program))
            if results.count == limit { break }
        }
        // On now first, then upcoming, then replays (most recent first).
        return results.sorted { lhs, rhs in
            let left = Self.searchRank(lhs.program, now: now)
            let right = Self.searchRank(rhs.program, now: now)
            if left != right { return left < right }
            return left == 2 ? lhs.program.start > rhs.program.start : lhs.program.start < rhs.program.start
        }
    }

    private static func searchRank(_ program: EPGProgram, now: Date) -> Int {
        if program.isLive(at: now) { return 0 }
        return program.start > now ? 1 : 2
    }

    /// The programme on a channel at `date`, from the guide matches kept per catalogue and guide: lists ask for it
    /// for every row at every redraw, and matching a channel to the guide normalises its names.
    func currentProgram(for channel: Channel, at date: Date = Date()) -> EPGProgram? {
        if let id = guideIDsByChannelID()[channel.id] { return epg.current(guideID: id, at: date) }
        // A channel outside the catalogue (a multiview tile of another playlist…): matched on its own.
        return index.channelsByID[channel.id] == nil ? epg.current(for: channel, at: date) : nil
    }

    /// Playlist channels per guide channel id.
    func channelsByGuideID() -> [String: [Channel]] {
        guideMatches().map
    }

    /// The guide channel of each playlist channel: one matching pass per catalogue and guide, then lookups (a zapping
    /// queue asks for thousands at once).
    func guideIDsByChannelID() -> [String: String] {
        guideMatches().guideIDs
    }

    private func guideMatches() -> (map: [String: [Channel]], guideIDs: [String: String]) {
        let key = "\(content.fetchedAt.timeIntervalSince1970)|\(content.channels.count)|\(epg.data.fetchedAt.timeIntervalSince1970)|\(epg.data.programs.count)|\(epg.overrides.hashValue)"
        if let cache = channelsByGuideIDCache, cache.key == key { return (cache.map, cache.guideIDs) }
        var map: [String: [Channel]] = [:]
        var guideIDs: [String: String] = [:]
        for channel in content.channels {
            if let id = epg.guideID(for: channel) {
                map[id, default: []].append(channel)
                guideIDs[channel.id] = id
            }
        }
        channelsByGuideIDCache = (key, map, guideIDs)
        return (map, guideIDs)
    }
}
