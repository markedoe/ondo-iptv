import Foundation
import IPTVCore

/// A movie or a series shown on the home screen.
enum ShowcaseItem: Identifiable, Hashable {
    case movie(Movie)
    case series(Series)

    var id: String {
        switch self {
        case .movie(let movie): return "m-" + movie.id
        case .series(let series): return "s-" + series.id
        }
    }

    var title: String {
        switch self {
        case .movie(let movie): return movie.name
        case .series(let series): return series.name
        }
    }

    var posterURL: URL? {
        switch self {
        case .movie(let movie): return movie.posterURL
        case .series(let series): return series.coverURL
        }
    }

    var year: Int? {
        switch self {
        case .movie(let movie): return movie.year
        case .series(let series): return series.year
        }
    }

    var rating: Double? {
        switch self {
        case .movie(let movie): return movie.rating
        case .series(let series): return series.rating
        }
    }
}

/// What the home screen features: continue watching first, then trending and recent titles.
extension AppModel {
    /// Movies newest first when the provider dates them, otherwise in playlist order.
    func featuredMovies(limit: Int = 20) -> [Movie] {
        let recent = recentlyAddedMovies(limit: limit)
        return recent.isEmpty ? Array(movies(in: nil, sort: .playlist).prefix(limit)) : recent
    }

    func featuredSeries(limit: Int = 20) -> [Series] {
        let recent = recentlyAddedSeries(limit: limit)
        return recent.isEmpty ? Array(series(in: nil, sort: .playlist).prefix(limit)) : recent
    }

    var moviesAreDated: Bool { content.movies.contains { $0.addedAt != nil } }
    var seriesAreDated: Bool { content.series.contains { $0.addedAt != nil } }

    /// Movies and series of the user's favourites (My List), most recently added first.
    func myList() -> [ShowcaseItem] {
        let movies = favorites(.movie).compactMap { entry in index.moviesByID[entry.reference.itemID].map { (entry.addedAt, ShowcaseItem.movie($0)) } }
        let series = favorites(.series).compactMap { entry in index.seriesByID[entry.reference.itemID].map { (entry.addedAt, ShowcaseItem.series($0)) } }
        return (movies + series).sorted { $0.0 > $1.0 }.map(\.1)
    }

    /// The item a history entry belongs to (movie or series).
    func showcaseItem(for entry: HistoryEntry) -> ShowcaseItem? {
        switch entry.reference.type {
        case .movie: return index.moviesByID[entry.reference.itemID].map(ShowcaseItem.movie)
        case .series: return index.seriesByID[entry.reference.itemID].map(ShowcaseItem.series)
        case .live: return nil
        }
    }

    /// Up to `limit` titles for the hero carousel, without duplicates.
    func heroItems(limit: Int = 5) -> [ShowcaseItem] {
        var items: [ShowcaseItem] = []
        func append(_ item: ShowcaseItem) {
            if items.count < limit, !items.contains(item) { items.append(item) }
        }
        for entry in continueWatching().prefix(2) {
            if let item = showcaseItem(for: entry) { append(item) }
        }
        for match in trending {
            switch match {
            case .movie(let movie, _): append(.movie(movie))
            case .series(let series, _): append(.series(series))
            }
        }
        let movies = featuredMovies(limit: limit)
        let series = featuredSeries(limit: limit)
        for index in 0..<max(movies.count, series.count) where items.count < limit {
            if index < movies.count { append(.movie(movies[index])) }
            if index < series.count { append(.series(series[index])) }
        }
        return items
    }

    /// Details already loaded (no network).
    func cachedDetails(for item: ShowcaseItem) -> MediaDetails? {
        switch item {
        case .movie(let movie): return movieDetailsCache[movie.id]
        case .series(let series): return seriesDetailsCache[series.id]?.info
        }
    }

    /// Loads the details of an item (Xtream info call; cached afterwards).
    func loadDetails(for item: ShowcaseItem) async -> MediaDetails? {
        switch item {
        case .movie(let movie): return await details(for: movie)
        case .series(let series): return try? await details(for: series).info
        }
    }

    /// Wide artwork: the provider's backdrop, else the trending one from TMDb.
    func backdropURL(for item: ShowcaseItem) -> URL? {
        if let url = cachedDetails(for: item)?.backdropURL { return url }
        return trending.first { match in
            switch (match, item) {
            case (.movie(let a, _), .movie(let b)): return a.id == b.id
            case (.series(let a, _), .series(let b)): return a.id == b.id
            default: return false
            }
        }?.trending.backdropURL
    }

    /// Plays a movie (resuming it) or the next episode of a series.
    func playShowcase(_ item: ShowcaseItem) async {
        switch item {
        case .movie(let movie):
            play(movie)
        case .series(let series):
            guard let details = try? await details(for: series), let next = nextEpisode(in: series, details: details) else { return }
            play(next.episode, in: series, season: next.season.episodes)
        }
    }

    /// Episode to watch next: the one in progress, else the one after the last finished, else the first.
    func nextEpisode(in series: Series, details: SeriesDetails) -> (episode: Episode, season: Season)? {
        var lastWatched: (episode: Episode, season: Season, entry: HistoryEntry)?
        for season in details.seasons {
            for episode in season.episodes {
                if let entry = progress(episode: episode, in: series),
                   lastWatched.map({ entry.lastWatchedAt > $0.entry.lastWatchedAt }) ?? true {
                    lastWatched = (episode, season, entry)
                }
            }
        }
        guard let last = lastWatched else {
            guard let first = details.seasons.first, let episode = first.episodes.first else { return nil }
            return (episode, first)
        }
        if last.entry.isFinished, let index = last.season.episodes.firstIndex(of: last.episode) {
            if index + 1 < last.season.episodes.count { return (last.season.episodes[index + 1], last.season) }
            if let seasonIndex = details.seasons.firstIndex(of: last.season), seasonIndex + 1 < details.seasons.count,
               let next = details.seasons[seasonIndex + 1].episodes.first {
                return (next, details.seasons[seasonIndex + 1])
            }
        }
        return (last.episode, last.season)
    }
}
