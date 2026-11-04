package com.markedoe.ondo.data

import com.markedoe.ondo.core.model.Profile
import com.markedoe.ondo.core.sync.SyncFavorite
import com.markedoe.ondo.core.sync.SyncKey
import com.markedoe.ondo.core.sync.SyncList
import com.markedoe.ondo.core.sync.SyncListItem
import com.markedoe.ondo.core.sync.SyncPerson
import com.markedoe.ondo.core.sync.SyncPlaylist
import com.markedoe.ondo.core.sync.SyncProfile
import com.markedoe.ondo.core.sync.SyncProfileData
import com.markedoe.ondo.core.sync.SyncProgress
import com.markedoe.ondo.core.sync.SyncRows
import com.markedoe.ondo.core.sync.SyncSnapshot

/** The keys sync gives each record (docs/SYNC.md), from this app's records. */
object SyncKeys {
    fun kind(kind: ItemKind): String = when (kind) {
        ItemKind.CHANNEL -> "live"
        ItemKind.MOVIE -> "movie"
        ItemKind.SERIES -> "series"
        ItemKind.PERSON -> "person"
    }

    fun itemKind(kind: String): ItemKind? = when (kind) {
        "live" -> ItemKind.CHANNEL
        "movie" -> ItemKind.MOVIE
        "series" -> ItemKind.SERIES
        else -> null
    }

    fun playlist(id: String) = "playlist:${id.lowercase()}"
    fun list(id: String) = "list:${id.lowercase()}"
    fun favorite(favorite: Favorite) =
        if (favorite.kind == ItemKind.PERSON) person(favorite.itemId) else "favorite:${favorite.playlistId.lowercase()}|${kind(favorite.kind)}|${favorite.itemId}"
    fun person(name: String) = SyncPerson.KEY_PREFIX + SyncPerson.folded(name)

    /** The removal of [item] from the collection [listId]: `listitem:<list>|<item key>` (docs/SYNC.md). */
    fun listItem(listId: String, item: Favorite) =
        SyncList.ITEM_PREFIX + listId.lowercase() + "|" +
            (if (item.kind == ItemKind.PERSON) person(item.itemId) else "${item.playlistId.lowercase()}|${kind(item.kind)}|${item.itemId}")
    fun progress(entry: WatchEntry) = "progress:${entry.playlistId.lowercase()}|${kind(entry.kind)}|${entry.itemId}|${entry.episodeId.orEmpty()}"

    /** A person kept (in My List or a list) travels as a person, whatever the playlist; the rest as a favourite. */
    fun isPerson(favorite: Favorite) = favorite.kind == ItemKind.PERSON

    fun syncPerson(favorite: Favorite) = SyncPerson(favorite.title, favorite.imageUrl, favorite.addedAt)

    fun personFavorite(synced: SyncPerson) = Favorite(Favorite.ANY_PLAYLIST, ItemKind.PERSON, synced.name, synced.name, synced.image, synced.addedAt)

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

/**
 * This device's playlists and libraries as a sync snapshot, and a merged snapshot back into them. [libraries] holds each
 * profile's library by profile ID (docs/PROFILES.md): the main one's records are the snapshot's own, the others' travel
 * in its `profileData` (docs/SYNC.md, "Profiles").
 */
object SyncData {
    /**
     * What sync carries, without where each title was left (only whether it was finished): when it changes, a
     * session soon is worth it; the position alone travels with the next regular session. The other profiles' libraries
     * change only while open (then they are [OndoState.library]) or by sync.
     */
    fun changes(state: OndoState): Any = listOf(
        state.playlists.filter(SyncKeys::syncs),
        state.library.favorites,
        state.library.collections,
        state.library.syncDeleted,
        state.library.history.filter(SyncKeys::syncs).map { it.key to it.isFinished },
        state.library.rowOrders,
        state.settings.serviceKeys,
        state.profiles.map { it.copy(pinHash = null) },
    )

    /** Each profile's library: [others] (as saved) with the open profile's from [state]. */
    fun libraries(state: OndoState, others: Map<String, LibraryState>): Map<String, LibraryState> =
        others + (state.activeProfile.id to state.library)

    private fun main(state: OndoState, libraries: Map<String, LibraryState>): LibraryState =
        libraries[Profile.DEFAULT_ID] ?: if (state.activeProfile.isMain) state.library else LibraryState()

