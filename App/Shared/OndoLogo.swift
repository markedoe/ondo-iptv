import SwiftUI

/// The Ondo mark drawn in SwiftUI (same geometry as the app icon): a ring crossed by a wave.
struct OndoLogo: View {
    var size: CGFloat = 96
    var showsBackground = true

    private static let lime = Color(red: 0.84, green: 1, blue: 0.24)
    private static let cream = Color(red: 1, green: 0.965, blue: 0.87)

    var body: some View {
        ZStack {
            if showsBackground {
                RoundedRectangle(cornerRadius: size * 0.2237, style: .continuous)
                    .fill(LinearGradient(
                        colors: [Color(red: 0.11, green: 0.31, blue: 0.27), Color(red: 0.03, green: 0.11, blue: 0.09)],
                        startPoint: .top,
                        endPoint: .bottom
                    ))
            }
            Circle()
                .stroke(Self.lime, lineWidth: size * 0.115)
                .frame(width: size * 0.586, height: size * 0.586)
            WaveShape()
                .stroke(Self.cream, style: StrokeStyle(lineWidth: size * 0.0625, lineCap: .round))
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
