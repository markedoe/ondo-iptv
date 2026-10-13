#if DEBUG
import Foundation
import CoreGraphics
import CoreText
import ImageIO

/// Draws the fixture's artwork: stylised landscapes (sky, sun, layered hills) with an optional title,
/// so UI tests and App Store screenshots show real images going through the image pipeline.
enum FixtureArtwork {
    struct Style {
        var hue: Double
        var seed: UInt64
        var font: String = "AvenirNext-Heavy"
    }

    static func poster(title: String, style: Style) -> Data? {
        render(width: 400, height: 600, style: style) { context in
            let words = title.uppercased().split(separator: " ").map(String.init)
            drawTitle(words, in: context, width: 400, baseline: 120, font: (style.font, 46))
        }
    }

    static func backdrop(style: Style, width: Int = 1280, height: Int = 720) -> Data? {
        render(width: width, height: height, style: style) { _ in }
    }

    static func logo(title: String, hue: Double) -> Data? {
        render(width: 240, height: 160, style: Style(hue: hue, seed: 7, font: "AvenirNext-Bold"), landscape: false) { context in
            drawTitle([title.uppercased()], in: context, width: 240, baseline: 66, font: ("AvenirNext-Bold", 30))
        }
    }

    // MARK: - Drawing

    private static func render(width: Int, height: Int, style: Style, landscape: Bool = true, overlay: (CGContext) -> Void) -> Data? {
        guard let context = CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }
        let w = CGFloat(width)
        let h = CGFloat(height)
        // Sky: light at the horizon, deep at the top.
        let sky = [color(style.hue, 0.55, 0.95), color(style.hue + 0.04, 0.75, 0.55), color(style.hue + 0.08, 0.85, 0.22)] as CFArray
        if let gradient = CGGradient(colorsSpace: nil, colors: sky, locations: [0, 0.45, 1]) {
            context.drawLinearGradient(gradient, start: CGPoint(x: 0, y: h * 0.35), end: CGPoint(x: 0, y: h), options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
        }
        guard landscape else { return encode(context, overlay: overlay) }

        var random = SeededRandom(seed: style.seed)
        // Sun or moon.
        let sunCenter = CGPoint(x: w * (0.25 + 0.5 * random.next()), y: h * (0.45 + 0.2 * random.next()))
        let sunRadius = min(w, h) * (0.12 + 0.08 * random.next())
        let glow = [color(style.hue - 0.05, 0.2, 1, alpha: 0.9), color(style.hue - 0.05, 0.4, 1, alpha: 0)] as CFArray
        if let gradient = CGGradient(colorsSpace: nil, colors: glow, locations: [0, 1]) {
            context.drawRadialGradient(gradient, startCenter: sunCenter, startRadius: sunRadius * 0.9, endCenter: sunCenter, endRadius: sunRadius * 3, options: [])
        }
        context.setFillColor(color(style.hue - 0.05, 0.15, 1))
        context.fillEllipse(in: CGRect(x: sunCenter.x - sunRadius, y: sunCenter.y - sunRadius, width: sunRadius * 2, height: sunRadius * 2))

        // Layered hills, darker towards the viewer.
        for layer in 0..<4 {
            let base = h * (0.42 - 0.1 * CGFloat(layer))
            let path = CGMutablePath()
            path.move(to: CGPoint(x: 0, y: 0))
            path.addLine(to: CGPoint(x: 0, y: base))
            var x: CGFloat = 0
            while x < w {
                let step = w * (0.08 + 0.1 * random.next())
                let peak = base + h * (0.04 + 0.12 * random.next()) * (layer == 0 ? 1.4 : 1)
                path.addQuadCurve(to: CGPoint(x: min(x + step, w), y: base + h * 0.02 * random.next()), control: CGPoint(x: x + step / 2, y: peak))
                x += step
            }
            path.addLine(to: CGPoint(x: w, y: 0))
            path.closeSubpath()
            context.addPath(path)
            context.setFillColor(color(style.hue + 0.06 + 0.02 * Double(layer), 0.7, 0.42 - 0.09 * Double(layer)))
            context.fillPath()
        }
        // Vignette.
        let vignette = [CGColor(gray: 0, alpha: 0), CGColor(gray: 0, alpha: 0.45)] as CFArray
        if let gradient = CGGradient(colorsSpace: nil, colors: vignette, locations: [0.55, 1]) {
            let center = CGPoint(x: w / 2, y: h / 2)
            context.drawRadialGradient(gradient, startCenter: center, startRadius: 0, endCenter: center, endRadius: hypot(w, h) / 2, options: [.drawsAfterEndLocation])
        }
        return encode(context, overlay: overlay)
    }

    private static func encode(_ context: CGContext, overlay: (CGContext) -> Void) -> Data? {
        overlay(context)
        guard let image = context.makeImage() else { return nil }
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, "public.png" as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return data as Data
    }

    private static func drawTitle(_ lines: [String], in context: CGContext, width: Int, baseline: CGFloat, font: (name: String, size: CGFloat)) {
        let size = font.size
        let ctFont = CTFontCreateWithName(font.name as CFString, size, nil)
        let attributes: [NSAttributedString.Key: Any] = [
            NSAttributedString.Key(kCTFontAttributeName as String): ctFont,
            NSAttributedString.Key(kCTForegroundColorAttributeName as String): CGColor(gray: 1, alpha: 0.97),
            NSAttributedString.Key(kCTKernAttributeName as String): size * 0.04,
        ]
        let lineHeight = size * 1.05
        var y = baseline + lineHeight * CGFloat(lines.count - 1)
        context.setShadow(offset: CGSize(width: 0, height: -2), blur: 8, color: CGColor(gray: 0, alpha: 0.6))
        for text in lines {
            let line = CTLineCreateWithAttributedString(NSAttributedString(string: text, attributes: attributes))
            let bounds = CTLineGetBoundsWithOptions(line, [])
            let scale = min(1, (CGFloat(width) - 36) / max(bounds.width, 1))
            context.saveGState()
            context.translateBy(x: (CGFloat(width) - bounds.width * scale) / 2, y: y)
            context.scaleBy(x: scale, y: scale)
            context.textPosition = .zero
            CTLineDraw(line, context)
            context.restoreGState()
            y -= lineHeight
        }
    }

    private static func color(_ hue: Double, _ saturation: Double, _ brightness: Double, alpha: CGFloat = 1) -> CGColor {
        let h = (hue.truncatingRemainder(dividingBy: 1) + 1).truncatingRemainder(dividingBy: 1) * 6
        let b = min(max(brightness, 0), 1)
        let c = b * saturation
        let x = c * (1 - abs(h.truncatingRemainder(dividingBy: 2) - 1))
        let m = b - c
        let sectors: [(Double, Double, Double)] = [(c, x, 0), (x, c, 0), (0, c, x), (0, x, c), (x, 0, c), (c, 0, x)]
        let (r, g, bl) = sectors[min(Int(h), 5)]
        return CGColor(srgbRed: r + m, green: g + m, blue: bl + m, alpha: alpha)
    }
}

/// Deterministic generator so the artwork is identical on every run.
private struct SeededRandom {
    private var state: UInt64

    init(seed: UInt64) { state = seed &* 6_364_136_223_846_793_005 &+ 1 }

    mutating func next() -> CGFloat {
        state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
        return CGFloat(state >> 11) / CGFloat(UInt64(1) << 53)
    }
}
#endif