    /**
     * This device's snapshot, without the deletions [SyncSnapshot.pruned] forgets at [now] (this device's sync clock,
     * [com.markedoe.ondo.core.sync.SyncClock.now]). Its times are those stored: never moved.
     */
    fun snapshot(state: OndoState, device: String, deviceName: String?, now: Long, libraries: Map<String, LibraryState> = libraries(state, emptyMap())): SyncSnapshot {
        val main = main(state, libraries)
        return records(state, libraries, device, deviceName, now).pruned(now, sessions = main.syncPeers.values, learned = main.syncDeletedLearned)
    }

    /** The latest time of what this device holds for sync (its records and deletions, every profile's); null without any. */
    fun latestTime(state: OndoState, libraries: Map<String, LibraryState> = libraries(state, emptyMap())): Long? =
        records(state, libraries, "", null, 0).latestTime()

    private fun records(state: OndoState, libraries: Map<String, LibraryState>, device: String, deviceName: String?, now: Long): SyncSnapshot {
        val synced = state.playlists.filter(SyncKeys::syncs)
        val ids = synced.mapTo(HashSet()) { it.id.lowercase() }
        val known = state.playlists.mapTo(HashSet()) { it.id.lowercase() }
        // Records of a file playlist stay here; those of a playlist this device does not know (yet) still travel.
        fun travels(playlistId: String) = playlistId.lowercase().let { it in ids || it !in known }
        val main = main(state, libraries)
        val own = libraryRecords(main, ::travels)
        // A profile never changed (the main one as made) is not sent: the same "Me" made on two devices is no change.
        val profiles = state.profiles.filter { it.updatedAt > 0 }.map {
            SyncProfile(it.id.lowercase(), it.name, it.colorIndex, it.symbol, it.isKids, it.updatedAt)
        }.sortedBy { it.id }
        val profileData = state.profiles.filter { !it.isMain }.mapNotNull { profile ->
            libraries[profile.id]?.let { SyncProfileData.of(profile.id.lowercase(), libraryRecords(it, ::travels)) }
        }.sortedBy { it.profile }
        return own.copy(
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
            keys = state.settings.serviceKeys.filter { it.value.isNotBlank() },
            profiles = profiles,
            profileData = profileData,
            replaced = main.syncReplaced,
            clock = now,
        )
    }

    /** A profile's library as records: its My List, favourite channels, progress, lists, people, rows and deletions. */
    private fun libraryRecords(library: LibraryState, travels: (String) -> Boolean) = SyncSnapshot(
        device = "",
        favorites = library.favorites.filter { !SyncKeys.isPerson(it) && travels(it.playlistId) }.map { favorite ->
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
                items = list.items.filterNot(SyncKeys::isPerson).map {
                    SyncListItem(it.playlistId.lowercase(), SyncKeys.kind(it.kind), it.itemId, it.title, it.imageUrl, it.addedAt)
                },
                people = list.items.filter(SyncKeys::isPerson).map(SyncKeys::syncPerson),
                updatedAt = list.updatedAt,
                renamedAt = list.renamedAt.takeIf { it > 0 },
                orderedAt = list.orderedAt.takeIf { it > 0 },
            )
        },
        people = library.favorites.filter(SyncKeys::isPerson).map(SyncKeys::syncPerson),
        rows = library.rowOrders,
        deleted = library.syncDeleted,
    )

    /**
     * What applying [merged] gives: the playlists and each profile's library to keep (everything not synced stays as it
     * is), for a playlist removed because it is another one (the same one added on two devices) the ID of that one, and
     * the profiles as merged (each keeping its PIN, which stays on the device).
     */
    class Applied(
        val playlists: List<SavedPlaylist>,
        val removed: List<SavedPlaylist>,
        /** The main profile's library. */
        val library: LibraryState,
        val replacements: Map<String, String> = emptyMap(),
        /** The keys of outside services to keep: the merged ones (this device's own were part of the merge). */
        val keys: List<SyncKey> = emptyList(),
        /** The profiles to keep, and each profile's library by ID (the main one's too). */
        val profiles: List<Profile> = emptyList(),
        val libraries: Map<String, LibraryState> = emptyMap(),
        /** The profiles deleted on another device: their libraries go. */
        val removedProfiles: List<Profile> = emptyList(),
    ) {
        /** The library to show for [profileId]. */
        fun library(profileId: String): LibraryState? = libraries[profileId]

        /** The order of the rows of the main profile (as before profiles, for what reads it). */
        val rows: List<SyncRows> get() = library.rowOrders
    }

