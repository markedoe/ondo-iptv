package com.markedoe.ondo.core.sync

/**
 * Lists shared with every profile (docs/SYNC.md, "Shared lists", docs/PROFILES.md): what sharing, taking back and deleting
 * a shared list do to a snapshot, the same on every platform.
 *
 * A list moves whole, under the same ID: the space it leaves gets its deletion (`list:<id>`), the space it enters gets it
 * at a later time with `placedAt` set to that time, and the items taken out of it before stay taken out (their deletions
 * go with it). A device that still has the list where it was drops it (its copy is not later than the deletion); one that
 * changed it there meanwhile has its changes merged into the list where it went (rule 9 of the merge).
 */
object SyncSharing {
    /**
     * Whether a profile may change the shared lists (add or take out an item, rename, reorder, share, take back, delete):
     * any profile but a Kids one, which only sees them.
     */
    fun canEdit(kids: Boolean): Boolean = !kids

    /** [list] as it lands in another space at [time]: the same ID, name, items and times of its parts, changed and placed at [time]. */
    fun moved(list: SyncList, time: Long): SyncList = list.copy(updatedAt = maxOf(list.updatedAt, time), placedAt = time)

    /** The deletions of the items of list [id] in [deleted] (`listitem:<id>|…`): they go with the list when it moves. */
    fun itemDeletions(id: String, deleted: Map<String, Long>): Map<String, Long> {
        val prefix = SyncList.ITEM_PREFIX + id.lowercase() + "|"
        return deleted.filterKeys { it.startsWith(prefix) }
    }

    /**
     * [snapshot] with list [id] of [profile] (the main profile's or another's) shared with every profile: the profile's
     * space deletes it at [removedAt], the household has it from [placedAt] (later than [removedAt]). Unchanged when that
     * profile has no such list.
     */
    fun share(snapshot: SyncSnapshot, id: String, profile: String, removedAt: Long, placedAt: Long): SyncSnapshot {
        val listId = id.lowercase()
        val owner = profile.lowercase()
        val (lists, deleted) = records(owner, snapshot)
        val list = lists.firstOrNull { it.id == listId } ?: return snapshot
        val carried = itemDeletions(listId, deleted)
        val shared = replacing(owner, lists.filter { it.id != listId }, deleted + (list.key to maxOf(deleted[list.key] ?: removedAt, removedAt)), snapshot)
        val household = shared.household ?: SyncHousehold()
        return shared.copy(
            household = household.copy(
                lists = household.lists.filter { it.id != listId } + moved(list, placedAt),
                deleted = household.deleted + carried.mapValues { (key, time) -> maxOf(household.deleted[key] ?: time, time) },
            ),
        )
    }

    /**
     * [snapshot] with the shared list [id] taken back into [profile]'s own lists: the household deletes it at [removedAt]
     * (every other profile loses it), the profile has it from [placedAt] (later). Unchanged when no list is shared under
     * that ID.
     */
    fun unshare(snapshot: SyncSnapshot, id: String, profile: String, removedAt: Long, placedAt: Long): SyncSnapshot {
        val listId = id.lowercase()
        val owner = profile.lowercase()
        val household = snapshot.household ?: return snapshot
        val list = household.lists.firstOrNull { it.id == listId } ?: return snapshot
        val carried = itemDeletions(listId, household.deleted)
        val withoutIt = snapshot.copy(
            household = household.copy(
                lists = household.lists.filter { it.id != listId },
                deleted = household.deleted + (list.key to maxOf(household.deleted[list.key] ?: removedAt, removedAt)),
            ),
        )
        val (lists, deleted) = records(owner, withoutIt)
        return replacing(
            owner,
            lists.filter { it.id != listId } + moved(list, placedAt),
            deleted + carried.mapValues { (key, time) -> maxOf(deleted[key] ?: time, time) },
            withoutIt,
        )
    }

    /** [snapshot] with the shared list [id] deleted for every profile at [time]. */
    fun deleteShared(snapshot: SyncSnapshot, id: String, time: Long): SyncSnapshot {
        val listId = id.lowercase()
        val household = snapshot.household ?: SyncHousehold()
        val key = "list:$listId"
        return snapshot.copy(
            household = household.copy(
                lists = household.lists.filter { it.id != listId },
                deleted = household.deleted + (key to maxOf(household.deleted[key] ?: time, time)),
            ),
        )
    }

    /** The lists and deletions of [profile] in [snapshot]: the snapshot's own for the main profile, its bucket otherwise. */
    private fun records(profile: String, snapshot: SyncSnapshot): Pair<List<SyncList>, Map<String, Long>> {
        if (profile == SyncProfile.MAIN) return snapshot.lists to snapshot.deleted
        val bucket = snapshot.profileData.firstOrNull { it.profile == profile }
        return bucket?.lists.orEmpty() to bucket?.deleted.orEmpty()
    }

    private fun replacing(profile: String, lists: List<SyncList>, deleted: Map<String, Long>, snapshot: SyncSnapshot): SyncSnapshot = when {
        profile == SyncProfile.MAIN -> snapshot.copy(lists = lists, deleted = deleted)
        snapshot.profileData.any { it.profile == profile } ->
            snapshot.copy(profileData = snapshot.profileData.map { if (it.profile == profile) it.copy(lists = lists, deleted = deleted) else it })
        else -> snapshot.copy(profileData = (snapshot.profileData + SyncProfileData(profile, lists = lists, deleted = deleted)).sortedBy { it.profile })
    }
}
