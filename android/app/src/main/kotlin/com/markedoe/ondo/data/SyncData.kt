package com.markedoe.ondo.data

import com.markedoe.ondo.core.sync.SyncFavorite
import com.markedoe.ondo.core.sync.SyncList
import com.markedoe.ondo.core.sync.SyncListItem
import com.markedoe.ondo.core.sync.SyncPlaylist
import com.markedoe.ondo.core.sync.SyncProgress
import com.markedoe.ondo.core.sync.SyncSnapshot

/** The keys sync gives each record (docs/SYNC.md), from this app's records. */
object SyncKeys {
    fun kind(kind: ItemKind): String = when (kind) {
        ItemKind.CHANNEL -> "live"
        ItemKind.MOVIE -> "movie"
        ItemKind.SERIES -> "series"
    }

    fun itemKind(kind: String): ItemKind? = when (kind) {
        "live" -> ItemKind.CHANNEL
        "movie" -> ItemKind.MOVIE
        "series" -> ItemKind.SERIES
        else -> null
    }

    fun playlist(id: String) = "playlist:${id.lowercase()}"
    fun list(id: String) = "list:${id.lowercase()}"
    fun favorite(favorite: Favorite) = "favorite:${favorite.playlistId.lowercase()}|${kind(favorite.kind)}|${favorite.itemId}"
    fun progress(entry: WatchEntry) = "progress:${entry.playlistId.lowercase()}|${kind(entry.kind)}|${entry.itemId}|${entry.episodeId.orEmpty()}"

    /** Where a movie or an episode was left syncs; the channels watched stay on the device. */
    fun syncs(entry: WatchEntry) = entry.kind != ItemKind.CHANNEL

    /** A playlist imported from a file is on this device only: it never syncs. */
    fun syncs(playlist: SavedPlaylist): Boolean = !isFile(playlist.url)

    /** A file on the device (imported, or a path): what another device sends is never one. */
    fun isFile(address: String): Boolean {
        val url = address.trim().lowercase()
        return url.startsWith("content:") || url.startsWith("file:") || url.startsWith("/")
    }
}

/** A device sync talks to: its name, and when the last session with it worked (null: none yet). */
data class SyncDevice(val id: String, val name: String, val syncedAt: Long?, val unreachable: Boolean)

/** This device's playlists and library as a sync snapshot, and a merged snapshot back into them. */
object SyncData {
    /**
     * What sync carries, without where each title was left (only whether it was finished): when it changes, a
     * session soon is worth it; the position alone travels with the next regular session.
     */
    fun changes(state: OndoState): Any = listOf(
        state.playlists.filter(SyncKeys::syncs),
        state.library.favorites,
        state.library.collections,
        state.library.syncDeleted,
        state.library.history.filter(SyncKeys::syncs).map { it.key to it.isFinished },
    )

    fun snapshot(state: OndoState, device: String, deviceName: String?, now: Long): SyncSnapshot {
        val synced = state.playlists.filter(SyncKeys::syncs)
        val ids = synced.mapTo(HashSet()) { it.id.lowercase() }
        val known = state.playlists.mapTo(HashSet()) { it.id.lowercase() }
        val library = state.library
        // Records of a file playlist stay here; those of a playlist this device does not know (yet) still travel.
        fun travels(playlistId: String) = playlistId.lowercase().let { it in ids || it !in known }
        return SyncSnapshot(
            device = device,
            deviceName = deviceName,
            playlists = synced.map { playlist ->
                SyncPlaylist(
                    id = playlist.id.lowercase(),
                    name = playlist.name,
                    kind = if (playlist.isXtream) SyncPlaylist.XTREAM else SyncPlaylist.M3U,
                    url = playlist.url,
                    username = playlist.username.takeIf { playlist.isXtream },
                    password = playlist.password.takeIf { playlist.isXtream },
                    epgUrl = playlist.epgUrl,
                    addedAt = playlist.addedAt,
                    updatedAt = playlist.updatedAt.takeIf { it > 0 } ?: playlist.addedAt,
                )
            },
            favorites = library.favorites.filter { travels(it.playlistId) }.map { favorite ->
                SyncFavorite(favorite.playlistId.lowercase(), SyncKeys.kind(favorite.kind), favorite.itemId, favorite.title, favorite.imageUrl, favorite.addedAt)
            },
            progress = library.history.filter { SyncKeys.syncs(it) && travels(it.playlistId) }.map { entry ->
                SyncProgress(
                    playlist = entry.playlistId.lowercase(),
                    kind = SyncKeys.kind(entry.kind),
                    item = entry.itemId,
                    episode = entry.episodeId,
                    title = entry.title,
                    subtitle = entry.subtitle,
                    image = entry.imageUrl,
                    position = entry.position,
                    duration = entry.duration,
                    watchedAt = entry.lastWatchedAt,
                )
            },
            lists = library.collections.map { list ->
                SyncList(
                    id = list.id.lowercase(),
                    name = list.name,
                    items = list.items.map { SyncListItem(it.playlistId.lowercase(), SyncKeys.kind(it.kind), it.itemId, it.title, it.imageUrl) },
                    updatedAt = list.updatedAt,
                )
            },
            deleted = library.syncDeleted,
            replaced = library.syncReplaced,
        ).pruned(now)
    }

