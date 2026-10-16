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
    /// What arrived so far: shown when the provider does not give the size (the percentage stays at 0).
    var receivedBytes: Int64?

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
    /// Why a running download has received nothing for a while (what it waits for).
    private(set) var stalls: [String: DownloadProblem] = [:]
    /// Time without data after which a download says what it waits for.
    static let stallDelay: TimeInterval = 30
    /// Speed and time left of the downloads that ran in this session.
    private(set) var meters: [String: TransferMeter] = [:]

    @ObservationIgnored private let store = FileStore(directory: AppPaths.support)
    @ObservationIgnored private var session: URLSession!
    @ObservationIgnored private var tasks: [String: URLSessionDownloadTask] = [:]
    @ObservationIgnored private var resumeData: [String: Data] = [:]
    @ObservationIgnored private var lastSave = Date.distantPast
    @ObservationIgnored private var lastProgress: [String: Date] = [:]
    @ObservationIgnored private var probed: Set<String> = []
    @ObservationIgnored private var watchdog: Task<Void, Never>?
    /// What happened to each download in this session, for its details (Downloads, Details), and when it started.
    @ObservationIgnored private var logs: [String: PlaybackLog] = [:]
    @ObservationIgnored private var startedAt: [String: Date] = [:]
    /// Downloads whose answer from the provider is in their log.
    @ObservationIgnored private var answered: Set<String> = []
    /// Downloads the viewer paused: a cancellation of theirs is not an interruption to recover from.
    @ObservationIgnored private var pausedByViewer: Set<String> = []
    /// Automatic retries after a network failure, and the one waiting.
    @ObservationIgnored private var retries: [String: Int] = [:]
    @ObservationIgnored private var retryTasks: [String: Task<Void, Never>] = [:]
    /// Waits before each automatic retry, in seconds: a lost connection or a Mac that slept.
    static let retryDelays: [Double] = [5, 15, 30, 60, 120, 300]
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
        guard FeatureFlags.downloads, !item.isLive, !item.isCatchup else { return false }
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
        // Already running (resumed by two paths at once, at launch).
        guard tasks[record.id] == nil else { return }
        let task: URLSessionDownloadTask
        meters[record.id] = TransferMeter()
        answered.remove(record.id)
        pausedByViewer.remove(record.id)
        retryTasks.removeValue(forKey: record.id)?.cancel()
        if let data = takeResumeData(for: record.id) {
            task = session.downloadTask(withResumeData: data)
            note(record.id, "resume from \(DownloadReport.size(record.receivedBytes ?? 0))")
        } else {
            task = session.downloadTask(with: Self.request(for: record))
            let received = record.receivedBytes ?? 0
            note(record.id, received > 0 ? "start again from the beginning (\(DownloadReport.size(received)) lost: nothing to resume from)" : "start")
        }
        task.taskDescription = record.id
        tasks[record.id] = task
        update(record.id) { $0.status = .downloading }
        lastProgress[record.id] = Date()
        stalls[record.id] = nil
        probed.remove(record.id)
        task.resume()
        startWatchdog()
    }

    private static func request(for record: DownloadRecord) -> URLRequest {
        var request = URLRequest(url: record.remoteURL)
        for (key, value) in record.item.headers.dictionary { request.setValue(value, forHTTPHeaderField: key) }
        if request.value(forHTTPHeaderField: "User-Agent") == nil {
            request.setValue(defaultIPTVUserAgent, forHTTPHeaderField: "User-Agent")
        }
        return request
    }

    func pause(_ record: DownloadRecord) {
        retryTasks.removeValue(forKey: record.id)?.cancel()
        retries[record.id] = nil
        guard let task = tasks[record.id] else {
            // Waiting for an automatic retry: it stays paused.
            if case .failed = record.status { update(record.id) { $0.status = .paused } }
            return
        }
        pausedByViewer.insert(record.id)
        task.cancel(byProducingResumeData: { [weak self] data in
            Task { @MainActor in
                guard let self else { return }
                if let data { self.keepResumeData(data, for: record.id) }
                self.note(record.id, "paused at \(DownloadReport.size(self.record(for: record.item)?.receivedBytes ?? 0))\(data == nil ? ", nothing to resume from" : "")")
                self.tasks[record.id] = nil
                self.stalls[record.id] = nil
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
        keepResumeData(nil, for: record.id)
        retryTasks.removeValue(forKey: record.id)?.cancel()
        retries[record.id] = nil
        stalls[record.id] = nil
        meters[record.id] = nil
        logs[record.id] = nil
        startedAt[record.id] = nil
        try? FileManager.default.removeItem(at: localURL(for: record))
        records.removeAll { $0.id == record.id }
        save(force: true)
    }

    // MARK: Delegate callbacks (main actor)

    fileprivate func progressChanged(id: String, written: Int64, expected: Int64) {
        // A late callback of a task paused or deleted meanwhile must not mark it as downloading again.
        guard tasks[id] != nil else { return }
        update(id) { record in
            record.status = .downloading
            record.totalBytes = max(expected, 0)
            record.progress = expected > 0 ? Double(written) / Double(expected) : 0
            record.receivedBytes = written
        }
        lastProgress[id] = Date()
        retries[id] = nil
        if stalls[id] != nil {
            stalls[id] = nil
            note(id, "data again")
        }
        noteAnswer(id)
        measure(id, bytes: written)
        save(force: false)
    }

    fileprivate func finished(id: String, movedTo url: URL?, error: String?, resumeData: Data? = nil, transient: Bool = false) {
        noteAnswer(id)
        if let resumeData { keepResumeData(resumeData, for: id) }
        if let error {
            note(id, "failed: \(error)\(resumeData == nil ? "" : " (can resume)")")
        } else if url != nil {
            keepResumeData(nil, for: id)
            let received = records.first { $0.id == id }?.receivedBytes ?? 0
            note(id, "finished: \(DownloadReport.size(received)) in \(DownloadReport.duration(clock(for: id)))")
        }
        tasks[id] = nil
        stalls[id] = nil
        lastProgress[id] = nil
        update(id) { record in
            if let error {
                record.status = .failed(error)
            } else if url != nil {
                record.status = .completed
                record.progress = 1
            }
        }
        if error != nil, transient { scheduleRetry(id) }
        save(force: true)
    }

    /// A cancellation the viewer did not ask for: the system stopped the download (the app was closed, the system
    /// needed the resources). It goes on from where it was.
    fileprivate func cancelled(id: String, resumeData: Data?) {
        guard let record = records.first(where: { $0.id == id }), !record.isCompleted else {
            keepResumeData(nil, for: id)
            return
        }
        if let resumeData { keepResumeData(resumeData, for: id) }
        guard !pausedByViewer.contains(id), tasks[id] == nil || tasks[id]?.state != .running else { return }
        tasks[id] = nil
        note(id, "interrupted by the system\(resumeData == nil ? ", nothing to resume from" : "")")
        if resumeData != nil {
            start(record)
        } else {
            update(id) { $0.status = .paused }
            save(force: true)
        }
    }

    /// After a network failure: the download tries again by itself, from where it stopped, a few times and further
    /// apart each time.
    private func scheduleRetry(_ id: String) {
        let attempt = retries[id, default: 0]
        guard attempt < Self.retryDelays.count else { return }
        retries[id] = attempt + 1
        let delay = Self.retryDelays[attempt]
        note(id, "tries again in \(Int(delay)) s (\(attempt + 1) of \(Self.retryDelays.count))")
        retryTasks[id] = Task { [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard let self, !Task.isCancelled, let record = self.records.first(where: { $0.id == id }), case .failed = record.status else { return }
            self.retryTasks[id] = nil
            self.start(record)
        }
    }

    // MARK: Resume data

    /// Where a download's resume data is kept, so that it goes on from where it stopped even after the app closed.
    nonisolated static func resumeFile(for id: String) -> URL {
        directory.appendingPathComponent(StableHash.hex(id) + ".resume")
    }

    private func keepResumeData(_ data: Data?, for id: String) {
        resumeData[id] = data
        if let data {
            try? data.write(to: Self.resumeFile(for: id), options: .atomic)
        } else {
            try? FileManager.default.removeItem(at: Self.resumeFile(for: id))
        }
    }

    private func takeResumeData(for id: String) -> Data? {
        let data = resumeData.removeValue(forKey: id) ?? (try? Data(contentsOf: Self.resumeFile(for: id)))
        try? FileManager.default.removeItem(at: Self.resumeFile(for: id))
        return data
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
                for (id, task) in pairs {
                    self.tasks[id] = task
                    self.lastProgress[id] = Date()
                    self.note(id, "found running in the system when the app opened")
                }
                // Downloads interrupted while the app was closed go on from where they stopped when they can; the others
                // wait for the viewer.
                for record in self.records where record.isActive && self.tasks[record.id] == nil {
                    if FileManager.default.fileExists(atPath: Self.resumeFile(for: record.id).path) {
                        self.note(record.id, "interrupted while the app was closed")
                        self.start(record)
                    } else {
                        self.update(record.id) { $0.status = .paused }
                    }
                }
                // Downloads that went on while the app was closed are diagnosed like new ones.
                if !pairs.isEmpty { self.startWatchdog() }
            }
        }
    }

    // MARK: Details

    /// Seconds since `id` started in this session: the clock of its log and its meter.
    private func clock(for id: String) -> Double {
        if let start = startedAt[id] { return Date().timeIntervalSince(start) }
        startedAt[id] = Date()
        return 0
    }

    private func note(_ id: String, _ event: String) {
        let time = clock(for: id)
        logs[id, default: PlaybackLog()].add(event, at: time)
    }

    private func measure(_ id: String, bytes: Int64) {
        let time = clock(for: id)
        meters[id, default: TransferMeter()].note(bytes: bytes, at: time)
    }

    /// The provider's first answer, once: its status, the size, the type and whether the download can resume.
    private func noteAnswer(_ id: String) {
        guard !answered.contains(id), let http = tasks[id]?.response as? HTTPURLResponse else { return }
        answered.insert(id)
        let size = http.expectedContentLength > 0 ? DownloadReport.size(http.expectedContentLength) : "size not given"
        let type = http.value(forHTTPHeaderField: "Content-Type") ?? "no type"
        let ranges = http.value(forHTTPHeaderField: "Accept-Ranges")?.lowercased() == "bytes" ? "can resume" : "may not resume"
        note(id, "answer: HTTP \(http.statusCode), \(size), \(type), \(ranges)")
    }

    /// The details of a download, to show and share.
    func report(for record: DownloadRecord) -> DownloadReport {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        let task = tasks[record.id]
        let http = task?.response as? HTTPURLResponse
        var headers: [String: String] = [:]
        for name in DownloadReport.headerNames {
            if let value = http?.value(forHTTPHeaderField: name) { headers[name] = value }
        }
        let status: String
        switch record.status {
        case .queued: status = "queued"
        case .downloading: status = "downloading"
        case .completed: status = "completed"
        case .paused:
            let resumable = resumeData[record.id] != nil || FileManager.default.fileExists(atPath: Self.resumeFile(for: record.id).path)
            status = "paused\(resumable ? ", can resume" : ", nothing to resume from")"
        case .failed(let message): status = "failed: \(message)"
        }
        let context = DownloadReport.Context(
            app: "Ondo \(version) (\(build))",
            system: PlayerController.system,
            kind: record.item.reference.episodeID != nil ? "episode" : "film",
            container: record.remoteURL.pathExtension.isEmpty ? nil : record.remoteURL.pathExtension.lowercased(),
            status: status,
            task: task.map { Self.describe($0.state) },
            received: record.receivedBytes ?? 0,
            total: record.totalBytes,
            httpStatus: http?.statusCode,
            headers: headers,
            problem: stalls[record.id],
            now: startedAt[record.id].map { Date().timeIntervalSince($0) } ?? 0
        )
        return DownloadReport(context: context, meter: meters[record.id] ?? TransferMeter(), log: logs[record.id] ?? PlaybackLog())
    }

    private static func describe(_ state: URLSessionTask.State) -> String {
        switch state {
        case .running: return "running"
        case .suspended: return "suspended"
        case .canceling: return "canceling"
        case .completed: return "completed"
        @unknown default: return "unknown"
        }
    }

    // MARK: Diagnosis

    /// Every few seconds while downloads run: counts what arrived and, after `stallDelay` without data,
    /// says what the download waits for. A system download that the provider refuses or never answers
    /// does not fail: it waits, and without this would show "Starting…" forever.
    private func startWatchdog() {
        guard watchdog == nil else { return }
        watchdog = Task { [weak self] in
            while true {
                try? await Task.sleep(for: .seconds(5))
                guard let manager = self, !Task.isCancelled else { return }
                if !manager.checkRunningDownloads() {
                    manager.watchdog = nil
                    return
                }
            }
        }
    }

    /// Returns whether downloads are still running.
    private func checkRunningDownloads() -> Bool {
        let now = Date()
        var running = false
        for record in records where record.isActive {
            guard let task = tasks[record.id] else { continue }
            running = true
            let received = task.countOfBytesReceived
            noteAnswer(record.id)
            if received > (record.receivedBytes ?? 0) {
                update(record.id) { $0.receivedBytes = received }
                lastProgress[record.id] = now
                stalls[record.id] = nil
                measure(record.id, bytes: received)
                continue
            }
            // Nothing new: the speed falls.
            measure(record.id, bytes: record.receivedBytes ?? 0)
            let since = lastProgress[record.id] ?? now
            guard now.timeIntervalSince(since) >= Self.stallDelay, stalls[record.id] == nil else { continue }
            let stall = DownloadDiagnosis.stall(status: (task.response as? HTTPURLResponse)?.statusCode)
            stalls[record.id] = stall
            note(record.id, "nothing received for \(Int(now.timeIntervalSince(since))) s: \(DownloadReport.describe(stall))")
            if !probed.contains(record.id) {
                probed.insert(record.id)
                probe(record)
            }
        }
        return running
    }

    /// Asks the provider for the start of the file with an ordinary request, once per stalled download: tells
    /// a refusal (connection limit, downloads not allowed) from a download the system has not started.
    private func probe(_ record: DownloadRecord) {
        let request = Self.request(for: record)
        Task { [weak self] in
            let problem = await Self.probe(request)
            self?.note(record.id, "checked with an ordinary request: \(DownloadReport.describe(problem))")
            // Data arrived meanwhile: nothing to explain any more.
            guard let self, self.stalls[record.id] != nil else { return }
            self.stalls[record.id] = problem
        }
    }

    nonisolated static func probe(_ request: URLRequest) async -> DownloadProblem {
        var request = request
        request.setValue("bytes=0-511", forHTTPHeaderField: "Range")
        request.timeoutInterval = 20
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        do {
            let (bytes, response) = try await session.bytes(for: request)
            defer { bytes.task.cancel() }
            guard let http = response as? HTTPURLResponse else { return .noData }
            if let problem = DownloadDiagnosis.problem(status: http.statusCode) { return problem }
            var prefix = Data()
            for try await byte in bytes {
                prefix.append(byte)
                if prefix.count >= 512 { break }
            }
            let size = DownloadDiagnosis.totalSize(
                contentRange: http.value(forHTTPHeaderField: "Content-Range"),
                contentLength: http.expectedContentLength
            ) ?? Int64(prefix.count)
            if DownloadDiagnosis.isErrorPage(contentType: http.value(forHTTPHeaderField: "Content-Type"), prefix: prefix, size: size) {
                return .errorPage
            }
            return .downloadStalled
        } catch {
            return (error as? URLError).flatMap { DownloadDiagnosis.problem(urlErrorCode: $0.code.rawValue) } ?? .noResponse
        }
    }

    /// What the viewer reads about a download problem.
    nonisolated static func message(for problem: DownloadProblem) -> String {
        switch problem {
        case .refused(let status):
            return String(localized: "The provider refused the download (HTTP \(status)): expired subscription, connection limit reached (stop other playback) or downloads not allowed.")
        case .notFound(let status):
            return String(localized: "This file is no longer on the provider's server (HTTP \(status)).")
        case .tooManyConnections(let status):
            return String(localized: "Too many connections for this subscription (HTTP \(status)). Stop playback on this and your other devices, then resume the download.")
        case .httpError(let status):
            return String(localized: "The provider answered with an error (HTTP \(status)).")
        case .errorPage:
            return String(localized: "The provider sent a message instead of the video: its connection limit may be reached, or it does not allow downloads.")
        case .noResponse:
            return String(localized: "Nothing received yet: the provider has not answered. Its connection limit may be reached (stop playback on this and your other devices).")
        case .noData:
            return String(localized: "The provider accepted the download but sends nothing yet. It may limit connections or downloads.")
        case .downloadStalled:
            return String(localized: "The provider answers normally, but this download receives nothing. Pause it, then resume it.")
        case .offline:
            return String(localized: "No internet connection.")
        case .unreachable:
            return String(localized: "The provider's server cannot be reached.")
        case .timedOut:
            return String(localized: "The provider takes too long to answer.")
        case .connectionLost:
            return String(localized: "The connection to the provider was lost. Resume the download to continue.")
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
    /// When progress was last passed on, per download: the session reports every few kilobytes, the screen needs a
    /// few updates a second. Only touched on the session's delegate queue, which is serial.
    private var lastSent: [String: Date] = [:]

    init(manager: DownloadManager) {
        self.manager = manager
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let id = downloadTask.taskDescription else { return }
        let now = Date()
        if let last = lastSent[id], now.timeIntervalSince(last) < 0.25, totalBytesWritten < totalBytesExpectedToWrite { return }
        lastSent[id] = now
        Task { @MainActor [weak manager] in
            manager?.progressChanged(id: id, written: totalBytesWritten, expected: totalBytesExpectedToWrite)
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let id = downloadTask.taskDescription else { return }
        let http = downloadTask.response as? HTTPURLResponse
        if let http, let problem = DownloadDiagnosis.problem(status: http.statusCode) {
            let message = DownloadManager.message(for: problem)
            Task { @MainActor [weak manager] in manager?.finished(id: id, movedTo: nil, error: message) }
            return
        }
        if Self.holdsAMessage(location, contentType: http?.value(forHTTPHeaderField: "Content-Type")) {
            let message = DownloadManager.message(for: .errorPage)
            Task { @MainActor [weak manager] in manager?.finished(id: id, movedTo: nil, error: message) }
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
        // What the system kept to go on from where the transfer stopped (a server that allows it).
        let resumeData = nsError.userInfo[NSURLSessionDownloadTaskResumeData] as? Data
        if nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorCancelled {
            Task { @MainActor [weak manager] in manager?.cancelled(id: id, resumeData: resumeData) }
            return
        }
        let problem = nsError.domain == NSURLErrorDomain ? DownloadDiagnosis.problem(urlErrorCode: nsError.code) : nil
        let message = problem.map { DownloadManager.message(for: $0) } ?? error.localizedDescription
        // Network failures are tried again; the provider's refusals are not.
        let transient = nsError.domain == NSURLErrorDomain
        Task { @MainActor [weak manager] in
            manager?.finished(id: id, movedTo: nil, error: message, resumeData: resumeData, transient: transient)
        }
    }

    /// A short text received instead of the film ("Max connections reached", a web page) is not kept.
    private static func holdsAMessage(_ file: URL, contentType: String?) -> Bool {
        guard let handle = try? FileHandle(forReadingFrom: file) else { return false }
        defer { try? handle.close() }
        let prefix = (try? handle.read(upToCount: 512)) ?? Data()
        let size = ((try? FileManager.default.attributesOfItem(atPath: file.path))?[.size] as? NSNumber)?.int64Value ?? 0
        return DownloadDiagnosis.isErrorPage(contentType: contentType, prefix: prefix, size: size)
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        Task { @MainActor [weak manager] in manager?.backgroundEventsFinished() }
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
