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

        val favorites = pick(
            (left.favorites + right.favorites).map { it.copy(playlist = id(it.playlist)) },
            { it.key }, { it.addedAt }, compareBy { tie(it.title, optional(it.image)) }, deleted,
        ).filter { alive(it.playlist) }
        val progress = pick(
            (left.progress + right.progress).map { it.copy(playlist = id(it.playlist)) },
            { it.key }, { it.watchedAt }, progressOrder, deleted,
        ).filter { alive(it.playlist) }
        val lists = mergeLists(
            (left.lists + right.lists).map { list ->
                list.copy(items = list.items.map { it.copy(playlist = id(it.playlist)) }, order = list.order?.map { key -> SyncListItem.withPlaylist(key, ::id) })
            },
            deleted,
        ).map { list -> list.copy(items = list.items.filter { alive(it.playlist) }) }
        // A side without people (a device that does not keep them) removes none: only a deletion does.
        val people = pick(left.people + right.people, { it.key }, { it.addedAt }, personOrder, deleted)
        // A side without keys (a device that does not sync them) removes none: only a deletion does.
        val keys = pick(left.keys + right.keys, { it.key }, { it.updatedAt }, compareBy { it.value }, deleted)
        // Likewise for the order of the rows: a side without it (an older version) changes none.
        val rows = pick(left.rows + right.rows, { it.key }, { it.updatedAt }, compareBy { it.tie }, deleted)
        return left.copy(
            playlists = kept.sortedWith(compareBy<SyncPlaylist> { it.addedAt }.thenBy { it.id }),
            favorites = favorites.sortedWith(compareBy<SyncFavorite> { it.addedAt }.thenBy { it.key }),
            progress = progress.sortedWith(compareByDescending<SyncProgress> { it.watchedAt }.thenBy { it.key }),
            lists = lists.sortedBy { it.id },
            people = people.sortedWith(compareBy<SyncPerson> { it.addedAt }.thenBy { it.key }),
            keys = keys.sortedBy { it.service },
            rows = rows.sortedBy { it.tab },
            deleted = deleted.toSortedMap(),
            replaced = replaced.toSortedMap(),
        )
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
