package com.markedoe.ondo.core.sync

/**
 * Merges two snapshots (docs/SYNC.md, "Merge"): the later change of each record wins, deletions win over records
 * not changed since, the same playlist added on two devices becomes one, and the records of a deleted playlist go
 * with it. The result does not depend on the order of the two snapshots, and merging it again changes nothing.
 */
object SyncMerge {
    private const val MAX_REPLACEMENTS = 10

    /** [device] and [deviceName] of the result are [a]'s: the device that merges answers with its own. */
    fun merge(a: SyncSnapshot, b: SyncSnapshot): SyncSnapshot {
        val left = a.normalized()
        val right = b.normalized()
        val deleted = HashMap(left.deleted)
        right.deleted.forEach { (key, time) -> deleted.merge(key, time, ::maxOf) }
        val replaced = HashMap(left.replaced)
        right.replaced.forEach { (old, new) -> replaced.merge(old, new, ::minOf) }

        val playlists = pick(left.playlists + right.playlists, { it.key }, { it.updatedAt }, playlistOrder, deleted)
        // The same playlist added twice: the oldest stays, the others become it.
        val kept = playlists
            .groupBy { Triple(it.kind, it.url.trim().lowercase(), it.username.orEmpty()) }
            .values
            .map { same ->
                val oldest = same.minWith(compareBy<SyncPlaylist> { it.addedAt }.thenBy { it.id })
                same.filter { it !== oldest }.forEach { dropped ->
                    deleted.merge(dropped.key, dropped.updatedAt, ::maxOf)
                    replaced.merge(dropped.id, oldest.id, ::minOf)
                }
                oldest
            }
        val keptIds = kept.mapTo(HashSet()) { it.id }
        fun id(playlist: String): String {
            var current = playlist
            repeat(MAX_REPLACEMENTS) { current = replaced[current] ?: return current }
            return current
        }
        // A record of a deleted playlist goes with it; one of a playlist this device does not know stays.
        fun alive(playlist: String) = playlist in keptIds || "playlist:$playlist" !in deleted

        val profiles = mergeProfiles(left, right, deleted)
        // Lists shared with every profile (rule 9): a list is in one space only, the household's or a profile's.
        fun mapped(list: SyncList) = withPlaylists(list, ::id)
        val keptProfiles = profiles.mapTo(HashSet()) { it.id } - SyncProfile.MAIN
        val spaces = sharedSpaces(left, right, keptProfiles, deleted, ::mapped)
        val shared = sharedLists(spaces)
        fun outside(lists: List<SyncList>) = lists.filter { it.key !in shared.resolved }

        val favorites = pick(
            (left.favorites + right.favorites).map { it.copy(playlist = id(it.playlist)) },
            { it.key }, { it.addedAt }, compareBy { tie(it.title, optional(it.image)) }, deleted,
        ).filter { alive(it.playlist) }
        val progress = pick(
            (left.progress + right.progress).map { it.copy(playlist = id(it.playlist)) },
            { it.key }, { it.watchedAt }, progressOrder, deleted,
        ).filter { alive(it.playlist) }
        val lists = mergeLists(
            outside((left.lists + right.lists).map(::mapped)) + shared.lists[SpaceId.Main].orEmpty(),
            deleted,
        ).map { list -> list.copy(items = list.items.filter { alive(it.playlist) }) }
        // A side without people (a device that does not keep them) removes none: only a deletion does.
        val people = pick(left.people + right.people, { it.key }, { it.addedAt }, personOrder, deleted)
        // A side without keys (a device that does not sync them) removes none: only a deletion does.
        val keys = pick(left.keys + right.keys, { it.key }, { it.updatedAt }, compareBy { it.value }, deleted)
        // Likewise for the order of the rows: a side without it (an older version) changes none.
        val rows = pick(left.rows + right.rows, { it.key }, { it.updatedAt }, compareBy { it.tie }, deleted)
        // Each hidden category, language or style on its own: the later hiding or showing again wins; a side without them
        // (an older version) shows none again. A category of a replaced playlist becomes the kept one's, and goes with a
        // deleted one.
        val hiddenCategories = SyncHidden.capped(
            pick((left.hiddenCategories + right.hiddenCategories).map { it.withPlaylist(::id) }, { it.key }, { it.hiddenAt }, compareBy { it.key }, deleted)
                .filter { hidden -> hidden.playlist?.let(::alive) ?: true },
        )
        val keptPlaylists = kept.sortedWith(compareBy<SyncPlaylist> { it.addedAt }.thenBy { it.id })
        // The profiles' buckets without the lists rule 9 placed, then those it placed in each, with its deletions.
        val buckets = (left.profileData + right.profileData).map { it.copy(lists = outside(it.lists.map(::mapped))) } +
            keptProfiles.sorted().mapNotNull { profile ->
                val placed = shared.lists[SpaceId.Profile(profile)].orEmpty()
                if (placed.isEmpty()) null else SyncProfileData(profile, lists = placed)
            }
        val profileData = profileData(buckets, profiles, keptPlaylists, deleted, replaced)
        // The household: its lists merged by the same rules, as a space of their own.
        val household = mergeHousehold(left.household, right.household, shared.lists[SpaceId.Household], keptPlaylists, deleted, replaced) {
            outside(it.map(::mapped))
        }
        return left.copy(
            playlists = keptPlaylists,
            favorites = favorites.sortedWith(compareBy<SyncFavorite> { it.addedAt }.thenBy { it.key }),
            progress = progress.sortedWith(compareByDescending<SyncProgress> { it.watchedAt }.thenBy { it.key }),
            lists = lists.sortedBy { it.id },
            people = people.sortedWith(compareBy<SyncPerson> { it.addedAt }.thenBy { it.key }),
            keys = keys.sortedBy { it.service },
            rows = SyncRows.capped(rows).sortedBy { it.tab },
            hiddenCategories = hiddenCategories.sortedBy { it.key },
            profiles = profiles,
            profileData = profileData,
            household = household,
            deleted = deleted.toSortedMap(),
            replaced = replaced.toSortedMap(),
        )
    }

