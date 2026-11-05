import XCTest
@testable import IPTVCore

/// Selections in sync (docs/SYNC.md, "Selections"): each one merging on its own, deletions, each profile's own, older
/// devices, the digest, the limits. The same cases as the Kotlin `SyncSelectionsTest` (android/core).
final class SyncSelectionsTests: XCTestCase {
    private let sam = SyncProfile(id: "c3000000-0000-4000-8000-00000000000c", name: "Sam", updatedAt: 5)
    private let dramas = SyncSelection(id: "s1", name: "Dramas", tags: ["decade:1990", "genre:drama"], match: SyncSelection.any,
                                       tabs: ["home", "movie"], updatedAt: 100)
    private let anime = SyncSelection(id: "s2", name: "Anime", tags: ["style:manga"], match: SyncSelection.all, tabs: ["series"], updatedAt: 100)

    /// What a device of an older version keeps of `snapshot`: it reads it ignoring `selections`, its own and each profile's.
    private func olderVersion(_ snapshot: SyncSnapshot) throws -> SyncSnapshot {
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: try snapshot.encoded()) as? [String: Any])
        object["selections"] = nil
        object["profileData"] = (object["profileData"] as? [[String: Any]] ?? []).map { bucket in
            var bucket = bucket
            bucket["selections"] = nil
            return bucket
        }
        return try XCTUnwrap(SyncSnapshot.decode(try JSONSerialization.data(withJSONObject: object)))
    }

    private func normalizedSorted(_ selections: [SyncSelection]) -> [SyncSelection] {
        SyncSelection.normalized(selections)
    }

    private func device(_ snapshot: SyncSnapshot, _ name: String) -> SyncSnapshot {
        var copy = snapshot
        copy.device = name
        return copy
    }

    func testEachSelectionMergesOnItsOwnTheLaterChangeWinning() {
        var renamed = dramas
        renamed.name = "Nineties dramas"
        renamed.tags = ["genre:drama"]
        renamed.match = SyncSelection.all
        renamed.updatedAt = 200
        let a = SyncSnapshot(device: "a", selections: [renamed])
        let b = SyncSnapshot(device: "b", selections: [dramas, anime])
        let merged = SyncMerge.merge(a, b)
        XCTAssertEqual(merged.selections, SyncSelection.normalized([renamed, anime]))
        XCTAssertEqual(device(merged, ""), device(SyncMerge.merge(b, a), ""))
        // Merging again changes nothing.
        XCTAssertEqual(SyncMerge.merge(merged, b), merged)
        // The same time on both sides: the tie string decides, whatever the order.
        var other = dramas
        other.name = "Other"
        XCTAssertEqual(SyncMerge.merge(SyncSnapshot(device: "a", selections: [dramas]), SyncSnapshot(device: "b", selections: [other])).selections,
                       SyncMerge.merge(SyncSnapshot(device: "a", selections: [other]), SyncSnapshot(device: "b", selections: [dramas])).selections)
    }

    func testADeletionWinsOverASelectionNotChangedSince() {
        let deleted = SyncSnapshot(device: "a", deleted: [dramas.key: 150])
        let b = SyncSnapshot(device: "b", selections: [dramas, anime])
        let merged = SyncMerge.merge(deleted, b)
        XCTAssertEqual(merged.selections.map(\.id), ["s2"])
        XCTAssertEqual(merged.deleted["selection:s1"], 150)
        // Changed after it was deleted (on a device that had not heard of it): it stays.
        var changed = dramas
        changed.updatedAt = 160
        XCTAssertEqual(SyncMerge.merge(deleted, SyncSnapshot(device: "b", selections: [changed])).selections.map(\.id), ["s1"])
        // A deletion's ID is read in lowercase, as the selection's.
        XCTAssertEqual(SyncMerge.merge(SyncSnapshot(device: "a", deleted: ["selection:S1": 150]), b).selections.map(\.id), ["s2"])
    }

    func testEachProfileHasItsOwnSelections() {
        let a = SyncSnapshot(device: "a", selections: [dramas], profiles: [sam],
                             profileData: [SyncProfileData(profile: sam.id, selections: [anime])])
        let b = SyncSnapshot(device: "b", profiles: [sam], profileData: [SyncProfileData(profile: sam.id, deleted: [anime.key: 300])])
        let merged = SyncMerge.merge(a, b)
        XCTAssertEqual(merged.selections.map(\.id), ["s1"])
        XCTAssertEqual(merged.profileData.first?.selections, [])
        XCTAssertEqual(merged.profileData.first?.deleted[anime.key], 300)
        // Sam's selection does not touch the main profile's of the same ID.
        var samsOwn = dramas
        samsOwn.name = "Sam's"
        samsOwn.updatedAt = 400
        let both = SyncMerge.merge(a, SyncSnapshot(device: "c", profiles: [sam], profileData: [SyncProfileData(profile: sam.id, selections: [samsOwn])]))
        XCTAssertEqual(both.selections.map(\.name), ["Dramas"])
        XCTAssertEqual(both.profileData.first?.selections.map(\.name), ["Sam's", "Anime"])
    }

    func testAnOlderDeviceLosesNoSelectionsAndTheDigestIsAsBefore() throws {
        let snapshot = SyncSnapshot(device: "a", rows: [SyncRows(tab: SyncRows.movies, order: ["all", "sel:s1"], updatedAt: 90)])
        let content = try snapshot.contentForDigest()
        XCTAssertFalse(String(decoding: try snapshot.encoded(), as: UTF8.self).contains("selections"))
        XCTAssertEqual(try SyncMerge.merge(snapshot, SyncSnapshot(device: "b")).contentForDigest(), content)
        // With selections, its own and a profile's: another digest, read back as written.
        var withSelections = snapshot
        withSelections.selections = [dramas]
        withSelections.profiles = [sam]
        withSelections.profileData = [SyncProfileData(profile: sam.id, selections: [anime])]
        XCTAssertNotEqual(try withSelections.contentForDigest(), content)
        XCTAssertEqual(SyncSnapshot.decode(try withSelections.encoded()), withSelections.normalized())
        // An older device keeps none, and removes none when it answers.
        let older = try olderVersion(withSelections)
        XCTAssertEqual(older.selections, [])
        let back = SyncMerge.merge(withSelections, older)
        XCTAssertEqual(back.selections.map(\.id), ["s1"])
        XCTAssertEqual(back.profileData.first?.selections.map(\.id), ["s2"])
    }

    func testSelectionsAreKeptAsEveryDeviceKeepsThem() throws {
        let messy = SyncSelection(id: "S3", name: "Na\u{07}me", tags: ["style:manga", "genre:drama", "genre:drama", "mood:dark", " ", "a\nb"],
                                  match: "", tabs: ["series", "home", "series"], updatedAt: 7)
        let read = try XCTUnwrap(SyncSnapshot.decode(try SyncSnapshot(device: "a", selections: [messy, SyncSelection(id: " ", updatedAt: 1)]).encoded()))
        // The ID in lowercase, no control character, tags and tabs each once and sorted (unknown kinds kept), a match.
        XCTAssertEqual(read.selections, [SyncSelection(id: "s3", name: "Name", tags: ["genre:drama", "mood:dark", "style:manga"],
                                                       match: SyncSelection.any, tabs: ["home", "series"], updatedAt: 7)])
        // Past the limit, those changed last stay, the same on every device.
        let many = (0..<(SyncSelection.maxSelections + 5)).map { SyncSelection(id: "id\($0)", updatedAt: Int64($0 % 50)) }
        let kept = try XCTUnwrap(SyncSnapshot.decode(try SyncSnapshot(device: "a", selections: many).encoded())).selections
        XCTAssertEqual(kept.count, SyncSelection.maxSelections)
        XCTAssertFalse(kept.contains { $0.id == "id0" })
        XCTAssertEqual(SyncMerge.merge(SyncSnapshot(device: "a", selections: many.reversed()), SyncSnapshot(device: "b")).selections.map(\.id).sorted(),
                       kept.map(\.id).sorted())
    }

    func testSelectionsDatedFarAheadAreLeftOut() {
        var later = anime
        later.updatedAt = 5_000
        let snapshot = SyncSnapshot(device: "a", selections: [dramas, later], deleted: ["selection:x": 6_000])
        let kept = snapshot.withoutRecordsAfter(1_000)
        XCTAssertEqual(kept.selections.map(\.id), ["s1"])
        XCTAssertEqual(kept.deleted, [:])
        XCTAssertEqual(snapshot.latestTime, 6_000)
    }
}
