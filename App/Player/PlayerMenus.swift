import SwiftUI
import IPTVCore

/// Audio and subtitle tracks, external subtitle file and delay.
struct PlayerTracksMenu: View {
    let controller: PlayerController
    var onImportSubtitles: () -> Void

    var body: some View {
        Menu {
            if !controller.audioTracks.isEmpty {
                Section("Audio") {
                    ForEach(controller.audioTracks) { track in
                        Button {
                            controller.selectAudioTrack(track)
                        } label: {
                            if controller.selectedAudioTrackID == track.id {
                                Label(track.name, systemImage: "checkmark")
                            } else {
                                Text(track.name)
                            }
                        }
                    }
                }
            }
            Section("Subtitles") {
                Button {
                    onImportSubtitles()
                } label: {
                    Label("Load Subtitle File…", systemImage: "doc.badge.plus")
                }
                if let name = controller.externalSubtitleName {
                    Button {
                        controller.removeExternalSubtitles()
                    } label: {
                        Label("Remove \(name)", systemImage: "xmark.circle")
                    }
                    Menu("Subtitle Delay: \(String(format: "%+.1f s", controller.subtitleDelay))") {
                        Button("+0.5 s") { controller.subtitleDelay += 0.5 }
                        Button("−0.5 s") { controller.subtitleDelay -= 0.5 }
                        Button("Reset") { controller.subtitleDelay = 0 }
                    }
                }
                Button {
                    controller.selectSubtitleTrack(nil)
                } label: {
                    if controller.selectedSubtitleTrackID == nil {
                        Label("Off", systemImage: "checkmark")
                    } else {
                        Text("Off")
                    }
                }
                ForEach(controller.subtitleTracks) { track in
                    Button {
                        controller.selectSubtitleTrack(track)
                    } label: {
                        if controller.selectedSubtitleTrackID == track.id {
                            Label(track.name, systemImage: "checkmark")
                        } else {
                            Text(track.name)
                        }
                    }
                }
            }
        } label: {
            Image(systemName: "captions.bubble")
        }
        .help("Audio & subtitles")
        .accessibilityLabel("Audio & subtitles")
    }
}

/// Speed, aspect ratio, sleep timer, engine and stream information.
struct PlayerMoreMenu: View {
    let controller: PlayerController
    let item: PlaybackItem?

    var body: some View {
        Menu {
            if controller.canSeek {
                Picker("Speed", selection: Binding(get: { controller.rate }, set: { controller.rate = $0 })) {
                    ForEach([0.5, 0.75, 1.0, 1.25, 1.5, 2.0] as [Float], id: \.self) { rate in
                        Text(rate == 1 ? String(localized: "Normal") : String(format: "%.2gx", rate)).tag(rate)
                    }
                }
            }
            Picker("Aspect ratio", selection: Binding(get: { controller.aspect }, set: { controller.aspect = $0 })) {
                ForEach(VideoAspect.allCases) { aspect in
                    Text(aspect.label).tag(aspect)
                }
            }
            Menu("Sleep Timer") {
                Button("Off") { controller.setSleepTimer(minutes: nil) }
                ForEach([15, 30, 45, 60, 90, 120], id: \.self) { minutes in
                    Button("\(minutes) min") { controller.setSleepTimer(minutes: minutes) }
                }
            }
            if PlayerEngine.isVLCAvailable {
                Picker("Player Engine", selection: Binding(
                    get: { controller.activeEngine == .vlc ? PlayerEngine.vlc : PlayerEngine.apple },
                    set: { controller.switchEngine(to: $0) }
                )) {
                    Text("Apple (AVPlayer)").tag(PlayerEngine.apple)
                    Text("VLC").tag(PlayerEngine.vlc)
                }
            }
            if let size = controller.videoResolution {
                Text("Video: \(Int(size.width))×\(Int(size.height))")
            }
            if let item {
                Button("Copy Stream URL") { Pasteboard.copy(item.streamURL.absoluteString) }
            }
        } label: {
            Image(systemName: "ellipsis.circle")
        }
        .help("More")
        .accessibilityLabel("More")
    }
}
