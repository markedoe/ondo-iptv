import Foundation
import IPTVCore
import Observation

/// A player instance with its zapping queue (channels of the category, or episodes of a season).
///
/// For live channels with catch-up, the session can also play the channel "in the past" (restart of the
/// current programme, replay from the in-player guide, resume after a live pause). `current` stays the
/// live channel item so guide lookups and zapping keep working; `playingItem` is what the player plays.
@MainActor
@Observable
final class PlaybackSession: Identifiable {
    /// Live channel being watched behind its live edge.
    struct Timeshift: Equatable {
        var item: PlaybackItem
        /// Wall-clock instant the replay starts at.
        var start: Date
        /// Programme being replayed, when known.
        var programTitle: String?
    }

    let id = UUID()
    let controller = PlayerController()
    private(set) var queue: [PlaybackItem]
    private(set) var index: Int
    /// Channel watched before the current one ("last channel" button).
    private(set) var previousIndex: Int?
    private(set) var timeshift: Timeshift?
    /// When a live stream without DVR window was paused (resumed from the archive when possible).
    private(set) var livePausedAt: Date?

    /// Called every time a new item starts (history, "now playing").
    @ObservationIgnored var onItemStarted: ((PlaybackItem) -> Void)?
    /// Resume position for VOD items selected from the queue (next episode…).
    @ObservationIgnored var resumePosition: ((PlaybackItem) -> Double?)?
    /// Builds a catch-up item playing the live `item` from `start` (nil when the channel has no archive).
    @ObservationIgnored var catchupItem: ((_ item: PlaybackItem, _ start: Date, _ end: Date, _ title: String?) -> PlaybackItem?)?
    @ObservationIgnored var now: () -> Date = Date.init

    /// Pauses shorter than this resume where they stopped (the player buffer covers them).
    static let livePauseThreshold: TimeInterval = 10
    /// How far a live pause replay reaches (providers need an end for their archive URLs).
    static let livePauseWindow: TimeInterval = 4 * 3600

    init(queue: [PlaybackItem], index: Int) {
        self.queue = queue
        self.index = min(max(index, 0), max(queue.count - 1, 0))
    }

    var current: PlaybackItem? { queue.indices.contains(index) ? queue[index] : nil }
    /// The item the player is actually playing (the replay while time-shifted).
    var playingItem: PlaybackItem? { timeshift?.item ?? current }
    var hasNext: Bool { index + 1 < queue.count }
    var isLiveChannel: Bool { current?.isLive == true }
    var isTimeshifted: Bool { timeshift != nil }
    /// Watching a live channel at its live edge.
    var isAtLiveEdge: Bool { isLiveChannel && timeshift == nil }
    var canReturnToPreviousChannel: Bool {
        guard let previousIndex else { return false }
        return previousIndex != index && queue.indices.contains(previousIndex)
    }

    /// Starts the current item, or directly a programme of the live channel when `program` is given.
    func start(from program: EPGProgram? = nil) {
        guard let current else { return }
        onItemStarted?(current)
        if let program, play(program) { return }
        controller.load(current)
    }

    func select(index newIndex: Int) {
        guard queue.indices.contains(newIndex) else { return }
        if newIndex != index { previousIndex = index }
        index = newIndex
        timeshift = nil
        livePausedAt = nil
        var item = queue[newIndex]
        if !item.isLive { item.startPosition = resumePosition?(item) }
        controller.load(item)
        onItemStarted?(item)
    }

    /// Channel up / down (wraps around for live TV).
    func zap(_ offset: Int) {
        guard !queue.isEmpty else { return }
        var target = index + offset
        if isLiveChannel {
            target = (target % queue.count + queue.count) % queue.count
        }
        select(index: target)
    }

    /// Goes back to the channel watched before (pressing it again swaps back).
    func returnToPreviousChannel() {
        guard canReturnToPreviousChannel, let previousIndex else { return }
        select(index: previousIndex)
    }

    func replace(with item: PlaybackItem) {
        queue = [item]
        index = 0
        previousIndex = nil
        timeshift = nil
        livePausedAt = nil
        controller.load(item)
        onItemStarted?(item)
    }

    // MARK: - Time shifting

    /// Can the live channel be played from `start` (catch-up archive)?
    func canTimeshift(from start: Date) -> Bool {
        guard isLiveChannel, let current else { return false }
        return catchupItem?(current, start, start.addingTimeInterval(3600), nil) != nil
    }

    /// Plays the current live channel from `start` until `end`. Returns false without catch-up.
    @discardableResult
    func playFromArchive(start: Date, end: Date, title: String?) -> Bool {
        guard isLiveChannel, let current, let item = catchupItem?(current, start, end, title) else { return false }
        timeshift = Timeshift(item: item, start: start, programTitle: title)
        livePausedAt = nil
        controller.load(item)
        return true
    }

    /// Restarts (or replays) a programme of the current channel.
    @discardableResult
    func play(_ program: EPGProgram) -> Bool {
        playFromArchive(start: program.start, end: program.end, title: program.title)
    }

    func returnToLive() {
        guard timeshift != nil || livePausedAt != nil, let current else { return }
        timeshift = nil
        livePausedAt = nil
        controller.load(current)
    }

    /// Wall-clock instant being watched while time-shifted.
    var timeshiftPosition: Date? {
        timeshift.map { $0.start.addingTimeInterval(controller.currentTime) }
    }

    // MARK: - Transport

    func play() {
        // A long live pause without DVR window resumes from the archive, where the viewer stopped.
        if let pausedAt = livePausedAt, isAtLiveEdge, !controller.hasLiveWindow,
           now().timeIntervalSince(pausedAt) >= Self.livePauseThreshold,
           playFromArchive(start: pausedAt, end: pausedAt.addingTimeInterval(Self.livePauseWindow), title: nil) {
            return
        }
        livePausedAt = nil
        controller.play()
    }

    func pause() {
        if isAtLiveEdge, !controller.hasLiveWindow, canTimeshift(from: now().addingTimeInterval(-1)) {
            livePausedAt = now()
        }
        controller.pause()
    }

    func togglePlayPause() {
        if controller.isPlaying { pause() } else { play() }
    }
}
