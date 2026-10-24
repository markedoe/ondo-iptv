import SwiftUI
import IPTVCore
import Observation
#if canImport(GoogleCast)
import GoogleCast
#endif

/// Chromecast support through the official Google Cast SDK (iOS only; macOS casts with AirPlay).
///
/// The receiver downloads the stream itself, so custom HTTP headers are lost and only formats the
/// Default Media Receiver understands (HLS, MP4, WebM…) play. `castability(of:)` explains limits up front.
@MainActor
@Observable
final class CastManager {
    static let shared = CastManager()

    enum Castability: Equatable {
        case ok(URL)
        case warning(URL, String)
        case impossible(String)
    }

    private(set) var isAvailable = false
    private(set) var isConnected = false
    private(set) var deviceName: String?
    private(set) var isPlaying = false
    private(set) var castingItemID: String?
    var lastError: String?

    #if canImport(GoogleCast)
    @ObservationIgnored private var listener: CastSessionListener?
    #endif

    /// Picks the URL a Chromecast is most likely to play and explains the caveats.
    nonisolated static func castability(of item: PlaybackItem) -> Castability {
        let candidates = item.candidateURLs.filter { !$0.isFileURL }
        guard !candidates.isEmpty else {
            return .impossible(String(localized: "Local files can't be sent to a Chromecast. Use AirPlay instead."))
        }
        let castable = ["m3u8", "mp4", "m4v", "webm", "mp3", "aac", "mpd"]
        let best = candidates.first { castable.contains($0.pathExtension.lowercased()) }
            ?? candidates.first { $0.pathExtension.isEmpty }
        guard let url = best else {
            return .impossible(String(localized: "This stream format (TS, MKV, AVI…) can't be played by a Chromecast. Use AirPlay, or choose the HLS (m3u8) format for Xtream playlists."))
        }
        if !item.headers.isEmpty {
            return .warning(url, String(localized: "This provider requires custom headers that a Chromecast can't send. The stream may not play."))
        }
        if url.scheme?.lowercased() == "http" && url.pathExtension.lowercased() == "m3u8" {
            return .warning(url, String(localized: "Some Chromecast devices refuse unencrypted (HTTP) live streams. If nothing plays, use AirPlay."))
        }
        return .ok(url)
    }

    nonisolated static func contentType(for url: URL) -> String {
        switch url.pathExtension.lowercased() {
        case "m3u8": return "application/x-mpegURL"
        case "mpd": return "application/dash+xml"
        case "webm": return "video/webm"
        case "mp3": return "audio/mpeg"
        case "aac": return "audio/aac"
        default: return "video/mp4"
        }
    }

    func configure() {
        #if canImport(GoogleCast)
        guard !isAvailable else { return }
        let criteria = GCKDiscoveryCriteria(applicationID: kGCKDefaultMediaReceiverApplicationID)
        let options = GCKCastOptions(discoveryCriteria: criteria)
        options.physicalVolumeButtonsWillControlDeviceVolume = true
        // Do not trigger the local-network permission prompt before the user asks to cast.
        options.startDiscoveryAfterFirstTapOnCastButton = true
        // No analytics: keep the SDK's optional diagnostic logging off (see the App Privacy answers in docs/RELEASE.md).
        options.disableAnalyticsLogging = true
        GCKCastContext.setSharedInstanceWith(options)
        GCKCastContext.sharedInstance().useDefaultExpandedMediaControls = false
        let listener = CastSessionListener(manager: self)
        GCKCastContext.sharedInstance().sessionManager.add(listener)
        self.listener = listener
        isAvailable = true
        #endif
    }

    /// Sends the item to the connected receiver. Returns `false` when nothing is connected.
    @discardableResult
    func cast(_ item: PlaybackItem, from position: Double?) -> Bool {
        #if canImport(GoogleCast)
        guard let client = GCKCastContext.sharedInstance().sessionManager.currentCastSession?.remoteMediaClient else { return false }
        let url: URL
        switch Self.castability(of: item) {
        case .ok(let best):
            url = best
            lastError = nil
        case .warning(let best, let message):
            url = best
            lastError = message
        case .impossible(let message):
            lastError = message
            return false
        }
        let metadata = GCKMediaMetadata(metadataType: item.isLive ? .generic : .movie)
        metadata.setString(item.title, forKey: kGCKMetadataKeyTitle)
        if let subtitle = item.subtitle { metadata.setString(subtitle, forKey: kGCKMetadataKeySubtitle) }
        if let artwork = item.artworkURL { metadata.addImage(GCKImage(url: artwork, width: 480, height: 270)) }
        let builder = GCKMediaInformationBuilder(contentURL: url)
        builder.streamType = item.isLive ? .live : .buffered
        builder.contentType = Self.contentType(for: url)
        builder.metadata = metadata
        let options = GCKMediaLoadOptions()
        options.autoplay = true
        if let position, position > 0, !item.isLive { options.playPosition = position }
        client.loadMedia(builder.build(), with: options)
        castingItemID = item.id
        isPlaying = true
        return true
        #else
        return false
        #endif
    }

