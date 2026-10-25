import XCTest
@testable import IPTVCore

/// Local-network sync (docs/SYNC.md): the shared examples, the merge rules, local addresses and frames. The same
/// cases as the Kotlin `SyncTest` (android/core), on the same example files.
final class SyncTests: XCTestCase {
    /// `fixtures/sync` at the top of the repository, shared with the Kotlin tests.
    private func fixture(_ name: String) throws -> SyncSnapshot {
        var directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while !FileManager.default.fileExists(atPath: directory.appendingPathComponent("fixtures/sync").path) {
            let parent = directory.deletingLastPathComponent()
            guard parent.path != directory.path else { throw XCTSkip("fixtures/sync not found") }
            directory = parent
        }
        let data = try Data(contentsOf: directory.appendingPathComponent("fixtures/sync").appendingPathComponent(name))
        return try XCTUnwrap(SyncSnapshot.decode(data), name)
    }

    private func withoutDevice(_ snapshot: SyncSnapshot) -> SyncSnapshot {
        var copy = snapshot
        copy.device = ""
        copy.deviceName = nil
        return copy
    }

    func testMergingTheSharedExamplesGivesTheSharedResultInEitherOrder() throws {
        let a = try fixture("a.json")
        let b = try fixture("b.json")
        let expected = try fixture("merged.json")
        XCTAssertEqual(SyncMerge.merge(a, b), expected)
        XCTAssertEqual(withoutDevice(SyncMerge.merge(b, a)), withoutDevice(expected))
    }

    func testMergingAgainChangesNothing() throws {
        let merged = SyncMerge.merge(try fixture("a.json"), try fixture("b.json"))
        XCTAssertEqual(SyncMerge.merge(merged, merged), merged)
        XCTAssertEqual(SyncMerge.merge(merged, try fixture("b.json")), merged)
        XCTAssertEqual(withoutDevice(SyncMerge.merge(try fixture("a.json"), merged)), withoutDevice(merged))
    }

    func testTheLaterChangeWinsAndADeletionWinsOverWhatWasNotChangedSince() {
        let playlist = SyncPlaylist(id: "p", name: "Home", kind: SyncPlaylist.m3u, url: "http://example.com/a.m3u", addedAt: 1, updatedAt: 1)
        let favorite = SyncFavorite(playlist: "p", kind: "movie", item: "m1", title: "Glass City", addedAt: 10)
        let a = SyncSnapshot(device: "a", playlists: [playlist], favorites: [favorite])
        var renamedPlaylist = playlist
        renamedPlaylist.name = "Living room"
        renamedPlaylist.updatedAt = 5
        let renamed = SyncSnapshot(device: "b", playlists: [renamedPlaylist])
        XCTAssertEqual(SyncMerge.merge(a, renamed).playlists.map(\.name), ["Living room"])
        // Removed after it was added: gone. Added again after the removal: back.
        let removed = SyncSnapshot(device: "b", deleted: [favorite.key: 20])
        XCTAssertTrue(SyncMerge.merge(a, removed).favorites.isEmpty)
        var addedAgain = a
        var again = favorite
        again.addedAt = 30
        addedAgain.favorites = [again]
        XCTAssertEqual(SyncMerge.merge(addedAgain, removed).favorites.count, 1)
        // A removal at the same time as the change wins.
        XCTAssertTrue(SyncMerge.merge(a, SyncSnapshot(device: "b", deleted: [favorite.key: 10])).favorites.isEmpty)
    }

    func testKeysOfOutsideServicesFollowTheLaterSaveAndTheirRemoval() throws {
        let merged = SyncMerge.merge(try fixture("a.json"), try fixture("b.json"))
        // The later save wins, a removal after a save drops it, the key of a service this device does not know stays.
        XCTAssertEqual(merged.keys, [
            SyncKey(service: "future-service", value: "example-other-key", updatedAt: 300),
            SyncKey(service: "tmdb", value: "example-tmdb-token-b", updatedAt: 2500),
        ])
        XCTAssertEqual(merged.deleted["key:opensubtitles"], 800)
        // A device that does not sync keys sends none: that removes nothing.
        let mine = SyncSnapshot(device: "a", keys: [SyncKey(service: SyncKey.tmdb, value: "example-tmdb-token-a", updatedAt: 1500)])
        XCTAssertEqual(SyncMerge.merge(mine, SyncSnapshot(device: "old")).keys, mine.keys)
        XCTAssertEqual(SyncMerge.merge(SyncSnapshot(device: "old"), mine).keys, mine.keys)
        // On equal times, the smaller value wins, whatever the order.
        let other = SyncSnapshot(device: "b", keys: [SyncKey(service: SyncKey.tmdb, value: "example-tmdb-token-0", updatedAt: 1500)])
        XCTAssertEqual(SyncMerge.merge(mine, other).keys.map(\.value), ["example-tmdb-token-0"])
        XCTAssertEqual(SyncMerge.merge(other, mine).keys.map(\.value), ["example-tmdb-token-0"])
    }

