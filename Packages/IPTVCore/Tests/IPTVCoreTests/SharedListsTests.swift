import XCTest
@testable import IPTVCore

/// Lists shared with every profile (docs/SYNC.md, "Shared lists"): the shared examples, sharing and taking back a list,
/// concurrent edits, deletion for every profile, Kids profiles, older devices and digests. The same cases as the Kotlin
/// `SharedListsTest` (android/core), on the same example files.
final class SharedListsTests: XCTestCase {
    static let main = SyncProfile.main
    static let sam = "11111111-1111-4111-8111-111111111111"
    static let kids = "22222222-2222-4222-8222-222222222222"
    static let list = "c1c1c1c1-0000-4000-8000-000000000001"
    static let playlist = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

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

    static func item(_ id: String, at time: Int64) -> SyncListItem {
        SyncListItem(playlist: playlist, kind: "movie", item: id, title: id.uppercased(), addedAt: time)
    }

    /// A device whose profile Sam has the list "Movie night" (two movies), and the Kids profile.
    static func device(_ name: String) -> SyncSnapshot {
        SyncSnapshot(
            device: name,
            playlists: [SyncPlaylist(id: playlist, name: "Home", kind: SyncPlaylist.m3u, url: "http://example.com/list.m3u", addedAt: 100, updatedAt: 100)],
            profiles: [SyncProfile(id: sam, name: "Sam", updatedAt: 1000), SyncProfile(id: kids, name: "Kids", kids: true, updatedAt: 1000)],
            profileData: [SyncProfileData(profile: sam, lists: [SyncList(id: list, name: "Movie night", items: [item("m1", at: 2100), item("m2", at: 2200)],
                                                                         updatedAt: 2200, renamedAt: 2000, orderedAt: 2000)])]
        )
    }

    /// Where list `id` is in `snapshot`: "household", "main", or a profile's ID, once for each copy.
    static func places(of id: String, in snapshot: SyncSnapshot) -> [String] {
        (snapshot.household?.lists.filter { $0.id == id }.map { _ in "household" } ?? [])
            + snapshot.lists.filter { $0.id == id }.map { _ in "main" }
            + snapshot.profileData.flatMap { bucket in bucket.lists.filter { $0.id == id }.map { _ in bucket.profile } }
    }

    static func shared(_ id: String, in snapshot: SyncSnapshot) -> SyncList? {
        snapshot.household?.lists.first { $0.id == id }
    }

    // MARK: The shared examples

    func testTheSharedExamplesMergeInEitherOrder() throws {
        let a = try fixture("shared-lists-a.json")
        let b = try fixture("shared-lists-b.json")
        let expected = try fixture("shared-lists-merged.json")
        XCTAssertEqual(SyncMerge.merge(a, b), expected)
        XCTAssertEqual(withoutDevice(SyncMerge.merge(b, a)), withoutDevice(expected))
        XCTAssertEqual(SyncMerge.merge(expected, expected), expected)
        XCTAssertEqual(SyncMerge.merge(expected, b), expected)
        XCTAssertEqual(withoutDevice(SyncMerge.merge(a, expected)), withoutDevice(expected))
    }

    func testEveryListOfTheSharedExamplesEndsInOneSpace() throws {
        let merged = try fixture("shared-lists-merged.json")
        let ids = (merged.household?.lists ?? []).map(\.id) + merged.lists.map(\.id) + merged.profileData.flatMap { $0.lists.map(\.id) }
        XCTAssertEqual(ids.count, Set(ids).count)
        // Shared on one device while another added to it in Sam's lists: shared, with that item, and gone from Sam's.
        XCTAssertEqual(Self.shared(Self.list, in: merged)?.items.map(\.item), ["m1", "m2", "m3"])
        XCTAssertEqual(Self.places(of: Self.list, in: merged), ["household"])
    }

    // MARK: Sharing and taking back

