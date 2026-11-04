package com.markedoe.ondo.data

import com.markedoe.ondo.core.model.HistoryEntry
import com.markedoe.ondo.core.sync.SyncHidden
import com.markedoe.ondo.core.sync.SyncList
import com.markedoe.ondo.core.sync.SyncRows
import kotlinx.serialization.Serializable

/** Kind of item in the library; a person (actor or director) is kept by name, whatever the playlist. */
@Serializable
enum class ItemKind { CHANNEL, MOVIE, SERIES, PERSON }

/** A favourite (channel, movie or series) of one playlist; movies and series form "My List". */
@Serializable
data class Favorite(
    val playlistId: String,
    val kind: ItemKind,
    val itemId: String,
    val title: String,
    val imageUrl: String? = null,
    val addedAt: Long,
) {
    val key: String get() = "$playlistId|$kind|$itemId"

    companion object {
        /** The playlist of the people kept: none, a person is kept whatever the playlist. */
        const val ANY_PLAYLIST = ""

        /** An actor or a director kept by [name] (with their photo, TMDb's, when known). */
        fun person(name: String, photoUrl: String?, now: Long) = Favorite(ANY_PLAYLIST, ItemKind.PERSON, name.trim(), name.trim(), photoUrl, now)
    }
}

/** Something watched: position and duration in seconds (episodes are keyed per episode). */
@Serializable
data class WatchEntry(
    val playlistId: String,
    val kind: ItemKind,
    val itemId: String,
    val episodeId: String? = null,
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val lastWatchedAt: Long,
    /** Where the file's end credits start (seconds), from its chapters; null when not known. Not synced. */
    val creditsStart: Double? = null,
) {
    /** Identifies the exact playable thing (episode level for series). */
    val key: String get() = "$playlistId|$kind|$itemId|${episodeId.orEmpty()}"
    val progress: Double get() = if (duration > 0) (position / duration).coerceIn(0.0, 1.0) else 0.0
    /** Watched, by the shared rule of [HistoryEntry.isFinished]: movies and episodes alike. */
    val isFinished: Boolean get() = kind != ItemKind.CHANNEL && HistoryEntry.isFinished(position, duration, creditsStart)
    val isResumable: Boolean get() = kind != ItemKind.CHANNEL && position > 30 && !isFinished
    val minutesLeft: Int get() = (((duration - position).coerceAtLeast(0.0)) / 60).toInt()
}

/** A programme the viewer wants to be told about, a few minutes before it starts (times in milliseconds). */
@Serializable
data class Reminder(
    val playlistId: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val start: Long,
    val end: Long,
) {
    val key: String get() = "$playlistId|$channelId|$start"
}

/** A collection the viewer made: movies, series and channels kept together under a name, as on Apple. */
@Serializable
data class UserCollection(
    val id: String,
    val name: String,
    val items: List<Favorite> = emptyList(),
    val updatedAt: Long = 0,
    /** When the name was set (sync merges it on its own); 0 when not known (an older version): [updatedAt] then. */
    val renamedAt: Long = 0,
    /** When the items were put in this order (sync); 0 when not known: [updatedAt] then. */
    val orderedAt: Long = 0,
    /**
     * When it was moved into the space it is in, shared with every profile or taken back into a profile (docs/SYNC.md,
     * "Shared lists"); 0 for a collection never moved.
     */
    val placedAt: Long = 0,
) {
    fun contains(playlistId: String, kind: ItemKind, itemId: String): Boolean =
        items.any { it.playlistId == playlistId && it.kind == kind && it.itemId == itemId }
}

/**
 * Favourites, watch history, collections and hidden categories, persisted as one JSON document. Pure: every change
 * returns a new value.
 */
