import Foundation

/// What the player measures every half second: cumulative counters, as engines report them.
public struct PlaybackSample: Sendable, Equatable {
    /// Seconds since playback was opened (wall clock).
    public var clock: Double
    /// Position of the picture on screen, in seconds of media; nil while nothing plays.
    public var mediaTime: Double?
    /// Bytes received from the network so far.
    public var receivedBytes: Int
    /// Bytes the player has handed to its decoders so far (VLC): those of the tracks it plays only. A film's other
    /// audio languages are received and dropped, so this stays below what was received.
    public var consumedBytes: Int
    public var displayedPictures: Int
    public var lostPictures: Int
    /// The player is meant to be playing (not paused, not opening).
    public var isPlaying: Bool

    public init(
        clock: Double, mediaTime: Double?, receivedBytes: Int, consumedBytes: Int,
        displayedPictures: Int, lostPictures: Int, isPlaying: Bool
    ) {
        self.clock = clock
        self.mediaTime = mediaTime
        self.receivedBytes = receivedBytes
        self.consumedBytes = consumedBytes
        self.displayedPictures = displayedPictures
        self.lostPictures = lostPictures
        self.isPlaying = isPlaying
    }
}

/// Follows a playback to tell freezes apart: the network or the provider sending less than the video needs (too
/// slowly, or stopping), or the device not keeping up (data on time, pictures lost). Fed with `PlaybackSample`s; pure,
/// so it is tested on Linux. The rules come from libVLC runs against a provider that misbehaves on purpose
/// (tools/playback-lab).
///
/// libVLC updates its position and its counters only while data comes in, so they stand still during a *stall* (no
/// data) even while the picture goes on from the player's buffer. A stall shows as a freeze only for what exceeds
/// `buffer`, the seconds of video the player holds (VLC's network caching; 0 for AVPlayer, whose position is the
/// picture's).
public struct PlaybackHealth: Sendable {
    public enum Verdict: Equatable, Sendable {
        /// Too early to say (the first seconds of playback).
        case measuring
        /// No freeze and no picture lost.
        case smooth
        /// Freezes while the data came in too slowly or stopped: network or provider.
        case network
        /// Freezes or lost pictures while the data came in on time: the player or the device.
        case player
    }

    /// A freeze: the picture stopped for this long (the part of a stall the buffer did not cover).
    public struct Freeze: Equatable, Sendable {
        public var start: Double
        public var duration: Double
        /// Bytes per second received during the stall and the 5 seconds before it.
        public var receivedRate: Double
        /// The longest time without a single byte received, during the stall and the 3 seconds before it.
        public var longestGap: Double
        /// The picture stopped while the position (the sound) went on. With VLC, after a refill: the player threw
        /// away what it held and the picture waits for the next key frame.
        public var picturesOnly = false
    }

    /// Why a freeze happened, in the report's words.
    public enum FreezeCause: Equatable, Sendable {
        /// The provider sent nothing for this many seconds.
        case noData(seconds: Double)
        /// Data kept coming, more slowly than the video uses it (bytes per second): the player refilled.
        case slowData(received: Double, needed: Double?)
        /// Data came in as fast as the video uses it: the player or the device did not keep up.
        case player
    }

    /// A pause in the picture shorter than this is not a freeze.
    public static let minimumFreeze = 0.7
    /// The data must come in at least this fraction of what the video needs.
    public static let enoughData = 0.97
    /// Nothing received for this long before or during a freeze: the provider stopped sending.
    public static let dataGap = 1.0
    /// Below this pace (seconds of video per second while playing), the data does not keep up with the video, even
    /// before the buffer runs out or with only short stops.
    public static let smoothPace = 0.97
    /// More pictures lost than this share while the data keeps up: the device does not decode in time.
    public static let lostShare = 0.02

    private struct Entry: Sendable {
        var sample: PlaybackSample
        /// The picture has moved since the stream started (or since the last seek): waits before it are not freezes.
        var started: Bool
    }

