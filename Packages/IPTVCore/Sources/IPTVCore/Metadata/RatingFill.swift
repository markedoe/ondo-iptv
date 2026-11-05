import Foundation

/// A grid sorted by rating ranks the titles TMDb rated first (`WeightedRating.ratingOrder`), but TMDb is asked about a
/// title only when it shows somewhere, so most of a category has no TMDb rating yet. With the viewer's TMDb token, the
/// grid asks for the missing ones in the background, a bounded number, at TMDb's pace, and re-sorts in two steps
/// rather than at each answer (the grid would move under the viewer). The same rule, constants and tests as the Android
/// core (`RatingFill.kt`).
public enum RatingFill {
    /// Titles asked at most each time a grid is sorted by rating (the rest wait for the next time, or their own page).
    public static let limit = 100
    /// Answers with a rating before the first re-sort; the second comes when the fill ends.
    public static let firstBatch = 20
    /// Requests at the same time (the pace itself is the caller's, shared with the other background requests).
    public static let concurrency = 2

    /// The titles to ask TMDb about: those it never answered for (`isKnown` false), in the grid's order, at most `limit`.
    public static func toAsk<ID>(_ ids: [ID], isKnown: (ID) -> Bool, limit: Int = RatingFill.limit) -> [ID] {
        var result: [ID] = []
        for id in ids where result.count < limit && !isKnown(id) { result.append(id) }
        return result
    }

    /// Asks `fetch` about each id, `concurrency` at a time, in order. `fetch` answers whether TMDb gave a rating;
    /// `resort` is called once `firstBatch` ratings came, then once at the end when more came since. Stops asking when
    /// the task is cancelled (the grid left), without a last re-sort. Returns how many times it re-sorted.
    @discardableResult
    public static func run(
        _ ids: [String],
        concurrency: Int = RatingFill.concurrency,
        firstBatch: Int = RatingFill.firstBatch,
        fetch: @escaping @Sendable (String) async -> Bool,
        resort: @escaping @Sendable () async -> Void
    ) async -> Int {
        var resorts = 0
        var rated = 0
        var sinceResort = 0
        await withTaskGroup(of: Bool.self) { group in
            var next = ids.makeIterator()
            for _ in 0..<max(1, concurrency) {
                guard let id = next.next() else { break }
                group.addTask { await fetch(id) }
            }
            while let gave = await group.next() {
                if gave {
                    rated += 1
                    sinceResort += 1
                }
                if resorts == 0, rated >= firstBatch, sinceResort > 0, !Task.isCancelled {
                    await resort()
                    resorts += 1
                    sinceResort = 0
                }
                if !Task.isCancelled, let id = next.next() {
                    group.addTask { await fetch(id) }
                }
            }
        }
        if sinceResort > 0, !Task.isCancelled {
            await resort()
            resorts += 1
        }
        return resorts
    }
}
