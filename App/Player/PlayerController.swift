import AVFoundation
import AVKit
import SwiftUI
import IPTVCore
import Observation

/// One selectable audio or subtitle track.
struct MediaTrack: Identifiable, Hashable {
    let id: Int
    let name: String
    let languageCode: String?
}

/// Wraps an `AVPlayer` (and VLC for what AVFoundation cannot play) with what an IPTV player needs on top: URL
/// fallbacks, automatic retries, stall recovery on live streams, resume positions, track selection, aspect ratio,
/// PiP, sleep timer and progress reporting.
@MainActor
@Observable
final class PlayerController: NSObject {
    enum State: Equatable {
        case idle
        case loading
        case playing
        case paused
        case ended
        case failed(message: String, attribution: ErrorAttribution)
    }

    let player = AVPlayer()

    private(set) var item: PlaybackItem?
    private(set) var state: State = .idle
    private(set) var currentTime: Double = 0
    private(set) var duration: Double = 0
    /// Set by the engines' observers, and by the freeze watch for VLC (PlayerController+Health).
    var isBuffering = false
    private(set) var audioTracks: [MediaTrack] = []
    private(set) var subtitleTracks: [MediaTrack] = []
    private(set) var selectedAudioTrackID: Int?
    private(set) var selectedSubtitleTrackID: Int?
    private(set) var isPictureInPictureActive = false
    private(set) var isPictureInPicturePossible = false
    private(set) var isExternalPlaybackActive = false
    private(set) var sleepTimerEnd: Date?
    private(set) var videoResolution: CGSize?
    /// Seekable range of a live stream (player timeline, seconds). Long ranges mean the provider keeps a DVR window.
    private(set) var liveWindow: ClosedRange<Double>?
    /// SRT / WebVTT file loaded by the user, drawn by `PlayerView`.
    private(set) var externalSubtitles: [SubtitleCue] = []
    private(set) var externalSubtitleName: String?
    var subtitleDelay: Double = 0
    /// Engine rendering the current item.
    private(set) var activeEngine: PlayerEngine = .apple
    var aspect: VideoAspect = .fit {
        didSet { applyAspect() }
    }
    /// Mutes both engines (multiview keeps the sound of one tile only).
    var isMuted = false {
        didSet {
            player.isMuted = isMuted
            setVLCMuted(isMuted)
        }
    }
    var rate: Float = 1 {
        didSet {
            if activeEngine == .vlc {
                setVLCRate(rate)
            } else if state == .playing {
                player.rate = rate
            }
        }
    }

    /// Called about every 10 s and when playback stops, to persist resume positions.
    @ObservationIgnored var onProgress: ((PlaybackItem, Double, Double) -> Void)?
    /// Called when a VOD item plays to the end (auto-play next episode).
    @ObservationIgnored var onEnded: ((PlaybackItem) -> Void)?
    @ObservationIgnored var onPictureInPictureChange: ((Bool) -> Void)?
    /// Asked to bring the player UI back when the user taps "restore" in PiP.
    @ObservationIgnored var restoreUserInterface: ((@escaping (Bool) -> Void) -> Void)?

    @ObservationIgnored var autoRetry = true
    /// Multiview tiles never start Picture in Picture.
    @ObservationIgnored var allowsPictureInPicture = true
    @ObservationIgnored var preferredAudioLanguage: String?
    @ObservationIgnored var preferredSubtitleLanguage: String?
    @ObservationIgnored var subtitlesEnabledByDefault = false
    @ObservationIgnored var enginePreference: PlayerEngine = .automatic
    /// Buffer settings in seconds, 0 for automatic (`PlaybackBuffer`).
    @ObservationIgnored var liveBufferSetting = 0
    @ObservationIgnored var vodBufferSetting = 0
    /// Set when AVPlayer failed on the current item and VLC takes over.
    @ObservationIgnored private var forceVLCForItem = false
    /// Freezes and lost pictures of this playback, explained (sampled twice a second, PlayerController+Health).
    var health = PlaybackHealth()
    /// The statistics panel is shown over the picture.
    var showsStatistics = false
    @ObservationIgnored var healthTask: Task<Void, Never>?
    /// The spinner is shown for a freeze VLC did not report (PlayerController+Health).
    @ObservationIgnored var freezeShown = false
    /// What happened during this playback, for a problem report, and the waits being timed (PlayerController+Report).
    @ObservationIgnored var log = PlaybackLog()
    @ObservationIgnored var playbackStart = Date()
    @ObservationIgnored var startWatch = MovementWatch()
    @ObservationIgnored var seekWatch = MovementWatch()
    @ObservationIgnored var loggedState = ""
    /// Scrubbing: the latest position asked for, and whether a seek is still on its way (PlayerController+Scrub).
    @ObservationIgnored var scrubTarget: Double?
    @ObservationIgnored var scrubInFlight = false
    #if canImport(VLCKitSPM)
    @ObservationIgnored var vlcBackend: VLCBackend?
    @ObservationIgnored private var vlcViewSize: CGSize = .zero
    @ObservationIgnored private weak var vlcDrawable: AnyObject?
    #endif

