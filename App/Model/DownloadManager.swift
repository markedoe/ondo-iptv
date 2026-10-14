import Foundation
import IPTVCore
import Observation

/// A movie or episode saved for offline viewing.
struct DownloadRecord: Identifiable, Codable, Hashable {
    enum Status: Codable, Hashable {
        case queued
        case downloading
        case completed
        case failed(String)
        case paused
    }

    /// `PlaybackItem.id` of the downloaded item.
    var id: String
    var item: PlaybackItem
    var remoteURL: URL
    var fileName: String
    var status: Status
    var progress: Double
    var totalBytes: Int64
    var createdAt: Date

    var isCompleted: Bool { status == .completed }
    var isActive: Bool { status == .queued || status == .downloading }
}

/// Downloads direct-file VOD (MP4, MKV…) in a background session so they continue when the app is suspended.
@MainActor
@Observable
final class DownloadManager: NSObject {
    static let shared = DownloadManager()
    static let sessionIdentifier = "com.markedoe.ondo.downloads"

    private(set) var records: [DownloadRecord] = []

    @ObservationIgnored private let store = FileStore(directory: AppPaths.support)
    @ObservationIgnored private var session: URLSession!
    @ObservationIgnored private var tasks: [String: URLSessionDownloadTask] = [:]
    @ObservationIgnored private var resumeData: [String: Data] = [:]
    @ObservationIgnored private var lastSave = Date.distantPast
    /// Set by the iOS app delegate when the system relaunches the app for finished downloads.
    @ObservationIgnored var backgroundCompletionHandler: (() -> Void)?

