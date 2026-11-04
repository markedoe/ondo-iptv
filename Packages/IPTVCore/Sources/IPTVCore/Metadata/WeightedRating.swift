import Foundation

/// The home carousel's "best recent" ranking: TMDb's average vote weighted by how many gave it (a Bayesian weighted
/// rating), so a title rated 10 by ten people does not beat one rated 7.8 by thousands. The same rule, constants and
/// tests as the Android core (`WeightedRating.kt`).
///
/// `score = v / (v + m) * R + m / (v + m) * C`, with `R` the title's average, `v` its votes, `m` = 200 and `C` = 6.5.
/// A title needs at least 50 votes to qualify at all.
public enum WeightedRating {
    /// `m`: the votes a title needs for its own average to weigh as much as the prior.
    public static let priorVotes = 200.0
    /// `C`: the average assumed for a title nobody has rated.
    public static let priorAverage = 6.5
    /// Fewer votes than this and a title does not qualify.
    public static let minimumVotes = 50
    /// How far back (in days) the carousel looks for titles the provider added.
    public static let recentDays = 4

    /// Whether a title added at `addedAt` is inside the carousel's window at `now` (exactly `recentDays` days ago is in).
    public static func isRecent(_ addedAt: Date, now: Date) -> Bool {
        addedAt >= now.addingTimeInterval(-TimeInterval(recentDays) * 24 * 3600)
    }

    /// A title to rank.
    public struct Candidate: Hashable, Sendable {
        public var id: String
        public var title: String
        /// TMDb's `vote_average` and `vote_count`.
        public var average: Double
        public var votes: Int
        /// When the provider added it.
        public var addedAt: Date

        public init(id: String, title: String, average: Double, votes: Int, addedAt: Date) {
            self.id = id
            self.title = title
            self.average = average
            self.votes = votes
            self.addedAt = addedAt
        }
    }

    /// The weighted rating, nil when the title does not qualify (fewer than `minimumVotes` votes, or no rating).
    public static func score(average: Double, votes: Int) -> Double? {
        guard votes >= minimumVotes else { return nil }
        return weighted(average: average, votes: votes)
    }

    /// The same weighted rating without the carousel's floor of `minimumVotes` (the order of a grid sorted by rating: a
    /// title with few votes stays near `priorAverage`), nil without a rating or a vote.
    public static func weighted(average: Double, votes: Int) -> Double? {
        guard votes > 0, average > 0 else { return nil }
        let v = Double(votes)
        return v / (v + priorVotes) * average + priorVotes / (v + priorVotes) * priorAverage
    }

    /// The order of a grid sorted by rating. The titles TMDb rated come first, by their weighted rating (`weighted`),
    /// then by more votes; the others after them, by the provider's rating (none last): the two scales never mix. Ties
    /// go to the title (without regard to case), then to the id, so the order never depends on the list's.
    /// - Parameters:
    ///   - tmdb: TMDb's `vote_average` and `vote_count` of an item, nil when unknown (no token, not asked yet, not found).
    ///   - provider: the provider's rating of an item.
    public static func ratingOrder<Item>(
        _ items: [Item],
        tmdb: (Item) -> (average: Double, count: Int)?,
        provider: (Item) -> Double?,
        title: (Item) -> String,
        id: (Item) -> String
    ) -> [Item] {
        // (item, weighted rating, votes, provider's rating, title, id): each read once, not at every comparison.
        typealias Keyed = (item: Item, score: Double?, votes: Int, provider: Double?, title: String, id: String)
        let keyed = items.map { item -> Keyed in
            let votes = tmdb(item)
            let score = votes.flatMap { weighted(average: $0.average, votes: $0.count) }
            return (item, score, score == nil ? 0 : votes?.count ?? 0, provider(item).flatMap { $0 > 0 ? $0 : nil }, title(item), id(item))
        }
        return keyed.sorted { a, b in
            switch (a.score, b.score) {
            case let (x?, y?):
                if x != y { return x > y }
                if a.votes != b.votes { return a.votes > b.votes }
            case (.some, nil): return true
            case (nil, .some): return false
            case (nil, nil):
                switch (a.provider, b.provider) {
                case let (x?, y?): if x != y { return x > y }
                case (.some, nil): return true
                case (nil, .some): return false
                case (nil, nil): break
                }
            }
            let byTitle = a.title.caseInsensitiveCompare(b.title)
            if byTitle != .orderedSame { return byTitle == .orderedAscending }
            return a.id < b.id
        }.map(\.item)
    }

    /// The carousel's pick: the candidates added inside the window at `now` that qualify, best first (`rank`).
    public static func bestRecent(_ candidates: [Candidate], now: Date) -> [Candidate] {
        rank(candidates.filter { isRecent($0.addedAt, now: now) })
    }

    /// The qualifying candidates, best first: highest score, then latest added, then title (without regard to case).
    public static func rank(_ candidates: [Candidate]) -> [Candidate] {
        candidates
            .compactMap { candidate in score(average: candidate.average, votes: candidate.votes).map { (candidate, $0) } }
            .sorted { a, b in
                if a.1 != b.1 { return a.1 > b.1 }
                if a.0.addedAt != b.0.addedAt { return a.0.addedAt > b.0.addedAt }
                return a.0.title.caseInsensitiveCompare(b.0.title) == .orderedAscending
            }
            .map(\.0)
    }
}