    @ObservationIgnored private weak var playerLayer: AVPlayerLayer?
    @ObservationIgnored private var pipController: AVPictureInPictureController?
    @ObservationIgnored private var pipPossibleObservation: NSKeyValueObservation?
    @ObservationIgnored private(set) var candidates: [URL] = []
    @ObservationIgnored private(set) var candidateIndex = 0
    @ObservationIgnored private var retries = 0
    @ObservationIgnored private var statusObservation: NSKeyValueObservation?
    @ObservationIgnored private var timeControlObservation: NSKeyValueObservation?
    @ObservationIgnored private var externalObservation: NSKeyValueObservation?
    @ObservationIgnored private var presentationSizeObservation: NSKeyValueObservation?
    @ObservationIgnored private var timeObserver: Any?
    @ObservationIgnored private var itemObservers: [NSObjectProtocol] = []
    @ObservationIgnored private var sleepTask: Task<Void, Never>?
    @ObservationIgnored private var stallTask: Task<Void, Never>?
    @ObservationIgnored private var retryTask: Task<Void, Never>?
    @ObservationIgnored private var lastReportedProgress = Date.distantPast
    @ObservationIgnored private var audioGroup: AVMediaSelectionGroup?
    @ObservationIgnored private var subtitleGroup: AVMediaSelectionGroup?
    /// Resume position not reached yet. Until then the player's own clock (still at 0) is neither shown
    /// nor saved: saving it would replace the resume point with the beginning of the film.
    @ObservationIgnored private var pendingStart: Double?
    /// Whether playback starts once the item is ready (false when the user paused meanwhile).
    @ObservationIgnored private var playsWhenReady = true
    /// The resume seek was sent (the item is ready); until then a seek only moves `pendingStart`.
    @ObservationIgnored private var startSeekSent = false
    #if canImport(VLCKitSPM)
    /// VLC sometimes ignores `:start-time` (servers without byte ranges): one explicit seek follows.
    @ObservationIgnored private var vlcStartSeekSent = false
    #endif