    /// On equal times both platforms keep the same record: strings compare as UTF-16 code units (Kotlin's order), not
    /// as Swift orders them.
    func testTiesCompareStringsAsUTF16CodeUnits() {
        // U+FF5E (one UTF-16 unit) and U+1F600 (a surrogate pair starting with 0xD83D): UTF-16 puts the emoji first,
        // Unicode scalar order puts it last.
        let wide = SyncFavorite(playlist: "p", kind: "movie", item: "m1", title: "\u{FF5E}", addedAt: 10)
        let emoji = SyncFavorite(playlist: "p", kind: "movie", item: "m1", title: "\u{1F600}", addedAt: 10)
        let merged = SyncMerge.merge(SyncSnapshot(device: "a", favorites: [wide]), SyncSnapshot(device: "b", favorites: [emoji]))
        XCTAssertEqual(merged.favorites.map(\.title), ["\u{1F600}"])
        XCTAssertTrue("\u{1F600}".isBefore("\u{FF5E}"))
        // Progress: the larger position, then the smaller title.
        let early = SyncProgress(playlist: "p", kind: "movie", item: "m1", title: "B", position: 100, duration: 600, watchedAt: 5)
        var later = early
        later.position = 200
        later.title = "C"
        let progress = SyncMerge.merge(SyncSnapshot(device: "a", progress: [early]), SyncSnapshot(device: "b", progress: [later]))
        XCTAssertEqual(progress.progress.map(\.position), [200])
    }

    func testTheSameAccountAddedTwiceBecomesOnePlaylist() {
        let first = SyncPlaylist(id: "b", name: "Home", kind: SyncPlaylist.xtream, url: "http://panel.example.com",
                                 username: "viewer", password: "secret", addedAt: 10, updatedAt: 10)
        var second = first
        second.id = "a"
        second.url = "\u{2003}HTTP://Panel.Example.com\t"
        second.addedAt = 10
        let favorite = SyncFavorite(playlist: "b", kind: "movie", item: "m1", title: "Glass City", addedAt: 20)
        let merged = SyncMerge.merge(SyncSnapshot(device: "x", playlists: [first], favorites: [favorite]),
                                     SyncSnapshot(device: "y", playlists: [second]))
        // Same time added: the smaller ID stays, the other one points to it.
        XCTAssertEqual(merged.playlists.map(\.id), ["a"])
        XCTAssertEqual(merged.replaced, ["b": "a"])
        XCTAssertEqual(merged.deleted["playlist:b"], 10)
        XCTAssertEqual(merged.favorites.map(\.playlist), ["a"])
        XCTAssertEqual(SyncMerge.merge(merged, SyncSnapshot(device: "y", playlists: [second])), merged)
    }

