import SwiftUI

/// Ondo's palette: neutral greys on pure black, white for what is chosen or focused. The colour on screen comes from
/// the pictures, not from the frame around them (the same palette as the Android app's `OndoColors`).
enum OndoColor {
    /// The ground of every page.
    static let ground = Color.black
    /// Raised surfaces: cards without a picture, grouped rows.
    static let surface = Color(red: 0x1C / 255, green: 0x1C / 255, blue: 0x1E / 255)
    /// Surfaces raised a step further: chips, round actions, a selected row.
    static let surfaceHigh = Color(red: 0x2C / 255, green: 0x2C / 255, blue: 0x2E / 255)
    /// Hairlines and the outline of badges.
    static let outline = Color(red: 0x3A / 255, green: 0x3A / 255, blue: 0x3C / 255)
    /// Secondary text: white at 60 %.
    static let secondary = Color.white.opacity(0.6)
    /// What is chosen or has the focus.
    static let selected = Color.white
    /// Behind white text where the system fills a control with the tint (swipe actions, the iPad sidebar's
    /// selected row): the white accent would leave that text unreadable.
    static let tintFill = Color(red: 0x63 / 255, green: 0x63 / 255, blue: 0x66 / 255)
}

/// The main action of a screen: a white capsule with black text (Play, Add a Playlist, Try Again…), in place of
/// the system's bordered prominent style, whose label stays white over the white accent.
struct ProminentButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        ProminentButtonBody(configuration: configuration)
    }
}

private struct ProminentButtonBody: View {
    let configuration: ButtonStyleConfiguration
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.controlSize) private var controlSize

    var body: some View {
        configuration.label
            .font(controlSize == .large ? .headline : .body.weight(.semibold))
            .multilineTextAlignment(.center)
            .padding(.horizontal, horizontalPadding)
            .padding(.vertical, verticalPadding)
            .foregroundStyle(.black)
            .background(.white, in: Capsule())
            .contentShape(Capsule())
            .opacity(isEnabled ? (configuration.isPressed ? 0.8 : 1) : 0.4)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.15), value: configuration.isPressed)
    }

    private var horizontalPadding: CGFloat {
        switch controlSize {
        case .mini, .small: return 12
        case .large, .extraLarge: return 24
        default: return Platform.isMac ? 14 : 18
        }
    }

    private var verticalPadding: CGFloat {
        switch controlSize {
        case .mini, .small: return 5
        case .large, .extraLarge: return 13
        default: return Platform.isMac ? 6 : 9
        }
    }
}

extension ButtonStyle where Self == ProminentButtonStyle {
    /// White capsule, black text: the main action of a screen.
    static var ondoProminent: ProminentButtonStyle { ProminentButtonStyle() }
}

/// A small outlined tag for the format of a title (MKV, MP4, HLS…), after its facts.
struct FactBadge: View {
    let text: String

    var body: some View {
        Text(verbatim: text)
            .font(.caption2.weight(.bold))
            .padding(.horizontal, 5)
            .padding(.vertical, 1)
            .overlay {
                RoundedRectangle(cornerRadius: 4, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.5), lineWidth: 1)
            }
            .fixedSize()
    }
}

/// The facts of a title in one line ("2024 · Drama · 1 h 52"), then its badges.
struct FactsLine: View {
    let facts: [String]
    var badges: [String] = []
    var color: Color = OndoColor.secondary
    var lineLimit = 1

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            if !facts.isEmpty {
                Text(verbatim: facts.joined(separator: " · "))
                    .lineLimit(lineLimit)
            }
            ForEach(badges, id: \.self) { badge in
                FactBadge(text: badge)
            }
        }
        .foregroundStyle(color)
        .accessibilityElement(children: .combine)
    }
}
