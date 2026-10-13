package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.CustomList
import com.markedoe.ondo.core.model.FavoriteEntry
import com.markedoe.ondo.core.model.HistoryEntry
import com.markedoe.ondo.core.model.MediaReference
import com.markedoe.ondo.core.model.PlaybackItem
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.ProgramReminder
import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * Favourites, history, resume positions, custom lists, reminders and per-category / per-channel preferences.
 *
 * Immutable: every change returns a new library. Removals leave dated tombstones so that [merged] (sync between
 * devices) is deterministic and both sides converge. All times are epoch millis.
 */
@Serializable
data class UserLibrary(
    val favorites: List<FavoriteEntry> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    /** `playlistId|categoryId`. */
    val hiddenCategoryKeys: Set<String> = emptySet(),
    /** `playlistId|categoryId` locked behind the parental PIN. */
    val lockedCategoryKeys: Set<String> = emptySet(),
    val customLists: List<CustomList> = emptyList(),
    /** Deletion markers so removals propagate when merging with another device. */
    val favoriteTombstones: Map<String, Long> = emptyMap(),
    val historyTombstones: Map<String, Long> = emptyMap(),
    val historyClearedAt: Long? = null,
    val deletedListIds: Set<String> = emptySet(),
    /** Programmes to be notified about, soonest first, and their deletion markers. */
    val reminders: List<ProgramReminder> = emptyList(),
    val reminderTombstones: Map<String, Long> = emptyMap(),
    /** `playlistId|channelId` -> when it was hidden; unhiding leaves a dated marker for sync. */
    val hiddenChannels: Map<String, Long> = emptyMap(),
    val unhiddenChannels: Map<String, Long> = emptyMap(),
) {
    // Favourites

    fun isFavorite(reference: MediaReference): Boolean = favorites.any { it.reference.itemKey == reference.itemKey }

    /** Adds the item (series level for episodes) at the end of the favourites, or removes it. */
    fun toggleFavorite(
        reference: MediaReference,
        title: String,
        imageUrl: String?,
        now: Long = System.currentTimeMillis(),
    ): UserLibrary {
        val index = favorites.indexOfFirst { it.reference.itemKey == reference.itemKey }
        if (index >= 0) {
            return copy(
                favorites = favorites.filterIndexed { i, _ -> i != index },
                favoriteTombstones = favoriteTombstones + (favorites[index].id to now),
            )
        }
        val nextIndex = (favorites.maxOfOrNull { it.sortIndex } ?: -1) + 1
        val entry = FavoriteEntry(reference.copy(episodeId = null), title, imageUrl, addedAt = now, sortIndex = nextIndex)
        return copy(favorites = favorites + entry, favoriteTombstones = favoriteTombstones - reference.itemKey)
    }

    /** Favourites of a playlist (optionally of one type), in the user's order. */
    fun favorites(playlistId: String, type: ContentType? = null): List<FavoriteEntry> =
        favorites
            .filter { it.reference.playlistId == playlistId && (type == null || it.reference.type == type) }
            .sortedBy { it.sortIndex }

    /** Moves favourites like a list drag & drop (`fromIndices` / `toIndex` are positions in [favorites] of that filter). */
    fun moveFavorites(playlistId: String, type: ContentType?, fromIndices: Collection<Int>, toIndex: Int): UserLibrary {
        val ordered = favorites(playlistId, type).toMutableList()
        val reordered = move(ordered, fromIndices, toIndex)
        val baseIndex = reordered.minOfOrNull { it.sortIndex } ?: 0
        val newIndexById = HashMap<String, Int>()
        reordered.forEachIndexed { offset, entry -> newIndexById.putIfAbsent(entry.id, baseIndex + offset) }
        return copy(favorites = favorites.map { entry -> newIndexById[entry.id]?.let { entry.copy(sortIndex = it) } ?: entry })
    }

    // History & progress

    /** Puts `entry` first in the history; starting again without a position keeps the known resume point. */
    fun recordPlayback(entry: HistoryEntry): UserLibrary {
        val index = history.indexOfFirst { it.id == entry.id }
        var updated = entry
        if (index >= 0) {
            val existing = history[index]
            if (updated.position == 0.0 && existing.position > 0 && !existing.isFinished) {
                updated = updated.copy(position = existing.position, duration = maxOf(updated.duration, existing.duration))
            }
        }
        val newHistory = ArrayList<HistoryEntry>(history.size + 1)
        newHistory.add(updated)
        history.forEachIndexed { i, item -> if (i != index) newHistory.add(item) }
        return copy(history = if (newHistory.size > MAX_HISTORY) newHistory.subList(0, MAX_HISTORY).toList() else newHistory)
    }

    /** Records that `item` started playing (live channels never keep a position). */
    fun recordStart(item: PlaybackItem, now: Long = System.currentTimeMillis()): UserLibrary = recordPlayback(
        HistoryEntry(
            reference = item.reference,
            title = item.title,
            subtitle = item.subtitle,
            imageUrl = item.artworkUrl,
            lastWatchedAt = now,
            position = if (item.isLive) 0.0 else item.startPosition ?: 0.0,
        ),
    )

    /** Updates the resume position (seconds) of an item already in the history. */
    fun updateProgress(reference: MediaReference, position: Double, duration: Double, now: Long = System.currentTimeMillis()): UserLibrary {
        val index = history.indexOfFirst { it.reference.playbackKey == reference.playbackKey }
        if (index < 0) return this
        val existing = history[index]
        val updated = existing.copy(
            position = maxOf(position, 0.0),
            duration = if (duration.isFinite() && duration > 0) duration else existing.duration,
            lastWatchedAt = now,
        )
        return copy(history = history.toMutableList().also { it[index] = updated })
    }

    fun progress(reference: MediaReference): HistoryEntry? = history.firstOrNull { it.reference.playbackKey == reference.playbackKey }

    /** Movies and episodes in progress, most recent first, one entry per series (its latest episode). */
    fun continueWatching(playlistId: String? = null, limit: Int = 20): List<HistoryEntry> {
        val seenItems = HashSet<String>()
        val result = ArrayList<HistoryEntry>()
        for (entry in history.sortedByDescending { it.lastWatchedAt }) {
            if (entry.reference.type == ContentType.LIVE) continue
            if (playlistId != null && entry.reference.playlistId != playlistId) continue
            if (!seenItems.add(entry.reference.itemKey)) continue
            if (!entry.isResumable) continue
            result.add(entry)
            if (result.size >= limit) break
        }
        return result
    }

    fun recentChannels(playlistId: String, limit: Int = 20): List<HistoryEntry> =
        history
            .filter { it.reference.type == ContentType.LIVE && it.reference.playlistId == playlistId }
            .sortedByDescending { it.lastWatchedAt }
            .take(limit)

    fun lastChannel(playlistId: String): HistoryEntry? = recentChannels(playlistId, limit = 1).firstOrNull()

    fun removeHistory(entry: HistoryEntry, now: Long = System.currentTimeMillis()): UserLibrary =
        copy(history = history.filter { it.id != entry.id }, historyTombstones = historyTombstones + (entry.id to now))

    fun clearHistory(now: Long = System.currentTimeMillis()): UserLibrary =
        copy(history = emptyList(), historyTombstones = emptyMap(), historyClearedAt = now)

    // Categories and channels

    fun isCategoryHidden(playlistId: String, categoryId: String): Boolean = categoryKey(playlistId, categoryId) in hiddenCategoryKeys

    fun setCategoryHidden(hidden: Boolean, playlistId: String, categoryId: String): UserLibrary {
        val key = categoryKey(playlistId, categoryId)
        return copy(hiddenCategoryKeys = if (hidden) hiddenCategoryKeys + key else hiddenCategoryKeys - key)
    }

    fun isChannelHidden(playlistId: String, channelId: String): Boolean = categoryKey(playlistId, channelId) in hiddenChannels

    fun setChannelHidden(hidden: Boolean, playlistId: String, channelId: String, now: Long = System.currentTimeMillis()): UserLibrary {
        val key = categoryKey(playlistId, channelId)
        return when {
            hidden -> copy(hiddenChannels = hiddenChannels + (key to now), unhiddenChannels = unhiddenChannels - key)
            key in hiddenChannels -> copy(hiddenChannels = hiddenChannels - key, unhiddenChannels = unhiddenChannels + (key to now))
            else -> this
        }
    }

    /** Ids of the channels hidden in a playlist. */
    fun hiddenChannelIds(playlistId: String): Set<String> {
        val prefix = "$playlistId|"
        return hiddenChannels.keys.filter { it.startsWith(prefix) }.mapTo(HashSet()) { it.removePrefix(prefix) }
    }

    fun isCategoryLocked(playlistId: String, categoryId: String): Boolean = categoryKey(playlistId, categoryId) in lockedCategoryKeys

    fun setCategoryLocked(locked: Boolean, playlistId: String, categoryId: String): UserLibrary {
        val key = categoryKey(playlistId, categoryId)
        return copy(lockedCategoryKeys = if (locked) lockedCategoryKeys + key else lockedCategoryKeys - key)
    }

    /** Locks every adult-looking category of a freshly loaded playlist. */
    fun lockAdultCategories(playlistId: String, content: PlaylistContent): UserLibrary {
        val adult = (content.liveCategories + content.movieCategories + content.seriesCategories)
            .filter { it.looksAdult }
            .map { categoryKey(playlistId, it.id) }
        return if (adult.isEmpty()) this else copy(lockedCategoryKeys = lockedCategoryKeys + adult)
    }

    // Reminders

    fun hasReminder(id: String): Boolean = reminders.any { it.id == id }

    /** Adds the reminder, or removes it when it already exists. */
    fun toggleReminder(reminder: ProgramReminder, now: Long = System.currentTimeMillis()): UserLibrary {
        if (hasReminder(reminder.id)) return removeReminder(reminder.id, now)
        return copy(
            reminders = (reminders + reminder.copy(createdAt = now)).sortedWith(REMINDER_ORDER),
            reminderTombstones = reminderTombstones - reminder.id,
        )
    }

    fun removeReminder(id: String, now: Long = System.currentTimeMillis()): UserLibrary {
        if (!hasReminder(id)) return this
        return copy(reminders = reminders.filter { it.id != id }, reminderTombstones = reminderTombstones + (id to now))
    }

    /** Reminders of programmes that have not ended yet, soonest first. */
    fun upcomingReminders(at: Long = System.currentTimeMillis()): List<ProgramReminder> = reminders.filter { it.end > at }

    /** Drops reminders of programmes that ended before `instant`, and deletion markers older than two weeks. */
    fun pruneReminders(endedBefore: Long): UserLibrary {
        val stale = endedBefore - 14 * 86_400_000L
        return copy(
            reminders = reminders.filter { it.end >= endedBefore },
            reminderTombstones = reminderTombstones.filterValues { it > stale },
        )
    }

    // Sync

    /**
     * Merges a copy coming from another device: favourites and lists are united, the most recent history
     * entry wins, category preferences are united, tombstones remove older copies. Deterministic, so both
     * sides converge.
     */
    fun merged(other: UserLibrary): UserLibrary {
        val favoriteMarks = maxMerge(favoriteTombstones, other.favoriteTombstones)
        val historyMarks = maxMerge(historyTombstones, other.historyTombstones)
        val clearedAt = listOfNotNull(historyClearedAt, other.historyClearedAt).maxOrNull()

        val favoritesById = LinkedHashMap<String, FavoriteEntry>()
        for (favorite in favorites + other.favorites) favoritesById.putIfAbsent(favorite.id, favorite)
        val mergedFavorites = favoritesById.values
            .filter { favorite -> favoriteMarks[favorite.id]?.let { it < favorite.addedAt } ?: true }
            .sortedWith(compareBy<FavoriteEntry>({ it.sortIndex }, { it.addedAt }, { it.id }))

        val historyById = LinkedHashMap<String, HistoryEntry>()
        for (entry in history) historyById.putIfAbsent(entry.id, entry)
        for (entry in other.history) {
            val existing = historyById[entry.id]
            if (existing != null && existing.lastWatchedAt >= entry.lastWatchedAt) continue
            historyById[entry.id] = entry
        }
        val clearedBefore = clearedAt ?: Long.MIN_VALUE
        val mergedHistory = historyById.values
            .filter { entry ->
                entry.lastWatchedAt > clearedBefore && (historyMarks[entry.id]?.let { it < entry.lastWatchedAt } ?: true)
            }
            .sortedWith(compareByDescending<HistoryEntry> { it.lastWatchedAt }.thenByDescending { it.id })
            .take(MAX_HISTORY)

        val reminderMarks = maxMerge(reminderTombstones, other.reminderTombstones)
        val remindersById = LinkedHashMap<String, ProgramReminder>()
        for (reminder in reminders) remindersById.putIfAbsent(reminder.id, reminder)
        for (reminder in other.reminders) {
            val existing = remindersById[reminder.id]
            if (existing != null && existing.createdAt >= reminder.createdAt) continue
            remindersById[reminder.id] = reminder
        }
        val mergedReminders = remindersById.values
            .filter { reminder -> reminderMarks[reminder.id]?.let { it < reminder.createdAt } ?: true }
            .sortedWith(REMINDER_ORDER)

        val unhidden = maxMerge(unhiddenChannels, other.unhiddenChannels)
        val hidden = maxMerge(hiddenChannels, other.hiddenChannels)
            .filter { (key, hiddenAt) -> unhidden[key]?.let { it < hiddenAt } ?: true }

        val deletedLists = deletedListIds + other.deletedListIds
        val lists = customLists.toMutableList()
        for (list in other.customLists) {
            val index = lists.indexOfFirst { it.id == list.id }
            if (index < 0) {
                lists.add(list)
                continue
            }
            val mine = lists[index]
            if (list.updatedAt > mine.updatedAt || (list.updatedAt == mine.updatedAt && list.name > mine.name)) lists[index] = list
        }
        val mergedLists = lists
            .filter { it.id !in deletedLists }
            .sortedWith(compareBy<CustomList>({ it.name.lowercase() }, { it.id }))

        return UserLibrary(
            favorites = mergedFavorites,
            history = mergedHistory,
            hiddenCategoryKeys = hiddenCategoryKeys + other.hiddenCategoryKeys,
            lockedCategoryKeys = lockedCategoryKeys + other.lockedCategoryKeys,
            customLists = mergedLists,
            favoriteTombstones = favoriteMarks,
            historyTombstones = historyMarks,
            historyClearedAt = clearedAt,
            deletedListIds = deletedLists,
            reminders = mergedReminders,
            reminderTombstones = reminderMarks,
            hiddenChannels = hidden,
            unhiddenChannels = unhidden,
        )
    }

    // Custom lists

    /** Adds an empty list. Pass `id` to know the new list's id up front. */
    fun createList(name: String, id: String = UUID.randomUUID().toString(), now: Long = System.currentTimeMillis()): UserLibrary =
        copy(customLists = customLists + CustomList(id = id, name = name, updatedAt = now))

    fun renameList(id: String, name: String, now: Long = System.currentTimeMillis()): UserLibrary =
        updateList(id) { it.copy(name = name, updatedAt = now) }

    fun deleteList(id: String): UserLibrary =
        copy(customLists = customLists.filter { it.id != id }, deletedListIds = deletedListIds + id)

    /** Adds the item (series level for episodes) to the list, or removes it. */
    fun toggleInList(
        listId: String,
        reference: MediaReference,
        title: String,
        imageUrl: String?,
        now: Long = System.currentTimeMillis(),
    ): UserLibrary = updateList(listId) { list ->
        val ref = reference.copy(episodeId = null)
        val position = list.items.indexOfFirst { it.reference.itemKey == ref.itemKey }
        val items = if (position >= 0) {
            list.items.filterIndexed { i, _ -> i != position }
        } else {
            list.items + FavoriteEntry(ref, title, imageUrl, addedAt = now, sortIndex = list.items.size)
        }
        list.copy(items = items, updatedAt = now)
    }

    /** Moves items of a list like a drag & drop. */
    fun moveItems(listId: String, fromIndices: Collection<Int>, toIndex: Int, now: Long = System.currentTimeMillis()): UserLibrary =
        updateList(listId) { it.copy(items = move(it.items.toMutableList(), fromIndices, toIndex), updatedAt = now) }

    private fun updateList(id: String, transform: (CustomList) -> CustomList): UserLibrary {
        val index = customLists.indexOfFirst { it.id == id }
        if (index < 0) return this
        return copy(customLists = customLists.toMutableList().also { it[index] = transform(it[index]) })
    }

    // Cleanup

    /** Forgets everything about a removed playlist. */
    fun removeData(playlistId: String): UserLibrary {
        val prefix = "$playlistId|"
        return copy(
            favorites = favorites.filter { it.reference.playlistId != playlistId },
            history = history.filter { it.reference.playlistId != playlistId },
            hiddenCategoryKeys = hiddenCategoryKeys.filterTo(HashSet()) { !it.startsWith(prefix) },
            lockedCategoryKeys = lockedCategoryKeys.filterTo(HashSet()) { !it.startsWith(prefix) },
            customLists = customLists.map { list -> list.copy(items = list.items.filter { it.reference.playlistId != playlistId }) },
            reminders = reminders.filter { it.playlistId != playlistId },
            hiddenChannels = hiddenChannels.filterKeys { !it.startsWith(prefix) },
            unhiddenChannels = unhiddenChannels.filterKeys { !it.startsWith(prefix) },
        )
    }

    companion object {
        const val MAX_HISTORY = 500

        val EMPTY = UserLibrary()

        fun categoryKey(playlistId: String, categoryId: String): String = "$playlistId|$categoryId"

        private val REMINDER_ORDER = compareBy<ProgramReminder>({ it.start }, { it.id })

        private fun maxMerge(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> {
            val result = HashMap(a)
            for ((key, value) in b) result.merge(key, value, ::maxOf)
            return result
        }

        /** SwiftUI `move(fromOffsets:toOffset:)` semantics. */
        private fun <T> move(items: MutableList<T>, fromIndices: Collection<Int>, toIndex: Int): List<T> {
            val source = fromIndices.toSortedSet().filter { it in items.indices }
            val moving = source.map { items[it] }
            for (index in source.reversed()) items.removeAt(index)
            val adjusted = toIndex - source.count { it < toIndex }
            items.addAll(adjusted.coerceIn(0, items.size), moving)
            return items
        }
    }
}