    override init() {
        super.init()
        player.allowsExternalPlayback = true
        #if os(iOS)
        player.usesExternalPlaybackWhileExternalScreenIsActive = true
        #endif
        player.automaticallyWaitsToMinimizeStalling = true
        timeControlObservation = player.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
            let status = player.timeControlStatus
            Task { @MainActor in self?.timeControlStatusChanged(status) }
        }
        externalObservation = player.observe(\.isExternalPlaybackActive, options: [.new]) { [weak self] player, _ in
            let active = player.isExternalPlaybackActive
            Task { @MainActor in self?.isExternalPlaybackActive = active }
        }
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.25, preferredTimescale: 600), queue: .main) { [weak self] time in
            MainActor.assumeIsolated {
                self?.tick(time)
            }
        }
    }

    var isLive: Bool { item?.isLive ?? false }
    var isPlaying: Bool { state == .playing }
    var canSeek: Bool { !isLive && duration > 0 }
    /// Skip buttons work on seekable items and on live streams with a DVR window.
    var canSkip: Bool { canSeek || hasLiveWindow }
    /// Live stream that can be paused and rewound in the player (HLS with a DVR window).
    var hasLiveWindow: Bool {
        guard isLive, let liveWindow else { return false }
        return liveWindow.upperBound - liveWindow.lowerBound >= Self.minimumLiveWindow
    }
    /// Seconds behind the live edge.
    var liveOffset: Double {
        guard let liveWindow else { return 0 }
        return max(liveWindow.upperBound - currentTime, 0)
    }
    static let minimumLiveWindow: Double = 60
    var canUsePictureInPicture: Bool { activeEngine == .apple && isPictureInPicturePossible }

    // MARK: - Loading

    func load(_ item: PlaybackItem) {
        reportProgress(force: true)
        self.item = item
        candidates = item.candidateURLs
        candidateIndex = 0
        retries = 0
        currentTime = item.startPosition ?? 0
        duration = 0
        audioTracks = []
        subtitleTracks = []
        selectedAudioTrackID = nil
        selectedSubtitleTrackID = nil
        videoResolution = nil
        liveWindow = nil
        externalSubtitles = []
        externalSubtitleName = nil
        subtitleDelay = 0
        forceVLCForItem = false
        rate = 1
        health = PlaybackHealth()
        freezeShown = false
        startLog(for: item)
        // A new item plays; the automatic fallbacks below keep a pause the viewer made while it opened.
        playsWhenReady = true
        loadCurrentCandidate()
        startHealthMonitoring()
    }

    /// Reloads the current item with another engine, keeping the position.
    func switchEngine(to engine: PlayerEngine) {
        guard var current = item else { return }
        enginePreference = engine
        if !isLive { current.startPosition = currentTime }
        load(current)
    }

    private func loadCurrentCandidate() {
        guard let item, candidateIndex < candidates.count else { return }
        state = .loading
        retryTask?.cancel()
        stallTask?.cancel()
        let url = candidates[candidateIndex]
        if shouldUseVLC(for: url) {
            startVLC(url: url, item: item)
            return
        }
        stopVLC()
        activeEngine = .apple
        noteAttempt(url, engine: "AVPlayer")
        var options: [String: Any] = [:]
        let headers = item.headers.dictionary
        if !headers.isEmpty {
            options["AVURLAssetHTTPHeaderFieldsKey"] = headers
        }
        let asset = AVURLAsset(url: url, options: options)
        let playerItem = AVPlayerItem(asset: asset)
        if let seconds = [item.isLive ? liveBufferSetting : vodBufferSetting].first(where: { $0 > 0 }) {
            playerItem.preferredForwardBufferDuration = Double(seconds)
        } else if item.isLive {
            // Keep a small forward buffer on live streams to stay close to real time.
            playerItem.preferredForwardBufferDuration = 8
        }
        observe(playerItem)
        player.replaceCurrentItem(with: playerItem)
        startSeekSent = false
        if let start = Self.resumeStart(for: item) {
            // Seek once the item is ready: playing first buffers the beginning for nothing and the seek then
            // has to buffer again (slow resumes), and a seek sent before the item is ready can be dropped
            // (resumes that restart from the beginning).
            pendingStart = start
            currentTime = start
            return
        }
        pendingStart = nil
        guard playsWhenReady else {
            // Paused while a previous address was opening (casting started, remote pause): stay paused.
            state = .paused
            return
        }
        player.play()
        player.rate = item.isLive ? 1 : rate
    }

    private func observe(_ playerItem: AVPlayerItem) {
        statusObservation = playerItem.observe(\.status, options: [.new]) { [weak self] observed, _ in
            let status = observed.status
            Task { @MainActor in self?.itemStatusChanged(status, item: observed) }
        }
        presentationSizeObservation = playerItem.observe(\.presentationSize, options: [.new]) { [weak self] observed, _ in
            let size = observed.presentationSize
            Task { @MainActor in self?.videoResolution = size == .zero ? nil : size }
        }
        for token in itemObservers { NotificationCenter.default.removeObserver(token) }
        itemObservers = [
            NotificationCenter.default.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: playerItem, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.didPlayToEnd() }
            },
            NotificationCenter.default.addObserver(forName: AVPlayerItem.failedToPlayToEndTimeNotification, object: playerItem, queue: .main) { [weak self] note in
                let error = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
                MainActor.assumeIsolated { self?.handleFailure(error, item: playerItem) }
            },
        ]
    }

    private func itemStatusChanged(_ status: AVPlayerItem.Status, item playerItem: AVPlayerItem) {
        guard playerItem == player.currentItem else { return }
        switch status {
        case .readyToPlay:
            retries = 0
            let seconds = playerItem.duration.seconds
            duration = seconds.isFinite ? seconds : 0
            seekToPendingStart(playerItem)
            updateNowPlaying()
            Task { await loadTracks(for: playerItem) }
        case .failed:
            handleFailure(playerItem.error, item: playerItem)
        default:
            break
        }
    }

    private func handleFailure(_ error: Error?, item playerItem: AVPlayerItem) {
        guard playerItem == player.currentItem, activeEngine == .apple else { return }
        keepPositionForFallback()
        // AirPlay: the TV plays the stream itself, so VLC (which plays on this device only) is no fallback:
        // it would leave the TV connected without a picture.
        let onTV = isExternalPlaybackActive || player.isExternalPlaybackActive
        // 1. Next candidate URL (e.g. `.m3u8` variant of a `.ts` stream, other catch-up endpoint).
        if candidateIndex + 1 < candidates.count {
            candidateIndex += 1
            loadCurrentCandidate()
            return
        }
        // 1b. Format AVFoundation cannot play: hand the stream over to VLC.
        if !onTV, PlayerEngine.isVLCAvailable && enginePreference == .automatic && !forceVLCForItem {
            forceVLCForItem = true
            candidateIndex = 0
            loadCurrentCandidate()
            return
        }
        // 2. Automatic retry for live streams (providers drop connections all the time).
        if autoRetry, isLive, retries < 3 {
            retries += 1
            candidateIndex = 0
            state = .loading
            let delay = Double(retries) * 2
            retryTask = Task { [weak self] in
                try? await Task.sleep(for: .seconds(delay))
                guard !Task.isCancelled else { return }
                self?.loadCurrentCandidate()
            }
            return
        }
        if onTV {
            let message = String(localized: "Your TV could not open this stream over AirPlay. Some providers only accept the device that opened it; for Xtream, the HLS (m3u8) format works best.")
            state = .failed(message: message, attribution: .provider)
            return
        }
        let (message, attribution) = Self.describe(error, item: playerItem)
        state = .failed(message: message, attribution: attribution)
    }

    /// Turns AVFoundation errors into a readable message and says who is responsible.
    static func describe(_ error: Error?, item: AVPlayerItem) -> (String, ErrorAttribution) {
        if let event = item.errorLog()?.events.last, event.errorStatusCode >= 400 {
            let code = event.errorStatusCode
            switch code {
            case 401, 403:
                return (String(localized: "The provider refused access to this stream (HTTP \(code)). Your subscription may have expired or the connection limit is reached."), .provider)
            case 404:
                return (String(localized: "This stream does not exist on the provider's server (HTTP 404)."), .provider)
            default:
                return (String(localized: "The provider's server returned an error (HTTP \(code))."), .provider)
            }
        }
        let nsError = (error as NSError?)
        let underlying = nsError?.userInfo[NSUnderlyingErrorKey] as? NSError
        for candidate in [nsError, underlying].compactMap({ $0 }) {
            if candidate.domain == NSURLErrorDomain {
                switch candidate.code {
                case NSURLErrorNotConnectedToInternet, NSURLErrorNetworkConnectionLost, NSURLErrorTimedOut,
                     NSURLErrorInternationalRoamingOff, NSURLErrorDataNotAllowed:
                    return (String(localized: "Network problem: check your internet connection."), .network)
                case NSURLErrorCannotFindHost, NSURLErrorCannotConnectToHost, NSURLErrorDNSLookupFailed:
                    return (String(localized: "The provider's server cannot be reached."), .provider)
                default:
                    break
                }
            }
            if candidate.domain == AVFoundationErrorDomain,
               candidate.code == AVError.Code.fileFormatNotRecognized.rawValue
                || candidate.code == AVError.Code.decoderNotFound.rawValue
                || candidate.code == AVError.Code.failedToParse.rawValue {
                return (String(localized: "This stream format is not supported by the built-in player. For Xtream playlists, select the HLS (m3u8) output format in the playlist settings."), .app)
            }
        }
        let description = nsError?.localizedDescription ?? String(localized: "Playback failed.")
        return (description, .provider)
    }

    func retry() {
        guard item != nil else { return }
        note("reconnect")
        keepPositionForFallback()
        retries = 0
        candidateIndex = 0
        playsWhenReady = true
        loadCurrentCandidate()
    }

    // MARK: - Transport

    func play() {
        playsWhenReady = true
        if activeEngine == .vlc {
            playVLC()
            return
        }
        // The item is not ready yet: playback starts with the resume seek.
        if pendingStart != nil && !startSeekSent { return }
        if state == .ended { seek(to: 0) }
        if isLive && !hasLiveWindow {
            // Without a DVR window a paused live stream can only resume at the live edge.
            seekToLiveEdge()
        }
        player.play()
        player.rate = isLive ? 1 : rate
    }

    func pause() {
        playsWhenReady = false
        if activeEngine == .vlc {
            pauseVLC()
        } else {
            player.pause()
        }
        reportProgress(force: true)
    }

    func togglePlayPause() {
        if isPlaying {
            pause()
        } else {
            play()
        }
    }

    /// Moves to `seconds`. A `tolerance` lets AVPlayer stop on a nearby key frame instead of decoding up to the exact
    /// picture: skips and the end of a scrub use one second, which the viewer does not notice.
    func seek(to seconds: Double, tolerance: Double = 0) {
        let target = max(0, duration > 0 ? min(seconds, duration - 1) : seconds)
        noteSeek(from: currentTime, to: target)
        currentTime = target
        // The wait until the picture moves again is not a freeze.
        health.restart()
        if activeEngine == .vlc {
            pendingStart = nil
            seekVLC(to: target)
            return
        }
        if pendingStart != nil && !startSeekSent {
            // Before the item is ready: this becomes the position it starts at.
            pendingStart = target
            return
        }
        pendingStart = nil
        let margin = tolerance > 0 ? CMTime(seconds: tolerance, preferredTimescale: 600) : .zero
        player.seek(to: CMTime(seconds: target, preferredTimescale: 600), toleranceBefore: margin, toleranceAfter: margin) { [weak self] _ in
            Task { @MainActor in self?.updateNowPlaying() }
        }
    }

    func updateNowPlaying() {
        if NowPlayingCenter.shared.isCurrent(self) { NowPlayingCenter.shared.refresh() }
    }
}