    func testSharingMovesTheListWithItsItemsUnderTheSameID() {
        let before = Self.device("a")
        let shared = SyncSharing.share(before, list: Self.list, from: Self.sam, removedAt: 3000, placedAt: 3001)
        XCTAssertEqual(Self.places(of: Self.list, in: shared), ["household"])
        let list = Self.shared(Self.list, in: shared)
        XCTAssertEqual(list?.items, before.profileData[0].lists[0].items)
        XCTAssertEqual(list?.name, "Movie night")
        XCTAssertEqual(list?.placedAt, 3001)
        XCTAssertEqual(list?.updatedAt, 3001)
        // The move out of Sam's lists is a marker an older device passes on, not a deletion it would apply.
        XCTAssertEqual(shared.deleted[SyncSharing.movedKey(space: Self.sam, list: Self.list)], 3000)
        XCTAssertNil(shared.profileData[0].deleted["list:" + Self.list])
        // Nothing to share: unchanged.
        XCTAssertEqual(SyncSharing.share(before, list: "other", from: Self.sam, removedAt: 3000, placedAt: 3001), before)
    }

    func testADeviceThatStillHasTheListInTheProfileDoesNotBringBackADuplicate() {
        let shared = SyncSharing.share(Self.device("a"), list: Self.list, from: Self.sam, removedAt: 3000, placedAt: 3001)
        let stale = Self.device("b")
        for merged in [SyncMerge.merge(shared, stale), SyncMerge.merge(stale, shared)] {
            XCTAssertEqual(Self.places(of: Self.list, in: merged), ["household"])
            XCTAssertEqual(Self.shared(Self.list, in: merged)?.items.map(\.item), ["m1", "m2"])
        }
    }

    func testTakingBackMovesTheListIntoTheProfileAndTheOthersLoseIt() {
        let shared = SyncSharing.share(Self.device("a"), list: Self.list, from: Self.sam, removedAt: 3000, placedAt: 3001)
        // The main profile takes it back: the household deletes it, the main profile has it, later.
        let back = SyncSharing.unshare(shared, list: Self.list, to: Self.main, removedAt: 4000, placedAt: 4001)
        XCTAssertEqual(Self.places(of: Self.list, in: back), ["main"])
        XCTAssertEqual(back.deleted[SyncSharing.movedKey(space: SyncSharing.household, list: Self.list)], 4000)
        XCTAssertNil(back.household?.deleted["list:" + Self.list])
        XCTAssertEqual(back.lists.first?.placedAt, 4001)
        XCTAssertEqual(back.lists.first?.items.map(\.item), ["m1", "m2"])
        // A device that still has it shared (unchanged since) drops it; Sam's old copy stays gone.
        for merged in [SyncMerge.merge(back, shared), SyncMerge.merge(shared, back), SyncMerge.merge(back, Self.device("c"))] {
            XCTAssertEqual(Self.places(of: Self.list, in: merged), ["main"])
        }
        // Nothing shared under that ID: unchanged.
        XCTAssertEqual(SyncSharing.unshare(Self.device("a"), list: Self.list, to: Self.main, removedAt: 4000, placedAt: 4001), Self.device("a"))
    }

    func testTheItemsTakenOutStayTakenOutWhenTheListMoves() {
        var device = Self.device("a")
        device.profileData[0].deleted["listitem:\(Self.list)|\(Self.playlist)|movie|m9"] = 2500
        let shared = SyncSharing.share(device, list: Self.list, from: Self.sam, removedAt: 3000, placedAt: 3001)
        XCTAssertEqual(shared.household?.deleted["listitem:\(Self.list)|\(Self.playlist)|movie|m9"], 2500)
        // A device that still has m9 in Sam's list, from before it was taken out, does not bring it back.
        var stale = Self.device("b")
        stale.profileData[0].lists[0].items.append(Self.item("m9", at: 2400))
        stale.profileData[0].lists[0].updatedAt = 2400
        XCTAssertEqual(Self.shared(Self.list, in: SyncMerge.merge(shared, stale))?.items.map(\.item), ["m1", "m2"])
    }

    // MARK: Concurrent edits

