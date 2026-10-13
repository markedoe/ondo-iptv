import SwiftUI
import AVFoundation
import IPTVCore

@main
struct OndoApp: App {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase
    #if os(iOS)
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    #endif

    init() {
        Artwork.configurePipeline()
        ReminderNotificationRouter.shared.install()
        #if os(iOS)
        // Keep audio playing with the silent switch on, in the background and in PiP.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback, policy: .longFormVideo)
        CastManager.shared.configure()
        #endif
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .preferredColorScheme(model.settings.appearance.colorScheme)
        }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { model.flush() }
            if phase == .background { model.lockParentalControl() }
        }
        #if os(macOS)
        .defaultSize(width: 1180, height: 760)
        .commands {
            AppCommands(model: model)
            PlaybackCommands()
        }
        #endif

        #if os(macOS)
        WindowGroup("Player", id: "player", for: UUID.self) { $sessionID in
            PlayerWindow(sessionID: sessionID)
                .environment(model)
                .preferredColorScheme(.dark)
        }
        .defaultSize(width: 960, height: 540)
        .windowToolbarStyle(.unifiedCompact(showsTitle: true))
        .commands { PlaybackCommands() }

        Window("Multiview", id: "multiview") {
            MultiviewWindow()
                .environment(model)
                .preferredColorScheme(.dark)
        }
        .defaultSize(width: 1280, height: 720)

        Settings {
            NavigationStack { SettingsView() }
                .environment(model)
                .frame(minWidth: 520, minHeight: 560)
                .preferredColorScheme(model.settings.appearance.colorScheme)
        }
        #endif
    }
}

#if os(macOS)
struct AppCommands: Commands {
    let model: AppModel

    var body: some Commands {
        CommandGroup(after: .newItem) {
            Button("Refresh Playlist") {
                Task { await model.refreshActivePlaylist() }
            }
            .keyboardShortcut("r", modifiers: [.command])
            Button("Refresh TV Guide") {
                model.loadEPG(force: true)
            }
            .keyboardShortcut("r", modifiers: [.command, .shift])
        }
    }
}
#endif
