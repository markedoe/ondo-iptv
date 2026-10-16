import Foundation

/// A playback problem report: the app and system, the player settings, what is playing (its kind and format, never
/// its address or title), the start and seek times, the freezes and the last minute of the player's counters. Plain
/// text, to share or paste into an issue; every free text in it goes through `PrivacyScrubber`.
public struct ProblemReport: Sendable {
    public struct Context: Sendable {
        public var app: String
        public var system: String
        public var engine: String
        public var enginePreference: String
        /// Buffer settings in seconds, 0 for automatic, and the buffer in use.
        public var liveBufferSetting: Int
        public var vodBufferSetting: Int
        public var bufferInUse: Double
        /// "live channel", "film", "episode", "catch-up"…
        public var kind: String
        /// The container named by the address ("mkv", "m3u8"…), never the address.
        public var container: String?
        public var video: String?
        public var duration: Double?
        public var position: Double?
        public var state: String

        public init(
            app: String, system: String, engine: String, enginePreference: String, liveBufferSetting: Int,
            vodBufferSetting: Int, bufferInUse: Double, kind: String, container: String?, video: String?,
            duration: Double?, position: Double?, state: String
        ) {
            self.app = app
            self.system = system
            self.engine = engine
            self.enginePreference = enginePreference
            self.liveBufferSetting = liveBufferSetting
            self.vodBufferSetting = vodBufferSetting
            self.bufferInUse = bufferInUse
            self.kind = kind
            self.container = container
            self.video = video
            self.duration = duration
            self.position = position
            self.state = state
        }
    }

    public let text: String

    public init(context: Context, health: PlaybackHealth, log: PlaybackLog, generated: Date = Date()) {
        var lines: [String] = []
        func add(_ line: String) { lines.append(PrivacyScrubber.scrub(line)) }
        func setting(_ seconds: Int) -> String { seconds > 0 ? "\(seconds) s" : "automatic" }
        add("Ondo playback report, \(ISO8601DateFormatter().string(from: generated))")
        add("App: \(context.app)")
        add("System: \(context.system)")
        add("Engine: \(context.engine) (setting: \(context.enginePreference))")
        add("Buffer: \(Self.number(context.bufferInUse)) s in use; live \(setting(context.liveBufferSetting)), films and series \(setting(context.vodBufferSetting))")
        add("Playing: \(context.kind)\(context.container.map { ", .\($0)" } ?? "")\(context.video.map { ", \($0)" } ?? "")")
        if let duration = context.duration, duration > 0 { add("Duration: \(Self.number(duration)) s") }
        if let position = context.position { add("Position: \(Self.number(position)) s") }
        add("State: \(context.state)")
        add("")
        add("Verdict: \(Self.describe(health.verdict))")
        add("Freezes: \(health.freezes.count + (health.isFrozen ? 1 : 0)), \(Self.number(health.frozenSeconds)) s")
        for freeze in health.freezes.suffix(10) {
            add("  at \(Self.number(freeze.start)) s for \(Self.number(freeze.duration)) s; received \(Self.megabits(freeze.receivedRate)), longest gap \(Self.number(freeze.longestGap)) s")
        }
        add("Lost pictures: \(health.lostPictures)")
        add("Smoothness: \(health.pace.map { "\(Int(($0 * 100).rounded())) %" } ?? "-")")
        add("Video bitrate: \(health.contentRate.map(Self.megabits) ?? "-"); received (10 s): \(health.receivedRate().map(Self.megabits) ?? "-")")
        add("")
        add("Events (seconds since opening):")
        for entry in log.entries {
            add("  \(Self.number(entry.time)) \(entry.event)")
        }
        add("")
        // Received and used: the player reads ahead, then only as fast as the picture uses the data; received falling
        // behind used is the reserve running down.
        add("Samples (s, position, received kB, used kB, pictures shown, lost):")
        for sample in health.recentSamples(120) {
            let position = sample.mediaTime.map(Self.number) ?? "-"
            add(
                "  \(Self.number(sample.clock)) \(position) \(sample.receivedBytes / 1000) \(sample.consumedBytes / 1000) "
                    + "\(sample.displayedPictures) \(sample.lostPictures)"
            )
        }
        text = lines.joined(separator: "\n")
    }

    private static func describe(_ verdict: PlaybackHealth.Verdict) -> String {
        switch verdict {
        case .measuring: return "measuring"
        case .smooth: return "smooth"
        case .network: return "network or provider (data too slow or stopped)"
        case .player: return "player or device (data on time, pictures lost or stopped)"
        }
    }

    private static func number(_ value: Double) -> String {
        String(format: "%.1f", value)
    }

    private static func megabits(_ bytesPerSecond: Double) -> String {
        String(format: "%.2f Mb/s", bytesPerSecond * 8 / 1_000_000)
    }
}