    /**
     * Rule 8: the profiles of both sides, the later change winning; a profile's deletion wins only at or after its latest
     * time (its `updatedAt` and every time of its data); the main profile is never deleted.
     */
    private fun mergeProfiles(left: SyncSnapshot, right: SyncSnapshot, deleted: Map<String, Long>): List<SyncProfile> {
        // Profiles: the later change wins; a deleted profile goes with its data (the main one is never deleted). A side
        // without profiles (an older version) changes none.
        // A profile's deletion loses to its data changed after it, as a list's does (rule 7): data added on a device that
        // had not heard of the deletion brings the profile back, never goes with it.
        val activity = HashMap<String, Long>()
        (left.profileData + right.profileData).forEach { bucket -> bucket.records().latestTime()?.let { activity.merge(bucket.profile, it, ::maxOf) } }
        val profileDeletions = deleted.filter { (key, time) ->
            key != SyncProfile.KEY_PREFIX + SyncProfile.MAIN &&
                (!key.startsWith(SyncProfile.KEY_PREFIX) || (activity[key.removePrefix(SyncProfile.KEY_PREFIX)] ?: Long.MIN_VALUE) <= time)
        }
        return SyncProfile.capped(
            pick(left.profiles + right.profiles, { it.key }, { it.updatedAt }, compareBy { it.tie }, profileDeletions),
        ).sortedBy { it.id }
    }

    /** [list] with the playlist of each item, and of each key of its order, through [id] (rule 1). */
    private fun withPlaylists(list: SyncList, id: (String) -> String) = list.copy(
        items = list.items.map { it.copy(playlist = id(it.playlist)) },
        order = list.order?.map { key -> SyncListItem.withPlaylist(key, id) },
    )

