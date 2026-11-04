import XCTest
@testable import IPTVCore

/// Hidden categories, languages and styles in sync (docs/SYNC.md, "Hidden categories"): the shared examples, each one merging
/// on its own, older devices, the digest, the limits. The same cases as the Kotlin `SyncHiddenTest` (android/core).
final class SyncHiddenTests: XCTestCase {
    private let playlistID = "a1000000-0000-4000-8000-00000000000a"
    private var playlist: SyncPlaylist {
        SyncPlaylist(id: playlistID, name: "Home", kind: SyncPlaylist.m3u, url: "http://example.com/home.m3u", addedAt: 1, updatedAt: 1)
    }
    private let sam = SyncProfile(id: "c3000000-0000-4000-8000-00000000000c", name: "Sam", updatedAt: 5)

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

    /// What a device of an older version keeps of `snapshot` and sends on: it reads it ignoring the fields it does not
    /// know (`hiddenCategories`; before profiles, `profiles` and `profileData` too), and keeps every key of `deleted` as it
    /// comes (Android's `syncDeleted`, Apple's `sync.json`).
    private func olderVersion(_ snapshot: SyncSnapshot, knowsProfiles: Bool) throws -> SyncSnapshot {
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: try snapshot.encoded()) as? [String: Any])
        object["hiddenCategories"] = nil
        if knowsProfiles {
            object["profileData"] = (object["profileData"] as? [[String: Any]] ?? []).map { bucket in
                var bucket = bucket
                bucket["hiddenCategories"] = nil
                return bucket
            }
        } else {
            object["profiles"] = nil
            object["profileData"] = nil
        }
        return try XCTUnwrap(SyncSnapshot.decode(try JSONSerialization.data(withJSONObject: object)))
    }

    private func bucket(_ snapshot: SyncSnapshot, _ profile: String) -> [SyncHidden]? {
        snapshot.profileData.first { $0.profile == profile }?.hiddenCategories
    }

    func testMergingTheHiddenExamplesGivesTheSharedResultInEitherOrder() throws {
        let a = try fixture("hidden-a.json")
        let b = try fixture("hidden-b.json")
        let expected = try fixture("hidden-merged.json")
        XCTAssertEqual(SyncMerge.merge(a, b), expected)
        XCTAssertEqual(withoutDevice(SyncMerge.merge(b, a)), withoutDevice(expected))
        // Merging again changes nothing.
        let merged = SyncMerge.merge(a, b)
        XCTAssertEqual(SyncMerge.merge(merged, merged), merged)
        XCTAssertEqual(SyncMerge.merge(merged, b), merged)
    }

    /// Each hidden category, language and style merges on its own: showing one again later wins over hiding it, hiding it
    /// again later wins over that, and the others stay as they are; on equal times, the showing again wins.
    func testEachHiddenItemMergesOnItsOwnTheLaterChangeWinning() {
        var tvClock = SyncClock(last: 1_000)
        let drama = SyncHidden.forCategory(playlistID: playlistID, categoryID: "movie-12", hiddenAt: tvClock.stamp(wall: 0))
        let portuguese = SyncHidden(kind: SyncHidden.tag, value: "PT", hiddenAt: tvClock.stamp(wall: 0))
        let tv = SyncSnapshot(device: "tv", playlists: [playlist], hiddenCategories: [drama, portuguese])
        // The phone saw the TV's hides, then showed the drama category again.
        var phoneClock = SyncClock()
        _ = phoneClock.receive(tv, wall: 0)
        let shownAt = phoneClock.stamp(wall: 0)
        let phone = SyncSnapshot(device: "phone", playlists: [playlist], hiddenCategories: [portuguese], deleted: [drama.key: shownAt])
        for merged in [SyncMerge.merge(tv, phone), SyncMerge.merge(phone, tv)] {
            XCTAssertEqual(merged.hiddenCategories, [portuguese])
        }
        // Hidden again on the TV after it heard of that: hidden everywhere again.
        _ = tvClock.receive(phone, wall: 0)
        var again = drama
        again.hiddenAt = tvClock.stamp(wall: 0)
        var tvAgain = tv
        tvAgain.hiddenCategories = [again, portuguese]
        for merged in [SyncMerge.merge(tvAgain, phone), SyncMerge.merge(phone, tvAgain)] {
            XCTAssertEqual(merged.hiddenCategories, [again, portuguese])
        }
        // On equal times, the showing again wins.
        var tie = phone
        tie.deleted = [drama.key: drama.hiddenAt]
        XCTAssertEqual(SyncMerge.merge(tv, tie).hiddenCategories, [portuguese])
        // A language shown again leaves the categories hidden one by one, and the other way round.
        XCTAssertEqual(SyncMerge.merge(tv, SyncSnapshot(device: "phone", deleted: [portuguese.key: shownAt])).hiddenCategories, [drama])
    }

    /// One profile's hidden categories never touch another's: Sam keeps his while the main profile shows its own again.
    func testEachProfileHasItsOwnHiddenCategories() {
        let drama = SyncHidden.forCategory(playlistID: playlistID, categoryID: "movie-12", hiddenAt: 10)
        let tv = SyncSnapshot(device: "tv", playlists: [playlist], hiddenCategories: [drama], profiles: [sam],
                              profileData: [SyncProfileData(profile: sam.id, hiddenCategories: [drama])])
        let phone = SyncSnapshot(device: "phone", playlists: [playlist], profiles: [sam], deleted: [drama.key: 20])
        for merged in [SyncMerge.merge(tv, phone), SyncMerge.merge(phone, tv)] {
            XCTAssertTrue(merged.hiddenCategories.isEmpty)
            XCTAssertEqual(bucket(merged, sam.id), [drama])
        }
        // Hidden in Sam's profile on the phone reaches Sam's profile only.
        let anime = SyncHidden(kind: SyncHidden.word, value: "anime", hiddenAt: 30)
        var phoneSam = phone
        phoneSam.profileData = [SyncProfileData(profile: sam.id, hiddenCategories: [anime])]
        let merged = SyncMerge.merge(tv, phoneSam)
        XCTAssertEqual(bucket(merged, sam.id), [drama, anime])
        XCTAssertTrue(merged.hiddenCategories.isEmpty)
    }

    /// A device of the current release, or of the one before profiles, knows nothing of hidden categories: what it keeps
    /// and sends back has none, and a newer device that merges it loses none of its own. A showing again is a deletion,
    /// which it keeps and passes on: a device that meets only it still hears of it.
    func testAnOlderDeviceLosesNoHiddenCategoryAndPassesOnShowingsAgain() throws {
        let drama = SyncHidden.forCategory(playlistID: playlistID, categoryID: "movie-12", hiddenAt: 11)
        let portuguese = SyncHidden(kind: SyncHidden.tag, value: "PT", hiddenAt: 10)
        let spanish = SyncHidden(kind: SyncHidden.tag, value: "ES", hiddenAt: 12)
        let tv = SyncSnapshot(device: "tv", playlists: [playlist], hiddenCategories: [drama, portuguese], profiles: [sam],
                              profileData: [SyncProfileData(profile: sam.id, hiddenCategories: [spanish])])
        for knowsProfiles in [true, false] {
            let older = SyncSnapshot(device: "old", playlists: [playlist])
            let kept = try olderVersion(SyncMerge.merge(older, try olderVersion(tv, knowsProfiles: knowsProfiles)), knowsProfiles: knowsProfiles)
            XCTAssertTrue(kept.hiddenCategories.isEmpty)
            // The TV merges the older device's answer: every hide stays, in each profile.
            let back = SyncMerge.merge(tv, kept)
            XCTAssertEqual(back.hiddenCategories, tv.hiddenCategories)
            XCTAssertEqual(bucket(back, sam.id), [spanish])

            // Shown again on the TV, passed on by the older device to a phone that met only it.
            var shown = tv
            shown.hiddenCategories = [portuguese]
            shown.deleted = [drama.key: 20]
            shown.profileData = [SyncProfileData(profile: sam.id, deleted: [spanish.key: 21])]
            let relayed = try olderVersion(SyncMerge.merge(kept, try olderVersion(shown, knowsProfiles: knowsProfiles)), knowsProfiles: knowsProfiles)
            XCTAssertEqual(relayed.deleted[drama.key], 20)
            var laterPortuguese = portuguese
            laterPortuguese.hiddenAt = 30
            let phone = SyncSnapshot(device: "phone", playlists: [playlist], hiddenCategories: [drama, laterPortuguese], profiles: [sam],
                                     profileData: [SyncProfileData(profile: sam.id, hiddenCategories: [spanish])])
            let after = SyncMerge.merge(phone, relayed)
            XCTAssertEqual(after.hiddenCategories, [laterPortuguese], "the drama category shown again; Portuguese hidden later stays")
            // Profiles do not travel through a device older than them: Sam keeps his, nothing lost.
            XCTAssertEqual(bucket(after, sam.id), knowsProfiles ? [] : [spanish])
        }
    }

    /// A snapshot without hidden categories is written byte for byte as before they synced: an older device and a newer one
    /// holding the same data give it the same digest, and a client keeps sending probes across the update.
    func testASnapshotWithoutHiddenCategoriesIsWrittenAndDigestedAsBefore() throws {
        let snapshot = SyncSnapshot(
            device: "a",
            rows: [SyncRows(tab: SyncRows.movies, order: ["all"], hidden: ["new"], updatedAt: 900)],
            profiles: [SyncProfile(id: "p", updatedAt: 1)],
            profileData: [SyncProfileData(profile: "p", rows: [SyncRows(tab: SyncRows.series, updatedAt: 5)])],
            deleted: ["list:x": 3]
        )
        // What the version before hidden categories hashed for it: every field but the optional ones, keys sorted.
        let before = #"{"deleted":{"list:x":3},"device":"","favorites":[],"keys":[],"lists":[],"people":[],"playlists":[],"#
            + #""profileData":[{"deleted":{},"favorites":[],"lists":[],"people":[],"profile":"p","progress":[],"#
            + #""rows":[{"hidden":[],"order":[],"tab":"series","updatedAt":5}]}],"#
            + #""profiles":[{"colorIndex":0,"id":"p","kids":false,"name":"","symbol":"initial","updatedAt":1}],"#
            + #""progress":[],"replaced":{},"rows":[{"hidden":["new"],"order":["all"],"tab":"movie","updatedAt":900}],"#
            + #""type":"ondo-sync","version":1}"#
        let content = try snapshot.contentForDigest()
        XCTAssertEqual(String(decoding: content, as: UTF8.self), before)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: try snapshot.encoded()) as? [String: Any])
        XCTAssertNil(object["hiddenCategories"])
        XCTAssertNil((object["profileData"] as? [[String: Any]])?.first?["hiddenCategories"])
        // Merged with a device that hides nothing: the same.
        XCTAssertEqual(try SyncMerge.merge(snapshot, SyncSnapshot(device: "b")).contentForDigest(), content)
        XCTAssertEqual(try SyncMerge.merge(snapshot, try olderVersion(snapshot, knowsProfiles: true)).contentForDigest(), content)
        // A hidden category changes it, in the snapshot's records as in a profile's, and reads back as it was written.
        let hidden = SyncHidden(kind: SyncHidden.tag, value: "PT", hiddenAt: 7)
        var withHidden = snapshot
        withHidden.hiddenCategories = [hidden]
        var inProfile = snapshot
        inProfile.profileData[0].hiddenCategories = [hidden]
        XCTAssertNotEqual(try withHidden.contentForDigest(), content)
        XCTAssertNotEqual(try inProfile.contentForDigest(), content)
        XCTAssertNotEqual(try withHidden.contentForDigest(), try inProfile.contentForDigest())
        XCTAssertEqual(SyncSnapshot.decode(try withHidden.encoded()), withHidden)
        XCTAssertEqual(SyncSnapshot.decode(try inProfile.encoded()), inProfile)
    }

    /// Too many hidden records are trimmed as they are read, the same way on every device: the `maxRecords` hidden last
    /// (on equal times the smaller key, in UTF-16 code units); a kind or a value longer than `maxLength`, blank or with a
    /// control character is dropped. A merge keeps the same, whatever the order.
    func testTooManyHiddenRecordsAreTrimmedTheSameWayOnEveryDevice() throws {
        let max = SyncHidden.maxRecords
        let records = (0..<(max + 100)).map { index in
            SyncHidden(kind: SyncHidden.tag, value: "T" + String(repeating: "0", count: 5 - String(index).count) + String(index),
                       hiddenAt: Int64(index / 3))
        }
        let invalid = [
            SyncHidden(kind: SyncHidden.word, value: String(repeating: "x", count: SyncHidden.maxLength + 1), hiddenAt: 1_000_000),
            SyncHidden(kind: SyncHidden.word, value: "a\u{7}b", hiddenAt: 1_000_000),
            SyncHidden(kind: SyncHidden.word, value: " ", hiddenAt: 1_000_000),
            SyncHidden(kind: "a:b", value: "x", hiddenAt: 1_000_000),
            SyncHidden(kind: SyncHidden.category, value: "no playlist", hiddenAt: 1_000_000),
        ]
        let big = SyncSnapshot(device: "a", hiddenCategories: records.reversed() + invalid)
        let read = try XCTUnwrap(SyncSnapshot.decode(try big.encoded()))
        let expected = Set(records.sorted { first, second in
            first.hiddenAt != second.hiddenAt ? first.hiddenAt > second.hiddenAt : first.key.isBefore(second.key)
        }.prefix(max).map(\.key))
        XCTAssertEqual(read.hiddenCategories.count, max)
        XCTAssertEqual(Set(read.hiddenCategories.map(\.key)), expected)
        XCTAssertTrue(expected.contains("hide:tag:T00099") && !expected.contains("hide:tag:T00101"), "on equal times, the smaller key")
        XCTAssertEqual(try XCTUnwrap(SyncSnapshot.decode(try read.encoded())).contentForDigest(), try read.contentForDigest())
        // Hidden later on another device: in, whatever the order of the merge, in a profile's records too.
        let later = records.prefix(50).map { record -> SyncHidden in
            var record = record
            record.hiddenAt += 10_000
            return record
        }
        let other = SyncSnapshot(device: "b", hiddenCategories: later)
        let merged = SyncMerge.merge(read, other)
        XCTAssertEqual(SyncMerge.merge(other, read).hiddenCategories, merged.hiddenCategories)
        XCTAssertEqual(merged.hiddenCategories.count, max)
        XCTAssertTrue(later.allSatisfy { merged.hiddenCategories.contains($0) })
        let inProfile = SyncSnapshot(device: "a", profiles: [sam], profileData: [SyncProfileData(profile: sam.id, hiddenCategories: records + invalid)])
        let profileRead = try XCTUnwrap(SyncSnapshot.decode(try inProfile.encoded()))
        XCTAssertEqual(Set((bucket(profileRead, sam.id) ?? []).map(\.key)), expected)
    }

    /// Hidden records dated far ahead of the receiver's clock are left out, as any other (docs/SYNC.md, "Clocks").
    func testHiddenCategoriesDatedFarAheadAreLeftOut() {
        let ahead = SyncHidden(kind: SyncHidden.tag, value: "PT", hiddenAt: 10_000)
        var later = ahead
        later.hiddenAt = 20_000
        let snapshot = SyncSnapshot(device: "b", hiddenCategories: [ahead], profiles: [sam],
                                    profileData: [SyncProfileData(profile: sam.id, hiddenCategories: [later])])
        XCTAssertEqual(snapshot.latestTime, 20_000)
        let taken = snapshot.withoutRecordsAfter(9_999)
        XCTAssertTrue(taken.hiddenCategories.isEmpty)
        XCTAssertEqual(bucket(taken, sam.id), [])
        XCTAssertEqual(snapshot.withoutRecordsAfter(10_000).hiddenCategories, [ahead])
    }

    /// The deletion of a hidden category is read with its playlist ID in lowercase; its category ID keeps its case (the
    /// provider's), as do a tag and a word. A category of a replaced playlist becomes the kept playlist's.
    func testHiddenKeysAreReadWithTheirPlaylistInLowercaseAndFollowAReplacedPlaylist() throws {
        let json = #"""
        {"type":"ondo-sync","version":1,"device":"b","deleted":{
          "hide:category:A1000000-0000-4000-8000-00000000000A|Movie-12":20,
          "hide:tag:PT":20,"hide:word:Manga":20}}
        """#
        let b = try XCTUnwrap(SyncSnapshot.decode(Data(json.utf8)))
        XCTAssertEqual(Set(b.deleted.keys), ["hide:category:\(playlistID)|Movie-12", "hide:tag:PT", "hide:word:Manga"])
        let replacedID = "d4000000-0000-4000-8000-00000000000d"
        var same = playlist
        same.id = replacedID
        same.addedAt = 2
        same.updatedAt = 2
        let a = SyncSnapshot(device: "a", playlists: [playlist, same],
                             hiddenCategories: [SyncHidden.forCategory(playlistID: replacedID, categoryID: "live-3", hiddenAt: 5)])
        XCTAssertEqual(SyncMerge.merge(a, SyncSnapshot(device: "c")).hiddenCategories,
                       [SyncHidden.forCategory(playlistID: playlistID, categoryID: "live-3", hiddenAt: 5)])
    }
}
