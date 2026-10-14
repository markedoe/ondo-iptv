import Foundation

enum AppPaths {
    /// User data that must survive updates (playlists, favourites, history, settings).
    static let support: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let url = base.appendingPathComponent("Ondo", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }()

    /// Re-downloadable data (catalogues, guides).
    static let caches: URL = {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let url = base.appendingPathComponent("Ondo", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }()

    /// Playlist files imported by the user.
    static let importedPlaylists: URL = {
        let url = support.appendingPathComponent("ImportedPlaylists", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }()

    /// The copy of an imported playlist. Only the last component of the name counts: a name read from a
    /// backup file ("..", "../Ondo") never points outside the folder, so deleting the playlist cannot delete
    /// anything else.
    static func importedPlaylist(_ fileName: String) -> URL {
        importedPlaylists.appendingPathComponent(safeFileName(fileName), isDirectory: false)
    }

    static func safeFileName(_ name: String) -> String {
        let last = (name as NSString).lastPathComponent
        return ["", ".", "..", "/"].contains(last) ? "_" : last
    }

    /// Public demonstration channels curated by the iptv-org project, used by "Try the demo".
    static let demoPlaylistURL = URL(string: "https://iptv-org.github.io/iptv/countries/fr.m3u")!
}
