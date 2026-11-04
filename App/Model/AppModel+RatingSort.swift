import Foundation
import IPTVCore

/// The grids sorted by rating, with the viewer's TMDb token: TMDb's rating, weighted by its number of votes, before the
/// provider's (`WeightedRating.ratingOrder`), and a bounded fill of the ratings TMDb was never asked for
/// (`RatingFill`). Without a token, the provider's rating as before.
extension AppModel {
    /// What a sorted list's cache key adds for this order: the TMDb votes it was sorted with.
    func ratingSortKey(_ sort: PosterSort) -> String {
        guard sort == .rating, hasTMDbKey else { return "" }
        return "|tmdb\(tmdbMetadata.sortRevision)"
    }

    /// What a grid's fill depends on: it starts again with another category, order (`sort`, the grid's), token or
    /// catalogue.
    func ratingFillID(category: MediaCategory, sort: PosterSort) -> String {
        "\(category.id)|\(sort.rawValue)|\(hasTMDbKey)|\(content.fetchedAt.timeIntervalSince1970)"
    }

    /// TMDb's votes a rating sort ranks by, by `ShowcaseItem.id`; nil for another order or without a TMDb token.
    func ratingSortVotes(_ sort: PosterSort) -> [String: (average: Double, count: Int)]? {
        guard sort == .rating, hasTMDbKey else { return nil }
        // A grid that sorts without a cached key (a row's) redraws when the votes change.
        _ = tmdbMetadata.sortRevision
        return tmdbMetadata.sortVotes
    }

    /// A grid sorted by rating is on screen with `items` (in its order): what the store kept of them is read at once
    /// and the grid sorted by it, then up to `RatingFill.limit` of those TMDb was never asked about are fetched in the
    /// background at the pace of the other requests, and the grid sorts again twice (after `RatingFill.firstBatch`
    /// ratings and at the end). Nothing without the viewer's TMDb token; stops when the grid goes (its task is cancelled).
    func fillRatingSort(_ items: [ShowcaseItem]) async {
        guard hasTMDbKey, !isTMDbKeyRefused, !items.isEmpty else { return }
        let state = tmdbMetadata
        // Keys worked out off the main thread (a category can have thousands of titles).
        let inputs = items.map { item in (item.id, item.contentType, cachedDetails(for: item)?.tmdbID, item.title, item.year) }
        let keyed = await Task.detached(priority: .userInitiated) {
            inputs.map { input in
                (input.0 + "|" + (input.2 ?? ""), MetadataKey.all(input.1, tmdbID: input.2, name: input.3, year: input.4))
            }
        }.value
        for (cacheKey, keys) in keyed where state.keysCache[cacheKey] == nil { state.keysCache[cacheKey] = keys }
        await readStoredMetadata(keyed.flatMap { $0.1 })
        updateSortVotes(items)
        guard !Task.isCancelled, hasTMDbKey, !isTMDbKeyRefused, !state.isPaused else { return }
        let ask = RatingFill.toAsk(items) { item in
            state.attempted.contains(item.id) || state.prefetching[item.id] != nil || state.opened[item.id] != nil
                || metadataKeys(for: item).contains { state.titles[$0] != nil }
        }
        guard !ask.isEmpty else { return }
        let byID = Dictionary(ask.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        await RatingFill.run(
            ask.map(\.id),
            fetch: { [weak self] id in await self?.fetchRating(byID[id]) ?? false },
            resort: { [weak self] in await self?.updateSortVotes(items) }
        )
    }

    /// One title of the fill: asked unless something else did meanwhile; true when TMDb gave it a rating.
    private func fetchRating(_ item: ShowcaseItem?) async -> Bool {
        let state = tmdbMetadata
        guard let item, hasTMDbKey, !isTMDbKeyRefused, !state.isPaused, !state.attempted.contains(item.id),
              state.prefetching[item.id] == nil, state.opened[item.id] == nil else { return false }
        // Not also in the queue of the titles about to be seen.
        state.queue.remove(item.id)
        state.waiting[item.id] = nil
        await fetchTMDbMetadata(for: item, paced: true)
        return tmdbTitle(for: item)?.votes != nil
    }

    /// Takes TMDb's votes of `items` into the rating sort; the grids sort again only when one changed.
    func updateSortVotes(_ items: [ShowcaseItem]) {
        let state = tmdbMetadata
        var changed = false
        for item in items {
            let votes = tmdbTitle(for: item)?.votes
            let old = state.sortVotes[item.id]
            guard old?.average != votes?.average || old?.count != votes?.count else { continue }
            state.sortVotes[item.id] = votes
            changed = true
        }
        if changed { state.sortRevision += 1 }
    }
}
