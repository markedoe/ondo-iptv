import SwiftUI
import CoreImage
import Nuke
import NukeUI

/// Artwork (logos, posters) goes through Nuke: memory cache, a disk cache, request coalescing and
/// downsampling, so huge provider images do not cost memory in long lists.
enum Artwork {
    /// Called once at launch.
    static func configurePipeline() {
        ImagePipeline.shared = ImagePipeline(configuration: .withDataCache(name: "com.markedoe.ondo.artwork", sizeLimit: 300 * 1024 * 1024))
    }

    /// Decoded straight at `maxPixelSize` by ImageIO (a thumbnail of the downloaded file): a 2000×3000 poster is
    /// never decoded whole (24 MB) to be shrunk afterwards.
    static func request(_ url: URL, maxPixelSize: CGFloat) -> ImageRequest {
        ImageRequest(url: url, userInfo: [.thumbnailKey: ImageRequest.ThumbnailOptions(maxPixelSize: Float(maxPixelSize))])
    }

    /// A small copy of the picture, blurred once and kept in the cache (see `SoftBlur`).
    static func blurredRequest(_ url: URL) -> ImageRequest {
        ImageRequest(url: url, processors: [SoftBlur(sigma: 5)], userInfo: [.thumbnailKey: ImageRequest.ThumbnailOptions(maxPixelSize: 64)])
    }

    /// Decoded thumbnail (Now Playing artwork…).
    static func image(for url: URL, maxPixelSize: CGFloat) async -> PlatformImage? {
        try? await ImagePipeline.shared.image(for: request(url, maxPixelSize: maxPixelSize))
    }

    static func clearCaches() {
        ImagePipeline.shared.cache.removeAll()
    }
}

/// Async image with a monogram placeholder (channel logos are frequently missing or broken).
struct RemoteImage: View {
    let url: URL?
    var placeholderText: String = ""
    var contentMode: ContentMode = .fit
    var maxPixelSize: CGFloat = 300

    var body: some View {
        LazyImage(request: url.map { Artwork.request($0, maxPixelSize: maxPixelSize) }) { state in
            if let image = state.image {
                image
                    .resizable()
                    .aspectRatio(contentMode: contentMode)
            } else {
                MonogramView(text: placeholderText)
            }
        }
    }
}

/// A wide background picture (movie and series pages, home carousel). While it loads, without one, or when its
/// address leads nowhere, the poster fills the space, blurred, rather than an empty frame.
struct BackdropImage: View {
    let backdropURL: URL?
    let posterURL: URL?
    var dimming = 0.25
    /// Called when the backdrop does not load, so the title can be given another one.
    var onFailure: ((URL) -> Void)?

    var body: some View {
        LazyImage(request: backdropURL.map { Artwork.request($0, maxPixelSize: 1600) }) { state in
            if let image = state.image {
                image
                    .resizable()
                    .aspectRatio(contentMode: .fill)
            } else {
                LazyImage(request: posterURL.map(Artwork.blurredRequest)) { blurred in
                    if let image = blurred.image {
                        image
                            .resizable()
                            .aspectRatio(contentMode: .fill)
                    } else {
                        Color.secondary.opacity(0.15)
                    }
                }
                .overlay { Color.black.opacity(dimming) }
            }
        }
        // A cancelled request (scrolled away) reports nothing.
        .onCompletion { result in
            if case .failure = result, let backdropURL { onFailure?(backdropURL) }
        }
    }
}

struct MonogramView: View {
    let text: String

    private var initials: String {
        let words = text
            .replacingOccurrences(of: "|", with: " ")
            .split(separator: " ")
            .filter { $0.count > 1 || $0.first?.isNumber == true }
        let letters = words.prefix(2).compactMap { $0.first.map(String.init) }
        return letters.joined().uppercased()
    }

    private var tint: Color {
        let palette: [Color] = [.indigo, .purple, .pink, .orange, .teal, .blue, .mint, .cyan]
        let sum = text.unicodeScalars.reduce(0) { $0 &+ Int($1.value) }
        return palette[abs(sum) % palette.count]
    }

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                tint.opacity(0.18)
                Text(initials.isEmpty ? "TV" : initials)
                    .font(.system(size: max(min(proxy.size.width, proxy.size.height) * 0.36, 8), weight: .bold, design: .rounded))
                    .foregroundStyle(tint)
                    .minimumScaleFactor(0.5)
            }
        }
    }
}

/// Blurs a picture once, edges included, for the backgrounds that show a blurred poster: Nuke keeps the result, where
/// a live blur over a large view costs the GPU every frame while scrolling.
struct SoftBlur: ImageProcessing, Hashable {
    let sigma: Double

    var identifier: String { "com.markedoe.ondo/soft-blur?sigma=\(sigma)" }

    func process(_ image: PlatformImage) -> PlatformImage? {
        #if canImport(UIKit)
        guard let source = image.cgImage else { return nil }
        #else
        guard let source = image.cgImage(forProposedRect: nil, context: nil, hints: nil) else { return nil }
        #endif
        let input = CIImage(cgImage: source)
        // Clamped first: without it the blur fades the edges to transparent.
        let output = input.clampedToExtent().applyingGaussianBlur(sigma: sigma).cropped(to: input.extent)
        guard let blurred = CIContext().createCGImage(output, from: input.extent) else { return nil }
        #if canImport(UIKit)
        return UIImage(cgImage: blurred)
        #else
        return NSImage(cgImage: blurred, size: NSSize(width: blurred.width, height: blurred.height))
        #endif
    }
}
