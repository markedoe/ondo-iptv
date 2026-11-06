import Foundation

/// The home carousel: the titles released lately, best first by rating and freshness. A title counts from its release
/// date, never from the day the provider added it: a movie released in the last `movieDays` days (TMDb's release date),
/// a series with an episode aired in the last `seriesDays` days (its latest episode or season: a series from 2014 with a
/// new episode this week is in; one with nothing new is not). The same rule, constants and tests as the Android core
/// (`RecentReleases.kt`).
///
/// Each title's score is its weighted rating for new titles (`carouselRating`: few votes, a small prior) plus a freshness
/// bonus that falls from `freshnessBonus` on its release day to 0 at the end of its window (`freshness`). The titles with
/// at least `carouselMinimumVotes` votes come first by score, the others after them by date; movies and series take
/// turns. Fewer titles than the carousel holds: those there are; none: the carousel is empty, never filled with older
/// titles.
public enum RecentReleases {
    /// A movie is in the carousel this many days from its release (today included).
    public static let movieDays = 90
    /// A series is in it this many days from its latest episode.
    public static let seriesDays = 30
    /// The widest window: titles released before it are never in the carousel.
    public static let widest = movieDays

    /// `m` of the carousel's weighted rating: a new title has tens or hundreds of votes, not thousands, so the prior
    /// weighs as 50 votes (200 elsewhere, `WeightedRating.priorVotes`): a film rated 8 by 300 people (7.79) passes one
    /// rated 9 by 20 (7.21), and ten votes at 10 stay near the prior (7.08).
    public static let carouselPriorVotes = 50.0
    /// Fewer TMDb votes than this and a title is ranked by date, after the rated ones: a handful says nothing yet.
    public static let carouselMinimumVotes = 5
    /// The freshness bonus on a title's release day, falling evenly to 0 at the end of its window: half a point, the gap
    /// between a good and a very good weighted rating, so freshness decides between close ratings, never over a much
    /// better one.
    public static let freshnessBonus = 0.5

    /// Fewer TMDb votes than this and a selection's daily pick (`Selections.dailyOrder`) takes the prior average for a
    /// title's rating: with `WeightedRating.priorVotes` = 200, a handful of votes says nothing yet.
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
        /// The title it stands for (`identity(title:series:year:)`): candidates of one identity are one card.
        public var identity: String
        /// A series: its window is `seriesDays`, and the carousel alternates movies and series.
        public var series: Bool

