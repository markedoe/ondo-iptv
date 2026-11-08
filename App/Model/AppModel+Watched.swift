import Foundation
import IPTVCore

/// Watched titles: the badge on every thumbnail, the state and the "Mark as Watched" / "Mark as Unwatched" buttons of
/// the pages. Watched is the state the end of playback leaves (`HistoryEntry.isFinished`), kept in the open profile's
/// library: sync carries it like any position (docs/SYNC.md).
extension AppModel {
    /// The movies (each version of a film once one is) and the series (every episode known) watched in the active
    /// playlist, as `ShowcaseItem.id` keys. Made again only when the history, the catalogue or the known seasons change:
    /// a card asks it with one set lookup.
    var watchedKeys: Set<String> {
        // Read on every call, so that a view asking this is redrawn when they change.
        let history = library.history
        let movies = content.movies
        let revision = detailsRevision
        let playlist = activePlaylist?.id
        let grouped = settings.groupVersions
        if let cached = watchedKeysCache, cached.playlist == playlist, cached.revision == revision, cached.grouped == grouped,
           cached.history == history, cached.movies == movies {
            return cached.keys
        }
        let watched = WatchedTitles(history: history, playlistID: playlist)
        var keys = Set(films.expand(watched.movies).map { "m-" + $0 })
        for seriesID in watched.episodes.keys {
            // Its seasons as known: the provider's, else the playlist's own episodes (an M3U playlist lists them).
            let known: [String]
            if let details = seriesDetailsCache[seriesID] {
                known = details.seasons.flatMap(\.episodes).map(\.id)
            } else if let episodes = index.seriesByID[seriesID]?.episodes, !episodes.isEmpty {
                known = episodes.map(\.id)
            } else {
                continue
            }
            if watched.isSeriesWatched(seriesID, episodeIDs: known) { keys.insert("s-" + seriesID) }
        }
        watchedKeysCache = WatchedKeysCache(playlist: playlist, revision: revision, grouped: grouped, history: history, movies: movies, keys: keys)
        return keys
    }

    func isWatched(_ item: ShowcaseItem) -> Bool {
        watchedKeys.contains(item.id)
    }

    func isWatched(episode: Episode, in series: Series) -> Bool {
        progress(episode: episode, in: series)?.isFinished == true
    }

    /// Marks a film watched (the version its page shows, else its default one) or, unwatched, every version watched.
    func setWatched(_ watched: Bool, movie: Movie, version: Movie? = nil, duration: Int? = nil) {
        guard let playlist = activePlaylist else { return }
        if watched {
            let stream = version ?? defaultVersion(of: movie)
            let time = syncStamp()
            updateLibrary {
                $0.markWatched(HistoryEntry(
                    reference: MediaReference(playlistID: playlist.id, type: .movie, itemID: stream.id),
                    title: stream.name, imageURL: stream.posterURL, duration: Double(duration ?? 0)
                ), now: time)
            }
        } else {
            let time = syncStamp()
            // Every version, those of a hidden category too: the badge counts them all (`watchedKeys`).
            let versions = films.versions(of: movie) + [movie]
            updateLibrary { library in
                for stream in versions {
                    library.markUnwatched(MediaReference(playlistID: playlist.id, type: .movie, itemID: stream.id), now: time)
                }
            }
        }
        syncSoon()
    }

    /// Marks episodes of `series` watched or unwatched, in their order: the last one is the latest watched, so Continue
    /// Watching goes on after it as after playback.
    func setWatched(_ watched: Bool, episodes: [Episode], in series: Series) {
        guard let playlist = activePlaylist, !episodes.isEmpty else { return }
        let entries = episodes.map { episode in
            HistoryEntry(
                reference: MediaReference(playlistID: playlist.id, type: .series, itemID: series.id, episodeID: episode.id),
                title: series.name, subtitle: "\(episode.code) · \(episode.title)", imageURL: episode.posterURL ?? series.coverURL,
                duration: Double(episode.durationSeconds ?? 0)
            )
        }
        let times = entries.map { _ in syncStamp() }
        updateLibrary { library in
            for (entry, time) in zip(entries, times) {
                if watched { library.markWatched(entry, now: time) } else { library.markUnwatched(entry.reference, now: time) }
            }
        }
        syncSoon()
    }

    /// The whole series: every episode of its seasons as known.
    func setWatched(_ watched: Bool, series: Series, details: SeriesDetails) {
        var seen = Set<String>()
        let episodes = details.seasons.flatMap(\.episodes).filter { seen.insert($0.id).inserted }
        setWatched(watched, episodes: episodes, in: series)
    }

    /// A series' page: watched once every episode of its seasons is.
    func isWatched(series: Series, details: SeriesDetails) -> Bool {
        WatchedTitles(history: library.history, playlistID: activePlaylist?.id)
            .isSeriesWatched(series.id, episodeIDs: details.seasons.lazy.flatMap(\.episodes).map(\.id))
    }

    /// A film's page: watched once one of its versions is.
    func isWatchedFilm(_ movie: Movie) -> Bool {
        films.versions(of: movie).contains { progress(movie: $0)?.isFinished == true }
    }
}

/// What `watchedKeys` was made from, and its result.
struct WatchedKeysCache {
    let playlist: UUID?
    let revision: Int
    let grouped: Bool
    let history: [HistoryEntry]
    let movies: [Movie]
    let keys: Set<String>
}