    func testConcurrentEditsByTwoProfilesOnTwoDevicesConverge() {
        let shared = SyncSharing.share(Self.device("a"), list: Self.list, from: Self.sam, removedAt: 3000, placedAt: 3001)
        let synced = SyncMerge.merge(Self.device("b"), shared)
        // On device a, Sam adds m3 and takes m1 out; on device b, the main profile adds m4 and renames the list.
        var a = shared
        var list = Self.shared(Self.list, in: a)!
        list.items.removeAll { $0.item == "m1" }
        list.items.append(Self.item("m3", at: 5000))
        list.updatedAt = 5000
        a.household?.lists = [list]
        a.household?.deleted["listitem:\(Self.list)|\(Self.playlist)|movie|m1"] = 5000
        var b = synced
        var other = Self.shared(Self.list, in: b)!
        other.items.append(Self.item("m4", at: 5100))
        other.name = "Friday films"
        other.renamedAt = 5100
        other.updatedAt = 5100
        b.household?.lists = [other]
        let ab = SyncMerge.merge(a, b), ba = SyncMerge.merge(b, a)
        XCTAssertEqual(withoutDevice(ab), withoutDevice(ba))
        XCTAssertEqual(Self.shared(Self.list, in: ab)?.items.map(\.item).sorted(), ["m2", "m3", "m4"])
        XCTAssertEqual(Self.shared(Self.list, in: ab)?.name, "Friday films")
        XCTAssertEqual(Self.places(of: Self.list, in: ab), ["household"])
    }

    func testAnEditInTheProfileAfterTheShareIsMergedIntoTheSharedList() {
        let shared = SyncSharing.share(Self.device("a"), list: Self.list, from: Self.sam, removedAt: 3000, placedAt: 3001)
        // Device b, not told yet, adds m3 to Sam's list at 3050.
        var b = Self.device("b")
        b.profileData[0].lists[0].items.append(Self.item("m3", at: 3050))
        b.profileData[0].lists[0].updatedAt = 3050
        for merged in [SyncMerge.merge(shared, b), SyncMerge.merge(b, shared)] {
            XCTAssertEqual(Self.places(of: Self.list, in: merged), ["household"])
            XCTAssertEqual(Self.shared(Self.list, in: merged)?.items.map(\.item), ["m1", "m2", "m3"])
        }
    }

    func testTakingBackWhileAnotherDeviceEditsTheSharedListKeepsTheEdit() {
        let shared = SyncSharing.share(Self.device("a"), list: Self.list, from: Self.sam, removedAt: 3000, placedAt: 3001)
        let back = SyncSharing.unshare(shared, list: Self.list, to: Self.sam, removedAt: 4000, placedAt: 4001)
        // Device b adds m5 to the shared list at 4100, before it hears that Sam took it back.
        var b = SyncMerge.merge(Self.device("b"), shared)
        var list = Self.shared(Self.list, in: b)!
        list.items.append(Self.item("m5", at: 4100))
        list.updatedAt = 4100
        b.household?.lists = [list]
        for merged in [SyncMerge.merge(back, b), SyncMerge.merge(b, back)] {
            XCTAssertEqual(Self.places(of: Self.list, in: merged), [Self.sam])
            XCTAssertEqual(merged.profileData.first { $0.profile == Self.sam }?.lists.first?.items.map(\.item), ["m1", "m2", "m5"])
        }
    }

    func testTwoProfilesTakingBackTheSameListAtOnceEndInTheLaterOne() {
        let shared = SyncMerge.merge(Self.device("b"), SyncSharing.share(Self.device("a"), list: Self.list, from: Self.sam, removedAt: 3000, placedAt: 3001))
        let bySam = SyncSharing.unshare(shared, list: Self.list, to: Self.sam, removedAt: 4000, placedAt: 4001)
        let byMain = SyncSharing.unshare(shared, list: Self.list, to: Self.main, removedAt: 4002, placedAt: 4003)
        for merged in [SyncMerge.merge(bySam, byMain), SyncMerge.merge(byMain, bySam)] {
            XCTAssertEqual(Self.places(of: Self.list, in: merged), ["main"])
        }
    }

    // MARK: Deletion for every profile

