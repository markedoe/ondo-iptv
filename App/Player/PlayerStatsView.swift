import SwiftUI
import IPTVCore

/// Over the picture when asked for (More, Stream Statistics): what arrives, what the video needs, the freezes, and
/// where they come from (see `PlaybackHealth`).
struct PlayerStatsView: View {
    let controller: PlayerController

    var body: some View {
        let health = controller.health
        VStack(alignment: .leading, spacing: 4) {
            row("Engine", controller.activeEngine == .vlc ? "VLC" : "AVPlayer")
            if let size = controller.videoResolution {
                row("Video", "\(Int(size.width))×\(Int(size.height))")
            }
            row("Video bitrate", health.contentRate.map(Self.megabits) ?? "–")
            row("Received", health.receivedRate().map(Self.megabits) ?? "–")
            // Seconds of video shown per second of playing: 100 % when nothing stops.
            row("Smoothness", health.pace.map { min($0, 1).formatted(.percent.precision(.fractionLength(0))) } ?? "–")
            row("Freezes", "\(health.freezes.count + (health.isFrozen ? 1 : 0)) · \(health.frozenSeconds.formatted(.number.precision(.fractionLength(1)))) s")
            row("Lost pictures", "\(health.lostPictures)")
            verdict(health.verdict)
                .font(.caption.weight(.semibold))
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 4)
        }
        .font(.caption.monospacedDigit())
        .foregroundStyle(.white)
        .padding(12)
        .frame(maxWidth: 340, alignment: .leading)
        .background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 12))
        .allowsHitTesting(false)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("streamStatistics")
    }

    private func row(_ label: LocalizedStringKey, _ value: String) -> some View {
        HStack(spacing: 8) {
            Text(label).foregroundStyle(.white.opacity(0.7))
            Spacer(minLength: 12)
            Text(verbatim: value)
        }
    }

    @ViewBuilder
    private func verdict(_ verdict: PlaybackHealth.Verdict) -> some View {
        switch verdict {
        case .measuring:
            Text("Measuring…").foregroundStyle(.white.opacity(0.7))
        case .smooth:
            Text("No freeze: the stream plays smoothly.").foregroundStyle(.green)
        case .network:
            Text("The data arrives too slowly or stops: the network or the provider is the cause.").foregroundStyle(.orange)
        case .player:
            Text("The data arrives on time: the freezes or lost pictures come from the player. Try the other engine (More, Player Engine).")
                .foregroundStyle(.yellow)
        }
    }

    /// Bytes per second as megabits per second.
    private static func megabits(_ bytesPerSecond: Double) -> String {
        (bytesPerSecond * 8 / 1_000_000).formatted(.number.precision(.fractionLength(1))) + " Mb/s"
    }
}
