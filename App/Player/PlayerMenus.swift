import SwiftUI
import IPTVCore

/// Audio and subtitle tracks, external subtitle file and delay.
struct PlayerTracksMenu: View {
    let controller: PlayerController
    var onImportSubtitles: () -> Void
    var onSearchSubtitles: () -> Void

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
                    onSearchSubtitles()
                } label: {
                    Label("Find Subtitles Online…", systemImage: "magnifyingglass")
                }
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
    @Environment(AppModel.self) private var model
    let controller: PlayerController
    let item: PlaybackItem?

    var body: some View {
        Menu {
            if controller.canSeek {
                Picker("Speed", selection: Binding(get: { controller.rate }, set: { controller.rate = $0 })) {
                    ForEach([0.5, 0.75, 1.0, 1.25, 1.5, 2.0] as [Float], id: \.self) { rate in
                        // 1.25 as "1.25×" (not "1.2x"), with the viewer's decimal separator.
                        Text(rate == 1 ? String(localized: "Normal") : rate.formatted(.number.precision(.fractionLength(0...2))) + "×").tag(rate)
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
                Text("Video: \(String(Int(size.width)))×\(String(Int(size.height)))")
            }
            Toggle("Stream Statistics", isOn: Binding(get: { controller.showsStatistics }, set: { controller.showsStatistics = $0 }))
            // The address carries the provider login: not under parental control (it could re-add the playlist).
            if let item, !model.isParentalControlActive {
                Button("Copy Stream URL") { Pasteboard.copy(item.streamURL.absoluteString) }
            }
        } label: {
            Image(systemName: "ellipsis.circle")
        }
        .help("More")
        .accessibilityLabel("More")
    }
}
