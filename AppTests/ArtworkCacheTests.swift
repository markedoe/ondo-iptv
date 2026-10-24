import XCTest
import ImageIO
import Nuke
@testable import Ondo

/// Posters stay on disk: at the next launch they come back without a download, already at the size the screen
/// shows, whatever cache headers the image server sends.
final class ArtworkCacheTests: XCTestCase {
    func testAPosterSeenOnceComesBackFromDiskAtItsSize() async throws {
        let poster = try XCTUnwrap(Self.jpeg(width: 1200, height: 1800))
        let server = try LocalHTTPServer { _ in
            LocalHTTPServer.Response(headers: ["Content-Type": "image/jpeg", "Cache-Control": "no-store"], body: poster)
        }
        let url = try await server.start().appendingPathComponent("poster.jpg")
        let pipeline = Artwork.pipeline(cacheName: "OndoArtworkTests-\(UUID().uuidString)")
        let disk = try XCTUnwrap(pipeline.configuration.dataCache as? DataCache)
        defer { try? FileManager.default.removeItem(at: disk.path) }
        let request = Artwork.request(url, maxPixelSize: 300)

        let first = try await pipeline.imageTask(with: request).response
        XCTAssertEqual(Self.pixelHeight(first.image), 300, "decoded at the size shown")
        pipeline.configuration.imageEncodingQueue.waitUntilAllOperationsAreFinished()
        disk.flush()
        XCTAssertTrue(pipeline.cache.containsData(for: request), "the copy at the size shown is on disk")
        XCTAssertTrue(pipeline.cache.containsData(for: ImageRequest(url: url)), "and the file as downloaded")
        XCTAssertEqual(server.requests.count, 1)

        // The next launch: nothing in memory.
        var relaunch = request
        relaunch.options.insert(.disableMemoryCacheReads)
        let second = try await pipeline.imageTask(with: relaunch).response
        XCTAssertEqual(second.cacheType, .disk)
        XCTAssertEqual(Self.pixelHeight(second.image), 300)
        XCTAssertEqual(server.requests.count, 1, "no new download")
    }

    /// A provider's logo naming a file of the device outside the app is not loaded: Nuke reads a file whole, and
    /// `/dev/zero` never ends. The web, `data:` addresses and the app's own files (the UI test fixture) are.
    func testArtworkComesFromTheWebOrTheAppsOwnFiles() throws {
        for refused in ["file:///dev/zero", "FILE:///etc/hosts", "ftp://example.com/logo.png", "javascript:alert(1)"] {
            XCTAssertNil(Artwork.loadable(try XCTUnwrap(URL(string: refused))), refused)
        }
        let climbing = URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent(String(repeating: "../", count: 30) + "dev/zero")
        XCTAssertNil(Artwork.loadable(climbing))
        XCTAssertNil(Artwork.request(try XCTUnwrap(URL(string: "file:///dev/zero")), maxPixelSize: 300).url)
        XCTAssertNil(Artwork.blurredRequest(try XCTUnwrap(URL(string: "file:///dev/zero"))).url)

        for kept in ["http://example.com/logo.png", "HTTPS://example.com/poster.jpg", "data:image/png;base64,iVBORw0KGgo="] {
            XCTAssertNotNil(Artwork.loadable(try XCTUnwrap(URL(string: kept))), kept)
        }
        let own = AppPaths.importedPlaylists.appendingPathComponent("fixture-artwork/logo.png")
        XCTAssertEqual(Artwork.loadable(own), own)
    }

    func testTheCacheIsLargeEnoughForABigCatalogue() {
        #if os(macOS)
        XCTAssertEqual(Artwork.diskLimit, 1024 * 1024 * 1024)
        #else
        XCTAssertEqual(Artwork.diskLimit, 512 * 1024 * 1024)
        #endif
    }

    private static func jpeg(width: Int, height: Int) -> Data? {
        guard let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
        else { return nil }
        context.setFillColor(red: 0.2, green: 0.3, blue: 0.6, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        guard let image = context.makeImage() else { return nil }
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, "public.jpeg" as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, image, nil)
        return CGImageDestinationFinalize(destination) ? data as Data : nil
    }

    private static func pixelHeight(_ image: PlatformImage) -> Int? {
        #if canImport(UIKit)
        return image.cgImage?.height
        #else
        return image.cgImage(forProposedRect: nil, context: nil, hints: nil)?.height
        #endif
    }
}