extension PlayerController {
    func skip(by seconds: Double) {
        if hasLiveWindow, let liveWindow {
            seek(to: min(max(currentTime + seconds, liveWindow.lowerBound), liveWindow.upperBound))
            return
        }
        guard canSeek else { return }
        seek(to: currentTime + seconds, tolerance: 1)
    }

    func seekToLiveEdge() {
        guard activeEngine == .apple, let range = player.currentItem?.seekableTimeRanges.last?.timeRangeValue else { return }
        player.seek(to: CMTimeRangeGetEnd(range)) { [weak self] _ in
            Task { @MainActor in self?.updateNowPlaying() }
        }
    }

    func stop() {
        reportProgress(force: true)
        pendingStart = nil
        retryTask?.cancel()
        stallTask?.cancel()
        sleepTask?.cancel()
        healthTask?.cancel()
        sleepTimerEnd = nil
        player.pause()
        player.replaceCurrentItem(with: nil)
        stopVLC()
        activeEngine = .apple
        for token in itemObservers { NotificationCenter.default.removeObserver(token) }
        itemObservers = []
        statusObservation = nil
        state = .idle
        item = nil
    }

    private func didPlayToEnd() {
        guard let item else { return }
        if item.isLive {
            // A live stream "ending" means the provider closed it: reconnect.
            retry()
            return
        }
        state = .ended
        currentTime = duration
        reportProgress(force: true)
        onEnded?(item)
    }