    func testDeletingASharedListDeletesItForEveryProfile() {
        let shared = SyncSharing.share(Self.device("a"), list: Self.list, from: Self.sam, removedAt: 3000, placedAt: 3001)
        let deleted = SyncSharing.deleteShared(shared, list: Self.list, at: 6000)
        XCTAssertEqual(Self.places(of: Self.list, in: deleted), [])
        XCTAssertEqual(deleted.deleted[SyncSharing.deletedForAllKey(Self.list)], 6000)
        // Through an older device too, which passes on the deletion though it drops the household.
        let relayed = try? Self.asOlderDevice(deleted, knowsProfiles: true)
        XCTAssertEqual(relayed.map { Self.places(of: Self.list, in: SyncMerge.merge(shared, $0)) }, [])
        // Neither a device that still has it shared, nor one that changed Sam's old copy before the deletion, brings it back.
        var stale = Self.device("c")
        stale.profileData[0].lists[0].items.append(Self.item("m3", at: 5000))
        stale.profileData[0].lists[0].updatedAt = 5000
        for other in [shared, Self.device("b"), stale] {
            XCTAssertEqual(Self.places(of: Self.list, in: SyncMerge.merge(deleted, other)), [])
            XCTAssertEqual(Self.places(of: Self.list, in: SyncMerge.merge(other, deleted)), [])
        }
        // A change made after the deletion, on a device not told yet, wins over it (as rule 7): the list comes back.
        var later = SyncMerge.merge(Self.device("d"), shared)
        later.household?.lists[0].items.append(Self.item("m6", at: 6100))
        later.household?.lists[0].updatedAt = 6100
        XCTAssertEqual(Self.places(of: Self.list, in: SyncMerge.merge(deleted, later)), ["household"])
    }

    // MARK: Kids

    func testAKidsProfileCannotEditSharedLists() {
        XCTAssertFalse(SyncSharing.canEdit(kids: true))
        XCTAssertTrue(SyncSharing.canEdit(kids: false))
    }

    // MARK: Older devices

