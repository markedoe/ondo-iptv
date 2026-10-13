import SwiftUI

/// Exposes the player of the focused window to the menu bar (macOS) and keyboard shortcuts.
struct FocusedPlaybackSessionKey: FocusedValueKey {
    typealias Value = PlaybackSession
}

extension FocusedValues {
    var playbackSession: PlaybackSession? {
        get { self[FocusedPlaybackSessionKey.self] }
        set { self[FocusedPlaybackSessionKey.self] = newValue }
    }
}

#if os(macOS)
struct PlaybackCommands: Commands {
    @FocusedValue(\.playbackSession) private var session

    var body: some Commands {
        CommandMenu("Playback") {
            Button(session?.controller.isPlaying == true ? "Pause" : "Play") {
                session?.togglePlayPause()
            }
            .keyboardShortcut("p", modifiers: [.command])
            .disabled(session == nil)

            Divider()

            Button("Next Channel") { session?.zap(1) }
                .keyboardShortcut(.downArrow, modifiers: [.command])
                .disabled(session == nil || (session?.queue.count ?? 0) < 2)
            Button("Previous Channel") { session?.zap(-1) }
                .keyboardShortcut(.upArrow, modifiers: [.command])
                .disabled(session == nil || (session?.queue.count ?? 0) < 2)
            Button("Last Channel") { session?.returnToPreviousChannel() }
                .keyboardShortcut(.delete, modifiers: [.command])
                .disabled(session?.canReturnToPreviousChannel != true)
            Button("Back to Live") { session?.returnToLive() }
                .keyboardShortcut("l", modifiers: [.command, .shift])
                .disabled(session?.isTimeshifted != true)

            Divider()

            Button("Skip Forward 10 s") { session?.controller.skip(by: 10) }
                .keyboardShortcut(.rightArrow, modifiers: [.command])
                .disabled(session?.controller.canSkip != true)
            Button("Skip Back 10 s") { session?.controller.skip(by: -10) }
                .keyboardShortcut(.leftArrow, modifiers: [.command])
                .disabled(session?.controller.canSkip != true)

            Divider()

            Button("Picture in Picture") { session?.controller.togglePictureInPicture() }
                .keyboardShortcut("i", modifiers: [.command, .shift])
                .disabled(session?.controller.canUsePictureInPicture != true)
            Button("Next Aspect Ratio") {
                if let controller = session?.controller { controller.aspect = controller.aspect.next }
            }
            .keyboardShortcut("a", modifiers: [.command, .shift])
            .disabled(session == nil)
            Button(session?.controller.isMuted == true ? "Unmute" : "Mute") {
                session?.controller.isMuted.toggle()
            }
            .keyboardShortcut("m", modifiers: [.command, .shift])
            .disabled(session == nil)
        }
    }
}
#endif
