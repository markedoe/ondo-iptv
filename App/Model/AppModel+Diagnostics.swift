import Foundation
import IPTVCore

/// What `bootstrap()` read from disk, before sync or the viewer changed anything.
struct LaunchCounts: Equatable, Sendable {
    var hiddenCategoryTags: Int
    var dismissedTagSuggestions: Int
    var playlists: Int
}

/// The app's own data in a problem report (More, Report a Problem): the build, the files of the user data folder (names,
/// sizes and dates only, never their content or where the folder is), sync, and what was read at launch. Settings or a
/// library that come back as they were before cannot be found from the playback log alone.
extension AppModel {
    static var edition: String {
        #if DEBUG
        return "Debug"
        #elseif TESTFLIGHT_DOWNLOADS
        return "TestFlight"
        #else
        return "App Store"
        #endif
    }

    func dataReport(now: Date = Date()) -> String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        var lines = ["App data", "Build: \(version) (\(build)), \(Self.edition) edition"]

        let lastSync = syncState.lastSyncedAt.map { DataReport.time(Date(syncMilliseconds: $0)) } ?? "never"
        let sessionSync = localSync.devices.compactMap(\.syncedAt).max().map(DataReport.time) ?? "not this session"
        lines.append("Sync on the local network: \(settings.syncOnLocalNetwork ? "on" : "off"); last applied \(lastSync); "
                     + "devices found \(localSync.devices.count), last session \(sessionSync)")
        if let loaded = loadedAtLaunch {
            lines.append("Read at launch: \(loaded.playlists) playlists, \(loaded.hiddenCategoryTags) hidden tags, "
                         + "\(loaded.dismissedTagSuggestions) answered tag suggestions")
        }
        lines.append("Now: \(playlists.count) playlists, \(library.hiddenCategoryTags.count) hidden tags, "
                     + "\(settings.dismissedTagSuggestions.count) answered tag suggestions, profile \(syncsThisProfile ? "main" : "other")")

        let files = DataReport.listing(of: userStore.directory)
        let setAside = files.filter { $0.name.contains(".corrupt-") }.count
        lines.append("User data folder: \(files.count) entries, \(setAside) set aside as unreadable")
        for file in files {
            lines.append("  " + file.description)
        }
        lines.append("Generated: \(DataReport.time(now))")
        return lines.joined(separator: "\n")
    }
}

/// The pieces of the app data report, outside the model.
enum DataReport {
    struct FileEntry: Equatable {
        var name: String
        var size: Int64?
        var modified: Date?
        /// For a folder: how many entries it holds (their names are not listed: imported playlists keep the user's).
        var entries: Int?

        var description: String {
            let date = modified.map(DataReport.time) ?? "?"
            if let entries { return "\(name)/  \(entries) entries  \(date)" }
            return "\(name)  \(size.map { String($0) } ?? "?") B  \(date)"
        }
    }

    /// The entries directly in `directory`, by name: never a path, and a folder's own entries are only counted.
    static func listing(of directory: URL) -> [FileEntry] {
        let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey, .isDirectoryKey]
        let urls = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: keys)) ?? []
        return urls.map { url -> FileEntry in
            let values = try? url.resourceValues(forKeys: Set(keys))
            if values?.isDirectory == true {
                let count = (try? FileManager.default.contentsOfDirectory(atPath: url.path))?.count ?? 0
                return FileEntry(name: url.lastPathComponent, modified: values?.contentModificationDate, entries: count)
            }
            return FileEntry(name: url.lastPathComponent, size: values?.fileSize.map { Int64($0) }, modified: values?.contentModificationDate)
        }
        .sorted { $0.name < $1.name }
    }

    static func time(_ date: Date) -> String {
        date.formatted(.iso8601)
    }
}
