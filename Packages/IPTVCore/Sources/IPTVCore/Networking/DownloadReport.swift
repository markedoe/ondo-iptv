import Foundation

/// The details of a download (Downloads, Details): what the provider answered, the speed, the time left, what the
/// download waits for and what happened, as plain text to share. Like the playback report it names the kind of item
/// and its container, never its address or title, and every free text in it goes through `PrivacyScrubber`.
public struct DownloadReport: Sendable {
    public struct Context: Sendable {
        public var app: String
        public var system: String
        /// "film" or "episode".
        public var kind: String
        /// The container named by the address ("mkv", "mp4"…), never the address.
        public var container: String?
        /// "downloading", "paused", "failed: …"
        public var status: String
        /// The system's task: "running", "suspended"…; nil when there is none.
        public var task: String?
        public var received: Int64
        /// 0 when the provider did not give the size.
        public var total: Int64
        /// The provider's answer: its status and the headers that tell about the file.
        public var httpStatus: Int?
        public var headers: [String: String]
        /// What the download waits for, when it has received nothing for a while.
        public var problem: DownloadProblem?
        /// Seconds since the download started in this session, on the meter's and the log's clock.
        public var now: Double

        public init(
            app: String, system: String, kind: String, container: String?, status: String, task: String?, received: Int64,
            total: Int64, httpStatus: Int?, headers: [String: String], problem: DownloadProblem?, now: Double
        ) {
            self.app = app
            self.system = system
            self.kind = kind
            self.container = container
            self.status = status
            self.task = task
            self.received = received
            self.total = total
            self.httpStatus = httpStatus
            self.headers = headers
            self.problem = problem
            self.now = now
        }
    }

    /// The answer's headers a report keeps: size, type, whether it can resume, how it is sent. Not the others (a
    /// file name could be the title).
    public static let headerNames = ["Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Transfer-Encoding", "Server"]

    public let text: String

    public init(context: Context, meter: TransferMeter, log: PlaybackLog, generated: Date = Date()) {
        var lines: [String] = []
        func add(_ line: String) { lines.append(PrivacyScrubber.scrub(line)) }
        add("Ondo download report, \(ISO8601DateFormatter().string(from: generated))")
        add("App: \(context.app)")
        add("System: \(context.system)")
        add("Download: \(context.kind)\(context.container.map { ", .\($0)" } ?? "")")
        add("Status: \(context.status)\(context.task.map { "; system task \($0)" } ?? "; no system task")")
        let share = context.total > 0 ? String(format: " (%.1f %%)", Double(context.received) / Double(context.total) * 100) : ""
        add("Received: \(Self.size(context.received)) of \(context.total > 0 ? Self.size(context.total) : "an unknown size")\(share)")
        let recent = meter.rate().map { "\(Self.megabits($0)) over 10 s" } ?? "not measured yet"
        let average = meter.averageRate.map { "; \(Self.megabits($0)) on average" } ?? ""
        add("Speed: \(recent)\(average)")
        add("Last data: \(meter.lastArrival.map { "\(Self.number(max(context.now - $0, 0))) s ago" } ?? "none yet in this session")")
        if let left = meter.remaining(total: context.total) { add("Time left: \(Self.duration(left)) at this speed") }
        let headers = Self.headerNames.compactMap { name in context.headers[name].map { "\(name): \($0)" } }
        add("Answer: \(context.httpStatus.map { "HTTP \($0)" } ?? "none yet")\(headers.isEmpty ? "" : "; " + headers.joined(separator: "; "))")
        if let problem = context.problem { add("Waiting: \(Self.describe(problem))") }
        add("")
        add("Events (seconds since the download started in this session):")
        for entry in log.entries {
            add("  \(Self.number(entry.time)) \(entry.event)")
        }
        add("")
        add("Received every 10 s (s, MB, Mb/s since the line before):")
        var previous: TransferMeter.Point?
        for point in meter.points {
            if let before = previous, point.time - before.time < 10 { continue }
            let speed = previous.map { Self.megabits(Double(point.bytes - $0.bytes) / (point.time - $0.time)) } ?? "-"
            add("  \(Self.number(point.time)) \(point.bytes / 1_000_000) \(speed)")
            previous = point
        }
        text = lines.joined(separator: "\n")
    }

    /// What a download waits for, in the report's words.
    public static func describe(_ problem: DownloadProblem) -> String {
        switch problem {
        case .refused(let status): return "refused by the provider (HTTP \(status))"
        case .notFound(let status): return "file not found (HTTP \(status))"
        case .tooManyConnections(let status): return "too many connections (HTTP \(status))"
        case .httpError(let status): return "error from the provider (HTTP \(status))"
        case .errorPage: return "a message instead of the video"
        case .noResponse: return "no answer from the provider yet"
        case .noData: return "the provider answered but sends nothing"
        case .downloadStalled: return "the provider answers an ordinary request, the download receives nothing"
        case .offline: return "no internet connection"
        case .unreachable: return "the provider's server cannot be reached"
        case .timedOut: return "the provider takes too long to answer"
        case .connectionLost: return "connection lost"
        }
    }

    /// "1.20 GB", "350.4 MB": decimal units, as file sizes are shown.
    public static func size(_ bytes: Int64) -> String {
        bytes >= 1_000_000_000 ? String(format: "%.2f GB", Double(bytes) / 1e9) : String(format: "%.1f MB", Double(bytes) / 1e6)
    }

    /// "3 h 12 min", "4 min", "35 s".
    public static func duration(_ seconds: Double) -> String {
        let whole = Int(seconds.rounded())
        if whole >= 3_600 { return "\(whole / 3_600) h \(whole % 3_600 / 60) min" }
        if whole >= 60 { return "\(whole / 60) min" }
        return "\(whole) s"
    }

    public static func megabits(_ bytesPerSecond: Double) -> String {
        String(format: "%.2f Mb/s", bytesPerSecond * 8 / 1_000_000)
    }

    private static func number(_ value: Double) -> String {
        String(format: "%.1f", value)
    }
}
