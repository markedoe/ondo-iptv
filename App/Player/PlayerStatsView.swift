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

/// When the title data is worked out again: the statistics shown or hidden, another title played, the catalogue read,
/// and what is known of the streams (the player's reading of this one, a version's details, a failed request).
struct TitleDataKey: Equatable {
    var shown: Bool
    var item: String?
    var catalogue: Int
    var measured = 0
    var details = 0
    var failures = 0
}

/// Next to the statistics (More, Stream Statistics): what the provider calls the title and its versions (`TitleData`):
/// at most `TitleData.panelVersions` versions, the one playing first, then a line counting the others, then what the app
/// read of this screen. Never taller than the room it is given: when it does not fit (an iPhone in landscape, a small
/// window), it scrolls (a swipe, the trackpad, the scroll wheel) instead of running past the bottom of the screen.
struct TitleDataView: View {
    let data: TitleData

    var body: some View {
        ViewThatFits(in: .vertical) {
            // Touches go through to the player while it fits.
            rows.allowsHitTesting(false)
            ScrollView(.vertical) { rows }
                .scrollBounceBehavior(.basedOnSize)
        }
        .font(.caption.monospacedDigit())
        .foregroundStyle(.white)
        .frame(maxWidth: 420, alignment: .leading)
        .background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("titleData")
    }

    private var rows: some View {
        let shown = Array(data.versions.sorted { $0.playing && !$1.playing }.prefix(TitleData.panelVersions))
        return VStack(alignment: .leading, spacing: 2) {
            Text("Title data").fontWeight(.semibold)
            ForEach(Array(data.lines.enumerated()), id: \.offset) { _, line in
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    label(line.label)
                        .foregroundStyle(.white.opacity(0.7))
                        .frame(minWidth: 96, alignment: .leading)
                    Text(verbatim: line.value).lineLimit(2)
                }
            }
            // The versions before the screen: what a look at the panel is for.
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
            screenRows
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func label(_ label: TitleData.Label) -> some View {
        switch label {
        case .shown: Text("Displayed title")
        case .tags: Text("Tags")
        case .raw(let key): Text(verbatim: key)
        case .specs(let source): specSourceText(source)
        }
    }

    /// Where what a stream is made of comes from.
    private func specSourceText(_ source: SpecSource?) -> Text {
        switch source {
        case .measured: Text("Measured by the player")
        case .provider: Text("From the provider")
        case nil: Text("From the name")
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
            // What its stream is made of, and where that comes from (measured, the provider's, its name).
            (specSourceText(version.specSource) + Text(verbatim: ": " + version.specs))
                .lineLimit(2)
                .foregroundStyle(.white.opacity(0.7))
            // What its provider's details held (or how asking failed), whether they are kept, the names of their fields.
            (Text("Provider info: \(providerText(version))") + Text(verbatim: "  ·  ")
                + (version.detailsKept ? Text("details kept on the device") : Text("details not kept")))
                .lineLimit(2)
                .foregroundStyle(.white.opacity(0.7))
            if !version.providerKeys.isEmpty {
                Text("info fields: \(version.providerKeys.joined(separator: ", "))")
                    .lineLimit(2)
                    .foregroundStyle(.white.opacity(0.7))
            }
            if version.dolbyVision || !version.screenNotes.isEmpty {
                screenNotes(version)
                    .lineLimit(3)
                    .foregroundStyle(Color.orange)
            }
        }
        .padding(.top, 4)
    }

    private func providerText(_ version: TitleData.Version) -> String {
        switch version.provider {
        case .notRequested: String(localized: "not requested")
        case .notAvailable: String(localized: "none for this playlist")
        case .failed: String(localized: "failed (\(version.providerFailure ?? "–"))")
        case .keptBefore: String(localized: "kept by an earlier version, asked again")
        case .noTechnicalBlock: String(localized: "requested, no technical block")
        case .videoAndAudio: String(localized: "video + audio")
        case .videoOnly: String(localized: "video only")
        case .audioOnly: String(localized: "audio only")
        }
    }

    /// Its Dolby Vision profile, and what it asks of this screen and does not get.
    private func screenNotes(_ version: TitleData.Version) -> Text {
        var parts: [String] = []
        if version.dolbyVision { parts.append(String(localized: "Dolby Vision profile: \(version.dolbyVisionProfile ?? String(localized: "unknown"))")) }
        for note in version.screenNotes {
            switch note {
            case .dolbyVisionBaseLayer: parts.append(String(localized: "Dolby Vision not supported by this screen: its base layer plays (HDR10, SDR or HLG)"))
            case .dolbyVisionWrongColours: parts.append(String(localized: "Dolby Vision not supported by this screen: this profile shows wrong colours, never played first"))
            case .dolbyVisionUnknown: parts.append(String(localized: "Dolby Vision not supported by this screen: profile unknown, not played first"))
            case .hdrUnsupported: parts.append(String(localized: "HDR not supported by this screen"))
            case .uhdUnsupported: parts.append(String(localized: "4K above this screen’s largest mode"))
            }
        }
        return Text(verbatim: parts.joined(separator: "  ·  "))
    }

    /// What the app read of this screen, what the versions are chosen for, and the app's build.
    @ViewBuilder
    private var screenRows: some View {
        if let screen = data.screen {
            Text("This screen").fontWeight(.semibold).padding(.top, 4)
            Group {
                Text("Largest mode \(screen.width > 0 ? "\(screen.width)×\(screen.height)" : String(localized: "unknown"))")
                Text("HDR10 \(word(screen.hdr10)) · HDR10+ \(word(screen.hdr10Plus)) · HLG \(word(screen.hlg)) · Dolby Vision \(word(screen.dolbyVision))")
                Text("Decoders: Dolby Vision \(word(screen.dolbyVisionDecoder))")
                if let channels = screen.outputChannels {
                    Text("Sound output: up to \(StreamSpecs.layout(channels))")
                }
                if let spatial = screen.spatialAudio {
                    Text("Spatial Audio \(word(spatial))")
                }
                Text("Versions chosen for: \(chosenText)")
                Text("App \(data.app)")
            }
            .foregroundStyle(.white.opacity(0.7))
        }
    }

    private var chosenText: String {
        let chosen = [data.support.uhd ? "4K" : nil, data.support.hdr ? "HDR" : nil, data.support.dolbyVision ? "Dolby Vision" : nil].compactMap { $0 }
        return chosen.isEmpty ? "–" : chosen.joined(separator: " · ")
    }

    private func word(_ value: Bool?) -> String {
        switch value {
        case true?: String(localized: "yes")
        case false?: String(localized: "no")
        case nil: String(localized: "unknown")
        }
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
