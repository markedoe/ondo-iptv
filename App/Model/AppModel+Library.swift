import Foundation
import IPTVCore

/// Favourites, history, resume positions and custom lists of the active profile.
extension AppModel {
    // MARK: - Favourites & history

    private func reference(_ type: ContentType, _ id: String) -> MediaReference? {
        activePlaylist.map { MediaReference(playlistID: $0.id, type: type, itemID: id) }
    }

    func isFavorite(_ type: ContentType, id: String) -> Bool {
        guard let ref = reference(type, id) else { return false }
        return library.isFavorite(ref)
    }

    func toggleFavorite(_ channel: Channel) {
        guard let ref = reference(.live, channel.id) else { return }
        updateLibrary { $0.toggleFavorite(ref, title: channel.name, imageURL: logoURL(for: channel)) }
    }

    func toggleFavorite(_ movie: Movie) {
        guard let ref = reference(.movie, movie.id) else { return }
        updateLibrary { $0.toggleFavorite(ref, title: movie.name, imageURL: movie.posterURL) }
    }

    func toggleFavorite(_ series: Series) {
        guard let ref = reference(.series, series.id) else { return }
        updateLibrary { $0.toggleFavorite(ref, title: series.name, imageURL: series.coverURL) }
    }

    func favorites(_ type: ContentType) -> [FavoriteEntry] {
        guard let playlist = activePlaylist else { return [] }
        let excluded = excludedCategoryIDs
        return library.favorites(playlistID: playlist.id, type: type).filter { entry in
            switch type {
            case .live: return index.channelsByID[entry.reference.itemID].map { !excluded.contains($0.categoryID) } ?? false
            case .movie: return index.moviesByID[entry.reference.itemID].map { !excluded.contains($0.categoryID) } ?? false
            case .series: return index.seriesByID[entry.reference.itemID].map { !excluded.contains($0.categoryID) } ?? false
            }
        }
    }

    // MARK: - Custom lists

    @discardableResult
    func createList(named name: String) -> UUID {
        var id = UUID()
        updateLibrary { id = $0.createList(named: name) }
        return id
    }

    func renameList(_ id: UUID, to name: String) {
        updateLibrary { $0.renameList(id, to: name) }
    }

    func deleteList(_ id: UUID) {
        updateLibrary { $0.deleteList(id) }
    }

    func toggle(_ item: LibraryItem, inList id: UUID) {
        guard let ref = reference(item.type, item.id) else { return }
        let image = { () -> URL? in
            if case .channel(let channel) = item { return self.logoURL(for: channel) }
            return item.imageURL
        }()
        updateLibrary { $0.toggle(ref, title: item.title, imageURL: image, inList: id) }
    }

    func isInList(_ item: LibraryItem, list: CustomList) -> Bool {
        guard let ref = reference(item.type, item.id) else { return false }
        return list.contains(ref)
    }

    /// Items of a list that belong to the active playlist and are not locked.
    func items(in list: CustomList) -> [FavoriteEntry] {
        guard let playlist = activePlaylist else { return [] }
        let excluded = excludedCategoryIDs
        return list.items.filter { entry in
            guard entry.reference.playlistID == playlist.id, let item = libraryItem(for: entry.reference) else { return false }
            switch item {
            case .channel(let channel): return !excluded.contains(channel.categoryID)
            case .movie(let movie): return !excluded.contains(movie.categoryID)
            case .series(let series): return !excluded.contains(series.categoryID)
            }
        }
    }

    func moveListItems(_ id: UUID, fromOffsets source: IndexSet, toOffset destination: Int) {
        guard let list = library.customLists.first(where: { $0.id == id }) else { return }
        let move = Self.storedMove(visible: items(in: list), stored: list.items, fromOffsets: source, toOffset: destination)
        updateLibrary { $0.moveItems(inList: id, fromOffsets: move.source, toOffset: move.destination) }
    }

    /// Screens show a filtered list (locked, hidden and vanished items left out), so the offsets of a move
    /// are relative to it: this maps them to the stored list.
    static func storedMove(visible: [FavoriteEntry], stored: [FavoriteEntry], fromOffsets source: IndexSet,
                           toOffset destination: Int) -> (source: IndexSet, destination: Int) {
        let storedSource = IndexSet(source.compactMap { offset in stored.firstIndex { $0.id == visible[offset].id } })
        if destination < visible.count, let target = stored.firstIndex(where: { $0.id == visible[destination].id }) {
            return (storedSource, target)
        }
        return (storedSource, stored.count)
    }

