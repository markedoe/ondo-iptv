import XCTest
import IPTVCore
@testable import Ondo

/// The library is written off the main thread, and always in its latest version.
final class LibraryWriterTests: XCTestCase {
    private var root: URL!

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: root)
    }

    private func library(favorites count: Int) -> UserLibrary {
        var library = UserLibrary()
        for item in 0..<count {
            _ = library.toggleFavorite(MediaReference(playlistID: UUID(), type: .movie, itemID: "m\(item)"), title: "Movie \(item)", imageURL: nil)
        }
        return library
    }

    func testABurstOfChangesIsWrittenOnceInItsLatestVersion() async throws {
        let store = FileStore(directory: root)
        let writer = LibraryWriter(store: store)
        for count in 1...20 {
            writer.schedule(library(favorites: count), name: "library", after: .milliseconds(50))
        }
        XCTAssertNil(store.load(UserLibrary.self, name: "library"), "nothing written yet")
        try await Task.sleep(for: .milliseconds(400))
        XCTAssertEqual(store.load(UserLibrary.self, name: "library")?.favorites.count, 20)
    }

    /// Going to the background: the latest version is on disk when `flush` returns, whatever was under way.
    func testFlushWritesTheLatestVersionAtOnce() {
        let store = FileStore(directory: root)
        let writer = LibraryWriter(store: store)
        writer.schedule(library(favorites: 1), name: "library", after: .seconds(30))
        writer.schedule(library(favorites: 3), name: "library", after: .seconds(30))
        writer.flush()
        XCTAssertEqual(store.load(UserLibrary.self, name: "library")?.favorites.count, 3)
    }

    /// A deleted profile's library is not written again by a save still waiting.
    func testADiscardedVersionIsNeverWritten() async throws {
        let store = FileStore(directory: root)
        let writer = LibraryWriter(store: store)
        writer.schedule(library(favorites: 2), name: "library-kids", after: .milliseconds(50))
        writer.discard(name: "library-kids")
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertNil(store.load(UserLibrary.self, name: "library-kids"))
    }
}
