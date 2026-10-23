package com.markedoe.ondo.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What a device sends to sync on the local network: its playlists, My List and favourite channels, lists and
 * playback progress, and what it deleted (docs/SYNC.md). Times are milliseconds since 1970.
 */
@Serializable
data class SyncSnapshot(
    val type: String = TYPE,
    val version: Int = VERSION,
    val device: String,
    val deviceName: String? = null,
    val playlists: List<SyncPlaylist> = emptyList(),
    val favorites: List<SyncFavorite> = emptyList(),
    val progress: List<SyncProgress> = emptyList(),
    val lists: List<SyncList> = emptyList(),
    val deleted: Map<String, Long> = emptyMap(),
    /** Playlist IDs that became another playlist (the same playlist added on two devices): old ID → new ID. */
    val replaced: Map<String, String> = emptyMap(),
) {
    fun encoded(): ByteArray = json.encodeToString(serializer(), this).encodeToByteArray()

    /** The same snapshot without deletions older than [days] before [now] (and the replacements they ended). */
    fun pruned(now: Long, days: Int = DELETIONS_KEPT_DAYS): SyncSnapshot {
        val oldest = now - days * DAY_MS
        val kept = deleted.filterValues { it >= oldest }
        return copy(deleted = kept, replaced = replaced.filterKeys { "playlist:$it" in kept })
    }

    companion object {
        const val TYPE = "ondo-sync"
        const val VERSION = 1
        const val DELETIONS_KEPT_DAYS = 180
        private const val DAY_MS = 86_400_000L

        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

        /** Reads a snapshot; null when it is not one, or of another version (it is refused, not guessed). */
        fun decode(bytes: ByteArray): SyncSnapshot? = try {
            json.decodeFromString(serializer(), bytes.decodeToString()).takeIf { it.type == TYPE && it.version == VERSION }?.normalized()
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Playlist and list IDs in lowercase, as every device writes them. */
    fun normalized(): SyncSnapshot = copy(
        playlists = playlists.map { it.copy(id = it.id.lowercase()) },
        favorites = favorites.map { it.copy(playlist = it.playlist.lowercase()) },
        progress = progress.map { it.copy(playlist = it.playlist.lowercase()) },
        lists = lists.map { list -> list.copy(id = list.id.lowercase(), items = list.items.map { it.copy(playlist = it.playlist.lowercase()) }) },
        replaced = replaced.entries.associate { (old, new) -> old.lowercase() to new.lowercase() },
    )
}

@Serializable
data class SyncPlaylist(
    val id: String,
    val name: String,
    /** `m3u` or `xtream`. */
    val kind: String,
    /** The M3U address, or the Xtream server. */
    val url: String,
    val username: String? = null,
    val password: String? = null,
    val epgUrl: String? = null,
    val addedAt: Long,
    val updatedAt: Long,
) {
    val key: String get() = "playlist:$id"

    companion object {
        const val M3U = "m3u"
        const val XTREAM = "xtream"
    }
}

/** My List (movies, series) and favourite channels (`live`). */
@Serializable
data class SyncFavorite(
    val playlist: String,
    /** `live`, `movie` or `series`. */
    val kind: String,
    val item: String,
    val title: String,
    val image: String? = null,
    val addedAt: Long,
) {
    val key: String get() = "favorite:$playlist|$kind|$item"
}

/** Where a movie or an episode was left. */
@Serializable
data class SyncProgress(
    val playlist: String,
    /** `movie` or `series`. */
    val kind: String,
    /** The movie, or the series. */
    val item: String,
    val episode: String? = null,
    val title: String,
    val subtitle: String? = null,
    val image: String? = null,
    /** Seconds. */
    val position: Double,
    val duration: Double,
    val watchedAt: Long,
) {
    val key: String get() = "progress:$playlist|$kind|$item|${episode.orEmpty()}"
}

@Serializable
data class SyncList(
    val id: String,
    val name: String,
    val items: List<SyncListItem> = emptyList(),
    val updatedAt: Long,
) {
    val key: String get() = "list:$id"
}

@Serializable
data class SyncListItem(
    val playlist: String,
    val kind: String,
    val item: String,
    val title: String,
    val image: String? = null,
) {
    val key: String get() = "$playlist|$kind|$item"
}
