import Foundation
import IPTVCore

/// The caches: their size and Clear Cache (Settings), and the movie and series details kept between launches.
extension AppModel {
    // MARK: - Size and Clear Cache

    /// The playlist and guide caches and the artwork, as Settings shows them. Walks folders: off the main thread.
    func cacheSize() async -> Int64 {
        let store = cacheStore
        return await Task.detached(priority: .utility) { store.diskUsage() + Artwork.diskUsage() }.value
    }

    func clearCaches() async {
        await forgetStoredDetails()
        for playlist in playlists {
            cacheStore.remove(name: "epg-\(playlist.id.uuidString)")
            cacheStore.remove(name: DetailsArchive.documentName(for: playlist.id))
            if playlist.id != activePlaylist?.id { cacheStore.remove(name: "content-\(playlist.id.uuidString)") }
        }
        Artwork.clearCaches()
        loadEPG(force: true)
    }

    // MARK: - Details kept between launches

    // One document per playlist (`DetailsArchive`, with the caches): Home's backdrops, the pages and their episode
    // thumbnails show at once instead of waiting for the provider. A copy that is too old is fetched again, and
    // still shown when the provider cannot be reached.

    /// Reads the playlist's document in the background when it opens. `details(for:)` waits for it, so Home's
    /// prefetch finds what is on disk instead of asking the provider again.
    func loadStoredDetails(for playlist: Playlist) {
        storedDetailsLoad?.cancel()
        detailsPlaylistID = playlist.id
        let store = cacheStore
        let name = DetailsArchive.documentName(for: playlist.id)
        // After the last write: reopening the same playlist must read the details just saved.
        let write = storedDetailsWrite
        storedDetailsLoad = Task {
            await write?.value
            let archive = await Task.detached(priority: .userInitiated) { store.load(DetailsArchive.self, name: name) }.value
            guard let archive, !Task.isCancelled, detailsPlaylistID == playlist.id else { return }
            // A title fetched while the document was read has the newer details.
            for (id, entry) in archive.movies where movieDetailsCache[id] == nil {
                movieDetailsCache[id] = entry.value
                movieDetailsFetchedAt[id] = entry.fetchedAt
            }
            for (id, entry) in archive.series where seriesDetailsCache[id] == nil {
                seriesDetailsCache[id] = entry.value
                seriesDetailsFetchedAt[id] = entry.fetchedAt
            }
            detailsRevision += 1
        }
    }

    /// Waits until the open playlist's document has been read.
    func storedDetailsLoaded() async {
        await storedDetailsLoad?.value
    }

    /// Saves a moment after the last fetch: titles fetched together make one write.
    func scheduleStoredDetailsSave() {
        storedDetailsSave?.cancel()
        storedDetailsSave = Task {
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled else { return }
            saveStoredDetailsNow()
        }
    }

    /// Writes a pending save at once: the app leaves the screen, or another playlist opens.
    func saveStoredDetailsNow() {
        guard let pending = storedDetailsSave else { return }
        pending.cancel()
        storedDetailsSave = nil
        // The playlist was deleted meanwhile: its details go with it.
        guard let playlistID = detailsPlaylistID, playlists.contains(where: { $0.id == playlistID }) else { return }
        var archive = DetailsArchive()
        for (id, date) in movieDetailsFetchedAt {
            if let details = movieDetailsCache[id] { archive.movies[id] = .init(value: details, fetchedAt: date) }
        }
        for (id, date) in seriesDetailsFetchedAt {
            if let details = seriesDetailsCache[id] { archive.series[id] = .init(value: details, fetchedAt: date) }
        }
        let store = cacheStore
        let name = DetailsArchive.documentName(for: playlistID)
        // One write after the other: the last details are the ones on disk.
        let previous = storedDetailsWrite
        let document = archive
        storedDetailsWrite = Task.detached(priority: .utility) {
            await previous?.value
            try? store.save(document.trimmed(), name: name)
        }
    }

    /// Clear Cache removes the documents. The titles on screen keep their details; only those fetched again are saved.
    func forgetStoredDetails() async {
        storedDetailsSave?.cancel()
        storedDetailsSave = nil
        await storedDetailsWrite?.value
        movieDetailsFetchedAt = [:]
        seriesDetailsFetchedAt = [:]
    }
}