    /**
     * The spaces a list can be in (rule 9), each with its lists from both sides (playlists replaced by [mapped]) and its
     * deletions: the household, the main profile (whose deletions are [mainDeleted]), and each profile of [keptProfiles].
     */
    private fun sharedSpaces(
        left: SyncSnapshot,
        right: SyncSnapshot,
        keptProfiles: Set<String>,
        mainDeleted: Map<String, Long>,
        mapped: (SyncList) -> SyncList,
    ): List<Space> {
        val leftHousehold = left.household ?: SyncHousehold()
        val rightHousehold = right.household ?: SyncHousehold()
        val spaces = mutableListOf(
            Space(SpaceId.Household, (leftHousehold.lists + rightHousehold.lists).map(mapped), HashMap(leftHousehold.deleted).also { merged ->
                rightHousehold.deleted.forEach { (key, time) -> merged.merge(key, time, ::maxOf) }
            }),
            Space(SpaceId.Main, (left.lists + right.lists).map(mapped), HashMap(mainDeleted)),
        )
        val profileSpaces = HashMap<String, Int>()
        for (bucket in left.profileData + right.profileData) {
            if (bucket.profile !in keptProfiles) continue
            val index = profileSpaces[bucket.profile]
            if (index != null) {
                val space = spaces[index]
                spaces[index] = Space(space.id, space.lists + bucket.lists.map(mapped), HashMap(space.deleted).also { merged ->
                    bucket.deleted.forEach { (key, time) -> merged.merge(key, time, ::maxOf) }
                })
            } else {
                profileSpaces[bucket.profile] = spaces.size
                spaces += Space(SpaceId.Profile(bucket.profile), bucket.lists.map(mapped), HashMap(bucket.deleted))
            }
        }
        return spaces
    }

    /**
     * The household of the merge: both sides' lists (without those rule 9 placed: [outside]), then those it placed there,
     * merged by rules 2 to 7 as a snapshot of their own whose playlists are the merged ones. None when neither side has
     * one (no list shared, or an older version) and rule 9 placed none there.
     */
    private fun mergeHousehold(
        left: SyncHousehold?,
        right: SyncHousehold?,
        placed: List<SyncList>?,
        playlists: List<SyncPlaylist>,
        deleted: Map<String, Long>,
        replaced: Map<String, String>,
        outside: (List<SyncList>) -> List<SyncList>,
    ): SyncHousehold? {
        if (left == null && right == null && placed == null) return null
        val deletedPlaylists = deleted.filterKeys { it.startsWith("playlist:") }
        val sides = listOfNotNull(left, right).map { it.copy(lists = outside(it.lists)) } + SyncHousehold(lists = placed.orEmpty())
        // Merged with an empty side first, as a profile's data: one side alone gets the playlists' outcome too.
        val records = sides.fold(SyncHousehold().records(playlists, deletedPlaylists, replaced)) { result, side ->
            merge(result, side.records(playlists, deletedPlaylists, replaced))
        }
        return SyncHousehold.of(records).let { it.copy(deleted = it.deleted.toSortedMap()) }.takeIf { !it.isEmpty }
    }

    /** Where a list is: the household (shared with every profile), the main profile, or another profile. */
    internal sealed interface SpaceId {
        /** The space as `listmoved:<space>|<id>` names it: `household`, the main profile's ID, or the profile's ID. */
        val name: String

        data object Household : SpaceId {
            override val name = SyncSharing.HOUSEHOLD
        }

        data object Main : SpaceId {
            override val name = SyncProfile.MAIN
        }

        data class Profile(val id: String) : SpaceId {
            override val name get() = id
        }
    }

    /** Ties between spaces: the household, the main profile, then the others by ID. */
    private val spaceOrder: Comparator<SpaceId> = compareBy<SpaceId> {
        when (it) {
            SpaceId.Household -> 0
            SpaceId.Main -> 1
            is SpaceId.Profile -> 2
        }
    }.thenBy { (it as? SpaceId.Profile)?.id.orEmpty() }

    /** The lists of one space from both sides (playlists already replaced), and its deletions. */
    internal class Space(val id: SpaceId, val lists: List<SyncList>, val deleted: Map<String, Long>)

    /** What rule 9 decides: the lists it resolved (by key), each placed in one space. */
    internal class SharedOutcome(val resolved: Set<String>, val lists: Map<SpaceId, List<SyncList>>)

