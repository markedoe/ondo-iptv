import Foundation
import IPTVCore

/// Collections shared with every profile (docs/PROFILES.md, "Shared lists"): they live in `household`, apart from every
/// profile's library, and every profile sees them; any profile but a Kids one changes them. Sharing and taking back move a
/// collection whole, under the same ID, as `SyncSharing` does to a snapshot: the library it leaves deletes it at a first
/// time, the one it enters has it at a later one (its `updatedAt` and `placedAt`), and the deletions of its items go with it.
extension AppModel {
    /// The document of the shared collections.
    static let householdDocumentName = "library-household"

    /// The collections the open profile sees: its own, then those shared with every profile.
    var customLists: [CustomList] { library.customLists + household.customLists }

    /// Whether collection `id` is shared with every profile.
    func isShared(_ id: UUID) -> Bool { household.customLists.contains { $0.id == id } }

    /// Whether the open profile may change the shared collections: any but a Kids profile, which only sees them.
    var canEditSharedLists: Bool { SyncSharing.canEdit(kids: activeProfile.isKids) }

    /// Whether the "Shared with all profiles" switch shows for `id`: two profiles or more (or shared), not a Kids profile.
    func canShareList(_ id: UUID) -> Bool { canEditSharedLists && (profiles.count > 1 || isShared(id)) }

    /// Whether the open profile may rename, reorder, delete or fill collection `id`.
    func canEditList(_ id: UUID) -> Bool { !isShared(id) || canEditSharedLists }

    /// `change` made to the library collection `id` is in: the household's when it is shared (never by a Kids profile).
    func updateList(_ id: UUID, _ change: (inout UserLibrary) -> Void) {
        guard isShared(id) else { return updateLibrary(change) }
        guard canEditSharedLists else { return }
        updateHousehold(change)
    }

    /// Changes the shared collections and saves them.
    func updateHousehold(_ change: (inout UserLibrary) -> Void) {
        change(&household)
        guard isReady else { return }
        try? userStore.save(household, name: Self.householdDocumentName)
    }

    /// Remembers for sync that `key` was deleted among the shared collections at `time`.
    func noteHouseholdDeletedForSync(_ key: String, at time: Int64) {
        syncState.household.deleted[key] = max(syncState.household.deleted[key] ?? time, time)
    }

    /// Shares collection `id` of the open profile with every profile, or takes a shared one back into the open profile. Not
    /// in a Kids profile.
    func setListShared(_ id: UUID, _ shared: Bool) {
        guard isReady, canEditSharedLists, shared != isShared(id) else { return }
        let removedAt = syncStamp(), placedAt = syncStamp()
        let key = "list:" + id.uuidString.lowercased()
        let itemPrefix = SyncList.itemPrefix + id.uuidString.lowercased() + "|"
        let tombstonePrefix = id.uuidString + "|"
        if shared {
            guard var list = library.customLists.first(where: { $0.id == id }) else { return }
            let tombstones = library.listItemTombstones.filter { $0.key.hasPrefix(tombstonePrefix) }
            let deletions = (activeProfile.isMain ? syncState.deleted : syncState.slice(for: activeProfile.id.uuidString.lowercased()).deleted)
                .filter { $0.key.hasPrefix(itemPrefix) }
            updateLibrary { $0.customLists.removeAll { $0.id == id } }
            noteDeletedForSync(key, at: removedAt.syncMilliseconds)
            list.updatedAt = max(list.updatedAt, placedAt)
            list.placedAt = placedAt
            updateHousehold { household in
                household.customLists.removeAll { $0.id == id }
                household.customLists.append(list)
                household.listItemTombstones.merge(tombstones) { max($0, $1) }
            }
            for (deletion, time) in deletions { noteHouseholdDeletedForSync(deletion, at: time) }
        } else {
            guard var list = household.customLists.first(where: { $0.id == id }) else { return }
            let tombstones = household.listItemTombstones.filter { $0.key.hasPrefix(tombstonePrefix) }
            let deletions = syncState.household.deleted.filter { $0.key.hasPrefix(itemPrefix) }
            updateHousehold { $0.customLists.removeAll { $0.id == id } }
            noteHouseholdDeletedForSync(key, at: removedAt.syncMilliseconds)
            list.updatedAt = max(list.updatedAt, placedAt)
            list.placedAt = placedAt
            updateLibrary { library in
                library.customLists.removeAll { $0.id == id }
                library.customLists.append(list)
                library.listItemTombstones.merge(tombstones) { max($0, $1) }
            }
            for (deletion, time) in deletions { noteDeletedForSync(deletion, at: time) }
        }
        saveSyncState()
        syncSoon()
    }
}