        public init(id: String, title: String, released: Int?, average: Double? = nil, votes: Int = 0, categoryID: String = "",
                    hasArtwork: Bool = true, identity: String? = nil, series: Bool = false) {
            self.id = id
            self.identity = identity ?? id
            self.series = series
            self.title = title
            self.released = released
            self.average = average
            self.votes = votes
            self.categoryID = categoryID
            self.hasArtwork = hasArtwork
        }
    }

    /// How many days a title of this kind stays in the carousel.
    public static func window(series: Bool) -> Int { series ? seriesDays : movieDays }

    /// The weighted rating of a new title, `(v × R + m × C) / (v + m)` with `m` = `carouselPriorVotes` and `C` =
    /// `WeightedRating.priorAverage`; nil under `carouselMinimumVotes` votes or without an average.
    public static func carouselRating(average: Double?, votes: Int) -> Double? {
        guard votes >= carouselMinimumVotes, let average, average > 0 else { return nil }
        let v = Double(votes)
        return (v * average + carouselPriorVotes * WeightedRating.priorAverage) / (v + carouselPriorVotes)
    }

    /// The bonus of a title released `age` days ago: `freshnessBonus` on the day, falling evenly to 0 at its window's end.
    public static func freshness(age: Int, series: Bool) -> Double {
        freshnessBonus * (1.0 - Double(age) / Double(window(series: series)))
    }

    /// A title's score at `today`: `carouselRating` plus `freshness`; nil when it is not rated enough.
    public static func score(_ candidate: Candidate, today: Int) -> Double? {
        guard let released = candidate.released, let rating = carouselRating(average: candidate.average, votes: candidate.votes) else { return nil }
        return rating + freshness(age: today - released, series: candidate.series)
    }

    /// What a title is, whatever the provider's entry: a series by its name cleaned (`FilmVersions.titleKey`, the
    /// provider's tags and qualities left out) without an episode ("Harbor S01E08", "|FR| Harbor" and "Harbor 4K" are
    /// one series); a movie by its cleaned name and year (a remake is another film).
    public static func identity(title: String, series: Bool, year: Int?) -> String {
        if !series {
            let yearText: String = year.map { String($0) } ?? ""
            return "movie:" + FilmVersions.titleKey(title) + "|" + yearText
        }
        let name: String = TitleParser.parseEpisode(title)?.seriesName ?? title
        return "series:" + FilmVersions.titleKey(name)
    }

    /// One candidate per `Candidate.identity`: the one released last, then the one with more votes, then the smaller id;
    /// in the order of `candidates`.
    public static func distinctTitles(_ candidates: [Candidate]) -> [Candidate] {
        var best: [String: Candidate] = [:]
        for candidate in candidates {
            if let kept = best[candidate.identity], !isBetter(candidate, than: kept) { continue }
            best[candidate.identity] = candidate
        }
        return candidates.filter { (candidate: Candidate) -> Bool in best[candidate.identity]?.id == candidate.id }
    }

    private static func isBetter(_ a: Candidate, than b: Candidate) -> Bool {
        let aDay: Int = a.released ?? Int.min
        let bDay: Int = b.released ?? Int.min
        if aDay != bDay { return aDay > bDay }
        if a.votes != b.votes { return a.votes > b.votes }
        return a.id.utf16.lexicographicallyPrecedes(b.id.utf16)
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

    /// Up to `size` titles for the carousel at `today` (an epoch day): those released inside their window (`window`), the
    /// rated ones by `score`, then the others by date, then the title and the id; movies and series take turns, the kind
    /// of the first one first. Never a title released after `today`, without artwork, of a `hidden` category or without a
    /// full release date; one title once (`distinctTitles`).
    public static func pick(_ candidates: [Candidate], today: Int, size: Int, hidden: Set<String> = []) -> [Candidate] {
        guard size > 0 else { return [] }
        var seen = Set<String>()
        let shown: [Candidate] = candidates.filter { candidate in
            guard let day = candidate.released, day <= today, today - day < window(series: candidate.series),
                  candidate.hasArtwork, !hidden.contains(candidate.categoryID) else { return false }
            return seen.insert(candidate.id).inserted
        }
        let released: [Candidate] = distinctTitles(shown)
        return Array(alternate(rank(released, today: today)).prefix(size))
    }

    /// `candidates` in the carousel's order before movies and series take turns: by `score`, then by date.
    public static func rank(_ candidates: [Candidate], today: Int) -> [Candidate] {
        let keyed: [(Candidate, Double?)] = candidates.map { ($0, score($0, today: today)) }
        let sorted = keyed.sorted { (a: (Candidate, Double?), b: (Candidate, Double?)) -> Bool in
            switch (a.1, b.1) {
            case let (x?, y?) where x != y: return x > y
            case (.some, .none): return true
            case (.none, .some): return false
            default: return newestFirst(a.0, b.0)
            }
        }
        return sorted.map(\.0)
    }

    /// Movies and series in turn, each kind in its order, the kind of the first one first; the rest of one kind at the end.
    public static func alternate(_ ranked: [Candidate]) -> [Candidate] {
        guard let first = ranked.first else { return [] }
        let leading: [Candidate] = ranked.filter { $0.series == first.series }
        let other: [Candidate] = ranked.filter { $0.series != first.series }
        var result: [Candidate] = []
        for index in 0..<max(leading.count, other.count) {
            if index < leading.count { result.append(leading[index]) }
            if index < other.count { result.append(other[index]) }
        }
        return result
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
