import SwiftUI

/// The Ondo mark drawn in SwiftUI (same geometry and colours as the app icon): a white ring crossed by a grey wave, on black.
struct OndoLogo: View {
    var size: CGFloat = 96
    var showsBackground = true

    private static let ring = Color.white
    private static let wave = Color(red: 0.557, green: 0.557, blue: 0.576)

    var body: some View {
        ZStack {
            if showsBackground {
                RoundedRectangle(cornerRadius: size * 0.2237, style: .continuous)
                    .fill(Color.black)
            }
            Circle()
                .stroke(Self.ring, lineWidth: size * 0.115)
                .frame(width: size * 0.586, height: size * 0.586)
            WaveShape()
                .stroke(Self.wave, style: StrokeStyle(lineWidth: size * 0.0625, lineCap: .round))
                .frame(width: size * 0.707, height: size * 0.14)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// One and a half sine periods across the rect.
struct WaveShape: Shape {
    var periods: Double = 1.5

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let steps = 120
        for step in 0...steps {
            let t = Double(step) / Double(steps)
            let x = rect.minX + rect.width * t
            let y = rect.midY + rect.height / 2 * sin(2 * .pi * periods * t)
            if step == 0 { path.move(to: CGPoint(x: x, y: y)) } else { path.addLine(to: CGPoint(x: x, y: y)) }
        }
        return path
    }
}