    /** What applying [merged] gives: the playlists and the library to keep (everything not synced stays as it is). */
    class Applied(val playlists: List<SavedPlaylist>, val removed: List<SavedPlaylist>, val library: LibraryState)

    private fun usable(playlist: SyncPlaylist) = playlist.id.isNotBlank() && playlist.url.isNotBlank() && !SyncKeys.isFile(playlist.url) && when (playlist.kind) {
        SyncPlaylist.M3U -> true
        SyncPlaylist.XTREAM -> !playlist.username.isNullOrBlank() && playlist.password != null
        else -> false
    }

    fun apply(state: OndoState, merged: SyncSnapshot): Applied {
        // The device's own IDs, whatever their case; IDs it does not know keep the merged (lowercase) form.
        val localIds = state.playlists.associateBy({ it.id.lowercase() }, { it.id })
        fun local(id: String) = localIds[id.lowercase()] ?: id
        // A playlist this app could not open (no address, another kind, an Xtream one without its login), or one that
        // names a file of this device, is left out.
        val mergedById = merged.playlists.filter(::usable).associateBy { it.id }
        val removed = state.playlists.filter { playlist ->
            SyncKeys.syncs(playlist) && playlist.id.lowercase() !in mergedById && SyncKeys.playlist(playlist.id) in merged.deleted
        }
        val kept = state.playlists.filter { it !in removed }.map { playlist ->
            val synced = mergedById[playlist.id.lowercase()] ?: return@map playlist
            playlist.copy(
                name = synced.name,
                url = synced.url,
                username = synced.username.takeIf { synced.kind == SyncPlaylist.XTREAM },
                password = synced.password.takeIf { synced.kind == SyncPlaylist.XTREAM },
                epgUrl = synced.epgUrl,
                updatedAt = synced.updatedAt,
            )
        }
        val known = kept.mapTo(HashSet()) { it.id.lowercase() }
        val added = mergedById.values.filter { it.id !in known }.map { synced ->
            SavedPlaylist(
                id = synced.id,
                name = synced.name,
                url = synced.url,
                username = synced.username.takeIf { synced.kind == SyncPlaylist.XTREAM },
                password = synced.password.takeIf { synced.kind == SyncPlaylist.XTREAM },
                epgUrl = synced.epgUrl,
                addedAt = synced.addedAt,
                updatedAt = synced.updatedAt,
            )
        }

        val library = state.library
        val removedIds = removed.mapTo(HashSet()) { it.id }
        // What does not sync stays: the channels watched, and everything of a file playlist.
        val filePlaylists = state.playlists.filterNot(SyncKeys::syncs).mapTo(HashSet()) { it.id }
        val favorites = library.favorites.filter { it.playlistId in filePlaylists } + merged.favorites.mapNotNull { synced ->
            val kind = SyncKeys.itemKind(synced.kind) ?: return@mapNotNull null
            Favorite(local(synced.playlist), kind, synced.item, synced.title, synced.image, synced.addedAt)
        }.filter { it.playlistId !in filePlaylists }
        val history = library.history.filter { !SyncKeys.syncs(it) || it.playlistId in filePlaylists } + merged.progress.mapNotNull { synced ->
            val kind = SyncKeys.itemKind(synced.kind)?.takeIf { it != ItemKind.CHANNEL } ?: return@mapNotNull null
            WatchEntry(
                playlistId = local(synced.playlist),
                kind = kind,
                itemId = synced.item,
                episodeId = synced.episode,
                title = synced.title,
                subtitle = synced.subtitle,
                imageUrl = synced.image,
                position = synced.position,
                duration = synced.duration,
                lastWatchedAt = synced.watchedAt,
            )
        }.filter { it.playlistId !in filePlaylists }
        val localItems = library.collections.flatMap { it.items }.associateBy { "${it.playlistId.lowercase()}|${SyncKeys.kind(it.kind)}|${it.itemId}" }
        // The device's own list IDs, whatever their case (the first of each, as a search in order would find).
        val localLists = HashMap<String, String>()
        library.collections.forEach { localLists.putIfAbsent(it.id.lowercase(), it.id) }
        val collections = merged.lists.map { synced ->
            UserCollection(
                id = localLists[synced.id.lowercase()] ?: synced.id,
                name = synced.name,
                items = synced.items.mapNotNull { item ->
                    val kind = SyncKeys.itemKind(item.kind) ?: return@mapNotNull null
                    Favorite(local(item.playlist), kind, item.item, item.title, item.image, localItems[item.key]?.addedAt ?: synced.updatedAt)
                },
                updatedAt = synced.updatedAt,
            )
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, UserCollection::name).thenBy { it.id })
        val applied = library.copy(
            favorites = favorites,
            history = history.sortedByDescending { it.lastWatchedAt }.take(LibraryState.MAX_HISTORY),
            collections = collections,
            syncDeleted = merged.deleted,
            syncReplaced = merged.replaced,
        ).let { kept -> removedIds.fold(kept) { result, id -> result.removingPlaylist(id, merged.deleted[SyncKeys.playlist(id)] ?: 0) } }
        return Applied(kept + added, removed, applied)
    }
}