    private func tick(_ time: CMTime) {
        guard activeEngine == .apple else { return }
        let seconds = time.seconds
        if let start = pendingStart {
            // The clock is not at the resume point yet: neither shown nor saved.
            guard startSeekSent, seconds.isFinite, seconds >= start - 2 else { return }
            pendingStart = nil
        }
        if seconds.isFinite { currentTime = seconds }
        if let playerItem = player.currentItem {
            let total = playerItem.duration.seconds
            if total.isFinite && total > 0 && total != duration { duration = total }
            // Only true live streams (indefinite duration) have a DVR window; a file served as a channel does not.
            if isLive && playerItem.duration.isIndefinite {
                updateLiveWindow(playerItem)
            } else if liveWindow != nil {
                liveWindow = nil
            }
        }
        reportProgress(force: false)
    }

    private func updateLiveWindow(_ playerItem: AVPlayerItem) {
        guard let range = playerItem.seekableTimeRanges.last?.timeRangeValue else {
            if liveWindow != nil { liveWindow = nil }
            return
        }
        let start = range.start.seconds
        let end = CMTimeRangeGetEnd(range).seconds
        guard start.isFinite, end.isFinite, end >= start else { return }
        // Only publish meaningful changes: the window slides continuously.
        if let liveWindow, abs(liveWindow.lowerBound - start) < 1, abs(liveWindow.upperBound - end) < 1 { return }
        liveWindow = start...end
    }

    private func timeControlStatusChanged(_ status: AVPlayer.TimeControlStatus) {
        guard activeEngine == .apple else { return }
        defer { updateNowPlaying() }
        switch status {
        case .playing:
            isBuffering = false
            if case .failed = state { return }
            state = .playing
            stallTask?.cancel()
        case .paused:
            isBuffering = false
            // Also when the viewer paused while it was still loading (no endless spinner).
            if state == .playing || (state == .loading && !playsWhenReady) { state = .paused }
        case .waitingToPlayAtSpecifiedRate:
            isBuffering = true
            if state == .paused { state = .loading }
            scheduleStallRecovery()
        @unknown default:
            break
        }
    }

