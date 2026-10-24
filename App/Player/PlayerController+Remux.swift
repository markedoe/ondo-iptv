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
                let source = HTTPByteSource(url: url, headers: headers)
                let remux = try await MatroskaRemux.open(source, preferredLanguages: languages)
                let server = try RemuxServer(remux: remux, source: source)
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
                self.remuxTask = nil
                self.remuxFailed(error)
            }
        }
        return true
        #else
        return false
        #endif
    }

    /// The remux could not start: VLC plays the film, unless the Apple engine is chosen, which cannot open the MKV file
    /// itself (AVPlayer would fail with a message about Xtream's HLS format): a message names the engines that can.
    func remuxFailed(_ error: Error) {
        guard enginePreference == .apple else {
            note("remux not possible (\(error)): VLC plays the film")
            forceVLCForItem = true
            loadCurrentCandidate()
            return
        }
        // Not forced to VLC: a retry tries the remux again, rather than AVPlayer opening the MKV file itself.
        note("remux not possible (\(error)): the Apple engine cannot open the MKV file")
        let message = String(localized: "The Apple engine cannot play this MKV film. Choose Automatic or VLC (More, Player Engine).")
        state = .failed(message: message, attribution: .app)
    }

    /// Ends the remux of the film that played (a new item, a fallback, the player closing): while it opens, cancelling
    /// its task stops its requests; once it plays, the server's `stop()` closes the remux and its source, so that the
    /// provider sends nothing more for it (subscriptions often allow a single connection).
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
            // The file's type only: its name could be the film's title.
            let type = event.uri.flatMap { URL(string: $0)?.pathExtension }.flatMap { $0.isEmpty ? nil : $0 } ?? "-"
            note("AVPlayer access log: \(type) file, \(event.numberOfBytesTransferred) bytes, \(Int(event.indicatedBitrate)) b/s")
        }
    }

    /// The format playing, for Stream Statistics: "MKV", or "MKV, converted for the system player" while the film is
    /// remuxed for AVPlayer.
    var formatDescription: String? {
        guard candidates.indices.contains(candidateIndex), let format = MediaFormat.label(for: candidates[candidateIndex]) else {
            return nil
        }
        #if os(iOS)
        if remuxServer != nil { return String(localized: "\(format), converted for the system player") }
        #endif
        return format
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
