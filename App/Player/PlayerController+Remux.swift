import AVFoundation
import IPTVCore

/// MKV films through the system player on iPhone and iPad: their tracks are copied into HLS made of fragmented MP4 (no
/// transcoding, `MatroskaRemux`) and served on 127.0.0.1 (`RemuxServer`), so that HDR10, HLG and Dolby Vision show,
/// Dolby Atmos plays and Picture in Picture works. VLC plays the film when the system player cannot: a codec it does
/// not decode (DTS, TrueHD…), a file without index, a server that does not send parts of the file, or an error while
/// playing. On the Mac, VLC plays MKV films: listening on 127.0.0.1 would need the sandbox's network.server
/// entitlement, which App Review refuses to a player (CLAUDE.md, App Store).
extension PlayerController {
    /// Starts the remux of an MKV film when it applies; false lets VLC play it.
    func startRemux(url: URL, item: PlaybackItem) -> Bool {
        #if os(iOS)
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
                server.onRequest = { [weak self] request in
                    Task { @MainActor in self?.note("remux served \(request)") }
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
        #else
        return false
        #endif
    }

    /// Ends the remux of the film that played (a new item, a fallback, the player closing).
    func stopRemux() {
        remuxTask?.cancel()
        remuxTask = nil
        #if os(iOS)
        remuxServer?.stop()
        remuxServer = nil
        player.allowsExternalPlayback = true
        #endif
    }

    /// What AVPlayer said when it failed, for the log: its error, the one beneath, and the last entries of the item's
    /// error log (their addresses left out).
    func noteAppleFailure(_ error: Error?, item: AVPlayerItem) {
        guard let error = error as NSError? else {
            note("AVPlayer failed without an error")
            return
        }
        var line = "AVPlayer failed: \(error.domain) \(error.code), \(error.localizedDescription)"
        if let underlying = error.userInfo[NSUnderlyingErrorKey] as? NSError {
            line += " (\(underlying.domain) \(underlying.code), \(underlying.localizedDescription))"
        }
        note(line)
        for event in (item.errorLog()?.events ?? []).suffix(3) {
            note("AVPlayer error log: \(event.errorDomain) \(event.errorStatusCode), \(event.errorComment ?? "")")
        }
        for event in (item.accessLog()?.events ?? []).suffix(3) {
            let file = event.uri.flatMap { URL(string: $0)?.lastPathComponent } ?? "-"
            note("AVPlayer access log: \(file), \(event.numberOfBytesTransferred) bytes, \(Int(event.indicatedBitrate)) b/s")
        }
    }

    /// The engine as the problem report names it.
    var engineName: String {
        if activeEngine == .vlc { return "VLC" }
        #if os(iOS)
        if remuxServer != nil { return "AVPlayer (MKV remuxed)" }
        #endif
        return "AVPlayer"
    }
}