@Serializable
data class LibraryState(
    val favorites: List<Favorite> = emptyList(),
    val history: List<WatchEntry> = emptyList(),
    /** `playlistId|categoryId` hidden by the user. */
    val hiddenCategories: Set<String> = emptySet(),
    /** Language or country tags ("PT", see `CategoryTags`) hidden in every playlist. */
    val hiddenTags: Set<String> = emptySet(),
    /** Styles and words ("manga", "bollywood", see `CategoryStyles`) whose categories are hidden in every playlist. */
    val hiddenWords: Set<String> = emptySet(),
    /** By name. */
    val collections: List<UserCollection> = emptyList(),
    /** Soonest first. */
    val reminders: List<Reminder> = emptyList(),
    /** Sync on the local network (docs/SYNC.md): what was removed here or elsewhere, by sync key, and when. */
    val syncDeleted: Map<String, Long> = emptyMap(),
    /** Sync: playlist IDs that became another playlist (the same one added on two devices). */
    val syncReplaced: Map<String, String> = emptyMap(),
    /**
     * Sync: the last session with each device, by device ID (kept to the day). Deletions stay until each device seen in
     * the last year has had a session after them.
     */
    val syncPeers: Map<String, Long> = emptyMap(),
    /** Sync: when this device learned a deletion made elsewhere, by key (its own are learned when made). */
    val syncDeletedLearned: Map<String, Long> = emptyMap(),
    /**
     * The order of the rows of the Movies and Series tabs and the rows hidden (Settings → Row order, `RowOrder`), one
     * record per tab with the time it was set: this profile's (docs/PROFILES.md); sync carries them (docs/SYNC.md, "Rows").
     */
    val rowOrders: List<SyncRows> = emptyList(),
    /**
     * Sync (docs/SYNC.md, "Hidden categories"): when each hidden category, tag and word was hidden, here or on another
     * device, by its sync key (`hide:…`); showing one again remembers its key in [syncDeleted]. Also the hidden records of
     * kinds this version does not know, kept to pass them on. Empty before hidden categories synced: the launch dates them.
     */
    val hiddenAt: Map<String, Long> = emptyMap(),
) {
    /** The order saved for the Movies or the Series tab; null: the default order. */
    fun rowOrder(series: Boolean): SyncRows? = rowOrders.firstOrNull { it.tab == (if (series) SyncRows.SERIES else SyncRows.MOVIES) }

    /** [record] kept for its tab, the records sorted by tab as a merge gives them. */
    fun keepingRowOrder(record: SyncRows): LibraryState = copy(rowOrders = (rowOrders.filterNot { it.tab == record.tab } + record).sortedBy { it.tab })

    fun isCategoryHidden(playlistId: String, categoryId: String): Boolean = "$playlistId|$categoryId" in hiddenCategories

    fun settingCategoryHidden(playlistId: String, categoryId: String, hidden: Boolean): LibraryState =
        settingCategoriesHidden(playlistId, listOf(categoryId), hidden)

    /**
     * Hides or shows categories of [playlistId], dated [now] for sync (a showing again is remembered as a deletion). Several
     * shown again at once (Show all) leave one marker for each type of category instead of one deletion each
     * (`hide:category:<playlist>|<type>-*`, docs/SYNC.md, "Hidden categories"); the categories of those types still hidden
     * are dated [after], so that the marker leaves them hidden everywhere.
     */
    fun settingCategoriesHidden(playlistId: String, categoryIds: Collection<String>, hidden: Boolean, now: Long = 0, after: Long = now + 1): LibraryState {
        val keys = categoryIds.associateBy({ "$playlistId|$it" }, { SyncKeys.hiddenCategory(playlistId, it) })
        if (hidden) return copy(hiddenCategories = hiddenCategories + keys.keys, hiddenAt = hiddenAt + keys.values.associateWith { now })
        val shown = keys.filterKeys { it in hiddenCategories }
        val markers = if (shown.size > 1) shown.values.mapNotNullTo(HashSet()) { SyncHidden.showAllKey(it) } else emptySet()
        val remaining = hiddenCategories - keys.keys
        val stillHidden = remaining.mapNotNull { SyncKeys.hiddenCategoryOf(it) }.filter { SyncHidden.showAllKey(it) in markers }
        return copy(
            hiddenCategories = remaining,
            hiddenAt = hiddenAt - shown.values.toSet() + stillHidden.associateWith { after },
        ).deletingAll(shown.values.filter { SyncHidden.showAllKey(it) !in markers } + markers, now)
    }

    fun settingTagsHidden(tags: Collection<String>, hidden: Boolean, now: Long = 0): LibraryState {
        val keys = tags.associateWith(SyncKeys::hiddenTag)
        return if (hidden) {
            copy(hiddenTags = hiddenTags + tags, hiddenAt = hiddenAt + keys.values.associateWith { now })
        } else {
            val shown = keys.filterKeys { it in hiddenTags }
            copy(hiddenTags = hiddenTags - tags.toSet(), hiddenAt = hiddenAt - shown.values.toSet()).deletingAll(shown.values, now)
        }
    }

    fun settingWordHidden(word: String, hidden: Boolean, now: Long = 0): LibraryState {
        val key = SyncKeys.hiddenWord(word)
        return when {
            hidden -> copy(hiddenWords = hiddenWords + word, hiddenAt = hiddenAt + (key to now))
            word in hiddenWords -> copy(hiddenWords = hiddenWords - word, hiddenAt = hiddenAt - key).deleting(key, now)
            else -> this
        }
    }

    /**
     * Every hidden category, tag and word without a time gets [time] (docs/PROFILES.md, "Migration"): the hidden sets kept
     * before they synced travel, dated after everything the device held. The same state when none lacks a time.
     */
    fun datingHidden(time: Long): LibraryState {
        val undated = undatedHidden()
        return if (undated.isEmpty()) this else copy(hiddenAt = hiddenAt + undated.associateWith { time })
    }

    /** Whether a hidden category, tag or word has no time yet ([datingHidden]). */
    fun hasUndatedHidden(): Boolean = undatedHidden().isNotEmpty()

    private fun undatedHidden(): List<String> =
        (hiddenCategories.mapNotNull(SyncKeys::hiddenCategoryOf) + hiddenTags.map(SyncKeys::hiddenTag) + hiddenWords.map(SyncKeys::hiddenWord))
            .filter { it !in hiddenAt }

    /**
     * What this profile hides, as sync records: its categories (those of the playlists [travels] lets travel), tags and
     * words, each with its time (0 when it has none), and the records of other kinds kept to pass them on.
     */
    fun hiddenRecords(travels: (String) -> Boolean = { true }): List<SyncHidden> {
        val categories = hiddenCategories.mapNotNull { local ->
            val bar = local.indexOf('|').takeIf { it > 0 } ?: return@mapNotNull null
            val playlist = local.substring(0, bar)
            if (!travels(playlist)) return@mapNotNull null
            SyncHidden.category(playlist, local.substring(bar + 1), 0).let { it.copy(hiddenAt = hiddenAt[it.key] ?: 0) }
        }
        val tags = hiddenTags.map { SyncHidden(SyncHidden.TAG, it, hiddenAt[SyncKeys.hiddenTag(it)] ?: 0) }
        val words = hiddenWords.map { SyncHidden(SyncHidden.WORD, it, hiddenAt[SyncKeys.hiddenWord(it)] ?: 0) }
        val others = hiddenAt.mapNotNull { (key, time) -> SyncKeys.otherHidden(key, time) }
        return (categories + tags + words + others).distinctBy { it.key }.sortedBy { it.key }
    }

    fun isFavorite(playlistId: String, kind: ItemKind, itemId: String): Boolean =
        favorites.any { it.playlistId == playlistId && it.kind == kind && it.itemId == itemId }

    /** Adds [favorite], or takes it away (remembered for sync at its [Favorite.addedAt], the time of the toggle). */
    fun toggleFavorite(favorite: Favorite): LibraryState =
        if (favorites.any { it.key == favorite.key }) {
            copy(favorites = favorites.filterNot { it.key == favorite.key })
                .deleting(SyncKeys.favorite(favorite), favorite.addedAt)
        } else {
            copy(favorites = favorites + favorite)
        }

    fun favorites(playlistId: String, kind: ItemKind): List<Favorite> =
        favorites.filter { it.playlistId == playlistId && it.kind == kind }

    /** Starts (or restarts) an entry; keeps the known position. */
    fun recordStart(entry: WatchEntry): LibraryState {
        val existing = history.firstOrNull { it.key == entry.key }
        val updated = entry.copy(
            position = existing?.position ?: entry.position,
            duration = existing?.duration?.takeIf { it > 0 } ?: entry.duration,
            // The file's chapters stay the same: keep where its end credits start.
            creditsStart = entry.creditsStart ?: existing?.creditsStart,
        )
        return copy(history = listOf(updated) + history.filterNot { it.key == entry.key }).trimmed()
    }

    /** [creditsStart]: where the end credits start, when the player found them in the file's chapters; null keeps the one known. */
    fun updateProgress(key: String, position: Double, duration: Double, now: Long, creditsStart: Double? = null): LibraryState =
        copy(
            history = history.map {
                if (it.key != key) return@map it
                it.copy(
                    position = position.coerceAtLeast(0.0),
                    duration = if (duration > 0) duration else it.duration,
                    lastWatchedAt = now,
                    creditsStart = creditsStart?.takeIf { start -> start.isFinite() && start > 0 } ?: it.creditsStart,
                )
            },
        )

    fun progress(key: String): WatchEntry? = history.firstOrNull { it.key == key }

    /** The movies of [playlistId] watched to the end. */
    fun finishedMovies(playlistId: String): Set<String> =
        history.filter { it.playlistId == playlistId && it.kind == ItemKind.MOVIE && it.isFinished }.mapTo(HashSet()) { it.itemId }

    fun remove(entry: WatchEntry, now: Long = entry.lastWatchedAt): LibraryState =
        copy(history = history.filterNot { it.key == entry.key }).let { if (SyncKeys.syncs(entry)) it.deleting(SyncKeys.progress(entry), now) else it }

    /**
     * Takes a card out of Continue watching: the movie, or the series with its other episodes in progress (the one
     * before would take the card's place). Episodes watched to the end stay watched.
     */
    fun removingFromContinueWatching(entry: WatchEntry, now: Long): LibraryState =
        history
            .filter { it.key == entry.key || (entry.kind == ItemKind.SERIES && it.playlistId == entry.playlistId && it.kind == entry.kind && it.itemId == entry.itemId && it.isResumable) }
            .fold(this) { library, gone -> library.remove(gone, now) }

    /** Movies and episodes in progress, most recent first, one entry per series. */
    fun continueWatching(playlistId: String, limit: Int = 20): List<WatchEntry> {
        val seen = mutableSetOf<String>()
        return history
            .asSequence()
            .filter { it.playlistId == playlistId && it.isResumable }
            .sortedByDescending { it.lastWatchedAt }
            .filter { seen.add("${it.kind}|${it.itemId}") }
            .take(limit)
            .toList()
    }

    /** Channels watched recently, most recent first. */
    fun recentChannels(playlistId: String, limit: Int = 20): List<WatchEntry> =
        history.filter { it.playlistId == playlistId && it.kind == ItemKind.CHANNEL }.sortedByDescending { it.lastWatchedAt }.take(limit)

    /**
     * A person kept without a photo (none was known yet) gets [photoUrl], in My List and the collections; the same
     * state when nothing changes.
     */
    fun withPersonPhoto(name: String, photoUrl: String): LibraryState {
        fun Favorite.filled() = if (kind == ItemKind.PERSON && itemId == name && imageUrl == null) copy(imageUrl = photoUrl) else this
        val favorites = favorites.map { it.filled() }
        val collections = collections.map { list -> list.items.map { it.filled() }.let { items -> if (items == list.items) list else list.copy(items = items) } }
        return if (favorites == this.favorites && collections == this.collections) this else copy(favorites = favorites, collections = collections)
    }

    /** Everything of a playlist taken away; for sync, the playlist is remembered as deleted at [now]. */
    fun removingPlaylist(playlistId: String, now: Long = 0): LibraryState = deleting(SyncKeys.playlist(playlistId), now).copy(
        favorites = favorites.filterNot { it.playlistId == playlistId },
        history = history.filterNot { it.playlistId == playlistId },
        hiddenCategories = hiddenCategories.filterNot { it.startsWith("$playlistId|") }.toSet(),
        hiddenAt = hiddenAt.filterKeys { !it.startsWith(SyncHidden.CATEGORY_KEY_PREFIX + playlistId.lowercase() + "|") },
        collections = collections.map { list -> list.copy(items = list.items.filterNot { it.playlistId == playlistId }) },
        reminders = reminders.filterNot { it.playlistId == playlistId },
    )

    // Reminders

    /** Sets the reminder, or takes it away if it is set; programmes that ended are forgotten. */
    fun togglingReminder(reminder: Reminder, now: Long): LibraryState {
        val others = reminders.filter { it.key != reminder.key && it.end > now }
        val toggled = if (reminders.any { it.key == reminder.key }) others else others + reminder
        return copy(reminders = toggled.sortedBy { it.start })
    }

    fun upcomingReminders(playlistId: String, now: Long): List<Reminder> = reminders.filter { it.playlistId == playlistId && it.end > now }

    // Collections

    fun creatingCollection(id: String, name: String, now: Long): LibraryState =
        copy(collections = sortedByName(collections + UserCollection(id, name.trim(), updatedAt = now, renamedAt = now, orderedAt = now)))

    fun renamingCollection(id: String, name: String, now: Long): LibraryState =
        copy(collections = sortedByName(collections.map { if (it.id == id) it.copy(name = name.trim(), updatedAt = now, renamedAt = now) else it }))

    fun deletingCollection(id: String, now: Long = 0): LibraryState =
        copy(collections = collections.filterNot { it.id == id }).deleting(SyncKeys.list(id), now)

    /** Remembers for sync that [key] was removed at [time] (the later time is kept). */
    fun deleting(key: String, time: Long): LibraryState =
        if ((syncDeleted[key] ?: Long.MIN_VALUE) >= time) this else copy(syncDeleted = syncDeleted + (key to time))

    private fun deletingAll(keys: Collection<String>, time: Long): LibraryState = keys.fold(this) { library, key -> library.deleting(key, time) }

    /** Adds the item to the collection, or takes it out if it is there (remembered for sync at [now]). */
    fun togglingInCollection(id: String, item: Favorite, now: Long): LibraryState {
        val removed = collections.any { list -> list.id == id && list.items.any { it.key == item.key } }
        val toggled = copy(
            collections = collections.map { list ->
                when {
                    list.id != id -> list
                    removed -> list.copy(items = list.items.filterNot { it.key == item.key }, updatedAt = now)
                    else -> list.copy(items = list.items + item, updatedAt = now)
                }
            },
        )
        return if (removed) toggled.deleting(SyncKeys.listItem(id, item), now) else toggled
    }

    private fun sortedByName(lists: List<UserCollection>) = lists.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, UserCollection::name).thenBy { it.id })

    private fun trimmed(): LibraryState = if (history.size <= MAX_HISTORY) this else copy(history = history.sortedByDescending { it.lastWatchedAt }.take(MAX_HISTORY))

    companion object {
        const val MAX_HISTORY = 500
    }
}