    /// Seconds of video the player holds ahead of the picture: a stall shorter than this does not show.
    public var buffer: Double
    public private(set) var freezes: [Freeze] = []
    private var entries: [Entry] = []
    /// When the position stopped moving, while it should be playing: the start of the current stall.
    private var stillSince: PlaybackSample?
    /// The picture moves forward since the stream started or since the last seek (`restart`).
    public private(set) var hasMoved = false
    /// A seek was made and the position has not jumped to where it lands yet.
    private var landing = false
    /// When the picture stopped while the position went on (players that count their pictures: VLC). libVLC's
    /// counters move only while data comes in, and a moving position says it does: pictures that stand still then
    /// are a freeze the position does not show (after a refill, VLC starts the picture again at a key frame).
    private var picturesStillSince: Double?
    /// The pictures are on screen and counted (not while the app is in the background, where the sound may go on).
    public var watchesPictures = true {
        didSet { if !watchesPictures { picturesStillSince = nil } }
    }

    public init(buffer: Double = 0) {
        self.buffer = buffer
    }

    public mutating func add(_ sample: PlaybackSample) {
        if let last = entries.last?.sample,
           sample.receivedBytes < last.receivedBytes || sample.consumedBytes < last.consumedBytes {
            // A new stream (reconnection), or a counter that wrapped: everything starts again.
            entries.removeAll()
            stillSince = nil
            hasMoved = false
            landing = false
            picturesStillSince = nil
        }
        let last = entries.last?.sample
        if let last { notePictures(sample, after: last) }
        let moved = last.map { (sample.mediaTime ?? 0) != ($0.mediaTime ?? 0) || sample.displayedPictures != $0.displayedPictures } ?? false
        if let last, let now = sample.mediaTime, let before = last.mediaTime, now != before {
            if landing {
                // The jump to where the seek lands (a 10 s skip looks like 10 s played): the picture moves after it.
                landing = false
            } else if Self.isPlayed(now - before) {
                hasMoved = true
            }
        }
        if last != nil {
            if sample.isPlaying && !moved && sample.mediaTime != nil && hasMoved {
                // From the first sample that found the picture still (it stopped since the previous one).
                if stillSince == nil { stillSince = sample }
            } else if let still = stillSince {
                stillSince = nil
                if let freeze = freeze(stalledFrom: still.clock, to: sample.clock) { freezes.append(freeze) }
            }
        }
        entries.append(Entry(sample: sample, started: hasMoved && sample.isPlaying && sample.mediaTime != nil))
        // Two minutes are enough for the rates and the verdict.
        while let first = entries.first, first.sample.clock < sample.clock - 120 { entries.removeFirst() }
    }

    /// The picture against the position: still pictures while the position goes on are a freeze of the picture alone,
    /// from the first sample that found them still (as a stall); when the position stops too, the stall measures the
    /// freeze from then on.
    private mutating func notePictures(_ sample: PlaybackSample, after last: PlaybackSample) {
        let advanced = Self.isPlayed((sample.mediaTime ?? 0) - (last.mediaTime ?? 0))
        if watchesPictures, sample.isPlaying, hasMoved, advanced, sample.displayedPictures > 0,
           sample.displayedPictures == last.displayedPictures {
            if picturesStillSince == nil { picturesStillSince = sample.clock }
        } else {
            endPictureFreeze(at: sample.clock)
        }
    }

    private mutating func endPictureFreeze(at end: Double) {
        guard let start = picturesStillSince else { return }
        picturesStillSince = nil
        guard end - start >= Self.minimumFreeze else { return }
        freezes.append(Freeze(
            start: start, duration: end - start, receivedRate: receivedRate(from: start - 5, to: end), longestGap: longestGap(from: start - 3, to: end),
            picturesOnly: true
        ))
    }

