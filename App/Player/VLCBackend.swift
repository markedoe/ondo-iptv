import SwiftUI
import IPTVCore

/// Which video engine plays a stream.
enum PlayerEngine: String, Codable, CaseIterable, Identifiable {
    /// AVFoundation for what it supports (HLS, MP4, PiP, AirPlay, HDR), VLC for the rest.
    case automatic
    case apple
    case vlc

    var id: String { rawValue }

    var label: LocalizedStringKey {
        switch self {
        case .automatic: return "Automatic"
        case .apple: return "Apple (AVPlayer)"
        case .vlc: return "VLC"
        }
    }

    static var isVLCAvailable: Bool {
        #if canImport(VLCKitSPM)
        return true
        #else
        return false
        #endif
    }

    /// Containers AVFoundation cannot open.
    static let vlcOnlyExtensions: Set<String> = ["mkv", "avi", "wmv", "flv", "divx", "xvid", "mpg", "mpeg", "webm", "ogv", "ogg", "rmvb", "rm", "3gp", "vob", "asf", "m2ts", "mts"]

    static func prefersVLC(for url: URL) -> Bool {
        vlcOnlyExtensions.contains(url.pathExtension.lowercased())
    }
}

#if canImport(VLCKitSPM)
import VLCKitSPM
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

/// Thin Swift wrapper over `VLCMediaPlayer` (VLCKit 3, LGPL).
@MainActor
final class VLCBackend: NSObject {
    enum Event {
        case opening
        case buffering
        case playing
        case paused
        case ended
        case error
    }

    let player = VLCMediaPlayer()
    var onEvent: ((Event) -> Void)?
    var onTime: ((Double, Double) -> Void)?
    private var bridge: VLCDelegateBridge?
    private weak var drawable: AnyObject?

    override init() {
        super.init()
        let bridge = VLCDelegateBridge()
        bridge.onStateChange = { [weak self] in self?.stateChanged() }
        bridge.onTimeChange = { [weak self] in self?.timeChanged() }
        self.bridge = bridge
        player.delegate = bridge
    }

    func attach(_ view: AnyObject) {
        drawable = view
        player.drawable = view
    }

    func load(url: URL, headers: HTTPHeaders, startPosition: Double?, isLive: Bool) {
        let media = VLCMedia(url: url)
        if let userAgent = headers.userAgent, !userAgent.isEmpty {
            media.addOption(":http-user-agent=\(userAgent)")
        }
        if let referrer = headers.referrer, !referrer.isEmpty {
            media.addOption(":http-referrer=\(referrer)")
        }
        media.addOption(":network-caching=\(isLive ? 2000 : 3000)")
        if let startPosition, startPosition > 5, !isLive {
            media.addOption(":start-time=\(Int(startPosition))")
        }
        player.media = media
        player.play()
    }

    func play() { player.play() }
    func pause() { player.pause() }

    func setMuted(_ muted: Bool) {
        player.audio?.isMuted = muted
    }

    func stop() {
        player.stop()
        player.media = nil
    }

    var isPlaying: Bool { player.isPlaying }

    func seek(to seconds: Double) {
        player.time = VLCTime(int: Int32(max(seconds, 0) * 1000))
    }

    func setRate(_ rate: Float) {
        player.rate = rate
    }

    var currentTime: Double { Double(player.time.intValue) / 1000 }
    var duration: Double { Double(player.media?.length.intValue ?? 0) / 1000 }
    var videoSize: CGSize { player.videoSize }

    // MARK: Tracks

    var audioTracks: [MediaTrack] {
        Self.tracks(names: player.audioTrackNames, indexes: player.audioTrackIndexes).filter { $0.id >= 0 }
    }

    var selectedAudioTrackID: Int? {
        let current = Int(player.currentAudioTrackIndex)
        return current >= 0 ? current : nil
    }

    func selectAudioTrack(_ track: MediaTrack) {
        player.currentAudioTrackIndex = Int32(track.id)
    }

    var subtitleTracks: [MediaTrack] {
        Self.tracks(names: player.videoSubTitlesNames, indexes: player.videoSubTitlesIndexes).filter { $0.id >= 0 }
    }

    var selectedSubtitleTrackID: Int? {
        let current = Int(player.currentVideoSubTitleIndex)
        return current >= 0 ? current : nil
    }

    func selectSubtitleTrack(_ track: MediaTrack?) {
        player.currentVideoSubTitleIndex = Int32(track?.id ?? -1)
    }

    private static func tracks(names: [Any], indexes: [Any]) -> [MediaTrack] {
        zip(names, indexes).compactMap { name, index in
            guard let number = (index as? NSNumber)?.intValue else { return nil }
            return MediaTrack(id: number, name: (name as? String) ?? String(localized: "Track \(number)"), languageCode: nil)
        }
    }

    // MARK: Aspect ratio

    func apply(_ aspect: VideoAspect, viewSize: CGSize) {
        clearAspect()
        guard viewSize.width > 0, viewSize.height > 0 else { return }
        let ratio = Self.ratioString(viewSize)
        switch aspect {
        case .fit:
            break
        case .fill:
            player.videoCropGeometry = strdup(ratio)
        case .stretch:
            player.videoAspectRatio = strdup(ratio)
        }
    }

    private func clearAspect() {
        player.videoAspectRatio = nil
        player.videoCropGeometry = nil
    }

    private static func ratioString(_ size: CGSize) -> String {
        "\(Int(size.width.rounded())):\(Int(size.height.rounded()))"
    }

    func addSubtitleFile(_ url: URL) {
        player.addPlaybackSlave(url, type: .subtitle, enforce: true)
    }

    // MARK: Events

    private func stateChanged() {
        switch player.state {
        case .opening: onEvent?(.opening)
        case .buffering: onEvent?(player.isPlaying ? .playing : .buffering)
        case .playing, .esAdded: onEvent?(.playing)
        case .paused: onEvent?(.paused)
        case .ended, .stopped: onEvent?(.ended)
        case .error: onEvent?(.error)
        @unknown default: break
        }
    }

    private func timeChanged() {
        onTime?(currentTime, duration)
    }
}

/// VLCKit calls its delegate on the main thread; this hop keeps the main-actor types clean.
private final class VLCDelegateBridge: NSObject, VLCMediaPlayerDelegate {
    var onStateChange: (() -> Void)?
    var onTimeChange: (() -> Void)?

    func mediaPlayerStateChanged(_ aNotification: Notification) {
        let callback = onStateChange
        Task { @MainActor in callback?() }
    }

    func mediaPlayerTimeChanged(_ aNotification: Notification) {
        let callback = onTimeChange
        Task { @MainActor in callback?() }
    }
}

#if os(iOS)
/// Hosts VLC's video output.
struct VLCVideoSurface: UIViewRepresentable {
    let controller: PlayerController

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .black
        controller.attachVLCDrawable(view)
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        controller.vlcViewSizeChanged(uiView.bounds.size)
    }
}
#elseif os(macOS)
struct VLCVideoSurface: NSViewRepresentable {
    let controller: PlayerController

    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        view.wantsLayer = true
        view.layer?.backgroundColor = NSColor.black.cgColor
        controller.attachVLCDrawable(view)
        return view
    }

    func updateNSView(_ nsView: NSView, context: Context) {
        controller.vlcViewSizeChanged(nsView.bounds.size)
    }
}
#endif
#endif
