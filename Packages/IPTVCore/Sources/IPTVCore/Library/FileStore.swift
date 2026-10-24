import Foundation

/// Versioned JSON documents in a directory (Application Support on Apple platforms).
///
/// Every document is wrapped in `{ "version": n, "savedAt": …, "value": … }` and written atomically,
/// and the previous file is kept as `.bak` so an update can never silently wipe user data. A file that
/// cannot be read is moved aside as `name.corrupt-<time>.json` (deleted in a `disposable` store): left in place, the
/// next save would rotate it over the good `.bak`, and the one after would lose the data for good.
public final class FileStore: Sendable {
    public let directory: URL
    /// Documents that can be downloaded again (the caches): one that cannot be read is deleted instead of kept aside,
    /// where nothing would ever remove it.
    public let disposable: Bool
    private let lock = NSLock()

    public static let currentVersion = 1

    public init(directory: URL, disposable: Bool = false) {
        self.directory = directory
        self.disposable = disposable
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    private struct Envelope<T: Codable>: Codable {
        var version: Int
        var savedAt: Date
        var value: T
    }

    public func url(for name: String) -> URL {
        directory.appendingPathComponent(name).appendingPathExtension("json")
    }

    public func load<T: Codable>(_ type: T.Type, name: String, now: Date = Date()) -> T? {
        lock.lock()
        defer { lock.unlock() }
        let decoder = JSONDecoder()
        for candidate in [url(for: name), url(for: name).appendingPathExtension("bak")] {
            guard let data = try? Data(contentsOf: candidate) else { continue }
            if let envelope = try? decoder.decodeCheckingKeys(Envelope<T>.self, from: data) {
                return envelope.value
            }
            if disposable {
                try? FileManager.default.removeItem(at: candidate)
                continue
            }
            let aside = directory.appendingPathComponent("\(candidate.lastPathComponent).corrupt-\(Int(now.timeIntervalSince1970)).json")
            try? FileManager.default.moveItem(at: candidate, to: aside)
        }
        return nil
    }

    /// Saves [value] as the document [name]. `keepingPrevious: false` leaves nothing of the previous version behind,
    /// neither the `.bak` copy nor a damaged copy set aside: for a document something was deleted from (a playlist and
    /// its login).
    public func save<T: Codable>(_ value: T, name: String, keepingPrevious: Bool = true) throws {
        let encoder = JSONEncoder()
        let data = try encoder.encode(Envelope(version: Self.currentVersion, savedAt: Date(), value: value))
        lock.lock()
        defer { lock.unlock() }
        let target = url(for: name)
        let backup = target.appendingPathExtension("bak")
        let fileManager = FileManager.default
        if keepingPrevious, fileManager.fileExists(atPath: target.path) {
            try? fileManager.removeItem(at: backup)
            try? fileManager.copyItem(at: target, to: backup)
        }
        try data.write(to: target, options: .atomic)
        guard !keepingPrevious else { return }
        try? fileManager.removeItem(at: backup)
        let asides = [target.lastPathComponent + ".corrupt-", backup.lastPathComponent + ".corrupt-"]
        for file in (try? fileManager.contentsOfDirectory(atPath: directory.path)) ?? []
        where asides.contains(where: file.hasPrefix) {
            try? fileManager.removeItem(at: directory.appendingPathComponent(file))
        }
    }

    public func remove(name: String) {
        lock.lock()
        defer { lock.unlock() }
        try? FileManager.default.removeItem(at: url(for: name))
        try? FileManager.default.removeItem(at: url(for: name).appendingPathExtension("bak"))
    }

    public func modificationDate(name: String) -> Date? {
        (try? FileManager.default.attributesOfItem(atPath: url(for: name).path))?[.modificationDate] as? Date
    }

    /// Total size of the store in bytes.
    public func diskUsage() -> Int64 {
        guard let enumerator = FileManager.default.enumerator(at: directory, includingPropertiesForKeys: [.fileSizeKey]) else { return 0 }
        var total: Int64 = 0
        for case let file as URL in enumerator {
            total += Int64((try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
        return total
    }
}

/// Portable backup of the user's setup (playlists, their logins included, and the library), for export / import:
/// the app asks for the parental PIN to export it and tells the user to keep it private.
public struct BackupArchive: Codable, Sendable {
    public var version: Int
    public var exportedAt: Date
    public var playlists: [Playlist]
    public var library: UserLibrary

    public init(playlists: [Playlist], library: UserLibrary, exportedAt: Date = Date()) {
        self.version = FileStore.currentVersion
        self.exportedAt = exportedAt
        self.playlists = playlists
        self.library = library
    }

    public func encoded() throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try encoder.encode(self)
    }

    public static func decode(_ data: Data) throws -> BackupArchive {
        let decoder = JSONDecoder()
        return try decoder.decodeCheckingKeys(BackupArchive.self, from: data)
    }
}
