import Foundation
import MediaPlayer
import IPTVCore

/// Lock screen / Control Center / macOS "Now Playing" integration and media keys.
@MainActor
final class NowPlayingCenter {
    static let shared = NowPlayingCenter()

    private weak var session: PlaybackSession?
    private var commandsInstalled = false
    private var artworkTask: Task<Void, Never>?
    private var artworkURL: URL?
    private var artwork: MPMediaItemArtwork?

    func activate(_ session: PlaybackSession) {
        self.session = session
        installCommands()
        refresh()
    }

    func isCurrent(_ controller: PlayerController) -> Bool {
        session?.controller === controller
    }

    func deactivate(_ session: PlaybackSession) {
        guard self.session === session else { return }
        self.session = nil
        artworkTask?.cancel()
        artworkTask = nil
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        #if os(macOS)
        MPNowPlayingInfoCenter.default().playbackState = .stopped
        #endif
    }

    /// Rebuilds the info dictionary (cheap; the system extrapolates elapsed time from the rate).
    func refresh() {
        // During a replay, the programme played (the live channel stays `current` for the guide and zapping).
        guard let session, let item = session.playingItem else { return }
        let controller = session.controller
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: item.title,
            MPNowPlayingInfoPropertyIsLiveStream: item.isLive,
            MPNowPlayingInfoPropertyPlaybackRate: controller.isPlaying ? Double(item.isLive ? 1 : controller.rate) : 0.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.video.rawValue,
        ]
        if let subtitle = item.subtitle { info[MPMediaItemPropertyArtist] = subtitle }
        if !item.isLive && controller.duration > 0 {
            info[MPMediaItemPropertyPlaybackDuration] = controller.duration
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = controller.currentTime
        }
        if let artwork, artworkURL == item.artworkURL {
            info[MPMediaItemPropertyArtwork] = artwork
        } else if artworkURL != item.artworkURL || artworkTask == nil {
            // A logo still loading keeps loading: restarting it at each refresh, it never arrived.
            loadArtwork(item.artworkURL)
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        #if os(macOS)
        MPNowPlayingInfoCenter.default().playbackState = controller.isPlaying ? .playing : .paused
        #endif

        let center = MPRemoteCommandCenter.shared()
        center.changePlaybackPositionCommand.isEnabled = controller.canSeek
        center.skipForwardCommand.isEnabled = controller.canSeek
        center.skipBackwardCommand.isEnabled = controller.canSeek
        center.nextTrackCommand.isEnabled = session.queue.count > 1
        center.previousTrackCommand.isEnabled = session.queue.count > 1
    }

    private func loadArtwork(_ url: URL?) {
        artworkTask?.cancel()
        artworkURL = url
        artwork = nil
        guard let url else { return }
        artworkTask = Task { [weak self] in
            guard let image = await Artwork.image(for: url, maxPixelSize: 600), !Task.isCancelled else { return }
            self?.artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            self?.refresh()
        }
    }

    private func installCommands() {
        guard !commandsInstalled else { return }
        commandsInstalled = true
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] _ in
            self?.session?.play()
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            self?.session?.pause()
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            self?.session?.togglePlayPause()
            return .success
        }
        center.nextTrackCommand.addTarget { [weak self] _ in
            self?.session?.zap(1)
            return .success
        }
        center.previousTrackCommand.addTarget { [weak self] _ in
            self?.session?.zap(-1)
            return .success
        }
        center.skipForwardCommand.preferredIntervals = [30]
        center.skipForwardCommand.addTarget { [weak self] _ in
            self?.session?.controller.skip(by: 30)
            return .success
        }
        center.skipBackwardCommand.preferredIntervals = [10]
        center.skipBackwardCommand.addTarget { [weak self] _ in
            self?.session?.controller.skip(by: -10)
            return .success
        }
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            self?.session?.controller.seek(to: event.positionTime, tolerance: 1)
            return .success
        }
    }
}