    /**
     * Rule 9 of the merge (docs/SYNC.md, "Shared lists"): a list found in two spaces or more (moved between a profile and
     * the household on one device while another still had it where it was), or while the list is deleted for every
     * profile, ends in one space, and no copy is lost unless it was deleted.
     * - A space's copies are deleted when their latest `updatedAt` is not after that space's `list:<id>` deletion or,
     *   outside the household, not after the deletion for every profile (`sharedlistdeleted:<id>`, or the household's
     *   `list:<id>`).
     * - Of the spaces left, those whose copies are later than the list's move out of them (`listmoved:<space>|<id>`, kept
     *   with the top-level deletions so that an older device passes it on) keep it; the one it was moved into last wins
     *   (the latest `placedAt`, then the household, the main profile, the other profiles by ID). When every space left was
     *   moved out of (the copy where it went did not reach this device, through an older one), the list stays where it is,
     *   by the same order: a move never loses a list.
     * - The list there is every copy of the spaces left merged by rule 7 (a change made where it was, before or after the
     *   move, is carried), without the items taken out in any space.
     */
    internal fun sharedLists(spaces: List<Space>): SharedOutcome {
        val resolved = HashSet<String>()
        val placed = HashMap<SpaceId, MutableList<SyncList>>()
        val household = spaces.firstOrNull { it.id == SpaceId.Household } ?: return SharedOutcome(resolved, placed)
        val top = spaces.firstOrNull { it.id == SpaceId.Main }?.deleted ?: return SharedOutcome(resolved, placed)
        val copies = LinkedHashMap<String, LinkedHashMap<SpaceId, MutableList<SyncList>>>()
        for (space in spaces) {
            for (list in space.lists) copies.getOrPut(list.key) { LinkedHashMap() }.getOrPut(space.id) { ArrayList() } += list
        }
        for ((key, bySpace) in copies) {
            val first = bySpace.values.first().first()
            val forAll = listOfNotNull(household.deleted[key], top[SyncSharing.deletedForAllKey(first.id)]).maxOrNull()
            if (bySpace.size < 2 && forAll == null) continue
            resolved += key
            fun latest(space: Space) = bySpace.getValue(space.id).maxOf { it.updatedAt }
            val left = spaces.filter { space ->
                space.id in bySpace && latest(space).let { time ->
                    space.deleted[key]?.let { time <= it } != true && forAll?.let { time <= it } != true
                }
            }
            val keeping = left.filter { space -> top[SyncSharing.movedKey(space.id.name, first.id)]?.let { latest(space) > it } ?: true }
            fun placedAt(space: Space) = bySpace.getValue(space.id).mapNotNull { it.placedAt }.maxOrNull() ?: 0L
            val winner = keeping.ifEmpty { left }
                .minWithOrNull(compareByDescending<Space> { placedAt(it) }.thenComparator { a, b -> spaceOrder.compare(a.id, b.id) })
                ?: continue
            // The items taken out of the list in any space (its ID is in their keys).
            val itemPrefix = SyncList.ITEM_PREFIX + first.id + "|"
            val itemDeletions = HashMap<String, Long>()
            for (space in spaces) {
                space.deleted.forEach { (deletion, time) -> if (deletion.startsWith(itemPrefix)) itemDeletions.merge(deletion, time, ::maxOf) }
            }
            placed.getOrPut(winner.id) { ArrayList() } += mergeLists(left.flatMap { bySpace.getValue(it.id) }, itemDeletions)
        }
        return SharedOutcome(resolved, placed)
    }

    /**
     * The data of each profile kept, other than the main one (docs/SYNC.md, "Profiles"): merged as a snapshot of its own
     * by the same rules, with the playlists as merged (a favourite of a deleted playlist goes, a replaced playlist's
     * become the one kept). A profile deleted, or not kept, goes with its data.
     */
    private fun profileData(
        buckets: List<SyncProfileData>,
        profiles: List<SyncProfile>,
        playlists: List<SyncPlaylist>,
        deleted: Map<String, Long>,
        replaced: Map<String, String>,
    ): List<SyncProfileData> {
        val kept = profiles.mapTo(HashSet()) { it.id }
        val deletedPlaylists = deleted.filterKeys { it.startsWith("playlist:") }
        return buckets.filter { it.profile != SyncProfile.MAIN && it.profile in kept }.groupBy { it.profile }.map { (profile, same) ->
            // Merged with an empty side first: one side's bucket alone gets the playlists' outcome too.
            val merged = same.fold(SyncProfileData(profile).records(playlists, deletedPlaylists, replaced)) { result, bucket ->
                merge(result, bucket.records(playlists, deletedPlaylists, replaced))
            }
            SyncProfileData.of(profile, merged.copy(deleted = merged.deleted.toSortedMap()))
        }.sortedBy { it.profile }
    }

