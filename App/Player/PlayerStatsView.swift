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
            if let format = controller.formatDescription {
                row("Format", format)
            }
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

/// When the title data is worked out again: the statistics shown or hidden, another title played, the catalogue read.
struct TitleDataKey: Equatable {
    var shown: Bool
    var item: String?
    var catalogue: Int
}

/// Next to the statistics (More, Stream Statistics): what the provider calls the title and its versions (`TitleData`):
/// at most `TitleData.panelVersions` versions, the one playing first, then a line counting the others.
struct TitleDataView: View {
    let data: TitleData

    var body: some View {
        let shown = Array(data.versions.sorted { $0.playing && !$1.playing }.prefix(TitleData.panelVersions))
        VStack(alignment: .leading, spacing: 2) {
            Text("Title data").fontWeight(.semibold)
            ForEach(Array(data.lines.enumerated()), id: \.offset) { _, line in
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    label(line.label)
                        .foregroundStyle(.white.opacity(0.7))
                        .frame(minWidth: 96, alignment: .leading)
                    Text(verbatim: line.value).lineLimit(2)
                }
            }
            if !shown.isEmpty {
                Text("Versions").fontWeight(.semibold).padding(.top, 4)
                ForEach(shown) { version in
                    versionRows(version)
                }
                if data.versions.count > shown.count {
                    Text("More versions: \(data.versions.count - shown.count)").foregroundStyle(.white.opacity(0.7))
                }
                if let reason = data.reason {
                    reasonText(reason).foregroundStyle(.white.opacity(0.7)).padding(.top, 4)
                }
            }
        }
        .font(.caption.monospacedDigit())
        .foregroundStyle(.white)
        .padding(12)
        .frame(maxWidth: 420, alignment: .leading)
        .background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 12))
        .allowsHitTesting(false)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("titleData")
    }

    @ViewBuilder
    private func label(_ label: TitleData.Label) -> some View {
        switch label {
        case .shown: Text("Displayed title")
        case .tags: Text("Tags")
        case .raw(let key): Text(verbatim: key)
        }
    }

    /// Its chip and whether it plays; its name as the provider writes it; its category, id, container and tags.
    private func versionRows(_ version: TitleData.Version) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            HStack(spacing: 6) {
                if let chip = version.chip {
                    Text(verbatim: chip)
                } else {
                    Text("Hidden category: not offered")
                }
                if version.playing {
                    Text(verbatim: "· ▶")
                    Text("Playing")
                }
                if version.first {
                    Text(verbatim: "· ★")
                    Text("Plays first")
                }
                // One chip with the version it names: the same film, filed elsewhere by the provider.
                if let sameAs = version.sameAs {
                    Text(verbatim: "· =")
                    Text("Same as \(sameAs)")
                }
            }
            .fontWeight(.semibold)
            .foregroundStyle(version.playing ? Color.green : Color.white)
            Text(verbatim: version.name).lineLimit(2)
            Text(verbatim: [version.category, version.shownID.map { "id \($0)" }, version.container, version.tags].compactMap { $0 }.joined(separator: "  ·  "))
                .lineLimit(2)
                .foregroundStyle(.white.opacity(0.7))
        }
        .padding(.top, 4)
    }

    @ViewBuilder
    private func reasonText(_ reason: DefaultReason) -> some View {
        switch reason {
        case .only: EmptyView()
        case .inProgress: Text("Plays first because it is in progress.")
        case .chosen: Text("Plays first because it was chosen last on this device.")
        case .bestForScreen: Text("Plays first because it suits this screen best.")
        case .first: Text("Plays first because nothing sets the versions apart: it comes first in the catalogue.")
        }
    }
}