    /// A live stream stuck buffering for 12 s gets reconnected.
    private func scheduleStallRecovery() {
        // During AirPlay the TV buffers and this player waits: reconnecting would restart the TV's stream
        // every 12 s (the AirPlay icon blinking, no picture).
        guard isLive, autoRetry, !player.isExternalPlaybackActive else { return }
        stallTask?.cancel()
        stallTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(12))
            guard !Task.isCancelled, let self, self.isBuffering, self.isLive, !self.player.isExternalPlaybackActive else { return }
            self.retry()
        }
    }

    private func reportProgress(force: Bool) {
        guard let item, !item.isLive, pendingStart == nil else { return }
        guard force || Date().timeIntervalSince(lastReportedProgress) > 10 else { return }
        guard currentTime > 0 else { return }
        lastReportedProgress = Date()
        onProgress?(item, currentTime, duration)
    }

    // MARK: - Tracks

    private func loadTracks(for playerItem: AVPlayerItem) async {
        let asset = playerItem.asset
        let audible = try? await asset.loadMediaSelectionGroup(for: .audible)
        let legible = try? await asset.loadMediaSelectionGroup(for: .legible)
        guard playerItem == player.currentItem else { return }
        audioGroup = audible
        subtitleGroup = legible
        audioTracks = Self.tracks(in: audible)
        subtitleTracks = Self.tracks(in: legible)

        // Apply language preferences (kept between episodes and channels).
        if let audible {
            if let language = preferredAudioLanguage,
               let index = audible.options.firstIndex(where: { $0.extendedLanguageTag == language || $0.locale?.language.languageCode?.identifier == language }) {
                playerItem.select(audible.options[index], in: audible)
            }
            selectedAudioTrackID = playerItem.currentMediaSelection.selectedMediaOption(in: audible).flatMap { audible.options.firstIndex(of: $0) }
        }
        if let legible {
            if subtitlesEnabledByDefault, let language = preferredSubtitleLanguage,
               let index = legible.options.firstIndex(where: { $0.extendedLanguageTag == language || $0.locale?.language.languageCode?.identifier == language }) {
                playerItem.select(legible.options[index], in: legible)
            } else if !subtitlesEnabledByDefault {
                playerItem.select(nil, in: legible)
            }
            selectedSubtitleTrackID = playerItem.currentMediaSelection.selectedMediaOption(in: legible).flatMap { legible.options.firstIndex(of: $0) }
        }
    }

    private static func tracks(in group: AVMediaSelectionGroup?) -> [MediaTrack] {
        guard let group else { return [] }
        return group.options.enumerated().map { index, option in
            MediaTrack(id: index, name: option.displayName, languageCode: option.extendedLanguageTag)
        }
    }

    func selectAudioTrack(_ track: MediaTrack) {
        if activeEngine == .vlc {
            selectVLCAudioTrack(track)
            return
        }
        guard let group = audioGroup, track.id < group.options.count else { return }
        let option = group.options[track.id]
        player.currentItem?.select(option, in: group)
        selectedAudioTrackID = track.id
        preferredAudioLanguage = option.extendedLanguageTag
    }

    func selectSubtitleTrack(_ track: MediaTrack?) {
        if activeEngine == .vlc {
            selectVLCSubtitleTrack(track)
            return
        }
        guard let group = subtitleGroup else { return }
        if let track, track.id < group.options.count {
            let option = group.options[track.id]
            player.currentItem?.select(option, in: group)
            selectedSubtitleTrackID = track.id
            preferredSubtitleLanguage = option.extendedLanguageTag
            subtitlesEnabledByDefault = true
        } else {
            player.currentItem?.select(nil, in: group)
            selectedSubtitleTrackID = nil
            subtitlesEnabledByDefault = false
        }
    }

    func loadExternalSubtitles(from url: URL) throws {
        let accessing = url.startAccessingSecurityScopedResource()
        defer { if accessing { url.stopAccessingSecurityScopedResource() } }
        let cues = SubtitleParser.parse(try Data(contentsOf: url))
        externalSubtitles = cues
        externalSubtitleName = cues.isEmpty ? nil : url.lastPathComponent
        if !cues.isEmpty { selectSubtitleTrack(nil) }
    }

    /// A subtitle downloaded from a subtitle service.
    func loadExternalSubtitles(data: Data, name: String) -> Bool {
        let cues = SubtitleParser.parse(data)
        guard !cues.isEmpty else { return false }
        externalSubtitles = cues
        externalSubtitleName = name
        subtitleDelay = 0
        selectSubtitleTrack(nil)
        return true
    }

    func removeExternalSubtitles() {
        externalSubtitles = []
        externalSubtitleName = nil
        subtitleDelay = 0
    }

    var currentExternalCue: SubtitleCue? {
        guard !externalSubtitles.isEmpty else { return nil }
        return SubtitleParser.cue(at: currentTime - subtitleDelay, in: externalSubtitles)
    }

    // MARK: - Video layer, aspect ratio, PiP

    func attach(_ layer: AVPlayerLayer) {
        layer.player = player
        playerLayer = layer
        applyAspect()
        guard allowsPictureInPicture, AVPictureInPictureController.isPictureInPictureSupported(),
              pipController == nil || pipController?.playerLayer !== layer else { return }
        let controller = AVPictureInPictureController(playerLayer: layer)
        controller?.delegate = self
        #if os(iOS)
        controller?.canStartPictureInPictureAutomaticallyFromInline = true
        #endif
        pipController = controller
        pipPossibleObservation = controller?.observe(\.isPictureInPicturePossible, options: [.initial, .new]) { [weak self] observed, _ in
            let possible = observed.isPictureInPicturePossible
            Task { @MainActor in self?.isPictureInPicturePossible = possible }
        }
    }

    private func applyAspect() {
        if activeEngine == .vlc {
            applyVLCAspect()
            return
        }
        guard let playerLayer else { return }
        switch aspect {
        case .fit: playerLayer.videoGravity = .resizeAspect
        case .fill: playerLayer.videoGravity = .resizeAspectFill
        case .stretch: playerLayer.videoGravity = .resize
        }
    }

    func togglePictureInPicture() {
        guard let pipController else { return }
        if pipController.isPictureInPictureActive {
            pipController.stopPictureInPicture()
        } else {
            pipController.startPictureInPicture()
        }
    }

    // MARK: - Sleep timer

    func setSleepTimer(minutes: Int?) {
        sleepTask?.cancel()
        guard let minutes, minutes > 0 else {
            sleepTimerEnd = nil
            return
        }
        let end = Date().addingTimeInterval(Double(minutes) * 60)
        sleepTimerEnd = end
        sleepTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Double(minutes) * 60))
            guard !Task.isCancelled else { return }
            self?.pause()
            self?.sleepTimerEnd = nil
        }
    }
}

