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
            { it.key }, { it.addedAt }, compareBy { it.title }, deleted,
        ).filter { alive(it.playlist) }
        val progress = pick(
            (left.progress + right.progress).map { it.copy(playlist = id(it.playlist)) },
            { it.key }, { it.watchedAt }, compareByDescending<SyncProgress> { it.position }.thenBy { it.title }, deleted,
        ).filter { alive(it.playlist) }
        val lists = pick(
            (left.lists + right.lists).map { list -> list.copy(items = list.items.map { it.copy(playlist = id(it.playlist)) }) },
            { it.key }, { it.updatedAt }, compareBy { list -> (listOf(list.name) + list.items.map { it.key }).joinToString("\n") }, deleted,
        ).map { list -> list.copy(items = list.items.filter { alive(it.playlist) }) }
        return left.copy(
            playlists = kept.sortedWith(compareBy<SyncPlaylist> { it.addedAt }.thenBy { it.id }),
            favorites = favorites.sortedWith(compareBy<SyncFavorite> { it.addedAt }.thenBy { it.key }),
            progress = progress.sortedWith(compareByDescending<SyncProgress> { it.watchedAt }.thenBy { it.key }),
            lists = lists.sortedBy { it.id },
            deleted = deleted.toSortedMap(),
            replaced = replaced.toSortedMap(),
        )
    }

    private val playlistOrder: Comparator<SyncPlaylist> = compareBy { playlist ->
        listOf(playlist.name, playlist.url, playlist.username.orEmpty(), playlist.password.orEmpty(), playlist.epgUrl.orEmpty()).joinToString("\n")
    }

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
