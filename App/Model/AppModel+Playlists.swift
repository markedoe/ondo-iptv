import Foundation
import IPTVCore

extension AppModel {
    /// Saves a playlist edited in its form. A new address, login or format is checked with the provider first (the
    /// playlist is downloaded once, as when adding one): one the provider refuses is not saved, and the error is thrown.
    func saveEditedPlaylist(_ playlist: Playlist) async throws {
        guard let previous = playlists.first(where: { $0.id == playlist.id }) else { return }
        guard Self.reachesProviderDifferently(previous, playlist) else {
            await updatePlaylist(playlist)
            return
        }
        let loader = self.loader
        let fresh = try await Task.detached { try await loader.load(playlist) }.value
        await updatePlaylist(playlist, checked: fresh)
    }

    /// Copies a user selected file into the container and returns its stored name.
    func importPlaylistFile(at url: URL) throws -> String {
        let accessing = url.startAccessingSecurityScopedResource()
        defer { if accessing { url.stopAccessingSecurityScopedResource() } }
        let name = UUID().uuidString + "-" + url.lastPathComponent
        let destination = AppPaths.importedPlaylist(name)
        try FileManager.default.copyItem(at: url, to: destination)
        return name
    }

    /// What the provider sees of a playlist: a change there needs the playlist downloaded again.
    static func reachesProviderDifferently(_ previous: Playlist, _ playlist: Playlist) -> Bool {
        previous.source != playlist.source || previous.headers != playlist.headers || previous.xtreamOutput != playlist.xtreamOutput
            || previous.xtreamForceM3U != playlist.xtreamForceM3U
    }
}