// MARK: - Resume

extension PlayerController {
    /// Where a VOD item resumes (positions in the first seconds start from the beginning).
    static func resumeStart(for item: PlaybackItem) -> Double? {
        guard !item.isLive, let start = item.startPosition, start > 5 else { return nil }
        return start
    }

    /// The resume seek of a ready item, with playback started right away unless the viewer paused while it
    /// opened: it starts where the seek lands, after a single buffering. Waiting for the seek to land before
    /// playing does not work: a paused player does not always load anything, so the seek never lands.
    private func seekToPendingStart(_ playerItem: AVPlayerItem) {
        guard let start = pendingStart, !startSeekSent else { return }
        startSeekSent = true
        player.seek(to: CMTime(seconds: start, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .positiveInfinity) { [weak self] _ in
            Task { @MainActor in
                guard let self, playerItem == self.player.currentItem else { return }
                self.pendingStart = nil
                self.updateNowPlaying()
            }
        }
        if playsWhenReady {
            player.play()
            player.rate = rate
        } else {
            state = .paused
        }
    }

    /// Picture in Picture started or stopped (PlayerController+PictureInPicture).
    func pictureInPictureChanged(_ active: Bool) {
        isPictureInPictureActive = active
        onPictureInPictureChange?(active)
    }

    /// A film that fails after a while restarts where it was, not at the position it was opened at.
    private func keepPositionForFallback() {
        guard var current = item, !current.isLive, pendingStart == nil, currentTime > 5 else { return }
        current.startPosition = currentTime
        item = current
    }
}

// MARK: - VLC engine

extension PlayerController {
    private func shouldUseVLC(for url: URL) -> Bool {
        guard PlayerEngine.isVLCAvailable else { return false }
        switch enginePreference {
        case .vlc: return true
        case .apple: return false
        case .automatic: return forceVLCForItem || PlayerEngine.prefersVLC(for: url)
        }
    }

    #if canImport(VLCKitSPM)
    private func vlc() -> VLCBackend {
        if let vlcBackend { return vlcBackend }
        let backend = VLCBackend()
        backend.onEvent = { [weak self] event in self?.handleVLCEvent(event) }
        backend.onTime = { [weak self] current, total in self?.handleVLCTime(current, total) }
        if let vlcDrawable { backend.attach(vlcDrawable) }
        vlcBackend = backend
        return backend
    }

    func attachVLCDrawable(_ view: AnyObject) {
        vlcDrawable = view
        vlcBackend?.attach(view)
    }

    func vlcViewSizeChanged(_ size: CGSize) {
        guard size != vlcViewSize else { return }
        vlcViewSize = size
        if activeEngine == .vlc { applyVLCAspect() }
    }

    private func handleVLCEvent(_ event: VLCBackend.Event) {
        guard activeEngine == .vlc, let backend = vlcBackend else { return }
        switch event {
        case .opening, .buffering:
            isBuffering = true
            if state != .playing && state != .paused { state = .loading }
        case .playing:
            isBuffering = false
            retries = 0
            // A pause made while VLC was opening may have been ignored by it.
            if !playsWhenReady { backend.pause() }
            state = playsWhenReady ? .playing : .paused
            refreshVLCTracks(backend)
            applyVLCAspect()
        case .tracksChanged:
            refreshVLCTracks(backend)
        case .paused:
            isBuffering = false
            state = .paused
        case .ended:
            isBuffering = false
            guard state != .idle else { return }
            if isLive {
                if autoRetry { retry() }
            } else if duration > 0 && currentTime >= duration - 5 {
                state = .ended
                reportProgress(force: true)
                if let item { onEnded?(item) }
            } else {
                handleVLCFailure()
            }
        case .error:
            handleVLCFailure()
        }
        updateNowPlaying()
    }