    func favoriteChannels() -> [Channel] {
        let hidden = hiddenChannelIDs
        return favorites(.live).compactMap { index.channelsByID[$0.reference.itemID] }.filter { !hidden.contains($0.id) }
    }

    func moveFavorites(_ type: ContentType, fromOffsets source: IndexSet, toOffset destination: Int) {
        guard let playlist = activePlaylist else { return }
        let move = Self.storedMove(visible: favorites(type), stored: library.favorites(playlistID: playlist.id, type: type),
                                   fromOffsets: source, toOffset: destination)
        updateLibrary { $0.moveFavorites(playlistID: playlist.id, type: type, fromOffsets: move.source, toOffset: move.destination) }
    }

    func recentChannels() -> [Channel] {
        guard let playlist = activePlaylist else { return [] }
        let excluded = excludedCategoryIDs
        let hidden = hiddenChannelIDs
        return library.recentChannels(playlistID: playlist.id, limit: 20)
            .compactMap { index.channelsByID[$0.reference.itemID] }
            .filter { !excluded.contains($0.categoryID) && !hidden.contains($0.id) }
    }

    func continueWatching() -> [HistoryEntry] {
        guard let playlist = activePlaylist else { return [] }
        let excluded = excludedCategoryIDs
        return library.continueWatching(playlistID: playlist.id).filter { entry in
            switch entry.reference.type {
            case .movie: return index.moviesByID[entry.reference.itemID].map { !excluded.contains($0.categoryID) } ?? false
            case .series: return index.seriesByID[entry.reference.itemID].map { !excluded.contains($0.categoryID) } ?? false
            case .live: return false
            }
        }
    }

    /// The movies, or the series, in progress: Home shows them in two rows, each tab in one.
    func continueWatching(_ type: ContentType) -> [HistoryEntry] {
        continueWatching().filter { $0.reference.type == type }
    }

    func progress(movie: Movie) -> HistoryEntry? {
        reference(.movie, movie.id).flatMap { library.progress(for: $0) }
    }

    /// With "Hide watched movies" on, the movies of the active playlist the viewer has finished: left out where movies
    /// are browsed (Movies, Home, the pages opened from a movie), still found by search, My List and collections.
    func hiddenWatchedMovieIDs() -> Set<String> {
        guard settings.hideWatchedMovies, let playlist = activePlaylist else { return [] }
        return Set(library.history.lazy
            .filter { $0.reference.type == .movie && $0.reference.playlistID == playlist.id && $0.isFinished }
            .map(\.reference.itemID))
    }

    /// `movies` without the ones `hiddenWatchedMovieIDs` leaves out.
    func hidingWatched(_ movies: [Movie]) -> [Movie] {
        let hidden = hiddenWatchedMovieIDs()
        return hidden.isEmpty ? movies : movies.filter { !hidden.contains($0.id) }
    }

    func progress(episode: Episode, in series: Series) -> HistoryEntry? {
        guard let playlist = activePlaylist else { return nil }
        return library.progress(for: MediaReference(playlistID: playlist.id, type: .series, itemID: series.id, episodeID: episode.id))
    }

    func removeFromHistory(_ entry: HistoryEntry) {
        updateLibrary { $0.removeHistory(entry) }
    }

    func clearHistory() {
        updateLibrary { $0.clearHistory() }
    }

    func recordStart(_ item: PlaybackItem) {
        let container = item.streamURL.pathExtension.lowercased()
        updateLibrary {
            $0.recordPlayback(HistoryEntry(
                reference: item.reference,
                title: item.title,
                subtitle: item.subtitle,
                imageURL: item.artworkURL,
                position: item.isLive ? 0 : (item.startPosition ?? 0),
                streamExtension: item.reference.episodeID == nil || container.isEmpty ? nil : container
            ))
        }
    }

    func recordProgress(_ item: PlaybackItem, position: Double, duration: Double) {
        // Replays belong to the channel's history entry, not to a resumable position.
        guard !item.isCatchup else { return }
        updateLibrary { $0.updateProgress(item.reference, position: position, duration: duration) }
    }

    func resumePosition(for item: PlaybackItem) -> Double? {
        guard settings.resumePlayback, !item.isLive, let entry = library.progress(for: item.reference), entry.isResumable else { return nil }
        return entry.position
    }
}