    /// Downloaded films can weigh gigabytes and can be downloaded again: they stay out of iCloud and computer
    /// backups (Apple's storage guidelines).
    nonisolated static let directory: URL = {
        var url = AppPaths.support.appendingPathComponent("Downloads", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        return url
    }()

    override init() {
        super.init()
        records = store.load([DownloadRecord].self, name: "downloads") ?? []
        let configuration = URLSessionConfiguration.background(withIdentifier: Self.sessionIdentifier)
        configuration.isDiscretionary = false
        configuration.sessionSendsLaunchEvents = true
        configuration.allowsCellularAccess = true
        session = URLSession(configuration: configuration, delegate: DownloadDelegate(manager: self), delegateQueue: nil)
        reattachTasks()
    }

    // MARK: Queries

    static func canDownload(_ item: PlaybackItem) -> Bool {
        guard !item.isLive, !item.isCatchup else { return false }
        let ext = item.streamURL.pathExtension.lowercased()
        return !["m3u8", "m3u", "mpd", ""].contains(ext)
    }

    func record(for item: PlaybackItem) -> DownloadRecord? {
        records.first { $0.id == item.id }
    }

    func localURL(for record: DownloadRecord) -> URL {
        Self.directory.appendingPathComponent(record.fileName)
    }

    /// Local file to play instead of streaming, when the item was downloaded.
    func localFile(for item: PlaybackItem) -> URL? {
        guard let record = record(for: item), record.isCompleted else { return nil }
        let url = localURL(for: record)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    var usedBytes: Int64 {
        records.filter(\.isCompleted).reduce(0) { total, record in
            let attributes = try? FileManager.default.attributesOfItem(atPath: localURL(for: record).path)
            return total + ((attributes?[.size] as? NSNumber)?.int64Value ?? 0)
        }
    }

    // MARK: Actions

    func download(_ item: PlaybackItem) {
        guard Self.canDownload(item) else { return }
        if let existing = record(for: item), existing.isActive || existing.isCompleted { return }
        let ext = item.streamURL.pathExtension.isEmpty ? "mp4" : item.streamURL.pathExtension
        var stored = item
        stored.startPosition = nil
        let record = DownloadRecord(
            id: item.id,
            item: stored,
            remoteURL: item.streamURL,
            fileName: StableHash.hex(item.id) + "." + ext,
            status: .queued,
            progress: 0,
            totalBytes: 0,
            createdAt: Date()
        )
        records.removeAll { $0.id == record.id }
        records.insert(record, at: 0)
        start(record)
        save(force: true)
    }

    private func start(_ record: DownloadRecord) {
        let task: URLSessionDownloadTask
        if let data = resumeData.removeValue(forKey: record.id) {
            task = session.downloadTask(withResumeData: data)
        } else {
            var request = URLRequest(url: record.remoteURL)
            for (key, value) in record.item.headers.dictionary { request.setValue(value, forHTTPHeaderField: key) }
            if request.value(forHTTPHeaderField: "User-Agent") == nil {
                request.setValue(defaultIPTVUserAgent, forHTTPHeaderField: "User-Agent")
            }
            task = session.downloadTask(with: request)
        }
        task.taskDescription = record.id
        tasks[record.id] = task
        update(record.id) { $0.status = .downloading }
        task.resume()
    }

    func pause(_ record: DownloadRecord) {
        guard let task = tasks[record.id] else { return }
        task.cancel(byProducingResumeData: { [weak self] data in
            Task { @MainActor in
                guard let self else { return }
                if let data { self.resumeData[record.id] = data }
                self.tasks[record.id] = nil
                self.update(record.id) { $0.status = .paused }
                self.save(force: true)
            }
        })
    }

    func resume(_ record: DownloadRecord) {
        guard !record.isCompleted else { return }
        start(record)
    }

    func delete(_ record: DownloadRecord) {
        tasks[record.id]?.cancel()
        tasks[record.id] = nil
        resumeData[record.id] = nil
        try? FileManager.default.removeItem(at: localURL(for: record))
        records.removeAll { $0.id == record.id }
        save(force: true)
    }

    // MARK: Delegate callbacks (main actor)

    fileprivate func progressChanged(id: String, written: Int64, expected: Int64) {
        update(id) { record in
            record.status = .downloading
            record.totalBytes = max(expected, 0)
            record.progress = expected > 0 ? Double(written) / Double(expected) : 0
        }
        save(force: false)
    }

    fileprivate func finished(id: String, movedTo url: URL?, error: String?) {
        tasks[id] = nil
        update(id) { record in
            if let error {
                record.status = .failed(error)
            } else if url != nil {
                record.status = .completed
                record.progress = 1
            }
        }
        save(force: true)
    }

    fileprivate func destination(for id: String) -> URL? {
        records.first { $0.id == id }.map { localURL(for: $0) }
    }

    private func update(_ id: String, _ change: (inout DownloadRecord) -> Void) {
        guard let index = records.firstIndex(where: { $0.id == id }) else { return }
        change(&records[index])
    }

    private func save(force: Bool) {
        guard force || Date().timeIntervalSince(lastSave) > 3 else { return }
        lastSave = Date()
        try? store.save(records, name: "downloads")
    }

    private func reattachTasks() {
        session.getAllTasks { [weak self] tasks in
            let pairs = tasks.compactMap { task -> (String, URLSessionDownloadTask)? in
                guard let download = task as? URLSessionDownloadTask, let id = task.taskDescription else { return nil }
                return (id, download)
            }
            Task { @MainActor in
                guard let self else { return }
                for (id, task) in pairs { self.tasks[id] = task }
                // Downloads interrupted by the app being killed can be restarted by the user.
                for record in self.records where record.isActive && self.tasks[record.id] == nil {
                    self.update(record.id) { $0.status = .paused }
                }
            }
        }
    }

    fileprivate func backgroundEventsFinished() {
        backgroundCompletionHandler?()
        backgroundCompletionHandler = nil
    }
}

/// URLSession delegate living off the main actor; files must be moved before the callback returns.
private final class DownloadDelegate: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    weak var manager: DownloadManager?
    private let lock = NSLock()
    private var destinations: [String: URL] = [:]

    init(manager: DownloadManager) {
        self.manager = manager
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let id = downloadTask.taskDescription else { return }
        Task { @MainActor [weak manager] in
            manager?.progressChanged(id: id, written: totalBytesWritten, expected: totalBytesExpectedToWrite)
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let id = downloadTask.taskDescription else { return }
        if let http = downloadTask.response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            Task { @MainActor [weak manager] in
                manager?.finished(id: id, movedTo: nil, error: "HTTP \(http.statusCode)")
            }
            return
        }
        // The destination is derived from the id, without touching the main actor.
        let fileName = StableHash.hex(id) + "." + (downloadTask.originalRequest?.url?.pathExtension.nilIfEmpty ?? "mp4")
        let destination = DownloadManager.directory.appendingPathComponent(fileName)
        try? FileManager.default.removeItem(at: destination)
        do {
            try FileManager.default.moveItem(at: location, to: destination)
            Task { @MainActor [weak manager] in manager?.finished(id: id, movedTo: destination, error: nil) }
        } catch {
            let message = error.localizedDescription
            Task { @MainActor [weak manager] in manager?.finished(id: id, movedTo: nil, error: message) }
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error, let id = task.taskDescription else { return }
        let nsError = error as NSError
        if nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorCancelled { return }
        let message = error.localizedDescription
        Task { @MainActor [weak manager] in manager?.finished(id: id, movedTo: nil, error: message) }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        Task { @MainActor [weak manager] in manager?.backgroundEventsFinished() }
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
