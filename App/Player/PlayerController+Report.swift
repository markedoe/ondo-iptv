import Foundation
import IPTVCore
#if canImport(UIKit)
import UIKit
#endif

/// The playback log behind a problem report (More, Report a Problem): what was opened and with which engine, when
/// the picture moved, how long each seek took, the freezes, the errors and reconnections. No address, login or
/// title goes in: `PlaybackLog` scrubs every line, and only the address's extension is named.
extension PlayerController {
    var clock: Double { Date().timeIntervalSince(playbackStart) }

    func note(_ event: String) {
        log.add(event, at: clock)
    }

    func startLog(for item: PlaybackItem) {
        log = PlaybackLog()
        playbackStart = Date()
        loggedState = ""
        startWatch.start(at: 0)
        dataWatch = DataRateWatch()
        seekWatch.cancel()
        let resume = item.startPosition.map { $0 > 5 ? ", from \(Int($0)) s" : "" } ?? ""
        note("open \(Self.kind(of: item))\(resume)")
    }

    func noteAttempt(_ url: URL, engine: String) {
        let format = Self.container(of: url).map { " (.\($0))" } ?? ""
        note("address \(candidateIndex + 1) of \(max(candidates.count, 1))\(format) with \(engine)")
    }

    func noteSeek(from origin: Double, to target: Double) {
        note("seek from \(Self.seconds(origin)) to \(Self.seconds(target)) s")
        seekWatch.start(at: clock, target: target, from: origin)
    }

    /// Called twice a second: the state when it changed (errors included).
    func noteStateChange(at clock: Double) {
        let described = Self.describe(state)
        guard described != loggedState else { return }
        loggedState = described
        log.add("state: \(described)", at: clock)
    }

    /// The waits that ended with this sample, and a freeze that just ended.
    func noteTimings(_ sample: PlaybackSample, freezesBefore: Int) {
        if let wait = startWatch.sample(clock: sample.clock, position: sample.mediaTime) {
            log.add("picture moving after \(Self.seconds(wait)) s", at: sample.clock)
        }
        if let wait = seekWatch.sample(clock: sample.clock, position: sample.mediaTime) {
            log.add("seek done after \(Self.seconds(wait)) s", at: sample.clock)
        }
        for freeze in health.freezes.dropFirst(freezesBefore) {
            log.add("freeze of \(Self.seconds(freeze.duration)) s: \(ProblemReport.describe(freeze, in: health))", at: sample.clock)
        }
        // While the picture plays: data received more slowly than the picture uses it, before any freeze shows. VLC
        // counts as used the tracks it plays only: what it used stands for that share of what was received.
        let used = health.usedShare.flatMap { share in health.consumedRate().map { $0 / share } }
        if state == .playing, health.hasMoved, seekWatch.since == nil,
           let change = dataWatch.note(received: health.receivedRate(), consumed: used) {
            switch change {
            case .fellBehind(let received, let consumed):
                log.add("data slower than the picture uses it: \(Self.megabits(received)) of \(Self.megabits(consumed)) Mb/s over 10 s", at: sample.clock)
            case .caughtUp(let received):
                log.add("data caught up: \(Self.megabits(received)) Mb/s", at: sample.clock)
            }
        }
    }

    private static func megabits(_ bytesPerSecond: Double) -> String {
        String(format: "%.1f", bytesPerSecond * 8 / 1_000_000)
    }

    func problemReport() -> ProblemReport {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        let buffer: Double
        #if canImport(VLCKitSPM)
        buffer = activeEngine == .vlc ? vlcBackend?.cachingSeconds ?? 0 : 0
        #else
        buffer = 0
        #endif
        let context = ProblemReport.Context(
            app: "Ondo \(version) (\(build))",
            system: Self.system,
            engine: activeEngine == .vlc ? "VLC" : "AVPlayer",
            enginePreference: enginePreference.rawValue,
            liveBufferSetting: liveBufferSetting,
            vodBufferSetting: vodBufferSetting,
            bufferInUse: buffer,
            kind: item.map { Self.kind(of: $0) } ?? "nothing",
            container: candidates.indices.contains(candidateIndex) ? Self.container(of: candidates[candidateIndex]) : nil,
            video: videoResolution.map { "\(Int($0.width))×\(Int($0.height))" },
            duration: isLive ? nil : duration,
            position: isLive ? nil : currentTime,
            state: Self.describe(state)
        )
        return ProblemReport(context: context, health: health, log: log)
    }

    private static func kind(of item: PlaybackItem) -> String {
        if item.isCatchup { return "catch-up" }
        if item.isLive { return "live channel" }
        return item.reference.episodeID != nil ? "episode" : "film"
    }

    /// The extension of the address ("mkv", "m3u8"…), nothing else of it.
    private static func container(of url: URL) -> String? {
        let ext = url.pathExtension.lowercased()
        return (1...5).contains(ext.count) && ext.allSatisfy { $0.isLetter || $0.isNumber } ? ext : nil
    }

    private static func describe(_ state: State) -> String {
        switch state {
        case .idle: return "idle"
        case .loading: return "loading"
        case .playing: return "playing"
        case .paused: return "paused"
        case .ended: return "ended"
        case .failed(let message, let attribution): return "failed (\(attribution)): \(message)"
        }
    }

    private static func seconds(_ value: Double) -> String {
        String(format: "%.1f", value)
    }

    /// The system and the device model ("iPhone16,1", "Mac15,3"), for the playback and download reports.
    static var system: String {
        #if os(macOS)
        let key = "hw.model"
        #else
        let key = "hw.machine"
        #endif
        var size = 0
        sysctlbyname(key, nil, &size, nil, 0)
        var bytes = [CChar](repeating: 0, count: max(size, 1))
        sysctlbyname(key, &bytes, &size, nil, 0)
        let model = String(decoding: bytes.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }, as: UTF8.self)
        #if canImport(UIKit)
        return "\(UIDevice.current.systemName) \(UIDevice.current.systemVersion), \(model)"
        #else
        return "macOS \(ProcessInfo.processInfo.operatingSystemVersionString), \(model)"
        #endif
    }
}
