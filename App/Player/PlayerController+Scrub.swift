import AVFoundation
import IPTVCore

/// While the thumb of the seek bar moves, the picture follows it: one seek at a time to the nearest key frame,
/// the latest position waiting for the one on its way (the "chase" Apple describes in QA1820). The exact
/// position is sought when the thumb is released (`seek(to:tolerance:)`).
extension PlayerController {
    func scrub(to seconds: Double) {
        guard canSeek else { return }
        scrubTarget = max(0, min(seconds, duration - 1))
        if !scrubInFlight { sendScrub() }
    }

    /// The thumb was released: what is still waiting is dropped, the final seek follows.
    func endScrub() {
        scrubTarget = nil
    }

    private func sendScrub() {
        guard let target = scrubTarget else { return }
        scrubTarget = nil
        scrubInFlight = true
        if activeEngine == .vlc {
            #if canImport(VLCKitSPM)
            vlcBackend?.seek(to: target)
            #endif
            // VLC does not say when a seek is done: a few a second keep its demuxer from being swamped.
            Task { [weak self] in
                try? await Task.sleep(for: .milliseconds(250))
                self?.scrubFinished()
            }
            return
        }
        let time = CMTime(seconds: target, preferredTimescale: 600)
        player.seek(to: time, toleranceBefore: .positiveInfinity, toleranceAfter: .positiveInfinity) { [weak self] _ in
            Task { @MainActor in self?.scrubFinished() }
        }
    }

    private func scrubFinished() {
        scrubInFlight = false
        sendScrub()
    }
}

extension PlayerController {
    /// Where a VOD item resumes (positions in the first seconds start from the beginning).
    static func resumeStart(for item: PlaybackItem) -> Double? {
        guard !item.isLive, let start = item.startPosition, start > 5 else { return nil }
        return start
    }
}