    /// What a device that does not share lists keeps and passes on: everything but the household and `placedAt`; one that
    /// does not know profiles either keeps only the main profile's data.
    static func asOlderDevice(_ snapshot: SyncSnapshot, knowsProfiles: Bool) throws -> SyncSnapshot {
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: try snapshot.encoded()) as? [String: Any])
        json["household"] = nil
        if !knowsProfiles {
            json["profiles"] = nil
            json["profileData"] = nil
        }
        func strip(_ lists: Any?) -> Any? {
            (lists as? [[String: Any]])?.map { list in list.filter { $0.key != "placedAt" } }
        }
        json["lists"] = strip(json["lists"])
        if let buckets = json["profileData"] as? [[String: Any]] {
            json["profileData"] = buckets.map { bucket in
                var bucket = bucket
                bucket["lists"] = strip(bucket["lists"])
                return bucket
            }
        }
        return try XCTUnwrap(SyncSnapshot.decode(JSONSerialization.data(withJSONObject: json)))
    }

    func testAnOlderDeviceUsedAsARelayDestroysNothing() throws {
        // The main profile shares its list; an older device (with or without profiles) relays the result.
        var a = Self.device("a")
        a.lists = a.profileData[0].lists
        a.profileData = []
        let shared = SyncSharing.share(a, list: Self.list, from: Self.main, removedAt: 3000, placedAt: 3001)
        for knowsProfiles in [true, false] {
            let old = try Self.asOlderDevice(SyncMerge.merge(a, shared), knowsProfiles: knowsProfiles)
            // The older device drops the main profile's copy (the deletion), and has nothing shared.
            XCTAssertEqual(Self.places(of: Self.list, in: old), [])
            // What it passes on takes nothing from a device that shares lists, in either direction.
            for merged in [SyncMerge.merge(shared, old), SyncMerge.merge(old, shared)] {
                XCTAssertEqual(Self.places(of: Self.list, in: merged), ["household"])
                XCTAssertEqual(Self.shared(Self.list, in: merged)?.items.map(\.item), ["m1", "m2"])
            }
        }
    }

    func testAnEditOnAnOlderDeviceIsMergedIntoTheSharedListAndTheOlderDeviceKeepsItsCopy() throws {
        var a = Self.device("a")
        a.lists = a.profileData[0].lists
        a.profileData = []
        let shared = SyncSharing.share(a, list: Self.list, from: Self.main, removedAt: 3000, placedAt: 3001)
        // An older device, not told yet, adds m3 to the main profile's list at 3500.
        var old = try Self.asOlderDevice(a, knowsProfiles: false)
        old.lists[0].items.append(Self.item("m3", at: 3500))
        old.lists[0].updatedAt = 3500
        let merged = SyncMerge.merge(shared, old)
        XCTAssertEqual(Self.shared(Self.list, in: merged)?.items.map(\.item), ["m1", "m2", "m3"])
        XCTAssertEqual(Self.places(of: Self.list, in: merged), ["household"])
        // The older device keeps its copy (it cannot show a shared list: removing it would lose the list there); it is
        // merged into the shared list again at each session, and nothing changes from one to the next.
        let relayed = try Self.asOlderDevice(SyncMerge.merge(old, try Self.asOlderDevice(merged, knowsProfiles: false)), knowsProfiles: false)
        XCTAssertEqual(Self.places(of: Self.list, in: relayed), ["main"])
        XCTAssertEqual(SyncMerge.merge(merged, relayed).household, merged.household)
    }

    // MARK: An older device between two that share lists (an iPhone and an iPad behind an older TV)

    /// A session as a client and an older server have it: the server keeps and answers without the household or `placedAt`.
    static func sessionWithOlderServer(_ client: inout SyncSnapshot, _ server: inout SyncSnapshot) throws {
        server = try asOlderDevice(SyncMerge.merge(server, try asOlderDevice(client, knowsProfiles: true)), knowsProfiles: true)
        client = SyncMerge.merge(client, server)
    }

    func testAListSharedBehindAnOlderDeviceIsNeverLostOnTheFarSide() throws {
        let base = Self.device("base")
        var iphone = SyncSharing.share(base, list: Self.list, from: Self.sam, removedAt: 3000, placedAt: 3001)
        var tv = try Self.asOlderDevice(base, knowsProfiles: true)
        var ipad = base
        try Self.sessionWithOlderServer(&iphone, &tv)
        try Self.sessionWithOlderServer(&ipad, &tv)
        // The iPad never got the shared copy (the older TV dropped it): it keeps Sam's, where it was.
        XCTAssertEqual(Self.places(of: Self.list, in: ipad), [Self.sam])
        XCTAssertEqual(Self.places(of: Self.list, in: iphone), ["household"])
        try Self.sessionWithOlderServer(&iphone, &tv)
        XCTAssertEqual(Self.places(of: Self.list, in: iphone), ["household"])
        // Once both meet (directly, or through a device that shares lists): shared, once, on both.
        let met = SyncMerge.merge(ipad, iphone)
        XCTAssertEqual(Self.places(of: Self.list, in: met), ["household"])
        XCTAssertEqual(Self.places(of: Self.list, in: SyncMerge.merge(iphone, ipad)), ["household"])
    }

    func testAListTakenBackBehindAnOlderDeviceStaysWithTheProfileThatTookItBack() throws {
        let base = SyncSharing.share(Self.device("base"), list: Self.list, from: Self.sam, removedAt: 3000, placedAt: 3001)
        var iphone = SyncSharing.unshare(base, list: Self.list, to: Self.main, removedAt: 4000, placedAt: 4001)
        var tv = try Self.asOlderDevice(base, knowsProfiles: true)
        var ipad = base
        try Self.sessionWithOlderServer(&iphone, &tv)
        try Self.sessionWithOlderServer(&ipad, &tv)
        try Self.sessionWithOlderServer(&iphone, &tv)
        try Self.sessionWithOlderServer(&ipad, &tv)
        try Self.sessionWithOlderServer(&iphone, &tv)
        // The iPhone keeps it where it took it back; the iPad, whose shared copy is older than the move, follows.
        XCTAssertEqual(Self.places(of: Self.list, in: iphone), ["main"])
        XCTAssertEqual(Self.places(of: Self.list, in: ipad), ["main"])
        XCTAssertEqual(iphone.lists.first?.items.map(\.item), ["m1", "m2"])
        XCTAssertEqual(ipad.lists.first?.items.map(\.item), ["m1", "m2"])
    }

    // MARK: A change made where the list was, dated before the move

    func testAChangeDatedBeforeAConcurrentMoveIsCarriedWhateverTheOrderOfTheMerges() {
        let a = SyncSharing.share(Self.device("a"), list: Self.list, from: Self.sam, removedAt: 3006, placedAt: 3007)
        // Device b, not told yet, adds m3 at 3005 (before the share's time); device c adds m5 at 3003, d adds m6 at 3020.
        var b = Self.device("b")
        b.profileData[0].lists[0].items.append(Self.item("m3", at: 3005))
        b.profileData[0].lists[0].updatedAt = 3005
        XCTAssertEqual(Self.shared(Self.list, in: SyncMerge.merge(a, b))?.items.map(\.item), ["m1", "m2", "m3"])
        var c = Self.device("c")
        c.profileData[0].lists[0].items.append(Self.item("m5", at: 3003))
        c.profileData[0].lists[0].updatedAt = 3003
        let orders = [SyncMerge.merge(SyncMerge.merge(a, b), c), SyncMerge.merge(a, SyncMerge.merge(b, c)), SyncMerge.merge(SyncMerge.merge(a, c), b)]
        for merged in orders {
            XCTAssertEqual(Self.places(of: Self.list, in: merged), ["household"])
            XCTAssertEqual(Self.shared(Self.list, in: merged)?.items.map(\.item).sorted(), ["m1", "m2", "m3", "m5"])
        }
        // An item taken out of the shared list stays out, even if a copy where the list was still has it.
        var removed = a
        removed.household?.lists[0].items.removeAll { $0.item == "m2" }
        removed.household?.lists[0].updatedAt = 3010
        removed.household?.deleted["listitem:\(Self.list)|\(Self.playlist)|movie|m2"] = 3010
        XCTAssertEqual(Self.shared(Self.list, in: SyncMerge.merge(removed, b))?.items.map(\.item), ["m1", "m3"])
    }

    // MARK: Format and digest

    func testASnapshotWithoutSharedListsIsWrittenAsBefore() throws {
        let snapshot = try fixture("profiles-merged.json")
        XCTAssertNil(snapshot.household)
        let content = String(decoding: try snapshot.contentForDigest(), as: UTF8.self)
        XCTAssertFalse(content.contains("household"))
        XCTAssertFalse(content.contains("placedAt"))
        // An empty household reads as none, and changes no digest.
        var empty = snapshot
        empty.household = SyncHousehold()
        XCTAssertEqual(try empty.contentForDigest(), try snapshot.contentForDigest())
        XCTAssertNil(empty.normalized().household)
        XCTAssertNil(SyncMerge.merge(snapshot, empty).household)
        XCTAssertFalse(String(decoding: try empty.encoded(), as: UTF8.self).contains("household"))
        XCTAssertEqual(SyncMerge.merge(snapshot, try fixture("profiles-a.json")).household, nil)
    }

    func testTheDigestOfSharedListsIsStableAndFollowsAChange() throws {
        let hash: (Data) -> String = { $0.base64EncodedString() }
        let a = try fixture("shared-lists-a.json"), b = try fixture("shared-lists-b.json")
        let digest = hash(try SyncMerge.merge(a, b).contentForDigest())
        XCTAssertEqual(hash(try SyncMerge.merge(b, a).contentForDigest()), digest)
        let merged = SyncMerge.merge(a, b)
        XCTAssertEqual(hash(try SyncMerge.merge(merged, merged).contentForDigest()), digest)
        var renamed = merged
        renamed.household?.lists[0].name = "Other"
        XCTAssertNotEqual(hash(try renamed.contentForDigest()), digest)
    }

    func testSharedListsDatedFarAheadAreLeftOutAndTheirTimesCount() throws {
        let a = try fixture("shared-lists-a.json")
        XCTAssertEqual(a.latestTime, 3450)
        let bounded = a.withoutRecordsAfter(3050)
        XCTAssertEqual(bounded.household?.lists.map(\.id), [Self.list])
        XCTAssertNil(bounded.household?.deleted["list:c3c3c3c3-0000-4000-8000-000000000003"])
        // A list placed far ahead is left out too.
        var ahead = a
        ahead.household?.lists[0].placedAt = 9000
        XCTAssertFalse(ahead.withoutRecordsAfter(5000).household?.lists.contains { $0.id == Self.list } ?? false)
        XCTAssertEqual(ahead.latestTime, 9000)
    }

    func testDeletionsAmongSharedListsAreForgottenLikeTheOthers() throws {
        let a = try fixture("shared-lists-a.json")
        let day: Int64 = 86_400_000
        let removal = "listitem:c2c2c2c2-0000-4000-8000-000000000002|\(Self.playlist)|movie|s2"
        let now = 3300 + 181 * day
        XCTAssertEqual(a.pruned(now: now).household?.deleted, [:])
        // Learned late: kept from when it was learned.
        let learned = a.pruned(now: now, householdLearned: [removal: now - day])
        XCTAssertEqual(learned.household?.deleted, [removal: 3100])
    }

    func testSharedListIDsAreReadInLowercase() throws {
        let b = try fixture("shared-lists-b.json")
        XCTAssertTrue(b.household?.lists.allSatisfy { $0.id == $0.id.lowercased() } ?? false)
        XCTAssertTrue(b.profileData.allSatisfy { $0.lists.allSatisfy { $0.id == $0.id.lowercased() } })
    }

    // MARK: Three devices, any order

    /// Random moves and edits of one list on three devices, then sessions in a random order until every device has met
    /// every other twice: they end alike, the list in one space at most, and merging is the same in either order and
    /// changes nothing the second time.
    func testRandomMovesOnThreeDevicesConverge() {
        for seed in 0..<300 {
            var random = SyncFuzzTests.Random(state: UInt64(seed))
            let spaces = ["household", Self.main, Self.sam]
            var devices = (0..<3).map { index -> SyncSnapshot in
                var snapshot = SyncSnapshot(device: "d\(index)", profiles: [SyncProfile(id: Self.sam, name: "Sam", updatedAt: 1000)])
                var time: Int64 = 1000 + Int64(index)
                func next() -> Int64 { time += Int64(1 + random.int(40)); return time }
                for _ in 0..<(1 + random.int(3)) {
                    let place = random.pick(spaces)
                    let items = ["m1", "m2", "m3"].filter { _ in random.chance(50) }.map { Self.item($0, at: next()) }
                    let list = SyncList(id: Self.list, name: random.pick(["A", "B"]), items: items, updatedAt: next(),
                                        renamedAt: next(), orderedAt: next(), placedAt: random.chance(50) ? next() : nil)
                    Self.put(list, in: place, of: &snapshot)
                    if random.chance(40) { Self.delete("list:" + Self.list, at: next(), in: random.pick(spaces), of: &snapshot) }
                    if random.chance(30) { Self.delete("listitem:\(Self.list)|\(Self.playlist)|movie|m1", at: next(), in: random.pick(spaces), of: &snapshot) }
                }
                return snapshot
            }
            for first in devices {
                for second in devices {
                    let ab = SyncMerge.merge(first, second), ba = SyncMerge.merge(second, first)
                    XCTAssertEqual(withoutDevice(ab), withoutDevice(ba), "seed \(seed)")
                    XCTAssertEqual(SyncMerge.merge(ab, ab), ab, "seed \(seed)")
                    XCTAssertLessThanOrEqual(Self.places(of: Self.list, in: ab).count, 1, "seed \(seed)")
                }
            }
            for _ in 0..<12 {
                let client = random.int(3), server = (client + 1 + random.int(2)) % 3
                devices[server] = SyncMerge.merge(devices[server], devices[client])
                devices[client] = SyncMerge.merge(devices[client], devices[server])
            }
            for round in 0..<2 {
                _ = round
                for client in 0..<3 {
                    for server in 0..<3 where server != client {
                        devices[server] = SyncMerge.merge(devices[server], devices[client])
                        devices[client] = SyncMerge.merge(devices[client], devices[server])
                    }
                }
            }
            XCTAssertEqual(withoutDevice(devices[0]), withoutDevice(devices[1]), "seed \(seed)")
            XCTAssertEqual(withoutDevice(devices[1]), withoutDevice(devices[2]), "seed \(seed)")
        }
    }

    static func put(_ list: SyncList, in place: String, of snapshot: inout SyncSnapshot) {
        switch place {
        case "household":
            var household = snapshot.household ?? SyncHousehold()
            household.lists.append(list)
            snapshot.household = household
        case main: snapshot.lists.append(list)
        default:
            if let index = snapshot.profileData.firstIndex(where: { $0.profile == place }) {
                snapshot.profileData[index].lists.append(list)
            } else {
                snapshot.profileData.append(SyncProfileData(profile: place, lists: [list]))
            }
        }
    }

    static func delete(_ key: String, at time: Int64, in place: String, of snapshot: inout SyncSnapshot) {
        switch place {
        case "household":
            var household = snapshot.household ?? SyncHousehold()
            household.deleted[key] = max(household.deleted[key] ?? time, time)
            snapshot.household = household
        case main: snapshot.deleted[key] = max(snapshot.deleted[key] ?? time, time)
        default:
            if let index = snapshot.profileData.firstIndex(where: { $0.profile == place }) {
                snapshot.profileData[index].deleted[key] = max(snapshot.profileData[index].deleted[key] ?? time, time)
            } else {
                snapshot.profileData.append(SyncProfileData(profile: place, deleted: [key: time]))
            }
        }
    }
}