    func testASnapshotOfAnotherKindOrVersionIsRefused() throws {
        XCTAssertNil(SyncSnapshot.decode(Data(#"{"type":"other","version":1,"device":"a"}"#.utf8)))
        XCTAssertNil(SyncSnapshot.decode(Data(#"{"type":"ondo-sync","version":2,"device":"a"}"#.utf8)))
        XCTAssertNil(SyncSnapshot.decode(Data("not json".utf8)))
        XCTAssertNil(SyncSnapshot.decode(Data(#"{"type":"ondo-sync","version":1}"#.utf8)), "no device")
        let minimal = try XCTUnwrap(SyncSnapshot.decode(Data(#"{"type":"ondo-sync","version":1,"device":"a","extra":[1]}"#.utf8)))
        XCTAssertEqual(minimal, SyncSnapshot(device: "a"))
        let snapshot = SyncMerge.merge(try fixture("a.json"), try fixture("b.json"))
        XCTAssertEqual(SyncSnapshot.decode(try snapshot.encoded()), snapshot)
    }

    func testOptionalFieldsAreLeftOutWhenEmpty() throws {
        let favorite = SyncFavorite(playlist: "p", kind: "live", item: "c1", title: "News", addedAt: 1)
        let json = try XCTUnwrap(String(data: try JSONEncoder().encode(favorite), encoding: .utf8))
        XCTAssertFalse(json.contains("image"), json)
        XCTAssertFalse(json.contains("null"), json)
    }

    func testIDsAreReadInLowercase() throws {
        let a = try fixture("a.json")
        XCTAssertEqual(a.playlists.first?.id, "a0000000-0000-4000-8000-00000000000a")
        XCTAssertEqual(a.lists.first?.id, "d0000000-0000-4000-8000-00000000000d")
    }

    func testOldDeletionsAreForgotten() {
        let day: Int64 = 86_400_000
        let snapshot = SyncSnapshot(device: "a", deleted: ["list:old": 0, "list:recent": 170 * day, "playlist:old": 0, "playlist:new": 190 * day],
                                    replaced: ["old": "new", "new": "other"])
        let pruned = snapshot.pruned(now: 200 * day)
        XCTAssertEqual(Set(pruned.deleted.keys), ["list:recent", "playlist:new"])
        XCTAssertEqual(pruned.replaced, ["new": "other"])
    }

    private func local(_ address: String, file: StaticString = #filePath, line: UInt = #line) -> Bool {
        guard let bytes = SyncNetwork.addressBytes(address) else {
            XCTFail("not an address: \(address)", file: file, line: line)
            return false
        }
        return SyncNetwork.isLocal(bytes)
    }

    func testOnlyLocalNetworkAddressesAreLocal() {
        // Private, link-local and loopback ranges, IPv4 and IPv6 (and IPv4 mapped in IPv6).
        let localAddresses = [
            "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.254", "192.168.1.20", "169.254.3.4", "127.0.0.1", // leak-guard: allow
            "fd12:3456:789a::1", "fc00::1", "fe80::1", "::1", "::ffff:192.168.1.20", // leak-guard: allow
        ]
        for address in localAddresses { XCTAssertTrue(local(address), address) }
        // Everything else, among them the neighbours of each range.
        let otherAddresses = [
            "8.8.8.8", "9.255.255.255", "11.0.0.1", "172.15.255.255", "172.32.0.1", "192.167.1.1", "192.169.0.1", // leak-guard: allow
            "169.253.0.1", "100.64.0.1", "1.1.1.1", "2001:db8::1", "2a00:1450::1", "fec0::1", "::ffff:8.8.8.8", // leak-guard: allow
        ]
        for address in otherAddresses { XCTAssertFalse(local(address), address) }
        XCTAssertFalse(SyncNetwork.isLocal([]))
        XCTAssertFalse(SyncNetwork.isLocal([10, 0, 0]))
        XCTAssertNil(SyncNetwork.addressBytes("example.com"))
    }

    func testAFrameIsItsLengthThenItsBytes() throws {
        let frame = try SyncNetwork.frame(Data("hello".utf8))
        XCTAssertEqual([UInt8](frame.prefix(4)), [0, 0, 0, 5])
        XCTAssertEqual(String(data: try SyncNetwork.readFrame(frame), encoding: .utf8), "hello")
        XCTAssertEqual(try SyncNetwork.frameLength(Data([0, 1, 0, 0])), 65_536)
        // Too large, or cut short: refused.
        XCTAssertThrowsError(try SyncNetwork.readFrame(Data([0x7F, 0, 0, 0]))) { XCTAssertEqual($0 as? SyncNetwork.FrameError, .tooLarge) }
        XCTAssertThrowsError(try SyncNetwork.readFrame(Data([0x80, 0, 0, 0]))) { XCTAssertEqual($0 as? SyncNetwork.FrameError, .tooLarge) }
        XCTAssertThrowsError(try SyncNetwork.readFrame(Data([0, 0, 0, 9, 1, 2]))) { XCTAssertEqual($0 as? SyncNetwork.FrameError, .cutShort) }
        XCTAssertThrowsError(try SyncNetwork.frame(Data(count: SyncNetwork.maxFrame + 1)))
    }
}
