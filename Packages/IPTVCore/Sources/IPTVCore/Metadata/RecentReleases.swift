import Foundation

/// The home carousel: the titles released lately, the best first by TMDb's weighted rating (`WeightedRating.weighted`).
/// A title counts from its release date, never from the day the provider added it: a movie's release, a series' latest
/// episode or season (a series from 2014 with a new season this week is new; one with nothing new is not). The same
/// rule, constants and tests as the Android core (`RecentReleases.kt`).
///
/// The window is the last `windows` days, never a date in the future; it widens step by step while fewer titles than the
/// carousel holds qualify. When none qualifies at the widest, the newest releases by date stand in (the carousel stays
/// among recent releases); with none at all the carousel is empty, never filled with older titles.
public enum RecentReleases {
    /// The windows tried in turn (days, today included), until enough titles qualify.
    public static let windows = [7, 14, 30, 60]
    /// The widest window: titles released before it are never in the carousel.
    public static var widest: Int { windows[windows.count - 1] }

    /// Fewer TMDb votes than this and a title does not qualify for the ranking. New releases have few votes: the weight
    /// (`WeightedRating.priorVotes` = 200) already keeps a title with a handful near the prior (10 votes at 10/10 score
    /// 6.67), so the floor only keeps out the first few votes of a title nobody has seen yet.
    public static let minimumVotes = 10

    /// A title to place: its release (`epochDay`), TMDb's votes when known, and what the carousel needs to show it.
    public struct Candidate: Hashable, Sendable {
        public var id: String
        public var title: String
        /// The day it was released (`epochDay`); nil when only a year, or nothing, is known.
        public var released: Int?
        public var average: Double?
        public var votes: Int
        public var categoryID: String
        public var hasArtwork: Bool

        public init(id: String, title: String, released: Int?, average: Double? = nil, votes: Int = 0, categoryID: String = "",
                    hasArtwork: Bool = true) {
            self.id = id
            self.title = title
            self.released = released
            self.average = average
            self.votes = votes
            self.categoryID = categoryID
            self.hasArtwork = hasArtwork
        }
    }

    /// "2026-11-02" as a day count since 1970-01-01; nil for anything but a full, valid date (a year alone is not one).
    public static func epochDay(_ date: String?) -> Int? {
        guard let text = date?.trimmingCharacters(in: .whitespaces), text.count >= 10 else { return nil }
        let parts = text.prefix(10).split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0].count == 4, parts[1].count == 2, parts[2].count == 2,
              parts.allSatisfy({ $0.allSatisfy(\.isASCII) && $0.allSatisfy(\.isNumber) }),
              let year = Int(parts[0]), let month = Int(parts[1]), let day = Int(parts[2]) else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        let components = DateComponents(year: year, month: month, day: day)
        guard components.isValidDate(in: calendar), let date = calendar.date(from: components) else { return nil }
        return Int((date.timeIntervalSince1970 / 86_400).rounded(.down))
    }

    /// The day of `date` where the viewer is (`calendar`), as `epochDay` counts days.
    public static func today(_ date: Date, calendar: Calendar = .current) -> Int {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return epochDay(String(format: "%04d-%02d-%02d", parts.year ?? 1970, parts.month ?? 1, parts.day ?? 1)) ?? 0
    }

    /// The release the carousel counts: TMDb's (`TitleMetadata.releaseDate`) when it gave one, else the provider's when
    /// it is a full date (a movie's info, a series' latest episode).
    public static func releaseDay(tmdb: String?, provider: String?) -> Int? {
        epochDay(tmdb) ?? epochDay(provider)
    }

    /// Whether a title may have been released inside the widest window, from what the playlist alone says: released this
    /// year or last year, or changed by the provider lately (`addedAt`: a series gets a new episode). Only those are
    /// asked TMDb about for the carousel.
    public static func mayBeRecent(year: Int?, addedAt: Date?, today: Int) -> Bool {
        let thisYear = Int(String(dayString(today).prefix(4))) ?? 0
        if let year, year >= thisYear - 1, year <= thisYear + 1 { return true }
        guard let addedAt else { return false }
        return Int((addedAt.timeIntervalSince1970 / 86_400).rounded(.down)) >= today - widest
    }

    /// Whether TMDb is asked again about a title kept at `fetchedAt` with `releaseDate`: when nothing is kept, when it was
    /// kept before release dates were, or when the provider changed the title since (`addedAt`: a series' new episode
    /// moves its latest air date).
    public static func needsAsking(kept: Bool, found: Bool, releaseDate: String?, fetchedAt: Date, addedAt: Date?) -> Bool {
        guard kept else { return true }
        guard found else { return false }
        guard releaseDate != nil else { return true }
        return addedAt.map { $0 > fetchedAt } ?? false
    }

    /// Up to `size` titles for the carousel at `today` (an epoch day): those released in the first window of `windows`
    /// that gives `size` qualifying titles (at least `minimumVotes` votes), else the widest's, best first; when none
    /// qualifies, the newest releases of the widest window by date. Never a title released after `today`, without
    /// artwork, of a `hidden` category or without a full release date.
    public static func pick(_ candidates: [Candidate], today: Int, size: Int, hidden: Set<String> = []) -> [Candidate] {
        guard size > 0 else { return [] }
        var seen = Set<String>()
        let released = candidates.filter { candidate in
            guard let day = candidate.released, day <= today, candidate.hasArtwork, !hidden.contains(candidate.categoryID) else { return false }
            return seen.insert(candidate.id).inserted
        }
        var best: [Candidate] = []
        for days in windows {
            best = rank(released.filter { today - $0.released! < days })
            if best.count >= size { return Array(best.prefix(size)) }
        }
        if !best.isEmpty { return best }
        return Array(released.filter { today - $0.released! < widest }.sorted(by: newestFirst).prefix(size))
    }

    /// The candidates with enough votes, best first: the weighted rating, then the newest, then the title, then the id.
    public static func rank(_ candidates: [Candidate]) -> [Candidate] {
        candidates
            .compactMap { candidate -> (Candidate, Double)? in
                guard candidate.votes >= minimumVotes,
                      let score = WeightedRating.weighted(average: candidate.average ?? 0, votes: candidate.votes) else { return nil }
                return (candidate, score)
            }
            .sorted { a, b in
                if a.1 != b.1 { return a.1 > b.1 }
                return newestFirst(a.0, b.0)
            }
            .map(\.0)
    }

    private static func newestFirst(_ a: Candidate, _ b: Candidate) -> Bool {
        if a.released != b.released { return (a.released ?? .min) > (b.released ?? .min) }
        let byTitle = a.title.caseInsensitiveCompare(b.title)
        if byTitle != .orderedSame { return byTitle == .orderedAscending }
        return a.id < b.id
    }

    /// "2026-11-02" for an epoch day.
    public static func dayString(_ day: Int) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        let parts = calendar.dateComponents([.year, .month, .day], from: Date(timeIntervalSince1970: TimeInterval(day) * 86_400))
        return String(format: "%04d-%02d-%02d", parts.year ?? 1970, parts.month ?? 1, parts.day ?? 1)
    }
}
