import SwiftUI

/// What a card does when the pointer is over it (iPad, Mac), when it has the keyboard focus, or while it is pressed
/// (iPhone): it rises. The whole card grows a little; its picture (`CardLift`) takes a deep black shadow and a soft
/// white sheen from its top-left corner. No coloured border: the colour comes from the picture.
struct CardRise: ViewModifier {
    var pressed = false
    @Environment(\.isFocused) private var focused
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var hovering = false

    func body(content: Content) -> some View {
        let risen = pressed || hovering || focused
        content
            .environment(\.cardRisen, risen)
            .scaleEffect(risen && !reduceMotion ? 1.07 : 1)
            .animation(.spring(response: 0.3, dampingFraction: 0.8), value: risen)
            .onHover { hovering = $0 }
    }
}

/// The picture of a card: its shadow and sheen follow the card's `CardRise`.
struct CardLift: ViewModifier {
    var cornerRadius: CGFloat = 12
    @Environment(\.cardRisen) private var risen

    func body(content: Content) -> some View {
        content
            .overlay {
                LinearGradient(
                    stops: [.init(color: .white.opacity(0.24), location: 0), .init(color: .clear, location: 0.45)],
                    startPoint: .topLeading, endPoint: .bottomTrailing
                )
                .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
                .opacity(risen ? 1 : 0)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
            }
            .shadow(color: .black.opacity(risen ? 0.75 : 0.25), radius: risen ? 18 : 8, y: risen ? 12 : 4)
            .animation(.easeOut(duration: 0.2), value: risen)
    }
}

/// The button style of cards (posters, landscape cards, channel tiles): plain, and rising when pressed.
struct CardButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.modifier(CardRise(pressed: configuration.isPressed))
    }
}

extension View {
    /// A card that rises when pointed at, focused or pressed. On iPad the system's own pointer effect is left off,
    /// so the card's rise is the only one.
    func cardButtonStyle() -> some View {
        #if os(iOS)
        buttonStyle(CardButtonStyle()).hoverEffectDisabled()
        #else
        buttonStyle(CardButtonStyle())
        #endif
    }

    /// The picture of a card, lifted with it.
    func cardLift(cornerRadius: CGFloat = 12) -> some View {
        modifier(CardLift(cornerRadius: cornerRadius))
    }
}

private struct CardRisenKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    /// The card around this view has risen (pointer, focus, press).
    var cardRisen: Bool {
        get { self[CardRisenKey.self] }
        set { self[CardRisenKey.self] = newValue }
    }
}