    /// Why `freeze` happened: no data, data slower than the video needs, or a player that did not keep up. A freeze of
    /// the picture alone is judged on the data too: VLC stops it that way after a refill.
    public func cause(of freeze: Freeze) -> FreezeCause {
        if freeze.longestGap >= Self.dataGap { return .noData(seconds: freeze.longestGap) }
        guard let needed = contentRate else { return .slowData(received: freeze.receivedRate, needed: nil) }
        return freeze.receivedRate < needed * Self.enoughData ? .slowData(received: freeze.receivedRate, needed: needed) : .player
    }

    /// A seek: the wait until the picture moves again is not a freeze.
    public mutating func restart() {
        stillSince = nil
        hasMoved = false
        landing = true
        picturesStillSince = nil
    }

    /// The picture is stopped right now, for at least `minimumFreeze`.
    public var isFrozen: Bool {
        currentFreeze >= Self.minimumFreeze
    }

    /// How long the current freeze has lasted, in seconds (0 when the picture moves).
    public var currentFreeze: Double {
        guard let still = stillSince, let last = entries.last else { return 0 }
        return max(currentStall - covered(stalledFrom: still.clock, to: last.sample.clock), 0)
    }

    /// The part of a stall the buffer covers. While no data comes in, libVLC's position stands still and the picture
    /// goes on from the buffer; while data keeps coming in during a stall, the player has run out and refills (the
    /// data is slower than the video), and the picture stands still from the start: a provider sending a 12 Mb/s film
    /// at 9 Mb/s froze 6 to 8 s at a time with 5 s of buffer.
    private func covered(stalledFrom start: Double, to end: Double) -> Double {
        // From the sample before the stall: the data stopped between the two.
        min(buffer, longestGap(from: start - 0.75, to: end))
    }

    /// How long the position has stood still while playing (no data for libVLC), in seconds.
    public var currentStall: Double {
        guard let still = stillSince, let last = entries.last else { return 0 }
        return last.sample.clock - still.clock
    }

    public var frozenSeconds: Double { freezes.reduce(0) { $0 + $1.duration } + (isFrozen ? currentFreeze : 0) }

    /// While playing: seconds of wall clock, seconds of video shown, bytes received and used. A stall going on is left out:
    /// libVLC's position catches up when data comes back, and the part the buffer does not cover is a freeze.
    private var played: (wall: Double, media: Double, received: Double, consumed: Double) {
        var wall = 0.0, media = 0.0, received = 0.0, consumed = 0.0
        let end = stillSince?.clock ?? .infinity
        for (previous, current) in zip(entries, entries.dropFirst()) where previous.started && current.started && current.sample.clock < end {
            wall += current.sample.clock - previous.sample.clock
            let step = (current.sample.mediaTime ?? 0) - (previous.sample.mediaTime ?? 0)
            // After a stall, libVLC catches up in one step: that is video shown. A longer jump is a seek.
            if Self.isPlayed(step) { media += step }
            received += Double(current.sample.receivedBytes - previous.sample.receivedBytes)
            consumed += Double(current.sample.consumedBytes - previous.sample.consumedBytes)
        }
        return (wall, media, received, consumed)
    }

    /// A move of the position between two samples that is playback, not a seek.
    private static func isPlayed(_ step: Double) -> Bool { step > 0 && step <= 30 }

    /// Bytes per second the video needs: what was received per second of video shown, container and tracks not played
    /// included (the network has to carry them all).
    public var contentRate: Double? {
        let played = played
        return played.media >= 5 ? played.received / played.media : nil
    }

    /// The share of the data received that the player used: VLC hands its decoders the tracks it plays only, and a
    /// film's other audio languages are received all the same. Approximate: the player's reserve growing or running
    /// down meanwhile moves it a little.
    public var usedShare: Double? {
        let played = played
        guard played.received > 0, played.consumed > 0 else { return nil }
        return min(played.consumed / played.received, 1)
    }

    /// Seconds of video shown per second of playing: 1 when smooth, less when the picture stops or stutters.
    public var pace: Double? {
        let played = played
        return played.wall >= 5 ? played.media / played.wall : nil
    }