    /// Tracks and size as VLC sees them now; only what changed is set, so the player's controls are not redrawn
    /// for nothing.
    private func refreshVLCTracks(_ backend: VLCBackend) {
        let audio = backend.audioTracks
        if audio != audioTracks { audioTracks = audio }
        let subtitles = backend.subtitleTracks
        if subtitles != subtitleTracks { subtitleTracks = subtitles }
        let audioID = backend.selectedAudioTrackID
        if audioID != selectedAudioTrackID { selectedAudioTrackID = audioID }
        let subtitleID = backend.selectedSubtitleTrackID
        if subtitleID != selectedSubtitleTrackID { selectedSubtitleTrackID = subtitleID }
        let size = backend.videoSize
        let resolution: CGSize? = size == .zero ? nil : size
        if resolution != videoResolution { videoResolution = resolution }
    }

    private func handleVLCTime(_ current: Double, _ total: Double) {
        guard activeEngine == .vlc else { return }
        if let start = pendingStart {
            guard current < start - 15 else {
                pendingStart = nil
                currentTime = current
                return
            }
            // Still near the beginning: `:start-time` was ignored. Seek once; past that, take what plays.
            if !vlcStartSeekSent {
                vlcStartSeekSent = true
                vlcBackend?.seek(to: start)
                return
            }
            if current < 10 { return }
            pendingStart = nil
        }
        currentTime = current
        if total > 0 && total != duration { duration = total }
        if state == .loading { state = .playing }
        reportProgress(force: false)
    }

    private func handleVLCFailure() {
        if candidateIndex + 1 < candidates.count {
            candidateIndex += 1
            loadCurrentCandidate()
            return
        }
        if autoRetry, isLive, retries < 3 {
            retries += 1
            candidateIndex = 0
            state = .loading
            let delay = Double(retries) * 2
            retryTask = Task { [weak self] in
                try? await Task.sleep(for: .seconds(delay))
                guard !Task.isCancelled else { return }
                self?.loadCurrentCandidate()
            }
            return
        }
        state = .failed(message: String(localized: "The stream could not be opened, even with the VLC engine. It may be offline or blocked by the provider."), attribution: .provider)
    }

    private func startVLC(url: URL, item: PlaybackItem) {
        player.pause()
        player.replaceCurrentItem(with: nil)
        activeEngine = .vlc
        let backend = vlc()
        pendingStart = Self.resumeStart(for: item)
        vlcStartSeekSent = false
        if let pendingStart { currentTime = pendingStart }
        let caching = item.isLive
            ? PlaybackBuffer.seconds(setting: liveBufferSetting, automatic: VLCBackend.liveBuffer.seconds)
            : PlaybackBuffer.seconds(setting: vodBufferSetting, automatic: VLCBackend.filmBuffer.seconds)
        noteAttempt(url, engine: "VLC, buffer \(caching.formatted()) s")
        backend.load(url: url, headers: item.headers, startPosition: item.startPosition, isLive: item.isLive, caching: caching, autoplay: playsWhenReady)
        backend.setRate(item.isLive ? 1 : rate)
        backend.setMuted(isMuted)
        // VLC reports nothing until it plays.
        if !playsWhenReady { state = .paused }
    }

    private func stopVLC() {
        guard let vlcBackend, activeEngine == .vlc else { return }
        activeEngine = .apple
        vlcBackend.stop()
    }

    private func playVLC() {
        if state == .ended { vlcBackend?.seek(to: 0) }
        vlcBackend?.play()
    }

    private func pauseVLC() { vlcBackend?.pause() }
    private func seekVLC(to seconds: Double) { vlcBackend?.seek(to: seconds) }
    private func setVLCRate(_ rate: Float) { vlcBackend?.setRate(rate) }
    private func setVLCMuted(_ muted: Bool) { vlcBackend?.setMuted(muted) }

    private func selectVLCAudioTrack(_ track: MediaTrack) {
        vlcBackend?.selectAudioTrack(track)
        selectedAudioTrackID = track.id
    }

    private func selectVLCSubtitleTrack(_ track: MediaTrack?) {
        vlcBackend?.selectSubtitleTrack(track)
        selectedSubtitleTrackID = track?.id
    }

    private func applyVLCAspect() {
        vlcBackend?.apply(aspect, viewSize: vlcViewSize)
    }
    #else
    private func startVLC(url: URL, item: PlaybackItem) {}
    private func stopVLC() {}
    private func playVLC() {}
    private func pauseVLC() {}
    private func seekVLC(to seconds: Double) {}
    private func setVLCRate(_ rate: Float) {}
    private func setVLCMuted(_ muted: Bool) {}
    private func selectVLCAudioTrack(_ track: MediaTrack) {}
    private func selectVLCSubtitleTrack(_ track: MediaTrack?) {}
    private func applyVLCAspect() {}
    #endif
}
