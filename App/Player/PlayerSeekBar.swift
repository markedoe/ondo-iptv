import SwiftUI

/// The position in a film: the only part of the player that reads its clock, so the rest of the controls are not
/// redrawn four times a second. The picture follows the thumb while it moves (`PlayerController.scrub`).
struct PlayerSeekBar: View {
    let controller: PlayerController
    var tint: Color = .white
    /// The thumb is held (true) or released (false): the controls stay while it is held.
    var onScrubbing: (Bool) -> Void = { _ in }
    @State private var scrubPosition: Double?

    var body: some View {
        VStack(spacing: 6) {
            Slider(
                value: Binding(
                    get: { scrubPosition ?? controller.currentTime },
                    set: { position in
                        scrubPosition = position
                        controller.scrub(to: position)
                    }
                ),
                in: 0...max(controller.duration, 1)
            ) { editing in
                if !editing, let position = scrubPosition {
                    controller.endScrub()
                    controller.seek(to: position, tolerance: 1)
                    scrubPosition = nil
                }
                onScrubbing(editing)
            }
            .disabled(!controller.canSeek)
            .tint(tint)
            HStack {
                Text(Formatting.clock(scrubPosition ?? controller.currentTime))
                Spacer()
                if let end = controller.sleepTimerEnd {
                    Label(Formatting.time(end), systemImage: "moon.zzz")
                }
                Spacer()
                Text("-" + Formatting.clock(max(controller.duration - (scrubPosition ?? controller.currentTime), 0)))
            }
            .font(.caption.monospacedDigit())
            .foregroundStyle(.white.opacity(0.8))
        }
    }
}
