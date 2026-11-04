import Foundation

/// Lists shared with every profile (docs/SYNC.md, "Shared lists", docs/PROFILES.md): what sharing, taking back and deleting
/// a shared list do to a snapshot, the same on every platform. A port of the Kotlin `SyncSharing` (android/core).
///
/// A list moves whole, under the same ID: the move out of the space it leaves is noted with the top-level deletions
/// (`listmoved:<space>|<id>`, which an older device keeps and passes on, never `list:<id>`, which it would apply), the
/// space it enters gets it at a later time with `placedAt` set to that time, and the items taken out of it before stay
/// taken out (their deletions go with it). A device that still has the list where it was merges it into the list where it
/// went, changes included, once it has both; until then (it only met an older device) it keeps it where it was (rule 9).
public enum SyncSharing {
    /// Whether a profile may change the shared lists (add or take out an item, rename, reorder, share, take back, delete):
    /// any profile but a Kids one, which only sees them.
    public static func canEdit(kids: Bool) -> Bool { !kids }

    /// The space a list is in, as `movedKey` names it: the household's.
    public static let household = "household"

    /// The move of list `list` out of `space` (`household`, or a profile's ID, the main profile's included), kept with the
    /// top-level deletions at the time of the move.
    public static func movedKey(space: String, list: String) -> String { "listmoved:" + space.lowercased() + "|" + list.lowercased() }

    /// The deletion of shared list `list` for every profile, kept with the top-level deletions.
    public static func deletedForAllKey(_ list: String) -> String { "sharedlistdeleted:" + list.lowercased() }

    /// `list` as it lands in another space at `time`: the same ID, name, items and times of its parts, changed at `time`
    /// and placed there at `time`.
    public static func moved(_ list: SyncList, at time: Int64) -> SyncList {
        var moved = list
        moved.updatedAt = max(list.updatedAt, time)
        moved.placedAt = time
        return moved
    }

    /// The deletions of the items of list `id` in `deleted` (`listitem:<id>|…`): they go with the list when it moves.
    public static func itemDeletions(of id: String, in deleted: [String: Int64]) -> [String: Int64] {
        let prefix = SyncList.itemPrefix + id.lowercased() + "|"
        return deleted.filter { $0.key.utf16.starts(with: prefix.utf16) }
    }

    /// `snapshot` with list `id` of `profile` (the main profile's or another's) shared with every profile: it moves out of
    /// the profile's space at `removedAt`, the household has it from `placedAt` (later than `removedAt`). Unchanged when
    /// that profile has no such list.
    public static func share(_ snapshot: SyncSnapshot, list id: String, from profile: String, removedAt: Int64, placedAt: Int64) -> SyncSnapshot {
        let id = id.lowercased(), profile = profile.lowercased()
        var copy = snapshot
        var space = records(of: profile, in: snapshot)
        guard let list = space.lists.first(where: { $0.id == id }) else { return snapshot }
        space.lists.removeAll { $0.id == id }
        let carried = itemDeletions(of: id, in: space.deleted)
        copy = replacing(profile, with: space, in: copy)
        copy.deleted[movedKey(space: profile, list: id)] = max(copy.deleted[movedKey(space: profile, list: id)] ?? removedAt, removedAt)
        var household = copy.household ?? SyncHousehold()
        household.lists.removeAll { $0.id == id }
        household.lists.append(moved(list, at: placedAt))
        household.deleted.merge(carried, uniquingKeysWith: max)
        copy.household = household
        return copy
    }

    /// `snapshot` with the shared list `id` taken back into `profile`'s own lists: it moves out of the household at
    /// `removedAt` (every other profile loses it), the profile has it from `placedAt` (later). Unchanged when no list is
    /// shared under that ID.
    public static func unshare(_ snapshot: SyncSnapshot, list id: String, to profile: String, removedAt: Int64, placedAt: Int64) -> SyncSnapshot {
        let id = id.lowercased(), profile = profile.lowercased()
        guard var household = snapshot.household, let list = household.lists.first(where: { $0.id == id }) else { return snapshot }
        household.lists.removeAll { $0.id == id }
        let carried = itemDeletions(of: id, in: household.deleted)
        var copy = snapshot
        // No list shared any more: no household, as a snapshot read back has it.
        copy.household = household.isEmpty ? nil : household
        let movedOut = movedKey(space: Self.household, list: id)
        copy.deleted[movedOut] = max(copy.deleted[movedOut] ?? removedAt, removedAt)
        var space = records(of: profile, in: copy)
        space.lists.removeAll { $0.id == id }
        space.lists.append(moved(list, at: placedAt))
        space.deleted.merge(carried, uniquingKeysWith: max)
        return replacing(profile, with: space, in: copy)
    }

    /// `snapshot` with the shared list `id` deleted for every profile at `time`: in the household, and with the top-level
    /// deletions (`sharedlistdeleted:<id>`), which an older device passes on.
    public static func deleteShared(_ snapshot: SyncSnapshot, list id: String, at time: Int64) -> SyncSnapshot {
        let id = id.lowercased()
        var household = snapshot.household ?? SyncHousehold()
        household.lists.removeAll { $0.id == id }
        let key = "list:" + id
        household.deleted[key] = max(household.deleted[key] ?? time, time)
        var copy = snapshot
        copy.household = household
        copy.deleted[deletedForAllKey(id)] = max(copy.deleted[deletedForAllKey(id)] ?? time, time)
        return copy
    }

    /// The lists and deletions of `profile` in `snapshot`: the snapshot's own for the main profile, its bucket otherwise.
    private static func records(of profile: String, in snapshot: SyncSnapshot) -> (lists: [SyncList], deleted: [String: Int64]) {
        if profile == SyncProfile.main { return (snapshot.lists, snapshot.deleted) }
        let bucket = snapshot.profileData.first { $0.profile == profile }
        return (bucket?.lists ?? [], bucket?.deleted ?? [:])
    }

    private static func replacing(_ profile: String, with space: (lists: [SyncList], deleted: [String: Int64]),
                                  in snapshot: SyncSnapshot) -> SyncSnapshot {
        var copy = snapshot
        if profile == SyncProfile.main {
            copy.lists = space.lists
            copy.deleted = space.deleted
        } else if let index = copy.profileData.firstIndex(where: { $0.profile == profile }) {
            copy.profileData[index].lists = space.lists
            copy.profileData[index].deleted = space.deleted
        } else {
            copy.profileData.append(SyncProfileData(profile: profile, lists: space.lists, deleted: space.deleted))
            copy.profileData.sort { $0.profile.isBefore($1.profile) }
        }
        return copy
    }
}
