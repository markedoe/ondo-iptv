package com.markedoe.ondo.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What a device sends to sync on the local network: its playlists, My List and favourite channels, the people kept,
 * lists, playback progress and the keys of outside services, and what it deleted (docs/SYNC.md). Times are milliseconds since 1970.
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
    /** The actors and directors kept in My List (whatever the playlist); none from a device that does not keep any. */
    val people: List<SyncPerson> = emptyList(),
    /** The keys typed in Settings for outside services (the TMDb token); none from a device that does not sync them. */
    val keys: List<SyncKey> = emptyList(),
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

    /**
     * Playlist and list IDs in lowercase, as every device writes them, in the records and in the keys of what was
     * deleted (two keys that become one keep the later time).
     */
    fun normalized(): SyncSnapshot = copy(
        playlists = playlists.map { it.copy(id = it.id.lowercase()) },
        favorites = favorites.map { it.copy(playlist = it.playlist.lowercase()) },
        progress = progress.map { it.copy(playlist = it.playlist.lowercase()) },
        lists = lists.map { list -> list.copy(id = list.id.lowercase(), items = list.items.map { it.copy(playlist = it.playlist.lowercase()) }) },
        deleted = deleted.entries.fold(LinkedHashMap<String, Long>()) { times, (key, time) ->
            val lowercased = lowercasingId(key)
            times[lowercased] = maxOf(times[lowercased] ?: time, time)
            times
        },
        replaced = replaced.entries.associate { (old, new) -> old.lowercase() to new.lowercase() },
    )
}

/**
 * The key of a deleted record with its playlist or list ID in lowercase: `playlist:<id>`, `list:<id>`, and the
 * playlist of `favorite:<playlist>|…` and `progress:<playlist>|…`; the name of `person:<name>` with A to Z in lowercase.
 * The rest (the provider's item IDs, the service of `key:`) keeps its case.
 */
internal fun lowercasingId(key: String): String {
    if (key.startsWith(SyncPerson.KEY_PREFIX)) return SyncPerson.KEY_PREFIX + SyncPerson.folded(key.substring(SyncPerson.KEY_PREFIX.length))
    for (prefix in listOf("playlist:", "list:", "favorite:", "progress:")) {
        if (!key.startsWith(prefix)) continue
        val bar = if (prefix == "favorite:" || prefix == "progress:") key.indexOf('|', prefix.length) else -1
        if (bar < 0) return prefix + key.substring(prefix.length).lowercase()
        return prefix + key.substring(prefix.length, bar).lowercase() + key.substring(bar)
    }
    return key
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
    /** The actors and directors in the list; none from a device that does not keep any. */
    val people: List<SyncPerson> = emptyList(),
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

/** An actor or a director kept in My List or in a list, by name, whatever the playlist. */
@Serializable
data class SyncPerson(
    val name: String,
    /** Their photo (a web address). */
    val image: String? = null,
    val addedAt: Long,
) {
    /** `person:<name>` with only A to Z in lowercase: the same on every platform, whatever its case rules. */
    val key: String get() = KEY_PREFIX + folded(name)

    companion object {
        const val KEY_PREFIX = "person:"

        fun folded(name: String): String = buildString(name.length) { name.forEach { append(if (it in 'A'..'Z') it + 32 else it) } }
    }
}

/** The key or token of an outside service, typed in Settings: [service] `tmdb` for TMDb's token (or API Key). */
@Serializable
data class SyncKey(
    val service: String,
    val value: String,
    val updatedAt: Long,
) {
    val key: String get() = "key:$service"

    companion object {
        const val TMDB = "tmdb"
    }
}
