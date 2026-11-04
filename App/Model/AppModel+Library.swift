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
        let time = syncStamp()
        updateLibrary { $0.toggleFavorite(ref, title: channel.name, imageURL: logoURL(for: channel), now: time) }
        syncSoon()
    }

    func toggleFavorite(_ movie: Movie) {
        guard let ref = reference(.movie, movie.id) else { return }
        let time = syncStamp()
        updateLibrary { $0.toggleFavorite(ref, title: movie.name, imageURL: movie.posterURL, now: time) }
        syncSoon()
    }

    func toggleFavorite(_ series: Series) {
        guard let ref = reference(.series, series.id) else { return }
        let time = syncStamp()
        updateLibrary { $0.toggleFavorite(ref, title: series.name, imageURL: series.coverURL, now: time) }
        syncSoon()
    }

    func favorites(_ type: ContentType) -> [FavoriteEntry] {
        guard let playlist = activePlaylist else { return [] }
        let excluded = exclusions
        return library.favorites(playlistID: playlist.id, type: type).filter { entry in
            switch type {
            case .live: return index.channelsByID[entry.reference.itemID].map { !excluded.contains($0) } ?? false
            case .movie: return index.moviesByID[entry.reference.itemID].map { !excluded.contains($0) } ?? false
            case .series: return index.seriesByID[entry.reference.itemID].map { !excluded.contains($0) } ?? false
            }
        }
    }

    // MARK: - Custom lists

    @discardableResult
    func createList(named name: String) -> UUID {
        var id = UUID()
        let time = syncStamp()
        updateLibrary { id = $0.createList(named: name, now: time) }
        syncSoon()
        return id
    }

    func renameList(_ id: UUID, to name: String) {
        let time = syncStamp()
        updateLibrary { $0.renameList(id, to: name, now: time) }
        syncSoon()
    }

    func deleteList(_ id: UUID) {
        updateLibrary { $0.deleteList(id) }
        noteListDeletedForSync(id)
    }

    func toggle(_ item: LibraryItem, inList id: UUID) {
        guard let ref = reference(item.type, item.id) else { return }
        let image = { () -> URL? in
            if case .channel(let channel) = item { return self.logoURL(for: channel) }
            return item.imageURL
        }()
        let time = syncStamp()
        updateLibrary { $0.toggle(ref, title: item.title, imageURL: image, inList: id, now: time) }
        syncSoon()
    }

    func isInList(_ item: LibraryItem, list: CustomList) -> Bool {
        guard let ref = reference(item.type, item.id) else { return false }
        return list.contains(ref)
    }

    /// Items of a list that belong to the active playlist and are not locked.
    func items(in list: CustomList) -> [FavoriteEntry] {
        guard let playlist = activePlaylist else { return [] }
        let excluded = exclusions
        return list.items.filter { entry in
            guard entry.reference.playlistID == playlist.id, let item = libraryItem(for: entry.reference) else { return false }
            switch item {
            case .channel(let channel): return !excluded.contains(channel)
            case .movie(let movie): return !excluded.contains(movie)
            case .series(let series): return !excluded.contains(series)
            }
        }
    }

    func moveListItems(_ id: UUID, fromOffsets source: IndexSet, toOffset destination: Int) {
        guard let list = library.customLists.first(where: { $0.id == id }) else { return }
        let move = Self.storedMove(visible: items(in: list), stored: list.items, fromOffsets: source, toOffset: destination)
        let time = syncStamp()
        updateLibrary { $0.moveItems(inList: id, fromOffsets: move.source, toOffset: move.destination, now: time) }
        syncSoon()
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
        let excluded = exclusions
        let hidden = hiddenChannelIDs
        return library.recentChannels(playlistID: playlist.id, limit: 20)
            .compactMap { index.channelsByID[$0.reference.itemID] }
            .filter { !excluded.contains($0) && !hidden.contains($0.id) }
    }

    /// Movies and episodes in progress; a series whose last episode watched was finished goes on with the next one
    /// (`nextEpisode(in:details:)`, from its seasons once known: an entry at its start, or the next one's own when it was
    /// started), and leaves after its last episode. Its seasons not known yet, the finished entry stands for the series:
    /// `resume(_:)` plays the next one.
    func continueWatching() -> [HistoryEntry] {
        guard let playlist = activePlaylist else { return [] }
        // Categories only: a title already started stays here though its own name carries a hidden language.
        let excluded = excludedCategoryIDs
        return library.continueWatching(playlistID: playlist.id).compactMap { entry -> HistoryEntry? in
            switch entry.reference.type {
            case .movie:
                return index.moviesByID[entry.reference.itemID].map { !excluded.contains($0.categoryID) } == true ? entry : nil
            case .series:
                guard let series = index.seriesByID[entry.reference.itemID], !excluded.contains(series.categoryID) else { return nil }
                guard entry.isFinished else { return entry }
                return removedElsewhere(since: entry) ? nil : goingOn(from: entry, in: series)
            case .live: return nil
            }
        }
    }

    /// Whether an episode of `entry`'s series was taken out of the history after it, on this device or another (the
    /// deletions sync kept for the open profile): its card does not come back as the next episode.
    private func removedElsewhere(since entry: HistoryEntry) -> Bool {
        let deleted = activeProfile.isMain ? syncState.deleted : syncState.profileStates[activeProfile.id.uuidString.lowercased()]?.deleted ?? [:]
        let prefix = "progress:" + SyncMapping.lowercasingPlaylistID(entry.reference.itemKey) + "|"
        let watched = entry.lastWatchedAt.syncMilliseconds
        return deleted.contains { $0.value >= watched && $0.key.hasPrefix(prefix) }
    }

    /// The card of `series` after its episode `finished`: the next episode, nil after the last one; `finished` itself
    /// while the seasons are not known.
    private func goingOn(from finished: HistoryEntry, in series: Series) -> HistoryEntry? {
        guard let details = storedDetails(for: series) else { return finished }
        guard let next = nextEpisode(in: series, details: details), next.episode.id != finished.reference.episodeID else { return nil }
        if let own = progress(episode: next.episode, in: series), own.isResumable { return own }
        return HistoryEntry(
            reference: MediaReference(playlistID: finished.reference.playlistID, type: .series, itemID: series.id, episodeID: next.episode.id),
            title: finished.title,
            subtitle: "\(next.episode.code) · \(next.episode.title)",
            imageURL: next.episode.posterURL ?? series.coverURL,
            lastWatchedAt: finished.lastWatchedAt
        )
    }

    /// A Continue Watching card of a series going on with its next episode (the one before was finished): played from
    /// its start, and not taken away (that would take the finished episode out of the history).
    func isUpNext(_ entry: HistoryEntry) -> Bool {
        entry.reference.type == .series && !entry.isResumable
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
        // A film is watched once one of its versions is: every version is left out.
        return films.expand(Set(library.history.lazy
            .filter { $0.reference.type == .movie && $0.reference.playlistID == playlist.id && $0.isFinished }
            .map(\.reference.itemID)))
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
        let time = syncStamp()
        updateLibrary { $0.removeHistory(entry, now: time) }
        syncSoon()
    }

    func clearHistory() {
        let cleared = library.history
        let time = syncStamp()
        updateLibrary { $0.clearHistory(now: time) }
        noteHistoryClearedForSync(cleared)
    }

    func recordStart(_ item: PlaybackItem) {
        let container = item.streamURL.pathExtension.lowercased()
        let time = syncStamp()
        // The item belongs to the profile open as it starts (review S5).
        playbackProfiles[item.reference.playbackKey] = activeProfile.id
        updateLibrary {
            $0.recordPlayback(HistoryEntry(
                reference: item.reference,
                title: item.title,
                subtitle: item.subtitle,
                imageURL: item.artworkURL,
                lastWatchedAt: time,
                position: item.isLive ? 0 : (item.startPosition ?? 0),
                streamExtension: item.reference.episodeID == nil || container.isEmpty ? nil : container
            ))
        }
    }

    func recordProgress(_ item: PlaybackItem, position: Double, duration: Double, creditsStart: Double? = nil) {
        // Replays belong to the channel's history entry, not to a resumable position.
        guard !item.isCatchup else { return }
        let time = syncStamp()
        updatePlaybackLibrary(of: item.reference.playbackKey) {
            $0.updateProgress(item.reference, position: position, duration: duration, creditsStart: creditsStart, now: time)
        }
    }

    /// Changes the library of the profile `playbackKey` was started in (`playbackProfiles`): the open one's, another's kept
    /// at hand (written at once), or nothing when that profile was deleted meanwhile, never the main one's in its place.
    func updatePlaybackLibrary(of playbackKey: String, _ change: (inout UserLibrary) -> Void) {
        let owner = playbackProfiles[playbackKey] ?? activeProfile.id
        if owner == activeProfile.id {
            updateLibrary(change)
            return
        }
        guard let profile = profiles.first(where: { $0.id == owner }) else { return }
        var other = otherLibraries[owner] ?? (userStore.load(UserLibrary.self, name: profile.libraryDocumentName) ?? UserLibrary())
        change(&other)
        otherLibraries[owner] = other
        try? userStore.save(other, name: profile.libraryDocumentName)
    }

    func resumePosition(for item: PlaybackItem) -> Double? {
        guard settings.resumePlayback, !item.isLive, let entry = library.progress(for: item.reference), entry.isResumable else { return nil }
        return entry.position
    }
}
