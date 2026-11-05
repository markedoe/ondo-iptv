import Foundation

/// TMDb's average vote weighted by how many gave it (a Bayesian weighted rating), so a title rated 10 by ten people does
/// not beat one rated 7.8 by thousands: the home carousel's order (`RecentReleases`) and a grid sorted by rating. The
/// same rule, constants and tests as the Android core (`WeightedRating.kt`).
///
/// `score = v / (v + m) * R + m / (v + m) * C`, with `R` the title's average, `v` its votes, `m` = 200 and `C` = 6.5.
public enum WeightedRating {
    /// `m`: the votes a title needs for its own average to weigh as much as the prior.
    public static let priorVotes = 200.0
    /// `C`: the average assumed for a title nobody has rated.
    public static let priorAverage = 6.5
    /// The weighted rating (a title with few votes stays near `priorAverage`), nil without a rating or a vote.
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
        // Each read once, not at every comparison.
        let keyed = items.map { item -> RatedItem<Item> in
            let votes = tmdb(item)
            let score = votes.flatMap { weighted(average: $0.average, votes: $0.count) }
            return RatedItem(item: item, score: score, votes: score == nil ? 0 : votes?.count ?? 0,
                             provider: provider(item).flatMap { $0 > 0 ? $0 : nil }, title: title(item), id: id(item))
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
}

/// An item of `WeightedRating.ratingOrder` with what it is sorted by.
private struct RatedItem<Item> {
    let item: Item
    let score: Double?
    let votes: Int
    let provider: Double?
    let title: String
    let id: String
}