    /**
     * One list per key, merged field by field (docs/SYNC.md, "Merge", rule 7): it stays unless deleted after its last
     * change; its name is the one set last; its items and people are those of either side, each at its own time,
     * without those removed since; their order is the one set last, then the others by the time they were added.
     */
    private fun mergeLists(lists: List<SyncList>, deleted: Map<String, Long>): List<SyncList> =
        lists.groupBy { it.key }.mapNotNull { (key, same) ->
            val updatedAt = same.maxOf { it.updatedAt }
            if (deleted[key]?.let { updatedAt <= it } == true) return@mapNotNull null
            val first = same.first()
            val named = same.minWith(compareByDescending<SyncList> { it.nameTime }.thenBy { it.name })
            val ordered = same.minWith(compareByDescending<SyncList> { it.orderTime }.thenBy { it.itemOrder.joinToString("\n") })
            fun kept(item: String, time: Long) = deleted[first.itemKey(item)]?.let { time > it } ?: true
            val items = same.flatMap { list -> list.items.map { it.copy(addedAt = it.addedAt ?: list.updatedAt) } }
                .groupBy { it.key }.values
                .map { versions -> versions.minWith(itemOrder) }
                .filter { kept(it.key, it.addedAt ?: 0) }
            val people = same.flatMap { it.people }
                .groupBy { it.key }.values
                .map { versions -> versions.minWith(compareByDescending<SyncPerson> { it.addedAt }.then(personOrder)) }
                .filter { kept(it.key, it.addedAt) }
            // The order set last is kept as it was set (an order that changes as it is merged would depend on the order
            // of the merges); the items it does not name come after it.
            val order = ordered.itemOrder
            val position = HashMap<String, Int>()
            order.forEachIndexed { index, item -> position.putIfAbsent(item, index) }
            first.copy(
                name = named.name,
                items = items.sortedWith(compareBy<SyncListItem> { position[it.key] ?: Int.MAX_VALUE }.thenBy { it.addedAt }.thenBy { it.key }),
                people = people.sortedWith(compareBy<SyncPerson> { it.addedAt }.thenBy { it.key }),
                updatedAt = updatedAt,
                renamedAt = named.nameTime,
                orderedAt = ordered.orderTime,
                order = order,
                // When it was last moved into this space, if ever (rule 9).
                placedAt = same.mapNotNull { it.placedAt }.maxOrNull(),
            )
        }

    /** The later addition wins, then the smaller title, then no image, then the smaller image address. */
    private val itemOrder: Comparator<SyncListItem> =
        compareByDescending<SyncListItem> { it.addedAt ?: 0 }.thenBy { tie(it.title, optional(it.image)) }

    /** The larger position wins, then the longer duration, then the smaller title, subtitle and image (none first). */
    private val progressOrder: Comparator<SyncProgress> = compareByDescending<SyncProgress> { it.position }
        .thenByDescending { it.duration }
        .thenBy { tie(it.title, optional(it.subtitle), optional(it.image)) }

    /** The person with a photo wins, then the smaller name, then the smaller photo address. */
    private val personOrder: Comparator<SyncPerson> = compareBy<SyncPerson> { it.image == null }.thenBy { it.name }.thenBy { it.image.orEmpty() }

    private val playlistOrder: Comparator<SyncPlaylist> = compareBy { playlist ->
        tie(playlist.name, playlist.kind, playlist.url, optional(playlist.username), optional(playlist.password), optional(playlist.epgUrl), playlist.addedAt.toString())
    }

    /** A tie string (docs/SYNC.md, "Merge", rule 2): the fields joined with a line feed. */
    private fun tie(vararg fields: String) = fields.joinToString("\n")

    /** An optional field in a tie string: `0` when missing, else `1` then its value (none comes before any). */
    private fun optional(value: String?) = if (value == null) "0" else "1$value"

    /**
     * One record per key: the later time wins, then the one [tie] puts first; a record not changed after its key's
     * deletion is dropped.
     */
    private fun <T> pick(records: List<T>, key: (T) -> String, time: (T) -> Long, tie: Comparator<T>, deleted: Map<String, Long>): List<T> {
        val order = compareByDescending(time).then(tie)
        return records.groupBy(key).mapNotNull { (key, same) ->
            val winner = same.minWith(order)
            winner.takeIf { deleted[key]?.let { removed -> time(it) > removed } ?: true }
        }
    }
}