    /// Bytes per second received over the last `seconds`.
    public func receivedRate(lastSeconds seconds: Double = 10) -> Double? {
        guard let last = entries.last else { return nil }
        return receivedRate(from: last.sample.clock - seconds, to: last.sample.clock)
    }

    /// Bytes per second the picture consumed over the last `seconds` (what the demuxer read).
    public func consumedRate(lastSeconds seconds: Double = 10) -> Double? {
        guard let last = entries.last else { return nil }
        let window = window(from: last.sample.clock - seconds, to: last.sample.clock)
        guard let first = window.first, let end = window.last, end.clock > first.clock else { return nil }
        return Double(end.consumedBytes - first.consumedBytes) / (end.clock - first.clock)
    }

    private func window(from start: Double, to end: Double) -> [PlaybackSample] {
        entries.lazy.map(\.sample).filter { $0.clock >= start && $0.clock <= end }
    }

    private func receivedRate(from start: Double, to end: Double) -> Double {
        let window = window(from: start, to: end)
        guard let first = window.first, let last = window.last, last.clock > first.clock else { return 0 }
        return Double(last.receivedBytes - first.receivedBytes) / (last.clock - first.clock)
    }

    /// The longest time between `start` and `end` without a byte received.
    private func longestGap(from start: Double, to end: Double) -> Double {
        var longest = 0.0
        var since: Double?
        let window = window(from: start, to: end)
        for (previous, current) in zip(window, window.dropFirst()) {
            if current.receivedBytes == previous.receivedBytes {
                let gapStart = since ?? previous.clock
                since = gapStart
                longest = max(longest, current.clock - gapStart)
            } else {
                since = nil
            }
        }
        return longest
    }

    /// The freeze a stall caused, if it outlasted what the buffer covered by `minimumFreeze`.
    private func freeze(stalledFrom start: Double, to end: Double) -> Freeze? {
        let covered = covered(stalledFrom: start, to: end)
        let duration = end - start - covered
        guard duration >= Self.minimumFreeze else { return nil }
        return Freeze(
            start: start + covered, duration: duration, receivedRate: receivedRate(from: start - 5, to: end), longestGap: longestGap(from: start - 3, to: end)
        )
    }

    /// The last `count` samples, oldest first (for a problem report).
    public func recentSamples(_ count: Int) -> [PlaybackSample] {
        entries.suffix(count).map(\.sample)
    }

    /// Pictures lost by the player, since the stream started.
    public var lostPictures: Int { entries.last?.sample.lostPictures ?? 0 }

    /// Freezes, stutter and lost pictures of the last two minutes, explained.
    public var verdict: Verdict {
        guard let first = entries.first?.sample, let last = entries.last?.sample, last.clock - first.clock >= 10 else { return .measuring }
        let played = played
        let lost = last.lostPictures - first.lostPictures
        // AVPlayer counts no picture shown: 25 a second of video stands for them.
        let shown = last.displayedPictures > first.displayedPictures ? last.displayedPictures - first.displayedPictures : Int(played.media * 25)
        // Lost pictures point at the device when the data kept up: a player starved of data loses some too.
        let pace = pace
        if lost > 0 && Double(lost) / Double(max(shown + lost, 1)) > Self.lostShare && (pace ?? 1) >= Self.smoothPace { return .player }
        var recent = freezes.filter { $0.start >= first.clock }
        if let still = stillSince, let ongoing = freeze(stalledFrom: still.clock, to: last.clock) { recent.append(ongoing) }
        if recent.isEmpty {
            // Stutter without a long freeze and without lost pictures: the player waits for data that comes in
            // slower than the video plays (a device that does not keep up loses pictures instead).
            return (pace ?? 1) >= Self.smoothPace ? .smooth : .network
        }
        guard let needed = contentRate, needed > 0 else { return .network }
        let starved = recent.filter { $0.longestGap >= Self.dataGap || $0.receivedRate < needed * Self.enoughData }
        return starved.count * 2 >= recent.count ? .network : .player
    }
}
