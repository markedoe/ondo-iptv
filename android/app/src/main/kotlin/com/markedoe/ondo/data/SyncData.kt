package com.markedoe.ondo.data

import com.markedoe.ondo.core.model.Profile
import com.markedoe.ondo.core.sync.SyncFavorite
import com.markedoe.ondo.core.sync.SyncHidden
import com.markedoe.ondo.core.sync.SyncHousehold
import com.markedoe.ondo.core.sync.SyncKey
import com.markedoe.ondo.core.sync.SyncList
import com.markedoe.ondo.core.sync.SyncListItem
import com.markedoe.ondo.core.sync.SyncPerson
import com.markedoe.ondo.core.sync.SyncPlaylist
import com.markedoe.ondo.core.sync.SyncProfile
import com.markedoe.ondo.core.sync.SyncProfileData
import com.markedoe.ondo.core.sync.SyncProfiles
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

    /** A hidden category, tag or word (docs/SYNC.md, "Hidden categories"): `hide:category:<playlist>|<category>`, `hide:tag:<tag>`, `hide:word:<word>`. */
    fun hiddenCategory(playlistId: String, categoryId: String) = SyncHidden.category(playlistId, categoryId, 0).key
    fun hiddenTag(tag: String) = SyncHidden(SyncHidden.TAG, tag, 0).key
    fun hiddenWord(word: String) = SyncHidden(SyncHidden.WORD, word, 0).key

    /** The sync key of a hidden category as the library keeps it (`<playlist>|<category>`); null when it is not one. */
    fun hiddenCategoryOf(local: String): String? = local.indexOf('|').takeIf { it > 0 }?.let { hiddenCategory(local.substring(0, it), local.substring(it + 1)) }

    /** The record of [key] (`hide:<kind>:<value>`) when its kind is one this version does not know: kept to pass it on. */
    fun otherHidden(key: String, time: Long): SyncHidden? {
        if (!key.startsWith(SyncHidden.KEY_PREFIX)) return null
        val colon = key.indexOf(':', SyncHidden.KEY_PREFIX.length).takeIf { it > 0 } ?: return null
        val kind = key.substring(SyncHidden.KEY_PREFIX.length, colon)
        return if (kind in KNOWN_HIDDEN) null else SyncHidden(kind, key.substring(colon + 1), time)
    }

    private val KNOWN_HIDDEN = setOf(SyncHidden.CATEGORY, SyncHidden.TAG, SyncHidden.WORD)

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
        state.library.hiddenAt,
        state.household.collections,
        state.household.syncDeleted,
        state.settings.serviceKeys,
        state.profiles.map { it.copy(pinHash = null) },
    )

    /** Each profile's library: [others] (as saved) with the open profile's from [state]. */
    fun allLibraries(state: OndoState, others: Map<String, LibraryState>): Map<String, LibraryState> =
        others + (state.activeProfile.id to state.library)

    private fun main(state: OndoState, libraries: Map<String, LibraryState>): LibraryState =
        libraries[Profile.DEFAULT_ID] ?: if (state.activeProfile.isMain) state.library else LibraryState()

    /**
     * This device's snapshot, without the deletions [SyncSnapshot.pruned] forgets at [now] (this device's sync clock,
     * [com.markedoe.ondo.core.sync.SyncClock.now]). Its times are those stored: never moved.
     */
    fun snapshot(state: OndoState, device: String, deviceName: String?, now: Long, libraries: Map<String, LibraryState> = allLibraries(state, emptyMap())): SyncSnapshot {
        val main = main(state, libraries)
        // Each profile's late-learned deletions are kept from when they were learned, as the main profile's are.
        val profileLearned = state.profiles.filter { !it.isMain }.mapNotNull { profile ->
            libraries[profile.id]?.syncDeletedLearned?.takeIf { it.isNotEmpty() }?.let { profile.id.lowercase() to it }
        }.toMap()
        return records(state, libraries, device, deviceName, now)
            .pruned(
                now, sessions = main.syncPeers.values, learned = main.syncDeletedLearned, profileLearned = profileLearned,
                householdLearned = state.household.syncDeletedLearned,
            )
    }

    /** The latest time of what this device holds for sync (its records and deletions, every profile's); null without any. */
    fun latestTime(state: OndoState, libraries: Map<String, LibraryState> = allLibraries(state, emptyMap())): Long? =
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
            // The collections shared with every profile (docs/SYNC.md, "Shared lists"); none: no household, as before.
            household = SyncHousehold.of(libraryRecords(state.household, ::travels)).takeIf { !it.isEmpty },
            replaced = main.syncReplaced,
            clock = now,
        )
    }

    /**
     * A profile's library as records: its My List, favourite channels, progress, lists, people, rows, hidden categories
     * and deletions.
     */
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
                placedAt = list.placedAt.takeIf { it > 0 },
            )
        },
        people = library.favorites.filter(SyncKeys::isPerson).map(SyncKeys::syncPerson),
        rows = library.rowOrders,
        hiddenCategories = library.hiddenRecords(travels),
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
        /** The collections shared with every profile, and what was deleted among them. */
        val household: LibraryState = LibraryState(),
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
    fun apply(state: OndoState, merged: SyncSnapshot, now: Long = 0, libraries: Map<String, LibraryState> = allLibraries(state, emptyMap())): Applied {
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

        // A Kids profile on a device where a parent set a PIN keeps what it hides: a showing again from another device (where
        // no PIN guards it) does not apply here, as the Kids switch does not (docs/PROFILES.md, "Kids").
        val parentProtected = state.profiles.any { !it.isKids && it.isProtected }
        val mainKids = state.profiles.firstOrNull { it.isMain }?.isKids == true
        val mainLibrary = applyLibrary(main(state, libraries), merged, context, isMain = true, keepsHidden = mainKids && parentProtected)

        // The profiles: the merged ones, each keeping its PIN; one deleted elsewhere goes (never the main one).
        val mergedProfiles = merged.profiles.associateBy { it.id.lowercase() }
        val removedProfiles = state.profiles.filter { profile ->
            !profile.isMain && profile.id.lowercase() !in mergedProfiles &&
                (merged.deleted[SyncProfile.KEY_PREFIX + profile.id.lowercase()]?.let { it >= profile.updatedAt } == true)
        }
        val localById = state.profiles.associateBy { it.id.lowercase() }
        // Kids lifted elsewhere stays on where a parent set a PIN (as that PIN stays here): docs/PROFILES.md, "Kids".
        val profiles = (state.profiles - removedProfiles.toSet()).map { profile ->
            val synced = mergedProfiles[profile.id.lowercase()]?.takeIf { it.updatedAt >= profile.updatedAt } ?: return@map profile
            profile.copy(
                name = synced.name, colorIndex = synced.colorIndex, symbol = synced.symbol, updatedAt = synced.updatedAt,
                isKids = SyncProfiles.keptKids(profile.isKids, synced.kids, parentProtected),
            )
        } + merged.profiles.filter { it.id.lowercase() !in localById && it.id != SyncProfile.MAIN }.map { synced ->
            Profile(id = synced.id, name = synced.name, colorIndex = synced.colorIndex, symbol = synced.symbol, isKids = synced.kids, updatedAt = synced.updatedAt)
        }
        // Each other profile's data: its bucket, by the same rules. A profile not in the merge keeps its library as it is.
        val buckets = merged.profileData.associateBy { it.profile }
        val mergedPlaylistDeletions = merged.deleted.filterKeys { it.startsWith("playlist:") }
        val others = profiles.filter { !it.isMain && it.id.lowercase() in mergedProfiles }.associate { profile ->
            val bucket = buckets[profile.id.lowercase()] ?: SyncProfileData(profile.id.lowercase())
            val records = bucket.records(deletedPlaylists = mergedPlaylistDeletions, replaced = merged.replaced)
            profile.id to applyLibrary(libraries[profile.id] ?: LibraryState(), records, context, isMain = false, keepsHidden = profile.isKids && parentProtected)
        }
        // The collections shared with every profile: the household's records, by the same rules as another profile's.
        val householdRecords = (merged.household ?: SyncHousehold()).records(deletedPlaylists = mergedPlaylistDeletions, replaced = merged.replaced)
        val household = applyLibrary(state.household, householdRecords, context, isMain = false)
        return Applied(
            playlists, removed, mainLibrary, replacements.toMap(), merged.keys,
            profiles = profiles, libraries = others + (Profile.DEFAULT_ID to mainLibrary), removedProfiles = removedProfiles,
            household = household,
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
    private fun applyLibrary(library: LibraryState, records: SyncSnapshot, context: Context, isMain: Boolean, keepsHidden: Boolean = false): LibraryState {
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
                placedAt = synced.placedAt ?: 0,
            )
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, UserCollection::name).thenBy { it.id })
        // Another profile's deletions are its own (its bucket's); the main one's are the snapshot's, with the sessions.
        val deleted = if (isMain) records.deleted else records.deleted.filterKeys { !it.startsWith("playlist:") }
        val hidden = hidden(library, records, local, keepsHidden)
        val applied = library.copy(
            favorites = favorites,
            history = history.sortedByDescending { it.lastWatchedAt }.take(LibraryState.MAX_HISTORY),
            collections = collections,
            rowOrders = records.rows,
            hiddenCategories = hidden.hiddenCategories,
            hiddenTags = hidden.hiddenTags,
            hiddenWords = hidden.hiddenWords,
            hiddenAt = hidden.hiddenAt,
            syncDeleted = deleted,
            // When this device learned each deletion made elsewhere, in every profile (kept from then, docs/SYNC.md).
            syncDeletedLearned = deleted.keys.mapNotNull { key ->
                (library.syncDeletedLearned[key] ?: context.now.takeIf { key !in library.syncDeleted && it > 0 })?.let { key to it }
            }.toMap(),
            syncReplaced = if (isMain) context.merged.replaced else library.syncReplaced,
        ).let { kept -> context.removedIds.fold(kept) { result, id -> result.removingPlaylist(id, context.merged.deleted[SyncKeys.playlist(id)] ?: 0) } }
            .let { result ->
                // The categories hidden in a replaced playlist stay hidden in its replacement, as on Apple devices, with their time.
                val carried = context.replacements.flatMap { (old, into) ->
                    library.hiddenCategories.filter { it.startsWith("$old|") }.map { into + it.substring(old.length) to library.hiddenAt[SyncKeys.hiddenCategoryOf(it)] }
                }
                if (carried.isEmpty()) {
                    result
                } else {
                    result.copy(
                        hiddenCategories = result.hiddenCategories + carried.map { it.first },
                        hiddenAt = result.hiddenAt + carried.mapNotNull { (key, time) ->
                            val syncKey = SyncKeys.hiddenCategoryOf(key) ?: return@mapNotNull null
                            time?.takeIf { it > (result.hiddenAt[syncKey] ?: Long.MIN_VALUE) }?.let { syncKey to it }
                        },
                    )
                }
            }
        // A removed playlist leaves its deletion in another profile's records only through the snapshot's own.
        return if (isMain) applied else applied.copy(syncDeleted = applied.syncDeleted.filterKeys { !it.startsWith("playlist:") })
    }

    /**
     * The hidden categories, tags and words of [library] after the merge (docs/SYNC.md, "Hidden categories"): those of
     * [records] (the merge holds this device's own), and this device's own the merge did not carry (past its cap, or of a
     * file playlist) unless a showing again since removed them. Records of kinds this version does not know are kept in
     * `hiddenAt` to pass them on. [keepsHidden] (a Kids profile on a device where a parent set a PIN): this device's own
     * stay, whatever was shown again elsewhere, with their own times (the merge keeps the later showing again, and nothing
     * changes from one session to the next); a hide received still applies.
     */
    private fun hidden(library: LibraryState, records: SyncSnapshot, local: (String) -> String, keepsHidden: Boolean): LibraryState {
        fun kept(key: String?) = keepsHidden || key == null || SyncHidden.shownAt(key, records.deleted)?.let { it >= (library.hiddenAt[key] ?: 0) } != true
        val times = HashMap<String, Long>()
        val categories = library.hiddenCategories.filterTo(HashSet()) { kept(SyncKeys.hiddenCategoryOf(it)) }
        val tags = library.hiddenTags.filterTo(HashSet()) { kept(SyncKeys.hiddenTag(it)) }
        val words = library.hiddenWords.filterTo(HashSet()) { kept(SyncKeys.hiddenWord(it)) }
        (categories.mapNotNull(SyncKeys::hiddenCategoryOf) + tags.map(SyncKeys::hiddenTag) + words.map(SyncKeys::hiddenWord))
            .forEach { key -> library.hiddenAt[key]?.let { times[key] = it } }
        library.hiddenAt.forEach { (key, time) -> if (SyncKeys.otherHidden(key, time) != null && kept(key)) times[key] = time }
        for (record in records.hiddenCategories) {
            when (record.kind) {
                SyncHidden.CATEGORY -> {
                    val bar = record.value.indexOf('|').takeIf { it > 0 } ?: continue
                    val playlist = local(record.value.substring(0, bar))
                    categories += playlist + record.value.substring(bar)
                    times.merge(SyncKeys.hiddenCategory(playlist, record.value.substring(bar + 1)), record.hiddenAt, ::maxOf)
                }
                SyncHidden.TAG -> {
                    tags += record.value
                    times.merge(record.key, record.hiddenAt, ::maxOf)
                }
                SyncHidden.WORD -> {
                    words += record.value
                    times.merge(record.key, record.hiddenAt, ::maxOf)
                }
                else -> times.merge(record.key, record.hiddenAt, ::maxOf)
            }
        }
        return LibraryState(hiddenCategories = categories, hiddenTags = tags, hiddenWords = words, hiddenAt = times)
    }

    /** The people of a snapshot as this app keeps them: one per name (whatever its case), in the order they come. */
    private fun people(synced: List<SyncPerson>): List<Favorite> =
        synced.filter { it.name.isNotBlank() }.distinctBy { it.key }.map(SyncKeys::personFavorite)

    /** Replacements followed at most, as the merge follows them (docs/SYNC.md, "Merge"). */
    private const val MAX_REPLACEMENTS = 10
}