    private fun usable(playlist: SyncPlaylist) = playlist.id.isNotBlank() && playlist.url.isNotBlank() && !SyncKeys.isFile(playlist.url) && when (playlist.kind) {
        SyncPlaylist.M3U -> true
        SyncPlaylist.XTREAM -> !playlist.username.isNullOrBlank() && playlist.password != null
        else -> false
    }

    /** [now]: when this device learns the deletions of [merged] it did not have (kept from then, docs/SYNC.md). */
    fun apply(state: OndoState, merged: SyncSnapshot, now: Long = 0, libraries: Map<String, LibraryState> = libraries(state, emptyMap())): Applied {
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

        val playlists = kept + added
        // A playlist removed because it is another one (the same one added on two devices): that one, as kept here.
        fun replacement(id: String): SavedPlaylist? {
            var current = id.lowercase()
            repeat(MAX_REPLACEMENTS) { current = merged.replaced[current] ?: return@repeat }
            return playlists.firstOrNull { it.id.equals(current, ignoreCase = true) && !it.id.equals(id, ignoreCase = true) }
        }
        val replacements = removed.mapNotNull { old -> replacement(old.id)?.let { old.id to it.id } }
        val context = Context(state, merged, ::local, removed.mapTo(HashSet()) { it.id }, replacements, now)

        val mainLibrary = applyLibrary(main(state, libraries), merged, context, isMain = true)

        // The profiles: the merged ones, each keeping its PIN; one deleted elsewhere goes (never the main one).
        val mergedProfiles = merged.profiles.associateBy { it.id.lowercase() }
        val removedProfiles = state.profiles.filter { profile ->
            !profile.isMain && profile.id.lowercase() !in mergedProfiles &&
                (merged.deleted[SyncProfile.KEY_PREFIX + profile.id.lowercase()]?.let { it >= profile.updatedAt } == true)
        }
        val localById = state.profiles.associateBy { it.id.lowercase() }
        val profiles = (state.profiles - removedProfiles.toSet()).map { profile ->
            val synced = mergedProfiles[profile.id.lowercase()]?.takeIf { it.updatedAt >= profile.updatedAt } ?: return@map profile
            profile.copy(name = synced.name, colorIndex = synced.colorIndex, symbol = synced.symbol, isKids = synced.kids, updatedAt = synced.updatedAt)
        } + merged.profiles.filter { it.id.lowercase() !in localById && it.id != SyncProfile.MAIN }.map { synced ->
            Profile(id = synced.id, name = synced.name, colorIndex = synced.colorIndex, symbol = synced.symbol, isKids = synced.kids, updatedAt = synced.updatedAt)
        }
        // Each other profile's data: its bucket, by the same rules. A profile not in the merge keeps its library as it is.
        val buckets = merged.profileData.associateBy { it.profile }
        val mergedPlaylistDeletions = merged.deleted.filterKeys { it.startsWith("playlist:") }
        val others = profiles.filter { !it.isMain && it.id.lowercase() in mergedProfiles }.associate { profile ->
            val bucket = buckets[profile.id.lowercase()] ?: SyncProfileData(profile.id.lowercase())
            val records = bucket.records(deletedPlaylists = mergedPlaylistDeletions, replaced = merged.replaced)
            profile.id to applyLibrary(libraries[profile.id] ?: LibraryState(), records, context, isMain = false)
        }
        return Applied(
            playlists, removed, mainLibrary, replacements.toMap(), merged.keys,
            profiles = profiles, libraries = others + (Profile.DEFAULT_ID to mainLibrary), removedProfiles = removedProfiles,
        )
    }

    /** What every profile's library is applied with. */
    private class Context(
        val state: OndoState,
        val merged: SyncSnapshot,
        val local: (String) -> String,
        val removedIds: Set<String>,
        val replacements: List<Pair<String, String>>,
        val now: Long,
    ) {
        /** What does not sync stays: the channels watched, and everything of a file playlist. */
        val filePlaylists: Set<String> = state.playlists.filterNot(SyncKeys::syncs).mapTo(HashSet()) { it.id }
    }