    func togglePlayPause() {
        #if canImport(GoogleCast)
        guard let client = GCKCastContext.sharedInstance().sessionManager.currentCastSession?.remoteMediaClient else { return }
        if isPlaying { client.pause() } else { client.play() }
        isPlaying.toggle()
        #endif
    }

    func seek(by seconds: Double) {
        #if canImport(GoogleCast)
        guard let client = GCKCastContext.sharedInstance().sessionManager.currentCastSession?.remoteMediaClient else { return }
        let options = GCKMediaSeekOptions()
        options.relative = true
        options.interval = seconds
        client.seek(with: options)
        #endif
    }

    /// Stops casting; returns the receiver's position so local playback can resume there.
    @discardableResult
    func stopCasting() -> Double? {
        #if canImport(GoogleCast)
        let position = GCKCastContext.sharedInstance().sessionManager.currentCastSession?.remoteMediaClient?.approximateStreamPosition()
        GCKCastContext.sharedInstance().sessionManager.endSessionAndStopCasting(true)
        castingItemID = nil
        return position
        #else
        return nil
        #endif
    }

    /// The receiver's own state: paused from the TV's remote or another phone, or finished.
    fileprivate func receiverChanged(playing: Bool) {
        isPlaying = playing
    }

    fileprivate func sessionChanged(connected: Bool, name: String?) {
        isConnected = connected
        deviceName = connected ? name : nil
        if !connected {
            castingItemID = nil
            isPlaying = false
        }
    }
}

#if canImport(GoogleCast)
private final class CastSessionListener: NSObject, GCKSessionManagerListener, GCKRemoteMediaClientListener {
    weak var manager: CastManager?

    init(manager: CastManager) {
        self.manager = manager
    }

    func sessionManager(_ sessionManager: GCKSessionManager, didStart session: GCKCastSession) {
        session.remoteMediaClient?.add(self)
        let name = session.device.friendlyName
        Task { @MainActor in self.manager?.sessionChanged(connected: true, name: name) }
    }

    func sessionManager(_ sessionManager: GCKSessionManager, didResumeCastSession session: GCKCastSession) {
        session.remoteMediaClient?.add(self)
        let name = session.device.friendlyName
        Task { @MainActor in self.manager?.sessionChanged(connected: true, name: name) }
    }

    func sessionManager(_ sessionManager: GCKSessionManager, didEnd session: GCKCastSession, withError error: Error?) {
        session.remoteMediaClient?.remove(self)
        let message = error?.localizedDescription
        Task { @MainActor in
            self.manager?.sessionChanged(connected: false, name: nil)
            if let message { self.manager?.lastError = message }
        }
    }

    func sessionManager(_ sessionManager: GCKSessionManager, didFailToStart session: GCKCastSession, withError error: Error) {
        let message = error.localizedDescription
        Task { @MainActor in self.manager?.lastError = message }
    }

    func remoteMediaClient(_ client: GCKRemoteMediaClient, didUpdate mediaStatus: GCKMediaStatus?) {
        guard let state = mediaStatus?.playerState else { return }
        let playing = state == .playing || state == .buffering || state == .loading
        Task { @MainActor in self.manager?.receiverChanged(playing: playing) }
    }
}

/// The system-styled Cast button (discovery, device picker, connection state).
struct CastButton: UIViewRepresentable {
    var tint: UIColor = .white

    func makeUIView(context: Context) -> GCKUICastButton {
        let button = GCKUICastButton(frame: CGRect(x: 0, y: 0, width: 30, height: 30))
        button.tintColor = tint
        return button
    }

    func updateUIView(_ uiView: GCKUICastButton, context: Context) {}
}
#endif
