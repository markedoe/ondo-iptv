import AVFoundation
import IPTVCore

/// MKV films through the system player: their tracks are copied into HLS made of fragmented MP4 (no transcoding,
/// `MatroskaRemux`) and served on 127.0.0.1 (`RemuxServer`), so that HDR10, HLG and Dolby Vision show, Dolby Atmos
/// plays and Picture in Picture works. VLC plays the film when the system player cannot: a codec it does not decode
/// (DTS, TrueHD…), a file without index, a server that does not send parts of the file, or an error while playing.
extension PlayerController {
    /// Starts the remux of an MKV film when it applies; false lets VLC play it.
    func startRemux(url: URL, item: PlaybackItem) -> Bool {
        guard enginePreference != .vlc, !forceVLCForItem, !item.isLive, !item.isCatchup, url.pathExtension.lowercased() == "mkv",
              !(isExternalPlaybackActive || player.isExternalPlaybackActive) else { return false }
        player.pause()
        player.replaceCurrentItem(with: nil)
        noteAttempt(url, engine: "the system player (MKV remuxed)")
        let headers = item.headers.dictionary
        let languages = [preferredAudioLanguage].compactMap { $0 } + Locale.preferredLanguages
        let opened = clock
        remuxTask = Task { [weak self] in
            do {
                let remux = try await MatroskaRemux.open(HTTPByteSource(url: url, headers: headers), preferredLanguages: languages)
                let server = try RemuxServer(remux: remux)
                let address = try await server.start()
                guard let self, !Task.isCancelled, self.item?.id == item.id else {
                    server.stop()
                    return
                }
                server.onFailure = { [weak self] failure in
                    Task { @MainActor in self?.note("remux: \(failure)") }
                }
                self.remuxServer = server
                let codecs = remux.tracks.map { FragmentWriter.codecName($0.codec) }.joined(separator: ", ")
                let wait = String(format: "%.1f", self.clock - opened)
                self.note("remux ready after \(wait) s: \(codecs), \(remux.videoRange), \(remux.segments.count) segments")
                // The playlist lives on this device: a TV reached by AirPlay could not read it.
                self.player.allowsExternalPlayback = false
                self.startApple(url: address, headers: [:])
            } catch {
                guard let self, !Task.isCancelled, self.item?.id == item.id else { return }
                self.note("remux not possible (\(error)): VLC plays the film")
                self.remuxTask = nil
                self.forceVLCForItem = true
                self.loadCurrentCandidate()
            }
        }
        return true
    }

    /// Ends the remux of the film that played (a new item, a fallback, the player closing).
    func stopRemux() {
        remuxTask?.cancel()
        remuxTask = nil
        remuxServer?.stop()
        remuxServer = nil
        player.allowsExternalPlayback = true
    }

    /// The engine as the problem report names it.
    var engineName: String {
        if activeEngine == .vlc { return "VLC" }
        return remuxServer == nil ? "AVPlayer" : "AVPlayer (MKV remuxed)"
    }
}