    /**
     * [library] with the records of [records] (the main profile's: the merged snapshot itself; another's: its bucket):
     * My List and favourite channels, progress, lists, people, rows and deletions; what does not sync stays.
     */
    private fun applyLibrary(library: LibraryState, records: SyncSnapshot, context: Context, isMain: Boolean): LibraryState {
        val local = context.local
        val filePlaylists = context.filePlaylists
        val favorites = library.favorites.filter { it.playlistId in filePlaylists && !SyncKeys.isPerson(it) } + records.favorites.mapNotNull { synced ->
            val kind = SyncKeys.itemKind(synced.kind) ?: return@mapNotNull null
            Favorite(local(synced.playlist), kind, synced.item, synced.title, synced.image, synced.addedAt)
        }.filter { it.playlistId !in filePlaylists } + people(records.people)
        // Where the end credits start does not sync: each device keeps what its player read in the file's chapters.
        val creditsStarts = library.history.mapNotNull { entry -> entry.creditsStart?.let { entry.key to it } }.toMap()
        val history = library.history.filter { !SyncKeys.syncs(it) || it.playlistId in filePlaylists } + records.progress.mapNotNull { synced ->
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
            ).let { entry -> entry.copy(creditsStart = creditsStarts[entry.key]) }
        }.filter { it.playlistId !in filePlaylists }
        val localItems = library.collections.flatMap { it.items }.associateBy { "${it.playlistId.lowercase()}|${SyncKeys.kind(it.kind)}|${it.itemId}" }
        // The device's own list IDs, whatever their case (the first of each, as a search in order would find).
        val localLists = HashMap<String, String>()
        library.collections.forEach { localLists.putIfAbsent(it.id.lowercase(), it.id) }
        val collections = records.lists.map { synced ->
            UserCollection(
                id = localLists[synced.id.lowercase()] ?: synced.id,
                name = synced.name,
                items = synced.items.mapNotNull { item ->
                    val kind = SyncKeys.itemKind(item.kind) ?: return@mapNotNull null
                    Favorite(local(item.playlist), kind, item.item, item.title, item.image, item.addedAt ?: localItems[item.key]?.addedAt ?: synced.updatedAt)
                } + people(synced.people),
                updatedAt = synced.updatedAt,
                renamedAt = synced.renamedAt ?: 0,
                orderedAt = synced.orderedAt ?: 0,
            )
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, UserCollection::name).thenBy { it.id })
        // Another profile's deletions are its own (its bucket's); the main one's are the snapshot's, with the sessions.
        val deleted = if (isMain) records.deleted else records.deleted.filterKeys { !it.startsWith("playlist:") }
        val applied = library.copy(
            favorites = favorites,
            history = history.sortedByDescending { it.lastWatchedAt }.take(LibraryState.MAX_HISTORY),
            collections = collections,
            rowOrders = records.rows,
            syncDeleted = deleted,
            syncDeletedLearned = if (!isMain) library.syncDeletedLearned.filterKeys { it in deleted } else deleted.keys.mapNotNull { key ->
                (library.syncDeletedLearned[key] ?: context.now.takeIf { key !in library.syncDeleted && it > 0 })?.let { key to it }
            }.toMap(),
            syncReplaced = if (isMain) context.merged.replaced else library.syncReplaced,
        ).let { kept -> context.removedIds.fold(kept) { result, id -> result.removingPlaylist(id, context.merged.deleted[SyncKeys.playlist(id)] ?: 0) } }
            .let { result ->
                // The categories hidden in a replaced playlist stay hidden in its replacement, as on Apple devices.
                val carried = context.replacements.flatMap { (old, into) ->
                    library.hiddenCategories.filter { it.startsWith("$old|") }.map { into + it.substring(old.length) }
                }
                if (carried.isEmpty()) result else result.copy(hiddenCategories = result.hiddenCategories + carried)
            }
        // A removed playlist leaves its deletion in another profile's records only through the snapshot's own.
        return if (isMain) applied else applied.copy(syncDeleted = applied.syncDeleted.filterKeys { !it.startsWith("playlist:") })
    }

    /** The people of a snapshot as this app keeps them: one per name (whatever its case), in the order they come. */
    private fun people(synced: List<SyncPerson>): List<Favorite> =
        synced.filter { it.name.isNotBlank() }.distinctBy { it.key }.map(SyncKeys::personFavorite)

    /** Replacements followed at most, as the merge follows them (docs/SYNC.md, "Merge"). */
    private const val MAX_REPLACEMENTS = 10
}