/**
 * Collections shared with every profile (docs/PROFILES.md, "Shared lists"): the household's library holds them, apart from
 * every profile's. A move takes a collection whole, under the same ID, from one library to the other: the one it enters
 * has it at a later time (its `updatedAt` and `placedAt`), and the deletions of its items go with it, as `SyncSharing`
 * does to a snapshot. The move out of the one it leaves is no deletion: the repository notes it with the main profile's
 * deletions (`SyncSharing.movedKey`), which an older device passes on without applying.
 */
object SharedCollections {
    /** The deletions of the items of collection [id] in [library] (`listitem:<id>|…`). */
    private fun itemDeletions(library: LibraryState, id: String): Map<String, Long> =
        library.syncDeleted.filterKeys { it.startsWith(SyncList.ITEM_PREFIX + id.lowercase() + "|") }

    /**
     * Collection [id] moved from [from] into [into], which has it from [placedAt]. Null when [from] has no such collection.
     */
    fun move(from: LibraryState, into: LibraryState, id: String, placedAt: Long): Pair<LibraryState, LibraryState>? {
        val list = from.collections.firstOrNull { it.id == id } ?: return null
        val left = from.copy(collections = from.collections.filterNot { it.id == id })
        val moved = list.copy(updatedAt = maxOf(list.updatedAt, placedAt), placedAt = placedAt)
        val entered = itemDeletions(from, id).entries.fold(into.copy(collections = sorted(into.collections.filterNot { it.id == id } + moved))) { library, (key, time) ->
            library.deleting(key, time)
        }
        return left to entered
    }

    private fun sorted(lists: List<UserCollection>) = lists.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, UserCollection::name).thenBy { it.id })
}
