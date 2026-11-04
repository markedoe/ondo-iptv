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

    /// Profiles of the shared examples (docs/SYNC.md, "Profiles"): the same result whichever merges, and merging it again.
    func testTheProfilesOfTheSharedExamplesMergeInEitherOrder() throws {
        let a = try fixture("profiles-a.json")
        let b = try fixture("profiles-b.json")
        let expected = try fixture("profiles-merged.json")
        XCTAssertEqual(SyncMerge.merge(a, b), expected)
        XCTAssertEqual(withoutDevice(SyncMerge.merge(b, a)), withoutDevice(expected))
        XCTAssertEqual(SyncMerge.merge(expected, b), expected)
        // The device that still has the deleted profile (and its data) does not bring it back.
        XCTAssertEqual(SyncMerge.merge(expected, a), expected)
    }

    /// A profile deleted on one device at 100 while another kept watching in it at 200: the watch wins (review S2).
    func testAProfileDeletionLosesToDataAddedAfterIt() throws {
        let a = try fixture("profiles-late-a.json")
        let b = try fixture("profiles-late-b.json")
        let expected = try fixture("profiles-late-merged.json")
        XCTAssertEqual(SyncMerge.merge(a, b), expected)
        XCTAssertEqual(withoutDevice(SyncMerge.merge(b, a)), withoutDevice(expected))
        XCTAssertEqual(SyncMerge.merge(expected, a), expected)
        var older = b
        older.profileData = b.profileData.map { bucket in
            var bucket = bucket
            bucket.progress = bucket.progress.map { progress in
                var progress = progress
                progress.watchedAt = 100
                return progress
            }
            return bucket
        }
        let merged = SyncMerge.merge(a, older)
        XCTAssertTrue(merged.profiles.isEmpty)
        XCTAssertTrue(merged.profileData.isEmpty)
    }

    /// A deletion in a profile learned late is kept from when it was learned, as the main profile's are (review S6).
    func testADeletionLearnedLateInAProfileIsKeptFromWhenItWasLearned() {
        let day: Int64 = 86_400_000
        let now = 400 * day
        let old = now - 200 * day
        let key = "favorite:p|movie|m1"
        let profile = "11111111-1111-4111-8111-111111111111"
        let snapshot = SyncSnapshot(device: "x", profiles: [SyncProfile(id: profile, updatedAt: 1)],
                                    profileData: [SyncProfileData(profile: profile, deleted: [key: old])], deleted: [key: old])
        let pruned = snapshot.pruned(now: now, learned: [key: now - day], profileLearned: [profile: [key: now - day]])
        XCTAssertEqual(pruned.deleted.count, 1)
        XCTAssertEqual(pruned.profileData.first?.deleted.count, 1)
        XCTAssertEqual(snapshot.pruned(now: now).profileData.first?.deleted.count, 0)
    }

    /// Kids lifted on an unprotected device does not lift it where a parent set a PIN; a device keeps Kids, takes the
    /// record's time, and the next sessions change nothing (review S3).
    func testKidsLiftedElsewhereStaysOnWhereAParentSetAPIN() throws {
        XCTAssertTrue(SyncProfiles.keptKids(local: true, received: false, parentProtected: true))
        XCTAssertFalse(SyncProfiles.keptKids(local: true, received: false, parentProtected: false))
        XCTAssertTrue(SyncProfiles.keptKids(local: false, received: true, parentProtected: false))
        XCTAssertTrue(SyncProfiles.keptKids(local: false, received: true, parentProtected: true))
        let id = "11111111-1111-4111-8111-111111111111"
        let tv = SyncSnapshot(device: "tv", profiles: [SyncProfile(id: id, name: "Kid", kids: true, updatedAt: 100)])
        let ipad = SyncSnapshot(device: "ipad", profiles: [SyncProfile(id: id, name: "Kid", kids: false, updatedAt: 101)])
        let merged = SyncMerge.merge(tv, ipad)
        XCTAssertEqual(merged.profiles.first?.kids, false)
        var kept = try XCTUnwrap(merged.profiles.first)
        kept.kids = SyncProfiles.keptKids(local: true, received: kept.kids, parentProtected: true)
        var tvAfter = tv
        tvAfter.profiles = [kept]
        XCTAssertEqual(SyncMerge.merge(tvAfter, ipad).profiles, merged.profiles)
        XCTAssertEqual(SyncMerge.merge(SyncMerge.merge(tvAfter, ipad), tvAfter), SyncMerge.merge(tvAfter, ipad))
    }

    /// Profiles of the same name made apart on two devices are matched once; the smaller ID is kept (review S1).
    func testProfilesOfTheSameNameMadeApartAreMatchedOnce() {
        let small = "11111111-1111-4111-8111-111111111111"
        let large = "22222222-2222-4222-8222-222222222222"
        let first = SyncProfiles.matches(local: [SyncProfile(id: small, name: "Sam", updatedAt: 10)], received: [SyncProfile(id: large, name: "Sam", updatedAt: 11)])
        XCTAssertEqual(first.map(\.dropped), [large])
        XCTAssertEqual(first.map(\.kept), [small])
        let accented = SyncProfiles.matches(local: [SyncProfile(id: large, name: " ZOÉ ", updatedAt: 10)], received: [SyncProfile(id: small, name: "zoe", updatedAt: 11)])
        XCTAssertEqual(accented.map(\.dropped), [large])
        XCTAssertEqual(accented.map(\.kept), [small])
        XCTAssertTrue(SyncProfiles.matches(local: [SyncProfile(id: small, name: "Sam", kids: true, updatedAt: 1)], received: [SyncProfile(id: large, name: "Sam", updatedAt: 1)]).isEmpty)
        XCTAssertTrue(SyncProfiles.matches(local: [SyncProfile(id: SyncProfile.main, name: "Me", updatedAt: 1)], received: [SyncProfile(id: large, name: "Me", updatedAt: 1)]).isEmpty)
    }

    /// An older device knows nothing of profiles: its data is the main profile's, and the profiles stay as they are.
    func testAnOlderDeviceWithoutProfilesMapsToTheMainProfile() throws {
        let merged = try fixture("profiles-merged.json")
        let playlist = try XCTUnwrap(merged.playlists.first)
        let older = SyncSnapshot(device: "77777777-7777-4777-8777-777777777777", playlists: [playlist],
                                 favorites: [SyncFavorite(playlist: playlist.id, kind: "movie", item: "m9", title: "Older device", addedAt: 3_500)])
        let result = SyncMerge.merge(merged, older)
        XCTAssertEqual(result.profiles, merged.profiles)
        XCTAssertEqual(result.profileData, merged.profileData)
        XCTAssertTrue(result.favorites.contains { $0.item == "m9" })
        XCTAssertFalse(result.profileData.contains { $0.favorites.contains { $0.item == "m9" } })
        XCTAssertEqual(SyncMerge.merge(older, merged).favorites, result.favorites)
    }

    /// A watch on one profile stays in that profile, on whichever device merges.
    func testAWatchOnOneProfileIsNeverAppliedToAnother() {
        let playlist = SyncPlaylist(id: "p", name: "Home", kind: SyncPlaylist.m3u, url: "http://example.com/a.m3u", addedAt: 1, updatedAt: 1)
        let sam = SyncProfile(id: "11111111-1111-4111-8111-111111111111", name: "Sam", updatedAt: 10)
        let ana = SyncProfile(id: "33333333-3333-4333-8333-333333333333", name: "Ana", updatedAt: 10)
        let watch = SyncProgress(playlist: "p", kind: "movie", item: "m1", title: "M1", position: 600, duration: 6000, watchedAt: 20)
        let device1 = SyncSnapshot(device: "d1", playlists: [playlist], profiles: [sam, ana], profileData: [SyncProfileData(profile: sam.id, progress: [watch])])
        let device2 = SyncSnapshot(device: "d2", playlists: [playlist], profiles: [sam, ana])
        let merged = SyncMerge.merge(device2, device1)
        XCTAssertEqual(merged.profileData.first { $0.profile == sam.id }?.progress, [watch])
        XCTAssertFalse(merged.profileData.contains { $0.profile == ana.id })
        XCTAssertTrue(merged.progress.isEmpty)
    }

    /// A profile's PIN never travels: the record has no field for it, and a snapshot that sends one keeps none.
    func testAProfileRecordCarriesNoPIN() throws {
        let json = #"{"type":"ondo-sync","version":1,"device":"d","profiles":[{"id":"x","name":"Sam","pinHash":"ab","updatedAt":5}]}"#
        let snapshot = try XCTUnwrap(SyncSnapshot.decode(Data(json.utf8)))
        XCTAssertFalse(String(decoding: try snapshot.encoded(), as: UTF8.self).lowercased().contains("pin"))
    }

    func testTheRowOrderOfTheSharedExamplesMergesInEitherOrder() throws {
        let a = try fixture("rows-a.json")
        let b = try fixture("rows-b.json")
        let expected = try fixture("rows-merged.json")
        XCTAssertEqual(SyncMerge.merge(a, b), expected)
        XCTAssertEqual(withoutDevice(SyncMerge.merge(b, a)), withoutDevice(expected))
        XCTAssertEqual(SyncMerge.merge(expected, b), expected)
    }

    /// Two devices put the rows in order at about the same time: both keep the later one, whichever merges.
    func testConcurrentRowOrdersConvergeToTheLaterStamp() {
        var clockA = SyncClock(last: 1_000)
        var clockB = SyncClock(last: 1_000)
        let earlier = SyncRows(tab: SyncRows.movies, order: ["all", "cat:movie-1|action"], updatedAt: clockA.stamp(wall: 5_000))
        let later = SyncRows(tab: SyncRows.movies, order: ["cat:movie-1|action", "all"], hidden: ["trending"],
                             updatedAt: clockB.stamp(wall: 5_001))
        let a = SyncSnapshot(device: "a", rows: [earlier])
        let b = SyncSnapshot(device: "b", rows: [later])
        XCTAssertEqual(SyncMerge.merge(a, b).rows, [later])
        XCTAssertEqual(SyncMerge.merge(b, a).rows, [later])
        // A third device that saw neither ends up with the same.
        let c = SyncSnapshot(device: "c")
        XCTAssertEqual(SyncMerge.merge(SyncMerge.merge(c, a), b).rows, SyncMerge.merge(SyncMerge.merge(c, b), a).rows)
        // On equal stamps, the smaller tie string, whatever the order.
        let same = SyncRows(tab: SyncRows.movies, order: ["all"], updatedAt: earlier.updatedAt)
        let tied = SyncMerge.merge(a, SyncSnapshot(device: "b", rows: [same])).rows
        XCTAssertEqual(SyncMerge.merge(SyncSnapshot(device: "b", rows: [same]), a).rows, tied)
        XCTAssertEqual(tied, [same])
    }

    /// An older version sends no rows: the merge keeps the device's own, and its snapshot reads as before.
    func testASnapshotWithoutRowsChangesNone() throws {
        let mine = SyncSnapshot(device: "a", rows: [SyncRows(tab: SyncRows.series, order: ["mylist", "all"], hidden: ["new"], updatedAt: 700)])
        let old = try XCTUnwrap(SyncSnapshot.decode(Data(#"{"type":"ondo-sync","version":1,"device":"old","keys":[]}"#.utf8)))
        XCTAssertEqual(old.rows, [])
        XCTAssertEqual(SyncMerge.merge(mine, old).rows, mine.rows)
        XCTAssertEqual(SyncMerge.merge(old, mine).rows, mine.rows)
        XCTAssertEqual(SyncSnapshot.decode(try mine.encoded()), mine)
    }

    func testTheDigestOfTheRowsIsStableAndFollowsAChange() throws {
        let hash: (Data) -> String = { $0.base64EncodedString() }
        let rows = SyncRows(tab: SyncRows.movies, order: ["all", "cat:movie-1|action"], hidden: ["awards"], updatedAt: 900)
        let snapshot = SyncSnapshot(device: "a", rows: [rows])
        let digest = hash(try snapshot.contentForDigest())
        var moved = snapshot
        moved.device = "b"
        moved.clock = 5
        XCTAssertEqual(hash(try moved.contentForDigest()), digest)
        XCTAssertEqual(hash(try SyncMerge.merge(snapshot, snapshot).contentForDigest()), digest)
        XCTAssertEqual(hash(try SyncMerge.merge(snapshot, SyncSnapshot(device: "old")).contentForDigest()), digest)
        var reordered = snapshot
        reordered.rows = [SyncRows(tab: rows.tab, order: rows.order.reversed(), hidden: rows.hidden, updatedAt: rows.updatedAt)]
        XCTAssertNotEqual(hash(try reordered.contentForDigest()), digest)
        var shown = snapshot
        shown.rows = [SyncRows(tab: rows.tab, order: rows.order, updatedAt: rows.updatedAt)]
        XCTAssertNotEqual(hash(try shown.contentForDigest()), digest)
    }

    /// A record too large is trimmed as it is read, the same way on every device (as in Kotlin), and its digest is that
    /// of the trimmed record.
    func testAnOversizedRowsRecordIsTrimmedTheSameWayEachTime() throws {
        let hash: (Data) -> String = { $0.base64EncodedString() }
        let keys = (0..<2100).map { "k" + String(format: "%04d", $0) }
        let long = String(repeating: "x", count: SyncRows.maxKeyLength + 1)
        let tabs = (0...9).reversed().map { "t\($0)" } + [SyncRows.movies, long]
        let big = SyncSnapshot(device: "a", rows: tabs.map {
            SyncRows(tab: $0, order: [long] + keys + [keys[0]], hidden: keys.reversed(), updatedAt: 5)
        })
        let read = try XCTUnwrap(SyncSnapshot.decode(try big.encoded()))
        XCTAssertEqual(read.rows.map(\.tab), ["t6", "t5", "t4", "t3", "t2", "t1", "t0", "movie"])
        for rows in read.rows {
            XCTAssertEqual(rows.order, Array(keys.prefix(SyncRows.maxKeys)))
            XCTAssertEqual(rows.hidden, Array(keys.prefix(SyncRows.maxKeys)))
        }
        XCTAssertEqual(SyncSnapshot.decode(try read.encoded()), read)
        let again = try XCTUnwrap(SyncSnapshot.decode(try big.encoded()))
        XCTAssertEqual(hash(try again.contentForDigest()), hash(try read.contentForDigest()))
        let once = SyncMerge.merge(read, read)
        XCTAssertEqual(hash(try SyncMerge.merge(once, big).contentForDigest()), hash(try once.contentForDigest()))
        // Two sides of eight tabs each merge into eight.
        let other = SyncSnapshot(device: "b", rows: (0..<8).map { SyncRows(tab: "u\($0)", order: ["all"], updatedAt: 1) })
        let merged = SyncMerge.merge(read, other)
        XCTAssertEqual(merged.rows.map(\.tab), ["movie", "t0", "t1", "t2", "t3", "t4", "t5", "t6"])
        XCTAssertEqual(SyncMerge.merge(other, read).rows, merged.rows)
    }

    /// A record dated far ahead of the receiver's clock is left out, as any other (docs/SYNC.md, "Clocks").
    func testRowsDatedFarAheadAreLeftOut() {
        let ahead = SyncSnapshot(device: "b", rows: [SyncRows(tab: SyncRows.movies, order: ["all"], updatedAt: 10_000)])
        XCTAssertEqual(ahead.withoutRecordsAfter(9_999).rows, [])
        XCTAssertEqual(ahead.withoutRecordsAfter(10_000).rows, ahead.rows)
        XCTAssertEqual(ahead.latestTime, 10_000)
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

    /// A device that writes its IDs in uppercase writes them so in its deletions too: they still remove the records,
    /// read in lowercase. The item of a favourite or a progress is the provider's ID and keeps its case.
    func testDeletionsAreReadWithTheirIDsInLowercase() throws {
        let playlist = SyncPlaylist(id: "a0000000-0000-4000-8000-00000000000a", name: "Home", kind: SyncPlaylist.m3u,
                                    url: "http://example.com/a.m3u", addedAt: 1, updatedAt: 1)
        let other = SyncPlaylist(id: "c0000000-0000-4000-8000-00000000000c", name: "Office", kind: SyncPlaylist.m3u,
                                 url: "http://example.com/c.m3u", addedAt: 1, updatedAt: 1)
        let kept = SyncFavorite(playlist: other.id, kind: "movie", item: "M1", title: "Glass City", addedAt: 10)
        let removed = SyncFavorite(playlist: other.id, kind: "movie", item: "M2", title: "Quiet Water", addedAt: 10)
        let list = SyncList(id: "d0000000-0000-4000-8000-00000000000d", name: "Kids", updatedAt: 10)
        let a = SyncSnapshot(device: "a", playlists: [playlist, other], favorites: [kept, removed], lists: [list])
        let json = #"""
        {"type":"ondo-sync","version":1,"device":"b","deleted":{
          "playlist:A0000000-0000-4000-8000-00000000000A":20,
          "favorite:C0000000-0000-4000-8000-00000000000C|movie|M2":20,
          "favorite:C0000000-0000-4000-8000-00000000000C|movie|m1":20,
          "list:D0000000-0000-4000-8000-00000000000D":20,
          "list:d0000000-0000-4000-8000-00000000000d":30,
          "key:TMDB":20}}
        """#
        let b = try XCTUnwrap(SyncSnapshot.decode(Data(json.utf8)))
        XCTAssertEqual(b.deleted, [
            "playlist:a0000000-0000-4000-8000-00000000000a": 20,
            "favorite:c0000000-0000-4000-8000-00000000000c|movie|M2": 20,
            "favorite:c0000000-0000-4000-8000-00000000000c|movie|m1": 20,
            "list:d0000000-0000-4000-8000-00000000000d": 30,
            "key:TMDB": 20,
        ])
        for merged in [SyncMerge.merge(a, b), SyncMerge.merge(b, a)] {
            XCTAssertEqual(merged.playlists.map(\.id), [other.id])
            XCTAssertEqual(merged.favorites.map(\.item), ["M1"], "an item ID keeps its case")
            XCTAssertTrue(merged.lists.isEmpty)
        }
    }

    /// A list keeps its items of playlists still here or unknown to this device, and loses those of a deleted playlist;
    /// two versions changed in the same millisecond end as the one with the smaller name and items, in either order.
    func testListsLoseTheItemsOfADeletedPlaylistAndTiesEndTheSameWay() {
        let home = SyncPlaylist(id: "p", name: "Home", kind: SyncPlaylist.m3u, url: "http://example.com/a.m3u", addedAt: 1, updatedAt: 1)
        let items = [
            SyncListItem(playlist: "p", kind: "movie", item: "m1", title: "Glass City"),
            SyncListItem(playlist: "q", kind: "movie", item: "m2", title: "Quiet Water"),
            SyncListItem(playlist: "r", kind: "series", item: "s1", title: "Harbor Lights"),
        ]
        let a = SyncSnapshot(device: "a", playlists: [home], lists: [SyncList(id: "l", name: "Weekend", items: items, updatedAt: 10)])
        let b = SyncSnapshot(device: "b", lists: [SyncList(id: "l", name: "Kids", items: items, updatedAt: 10)], deleted: ["playlist:q": 5])
        let merged = SyncMerge.merge(a, b)
        XCTAssertEqual(merged.lists.map(\.name), ["Kids"])
        XCTAssertEqual(merged.lists.first?.items.map(\.item), ["m1", "s1"], "q was deleted; r is a playlist this device does not know")
        XCTAssertEqual(withoutDevice(SyncMerge.merge(b, a)), withoutDevice(merged))
    }

    /// A playlist that became another, which became a third: its records follow to the last one. A loop of
    /// replacements (written by no device, but possible in a snapshot) ends after a bounded number of steps.
    func testRecordsFollowAChainOfReplacementsAndALoopEnds() {
        let last = SyncPlaylist(id: "c", name: "Home", kind: SyncPlaylist.m3u, url: "http://example.com/a.m3u", addedAt: 1, updatedAt: 1)
        let favorite = SyncFavorite(playlist: "a", kind: "movie", item: "m1", title: "Glass City", addedAt: 10)
        let chain = SyncMerge.merge(SyncSnapshot(device: "a", playlists: [last], favorites: [favorite]),
                                    SyncSnapshot(device: "b", replaced: ["a": "b", "b": "c"]))
        XCTAssertEqual(chain.favorites.map(\.playlist), ["c"])

        let loop = SyncMerge.merge(SyncSnapshot(device: "a", favorites: [favorite]),
                                   SyncSnapshot(device: "b", replaced: ["a": "b", "b": "a"]))
        XCTAssertEqual(loop.favorites.count, 1)
        XCTAssertEqual(loop.replaced, ["a": "b", "b": "a"])
    }

    func testOldDeletionsAreForgotten() {
        let day: Int64 = 86_400_000
        let snapshot = SyncSnapshot(device: "a", deleted: ["list:old": 0, "list:recent": 170 * day, "playlist:old": 0, "playlist:new": 190 * day],
                                    replaced: ["old": "new", "new": "other"])
        let pruned = snapshot.pruned(now: 200 * day)
        XCTAssertEqual(Set(pruned.deleted.keys), ["list:recent", "playlist:new"])
        XCTAssertEqual(pruned.replaced, ["new": "other"])
    }

    func testADeletionStaysUntilEachDeviceSeenThisYearHasHadIt() {
        let day: Int64 = 86_400_000
        let now = 1_000 * day
        let snapshot = SyncSnapshot(device: "a", deleted: ["list:old": now - 300 * day, "list:older": now - 250 * day, "list:recent": now - 10 * day])
        // A device last synced 260 days ago has not had the deletion of 250 days ago: it stays, the older one goes.
        XCTAssertEqual(Set(snapshot.pruned(now: now, sessions: [now - 260 * day, now - 1 * day]).deleted.keys), ["list:older", "list:recent"])
        // Once it has synced since, both old ones go; a device not seen for over a year holds nothing back.
        XCTAssertEqual(Set(snapshot.pruned(now: now, sessions: [now - 100 * day]).deleted.keys), ["list:recent"])
        XCTAssertEqual(Set(snapshot.pruned(now: now, sessions: [now - 400 * day]).deleted.keys), ["list:recent"])
    }

    private func local(_ address: String, file: StaticString = #filePath, line: UInt = #line) -> Bool {
        guard let bytes = SyncNetwork.addressBytes(address) else {
            XCTFail("not an address: \(address)", file: file, line: line)
            return false
        }
        return SyncNetwork.isLocal(bytes)
    }

    // MARK: People kept in My List and in lists

    func testPeopleMergeLikeTheOtherRecordsOfMyListInEitherOrder() throws {
        let a = try fixture("people-a.json")
        let b = try fixture("people-b.json")
        let expected = try fixture("people-merged.json")
        XCTAssertEqual(SyncMerge.merge(a, b), expected)
        XCTAssertEqual(withoutDevice(SyncMerge.merge(b, a)), withoutDevice(expected))
        XCTAssertEqual(SyncMerge.merge(expected, expected), expected)
        // The same name in another case is one person; the one with a photo wins at the same time.
        XCTAssertEqual(expected.people.first?.image, "https://example.com/tom.jpg")
    }

    func testAPersonRemovedOnOneDeviceIsRemovedOnTheOther() {
        let added = SyncSnapshot(device: "a", people: [SyncPerson(name: "Ada Example", addedAt: 10)])
        let removed = SyncSnapshot(device: "b", deleted: ["person:ADA EXAMPLE": 20])
        XCTAssertTrue(SyncMerge.merge(added, removed).people.isEmpty)
        XCTAssertEqual(SyncMerge.merge(added, removed).deleted, ["person:ada example": 20])
        // Added again after the removal: back.
        let again = SyncSnapshot(device: "a", people: [SyncPerson(name: "Ada Example", addedAt: 30)])
        XCTAssertEqual(SyncMerge.merge(again, removed).people.map(\.name), ["Ada Example"])
    }

    func testADeviceThatDoesNotKnowPeopleRemovesNone() throws {
        // `a.json` is written by a build without people: they and the people of a list stay.
        let withPeople = try fixture("people-a.json")
        var old = withPeople
        old.people = []
        old.lists = withPeople.lists.map { list in
            var list = list
            list.people = []
            return list
        }
        for merged in [SyncMerge.merge(withPeople, old), SyncMerge.merge(old, withPeople), SyncMerge.merge(withPeople, try fixture("a.json"))] {
            XCTAssertEqual(merged.people.map(\.name), ["Tom Example", "Ada Example"])
            XCTAssertEqual(merged.lists.first { $0.id == "d1000000-0000-4000-8000-00000000000d" }?.people.map(\.name), ["Ada Example"])
        }
    }

    func testPeopleFieldsAreReadAndWrittenUnderTheSharedNames() throws {
        let snapshot = SyncSnapshot(device: "a", lists: [SyncList(id: "l", name: "L", people: [SyncPerson(name: "Ada", image: "https://example.com/a.jpg", addedAt: 1)], updatedAt: 1)],
                                    people: [SyncPerson(name: "Tom", addedAt: 2)])
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: try snapshot.encoded()) as? [String: Any])
        XCTAssertEqual((object["people"] as? [[String: Any]])?.first?.keys.sorted(), ["addedAt", "name"])
        let list = try XCTUnwrap((object["lists"] as? [[String: Any]])?.first)
        XCTAssertEqual((list["people"] as? [[String: Any]])?.first?.keys.sorted(), ["addedAt", "image", "name"])
        XCTAssertEqual(SyncSnapshot.decode(try snapshot.encoded()), snapshot)
    }

    func testOnlyAsciiLettersFoldInAPersonKey() {
        XCTAssertEqual(SyncPerson(name: "ÉLODIE Example", addedAt: 0).key, "person:Élodie example")
        XCTAssertEqual(SyncSnapshot.lowercasingID(ofDeleted: "person:ΣΟΦΙΑ Ex"), "person:ΣΟΦΙΑ ex")
    }

    // MARK: Probes

    func testAProbeGoesWhenNothingChangedSinceTheLastSnapshotSent() throws {
        let hash: (Data) -> String = { $0.base64EncodedString() }
        let snapshot = try fixture("people-a.json")
        let full = try snapshot.offer(sentDigest: nil, answerDigest: "server-1", hash: hash)
        XCTAssertFalse(full.isProbe)
        let digest = try XCTUnwrap(full.digest)
        // Who sends it, when, and the order of its deletions do not change the digest; a record does.
        var same = snapshot
        same.device = "other"
        same.deviceName = nil
        same.clock = 42
        XCTAssertEqual(hash(try same.contentForDigest()), digest)
        var changed = snapshot
        changed.people.removeLast()
        XCTAssertNotEqual(hash(try changed.contentForDigest()), digest)

        let probe = try snapshot.offer(sentDigest: digest, answerDigest: "server-1", hash: hash)
        XCTAssertTrue(probe.isProbe)
        XCTAssertEqual(probe.seen, "server-1")
        XCTAssertTrue(probe.people.isEmpty && probe.lists.isEmpty && probe.deleted.isEmpty)
        // Without the server's digest (an older version answered), the whole snapshot goes.
        XCTAssertFalse(try snapshot.offer(sentDigest: digest, answerDigest: nil, hash: hash).isProbe)
        // Decoded by an older version, a probe is an empty snapshot: merging it removes nothing.
        XCTAssertEqual(withoutDevice(SyncMerge.merge(snapshot, try XCTUnwrap(SyncSnapshot.decode(try probe.encoded())))), withoutDevice(SyncMerge.merge(snapshot, snapshot)))
    }

    // MARK: Lists, item by item

    func testListsMergeItemByItemInEitherOrder() throws {
        let a = try fixture("lists-a.json")
        let b = try fixture("lists-b.json")
        let expected = try fixture("lists-merged.json")
        XCTAssertEqual(SyncMerge.merge(a, b), expected)
        XCTAssertEqual(withoutDevice(SyncMerge.merge(b, a)), withoutDevice(expected))
        XCTAssertEqual(SyncMerge.merge(expected, expected), expected)
        XCTAssertEqual(SyncMerge.merge(expected, b), expected)
    }

    func testTwoDevicesAddingAndRemovingInTheSameListKeepEveryChange() {
        let start = SyncList(id: "l", name: "Weekend", items: [SyncListItem(playlist: "p", kind: "movie", item: "m1", title: "Glass City", addedAt: 10),
                                                               SyncListItem(playlist: "p", kind: "movie", item: "m2", title: "Quiet Water", addedAt: 10)], updatedAt: 10)
        // At the same moment, one device adds m3 and removes m1, the other adds m4 and a person, and removes m2.
        var one = start
        one.items = [start.items[1], SyncListItem(playlist: "p", kind: "movie", item: "m3", title: "Harbor Lights", addedAt: 20)]
        one.updatedAt = 20
        var other = start
        other.items = [start.items[0], SyncListItem(playlist: "p", kind: "movie", item: "m4", title: "Late Show", addedAt: 20)]
        other.people = [SyncPerson(name: "Ada Example", addedAt: 20)]
        other.updatedAt = 20
        let a = SyncSnapshot(device: "a", lists: [one], deleted: [start.itemKey("p|movie|m1"): 20])
        let b = SyncSnapshot(device: "b", lists: [other], deleted: [start.itemKey("p|movie|m2"): 20])
        let merged = SyncMerge.merge(a, b)
        XCTAssertEqual(merged.lists.flatMap { $0.items.map(\.item) }, ["m4", "m3"], "in the order of the list put in order last (a tie: the smaller order)")
        XCTAssertEqual(merged.lists.flatMap { $0.people.map(\.name) }, ["Ada Example"])
        XCTAssertEqual(withoutDevice(SyncMerge.merge(b, a)), withoutDevice(merged))
        // An item put back after its removal comes back.
        var back = merged.lists[0]
        back.items.append(SyncListItem(playlist: "p", kind: "movie", item: "m1", title: "Glass City", addedAt: 30))
        back.updatedAt = 30
        XCTAssertEqual(SyncMerge.merge(merged, SyncSnapshot(device: "b", lists: [back])).lists[0].items.map(\.item), ["m1", "m4", "m3"], "back in its place in the order set last")
    }

    func testListItemDeletionsAreReadWithTheirIDsInLowercase() {
        XCTAssertEqual(SyncSnapshot.lowercasingID(ofDeleted: "listitem:L1|P1|movie|M1"), "listitem:l1|p1|movie|M1")
        XCTAssertEqual(SyncSnapshot.lowercasingID(ofDeleted: "listitem:L1|person:ADA Élodie"), "listitem:l1|person:ada Élodie")
    }

    func testADeletionLearnedLateIsKeptFromWhenItWasLearned() {
        let day: Int64 = 86_400_000
        let now = 1_000 * day
        let snapshot = SyncSnapshot(device: "a", deleted: ["favorite:p|movie|m1": now - 250 * day])
        XCTAssertTrue(snapshot.pruned(now: now, sessions: [now - day]).deleted.isEmpty, "by its date alone, it goes")
        XCTAssertEqual(snapshot.pruned(now: now, sessions: [now - day], learned: ["favorite:p|movie|m1": now - 2 * day]).deleted.count, 1)
        XCTAssertTrue(snapshot.pruned(now: now, sessions: [now - day], learned: ["favorite:p|movie|m1": now - 200 * day]).deleted.isEmpty)
    }

    func testTiesBetweenRecordsDifferingInAnyFieldEndTheSameWayInEitherOrder() {
        let image1 = SyncSnapshot(device: "a", favorites: [SyncFavorite(playlist: "p", kind: "movie", item: "m1", title: "T", image: "https://a/1.jpg", addedAt: 5)])
        let image2 = SyncSnapshot(device: "b", favorites: [SyncFavorite(playlist: "p", kind: "movie", item: "m1", title: "T", image: "https://a/2.jpg", addedAt: 5)])
        let none = SyncSnapshot(device: "c", favorites: [SyncFavorite(playlist: "p", kind: "movie", item: "m1", title: "T", addedAt: 5)])
        for pair in [(image1, image2), (image1, none), (image2, none)] {
            XCTAssertEqual(SyncMerge.merge(pair.0, pair.1).favorites, SyncMerge.merge(pair.1, pair.0).favorites)
        }
        let long = SyncSnapshot(device: "a", progress: [SyncProgress(playlist: "p", kind: "movie", item: "m1", title: "T", position: 10, duration: 7_000, watchedAt: 5)])
        let short = SyncSnapshot(device: "b", progress: [SyncProgress(playlist: "p", kind: "movie", item: "m1", title: "T", subtitle: "S", position: 10, duration: 6_900, watchedAt: 5)])
        XCTAssertEqual(SyncMerge.merge(long, short).progress.map(\.duration), [7_000])
        XCTAssertEqual(SyncMerge.merge(short, long).progress.map(\.duration), [7_000])
        let m3u = SyncPlaylist(id: "p", name: "Home", kind: SyncPlaylist.m3u, url: "http://example.com/a.m3u", addedAt: 1, updatedAt: 5)
        var older = m3u
        older.addedAt = 0
        XCTAssertEqual(SyncMerge.merge(SyncSnapshot(device: "a", playlists: [m3u]), SyncSnapshot(device: "b", playlists: [older])).playlists,
                       SyncMerge.merge(SyncSnapshot(device: "b", playlists: [older]), SyncSnapshot(device: "a", playlists: [m3u])).playlists)
    }

    func testListOrderEndsTheSameWhateverTheOrderOfTheMergesOfThreeDevices() {
        func list(_ keys: [String], added: Int64) -> SyncSnapshot {
            SyncSnapshot(device: "d", lists: [SyncList(id: "l", name: "L", items: keys.map {
                SyncListItem(playlist: "p", kind: "movie", item: $0, title: $0, addedAt: added)
            }, updatedAt: 10, orderedAt: 10)])
        }
        let a = list(["x", "y"], added: 1), b = list(["y", "x", "z"], added: 2), c = list(["w", "x"], added: 3)
        let left = SyncMerge.merge(SyncMerge.merge(a, b), c)
        let right = SyncMerge.merge(a, SyncMerge.merge(b, c))
        let other = SyncMerge.merge(SyncMerge.merge(c, a), b)
        XCTAssertEqual(withoutDevice(left).lists, withoutDevice(right).lists)
        XCTAssertEqual(withoutDevice(left).lists, withoutDevice(other).lists)
        XCTAssertEqual(left.lists.first?.items.map(\.item).sorted(), ["w", "x", "y", "z"])
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
