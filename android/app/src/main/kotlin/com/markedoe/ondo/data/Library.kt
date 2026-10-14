package com.markedoe.ondo.data

import kotlinx.serialization.Serializable

/** Kind of item in the library. */
@Serializable
enum class ItemKind { CHANNEL, MOVIE, SERIES }

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
) {
    /** Identifies the exact playable thing (episode level for series). */
    val key: String get() = "$playlistId|$kind|$itemId|${episodeId.orEmpty()}"
    val progress: Double get() = if (duration > 0) (position / duration).coerceIn(0.0, 1.0) else 0.0
    val isFinished: Boolean get() = kind != ItemKind.CHANNEL && progress >= FINISHED
    val isResumable: Boolean get() = kind != ItemKind.CHANNEL && position > 30 && progress < FINISHED
    val minutesLeft: Int get() = (((duration - position).coerceAtLeast(0.0)) / 60).toInt()

    companion object {
        const val FINISHED = 0.95
    }
}

/**
 * Favourites, watch history and hidden categories, persisted as one JSON document. Pure: every change returns a
 * new value.
 */
@Serializable
data class LibraryState(
    val favorites: List<Favorite> = emptyList(),
    val history: List<WatchEntry> = emptyList(),
    /** `playlistId|categoryId` hidden by the user. */
    val hiddenCategories: Set<String> = emptySet(),
    /** Language or country tags ("PT", see `CategoryTags`) hidden in every playlist. */
    val hiddenTags: Set<String> = emptySet(),
) {
    fun isCategoryHidden(playlistId: String, categoryId: String): Boolean = "$playlistId|$categoryId" in hiddenCategories

    fun settingCategoryHidden(playlistId: String, categoryId: String, hidden: Boolean): LibraryState {
        val key = "$playlistId|$categoryId"
        return copy(hiddenCategories = if (hidden) hiddenCategories + key else hiddenCategories - key)
    }

    fun settingTagsHidden(tags: Collection<String>, hidden: Boolean): LibraryState =
        copy(hiddenTags = if (hidden) hiddenTags + tags else hiddenTags - tags.toSet())

    fun isFavorite(playlistId: String, kind: ItemKind, itemId: String): Boolean =
        favorites.any { it.playlistId == playlistId && it.kind == kind && it.itemId == itemId }

    fun toggleFavorite(favorite: Favorite): LibraryState =
        if (favorites.any { it.key == favorite.key }) copy(favorites = favorites.filterNot { it.key == favorite.key })
        else copy(favorites = favorites + favorite)

    fun favorites(playlistId: String, kind: ItemKind): List<Favorite> =
        favorites.filter { it.playlistId == playlistId && it.kind == kind }

    /** Starts (or restarts) an entry; keeps the known position. */
    fun recordStart(entry: WatchEntry): LibraryState {
        val existing = history.firstOrNull { it.key == entry.key }
        val updated = entry.copy(
            position = existing?.position ?: entry.position,
            duration = existing?.duration?.takeIf { it > 0 } ?: entry.duration,
        )
        return copy(history = listOf(updated) + history.filterNot { it.key == entry.key }).trimmed()
    }

    fun updateProgress(key: String, position: Double, duration: Double, now: Long): LibraryState =
        copy(history = history.map { if (it.key == key) it.copy(position = position.coerceAtLeast(0.0), duration = if (duration > 0) duration else it.duration, lastWatchedAt = now) else it })

    fun progress(key: String): WatchEntry? = history.firstOrNull { it.key == key }

    fun remove(entry: WatchEntry): LibraryState = copy(history = history.filterNot { it.key == entry.key })

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

    fun removingPlaylist(playlistId: String): LibraryState = copy(
        favorites = favorites.filterNot { it.playlistId == playlistId },
        history = history.filterNot { it.playlistId == playlistId },
        hiddenCategories = hiddenCategories.filterNot { it.startsWith("$playlistId|") }.toSet(),
    )

    private fun trimmed(): LibraryState = if (history.size <= MAX_HISTORY) this else copy(history = history.sortedByDescending { it.lastWatchedAt }.take(MAX_HISTORY))

    companion object {
        const val MAX_HISTORY = 500
    }
}
