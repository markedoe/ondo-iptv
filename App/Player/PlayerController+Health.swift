import AVFoundation
import IPTVCore

/// Samples the engine twice a second for `health`: bytes received and consumed, pictures shown and lost, so a freeze
/// can be told apart (network or provider, player or device) and shown in the statistics panel.
extension PlayerController {
    func startHealthMonitoring() {
        healthTask?.cancel()
        let start = Date()
        healthTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(500))
                guard let self, !Task.isCancelled else { return }
                if let sample = self.currentSample(clock: Date().timeIntervalSince(start)) { self.record(sample) }
            }
        }
    }

    /// A new sample. VLC does not report its freezes: once a stall outlasts its buffer the picture stops and the
    /// spinner shows, and a live channel that received nothing for 12 s is reconnected, as with AVPlayer. Only the
    /// start and the end of such a freeze touch `isBuffering`, so the spinner VLC's own events show while opening or
    /// seeking stays.
    func record(_ sample: PlaybackSample) {
        let buffer = engineBuffer
        if health.buffer != buffer { health.buffer = buffer }
        health.add(sample)
        guard activeEngine == .vlc else { return }
        #if canImport(VLCKitSPM)
        // A provider whose stop used up the buffer gets a longer one from the next channel on.
        if isLive { VLCBackend.liveBuffer.note(stall: health.currentStall, buffer: health.buffer) }
        #endif
        let frozen = state == .playing && health.isFrozen
        if frozen != freezeShown {
            freezeShown = frozen
            isBuffering = frozen
        }
        if state == .playing, isLive, autoRetry, health.currentStall >= 12 {
            freezeShown = false
            retry()
        }
    }

    /// Seconds of video the engine holds ahead of the picture: VLC's network caching (its position stands still
    /// during a stall while the picture goes on); AVPlayer's position is the picture's.
    private var engineBuffer: Double {
        #if canImport(VLCKitSPM)
        if activeEngine == .vlc { return vlcBackend?.cachingSeconds ?? 0 }
        #endif
        return 0
    }

    private func currentSample(clock: Double) -> PlaybackSample? {
        let playing = state == .playing
        #if canImport(VLCKitSPM)
        if activeEngine == .vlc {
            guard let backend = vlcBackend, let stats = backend.statistics else { return nil }
            return PlaybackSample(
                clock: clock, mediaTime: playing ? backend.currentTime : nil, receivedBytes: stats.received,
                consumedBytes: stats.consumed, displayedPictures: stats.displayed, lostPictures: stats.lost, isPlaying: playing
            )
        }
        #endif
        guard let item = player.currentItem else { return nil }
        let events = item.accessLog()?.events ?? []
        let received = events.reduce(0) { $0 + Int(max($1.numberOfBytesTransferred, 0)) }
        let lost = events.reduce(0) { $0 + max($1.numberOfDroppedVideoFrames, 0) }
        let time = item.currentTime().seconds
        // AVPlayer counts no bytes consumed: the media played at the stream's bitrate stands for them.
        let bitrate = events.last?.indicatedBitrate ?? 0
        let consumed = bitrate > 0 && time.isFinite ? Int(bitrate / 8 * time) : 0
        return PlaybackSample(
            clock: clock, mediaTime: playing && time.isFinite ? time : nil, receivedBytes: received,
            consumedBytes: consumed, displayedPictures: 0, lostPictures: lost, isPlaying: playing
        )
    }
}
