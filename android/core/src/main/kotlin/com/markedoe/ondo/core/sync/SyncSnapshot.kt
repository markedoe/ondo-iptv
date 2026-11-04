package com.markedoe.ondo.core.sync

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What a device sends to sync on the local network: its playlists, My List and favourite channels, the people kept,
 * lists, playback progress, the keys of outside services and the order of the rows of the Movies and Series tabs, and
 * what it deleted (docs/SYNC.md). Times are milliseconds since 1970.
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
    /** The order of the rows of the Movies and Series tabs, and the rows hidden; none from a device that does not sync them. */
    val rows: List<SyncRows> = emptyList(),
    /** The viewer profiles (docs/PROFILES.md, docs/SYNC.md "Profiles"); none from a device that does not sync them. */
    val profiles: List<SyncProfile> = emptyList(),
    /**
     * The data of each profile other than the main one, whose data is the records above (an older device knows only
     * those); none from a device that does not sync profiles.
     */
    val profileData: List<SyncProfileData> = emptyList(),
    /**
     * The lists shared with every profile (docs/SYNC.md, "Shared lists"), with what was deleted among them; null when there
     * are none, and from a device that does not share lists.
     */
    val household: SyncHousehold? = null,
    val deleted: Map<String, Long> = emptyMap(),
    /** Playlist IDs that became another playlist (the same playlist added on two devices): old ID → new ID. */
    val replaced: Map<String, String> = emptyMap(),
    /** The sender's clock when it sent the snapshot; receivers ignore it (docs/SYNC.md, "Clocks"). */
    val clock: Long? = null,
    /** The sender's [contentDigest] of its own data (docs/SYNC.md, "Transport"); none from an older version. */
    val digest: String? = null,
    /** In a probe: the server's [digest] the client got at their last session. */
    val seen: String? = null,
    /** A probe: no records, only [digest] and [seen]; a server answers [unchanged] when it has not changed since. */
    val probe: Boolean? = null,
    /** An answer to a probe: nothing changed on the server since the client's last session; nothing to merge. */
    val unchanged: Boolean? = null,
) {
    val isProbe: Boolean get() = probe == true
    val isUnchanged: Boolean get() = unchanged == true

    /**
     * A digest of the records and deletions (SHA-256, hexadecimal), without who sends them or when, nor the progress
     * records whose key is in [leavingOut] (the item playing, saved every few seconds): the same data gives the same
     * digest. A device compares it only with its own earlier digests and those a peer sent it.
     */
    fun contentDigest(leavingOut: Set<String> = emptySet()): String {
        val content = copy(
            device = "", deviceName = null, clock = null, digest = null, seen = null, probe = null, unchanged = null,
            progress = if (leavingOut.isEmpty()) progress else progress.filter { it.key !in leavingOut },
            deleted = deleted.toSortedMap(), replaced = replaced.toSortedMap(),
            // The item playing may be any profile's: left out of each profile's progress alike.
            profileData = profileData.map { bucket ->
                bucket.copy(
                    progress = if (leavingOut.isEmpty()) bucket.progress else bucket.progress.filter { it.key !in leavingOut },
                    deleted = bucket.deleted.toSortedMap(),
                )
            },
            // No household (no list shared) is left out, as an older version writes it: the digest stays the same.
            household = household?.takeIf { !it.isEmpty }?.let { it.copy(deleted = it.deleted.toSortedMap()) },
        )
        return MessageDigest.getInstance("SHA-256").digest(content.encoded()).joinToString("") { "%02x".format(it) }
    }

    /** A probe from this snapshot's device (docs/SYNC.md, "Transport"): its [digest], and the server's [seen] one. */
    fun probe(digest: String, seen: String): SyncSnapshot =
        SyncSnapshot(device = device, deviceName = deviceName, clock = clock, digest = digest, seen = seen, probe = true)

    /**
     * What a client sends a server (docs/SYNC.md, "Transport"): a probe when its data is the same as in the snapshot it
     * last sent ([sentDigest]) and the server answered with a digest ([answerDigest]), unless [full] (the regular full
     * session); else this snapshot, with its digest. [leavingOut]: as for [contentDigest].
     */
    fun offer(sentDigest: String?, answerDigest: String?, full: Boolean = false, leavingOut: Set<String> = emptySet()): SyncSnapshot {
        val digest = contentDigest(leavingOut)
        return if (!full && answerDigest != null && digest == sentDigest) probe(digest, answerDigest) else copy(digest = digest)
    }

    /**
     * [offer] against [sent], the snapshot last sent in full to that server (null: none yet): its digest is the one
     * for the same [leavingOut], so that an item that started playing since (its progress saved every few seconds)
     * does not make every offer a full one. Returns the offer, and what to keep as sent once the server has it: for a
     * full offer the offer itself, for a probe [sent] with its digest for [leavingOut].
     */
    fun offer(sent: SentSnapshot?, answerDigest: String?, full: Boolean = false, leavingOut: Set<String> = emptySet()): Offer {
        val now = sent?.leaving(leavingOut)
        val offer = offer(now?.digest, answerDigest, full, leavingOut)
        return Offer(offer, if (offer.isProbe) now else SentSnapshot(offer, leavingOut, requireNotNull(offer.digest)))
    }

    /** What [offer] gives: the snapshot to send, and what to keep as sent once the server has it. */
    data class Offer(val snapshot: SyncSnapshot, val sent: SentSnapshot?)

    fun encoded(): ByteArray = json.encodeToString(serializer(), this).encodeToByteArray()

    /**
     * The same snapshot without deletions older than [days] before [now] (and the replacements they ended), nor older
     * than the last session with each device of [sessions] (when it last synced with this one), for the devices seen
     * in the last [PEERS_KEPT_DAYS] days: a deletion goes only once every such device has had it (docs/SYNC.md,
     * "Deleted").
     */
    fun pruned(
        now: Long,
        days: Int = DELETIONS_KEPT_DAYS,
        sessions: Collection<Long> = emptyList(),
        learned: Map<String, Long> = emptyMap(),
        /** For each profile other than the main one, by ID: when this device learned the deletions made in it elsewhere. */
        profileLearned: Map<String, Map<String, Long>> = emptyMap(),
        /** When this device learned the deletions made among the shared lists elsewhere. */
        householdLearned: Map<String, Long> = emptyMap(),
    ): SyncSnapshot {
        val oldest = minOf(now - days * DAY_MS, sessions.filter { it >= now - PEERS_KEPT_DAYS * DAY_MS }.minOrNull() ?: Long.MAX_VALUE)
        // A deletion learned late (from a device away for months) counts from when this device learned it.
        val kept = deleted.filter { (key, time) -> maxOf(time, learned[key] ?: time) >= oldest }
        return copy(
            deleted = kept,
            replaced = replaced.filterKeys { "playlist:$it" in kept },
            // In each profile too, a deletion learned late counts from when this device learned it.
            profileData = profileData.map { bucket ->
                val learnedHere = profileLearned[bucket.profile].orEmpty()
                bucket.copy(deleted = bucket.deleted.filter { (key, time) -> maxOf(time, learnedHere[key] ?: time) >= oldest })
            },
            // Among the shared lists too.
            household = household
                ?.let { it.copy(deleted = it.deleted.filter { (key, time) -> maxOf(time, householdLearned[key] ?: time) >= oldest }) }
                ?.takeIf { !it.isEmpty },
        )
    }

    /** Every time of the snapshot: of the records, of the items and people of the lists, and of the deletions. */
    fun times(): Sequence<Long> = sequence {
        playlists.forEach { yield(it.addedAt); yield(it.updatedAt) }
        favorites.forEach { yield(it.addedAt) }
        progress.forEach { yield(it.watchedAt) }
        lists.forEach { list ->
            yield(list.updatedAt)
            list.renamedAt?.let { yield(it) }
            list.orderedAt?.let { yield(it) }
            list.placedAt?.let { yield(it) }
            list.items.forEach { item -> item.addedAt?.let { yield(it) } }
            list.people.forEach { yield(it.addedAt) }
        }
        people.forEach { yield(it.addedAt) }
        keys.forEach { yield(it.updatedAt) }
        rows.forEach { yield(it.updatedAt) }
        profiles.forEach { yield(it.updatedAt) }
        profileData.forEach { yieldAll(it.records().times()) }
        household?.let { yieldAll(it.records().times()) }
        yieldAll(deleted.values)
    }

    /** The latest of [times]; null for a snapshot without any. */
    fun latestTime(): Long? = times().maxOrNull()

    /**
     * The same snapshot without the records and deletions dated after [latest] ([SyncClock.receive], docs/SYNC.md,
     * "Clocks"): one device whose clock is far ahead cannot date its changes in the future and win over every later
     * one, nor push the others' clocks there. They are left out, not moved: the device that made them keeps them, and
     * they travel once their time has come. Applied by the device that receives a snapshot, before the merge.
     */
    fun withoutRecordsAfter(latest: Long): SyncSnapshot {
        if (latest == Long.MAX_VALUE) return this
        fun past(time: Long) = time <= latest
        return copy(
            playlists = playlists.filter { past(it.addedAt) && past(it.updatedAt) },
            favorites = favorites.filter { past(it.addedAt) },
            progress = progress.filter { past(it.watchedAt) },
            lists = lists.filter { past(it.updatedAt) && past(it.nameTime) && past(it.orderTime) && past(it.placedAt ?: it.updatedAt) }.map { list ->
                list.copy(items = list.items.filter { past(it.addedAt ?: list.updatedAt) }, people = list.people.filter { past(it.addedAt) })
            },
            people = people.filter { past(it.addedAt) },
            keys = keys.filter { past(it.updatedAt) },
            rows = rows.filter { past(it.updatedAt) },
            profiles = profiles.filter { past(it.updatedAt) },
            profileData = profileData.map { SyncProfileData.of(it.profile, it.records().withoutRecordsAfter(latest)) },
            household = household?.let { SyncHousehold.of(it.records().withoutRecordsAfter(latest)) }?.takeIf { !it.isEmpty },
            deleted = deleted.filterValues(::past),
        )
    }

    companion object {
        const val TYPE = "ondo-sync"
        const val VERSION = 1
        const val DELETIONS_KEPT_DAYS = 180
        /** A device not synced with for this long no longer holds deletions back. */
        const val PEERS_KEPT_DAYS = 365
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
        lists = lists.map { list ->
            list.copy(
                id = list.id.lowercase(),
                items = list.items.map { it.copy(playlist = it.playlist.lowercase()) },
                order = list.order?.map { key -> SyncListItem.withPlaylist(key) { it.lowercase() } },
            )
        },
        rows = SyncRows.capped(rows.map(SyncRows::normalized)),
        profiles = SyncProfile.capped(profiles.map { it.copy(id = it.id.lowercase()) }),
        profileData = profileData.map { SyncProfileData.of(it.profile.lowercase(), it.records().normalized()) },
        // No shared list is no household: an empty one is never written (an older version writes none).
        household = household?.let { SyncHousehold.of(it.records().normalized()) }?.takeIf { !it.isEmpty },
        deleted = deleted.entries.fold(LinkedHashMap<String, Long>()) { times, (key, time) ->
            val lowercased = lowercasingId(key)
            times[lowercased] = maxOf(times[lowercased] ?: time, time)
            times
        },
        replaced = replaced.entries.associate { (old, new) -> old.lowercase() to new.lowercase() },
    )
}

/**
 * A snapshot a client sent a server in full, with its [digest] leaving out the progress of [leavingOut] (docs/SYNC.md,
 * "Transport").
 */
class SentSnapshot(val snapshot: SyncSnapshot, val leavingOut: Set<String>, val digest: String) {
    /** The same snapshot with its digest leaving out [others] instead: worked out again only when they differ. */
    fun leaving(others: Set<String>): SentSnapshot =
        if (others == leavingOut) this else SentSnapshot(snapshot, others, snapshot.contentDigest(others))
}

/** [a] + [b], held at the ends of Long's range instead of wrapping around. */
internal fun saturated(a: Long, b: Long): Long {
    val sum = a + b
    return if (((a xor sum) and (b xor sum)) < 0) (if (a < 0) Long.MIN_VALUE else Long.MAX_VALUE) else sum
}

/**
 * The key of a deleted record with its playlist or list ID in lowercase: `playlist:<id>`, `list:<id>`, and the
 * playlist of `favorite:<playlist>|…` and `progress:<playlist>|…`; the name of `person:<name>` with A to Z in lowercase;
 * in `listitem:<list>|<item>`, the list and the item the same way. The rest (the provider's item IDs, the service of
 * `key:`) keeps its case.
 */
internal fun lowercasingId(key: String): String {
    if (key.startsWith(SyncPerson.KEY_PREFIX)) return SyncPerson.KEY_PREFIX + SyncPerson.folded(key.substring(SyncPerson.KEY_PREFIX.length))
    if (key.startsWith(SyncList.ITEM_PREFIX)) {
        val listEnd = key.indexOf('|', SyncList.ITEM_PREFIX.length).takeIf { it >= 0 } ?: return key.lowercase()
        val list = key.substring(0, listEnd).lowercase()
        val item = key.substring(listEnd + 1)
        if (item.startsWith(SyncPerson.KEY_PREFIX)) return "$list|${lowercasingId(item)}"
        val bar = item.indexOf('|').takeIf { it >= 0 } ?: return "$list|${item.lowercase()}"
        return "$list|${item.substring(0, bar).lowercase()}${item.substring(bar)}"
    }
    for (prefix in listOf("playlist:", "list:", "favorite:", "progress:", SyncProfile.KEY_PREFIX)) {
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
    /** When the name was last set; [updatedAt] when absent (an older version). */
    val renamedAt: Long? = null,
    /** When the items were last put in order; [updatedAt] when absent (an older version). */
    val orderedAt: Long? = null,
    /** The order of the items set at [orderedAt], by [SyncListItem.key]; the order of [items] when absent. */
    val order: List<String>? = null,
    /**
     * When the list was moved into the space it is in (shared with every profile, or taken back into a profile:
     * docs/SYNC.md, "Shared lists"); absent for a list never moved.
     */
    val placedAt: Long? = null,
) {
    val key: String get() = "list:$id"
    val nameTime: Long get() = renamedAt ?: updatedAt
    val orderTime: Long get() = orderedAt ?: updatedAt
    val itemOrder: List<String> get() = order ?: items.map { it.key }

    /** The key of the removal of an item or a person ([SyncListItem.key], [SyncPerson.key]) from this list. */
    fun itemKey(item: String) = "$ITEM_PREFIX$id|$item"

    companion object {
        const val ITEM_PREFIX = "listitem:"
    }
}

@Serializable
data class SyncListItem(
    val playlist: String,
    val kind: String,
    val item: String,
    val title: String,
    val image: String? = null,
    /** When it was put in the list; the list's `updatedAt` when absent (an older version). */
    val addedAt: Long? = null,
) {
    val key: String get() = "$playlist|$kind|$item"

    companion object {
        /** An item [key] with its playlist ID through [playlist]. */
        fun withPlaylist(key: String, playlist: (String) -> String): String {
            val bar = key.indexOf('|')
            return if (bar < 0) key else playlist(key.substring(0, bar)) + key.substring(bar)
        }
    }
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

/**
 * The rows of a tab of the Movies or Series tabs ([tab] `movie` or `series`) in the order the viewer set ([order], keys of
 * `RowOrder`: `all`, `continue`, … and `cat:<id>|<name>` for a category), and the rows they hid ([hidden]). One record
 * per tab, the whole of it set at once: the later [updatedAt] wins (docs/SYNC.md, "Rows").
 */
@Serializable
data class SyncRows(
    val tab: String,
    val order: List<String> = emptyList(),
    val hidden: List<String> = emptyList(),
    val updatedAt: Long,
) {
    val key: String get() = "rows:$tab"

    /** The tie string of the merge (docs/SYNC.md, "Merge", rule 2): unambiguous, as no key holds a line feed. */
    val tie: String get() = "${order.size}\n" + (order + hidden).joinToString("\n")

    /**
     * As every device keeps it: keys without control characters and not blank, [order] each once (the first), [hidden]
     * each once and sorted (UTF-16 code units).
     */
    fun normalized(): SyncRows = copy(
        order = order.filter(::valid).distinct().take(MAX_KEYS),
        hidden = hidden.filter(::valid).distinct().sorted().take(MAX_KEYS),
    )

    companion object {
        const val MOVIES = "movie"
        const val SERIES = "series"

        /** At most this many tabs in a snapshot, keys in `order` and in `hidden`, and UTF-16 units in a key or a tab. */
        const val MAX_TABS = 8
        const val MAX_KEYS = 2000
        const val MAX_KEY_LENGTH = 256

        private fun valid(key: String) = key.length <= MAX_KEY_LENGTH && key.isNotBlank() && key.none { it < ' ' || it == '\u007f' }

        /**
         * [rows] without the records of a tab that is not a valid key, and of the tabs past the [MAX_TABS] first
         * (`movie` and `series` first, then the others by UTF-16 code units); the records kept stay in their order.
         */
        fun capped(rows: List<SyncRows>): List<SyncRows> {
            val valid = rows.filter { valid(it.tab) }
            val tabs = valid.map { it.tab }.distinct()
            if (tabs.size <= MAX_TABS) return valid
            val known = listOf(MOVIES, SERIES).filter { it in tabs }
            val kept = (known + (tabs - known.toSet()).sorted()).take(MAX_TABS).toHashSet()
            return valid.filter { it.tab in kept }
        }
    }
}

/**
 * A viewer profile (docs/SYNC.md, "Profiles"): its name and avatar, and whether it is a Kids profile. Never its PIN,
 * which stays on each device (docs/PROFILES.md, "PIN").
 */
@Serializable
data class SyncProfile(
    val id: String,
    val name: String = "",
    val colorIndex: Int = 0,
    val symbol: String = "initial",
    val kids: Boolean = false,
    val updatedAt: Long,
) {
    val key: String get() = KEY_PREFIX + id

    /** The tie string of the merge (docs/SYNC.md, "Merge", rule 2). */
    val tie: String get() = listOf(name, colorIndex.toString(), symbol, if (kids) "1" else "0").joinToString("\n")

    companion object {
        const val KEY_PREFIX = "profile:"

        /** The main profile: its data is the snapshot's own records, and it is never deleted. */
        const val MAIN = "00000000-0000-0000-0000-000000000001"

        /** At most this many profiles in a snapshot: the main one first, then by ID. */
        const val MAX_PROFILES = 16

        /** [profiles] without those past the [MAX_PROFILES] first (the main one, then by ID); the others in their order. */
        fun capped(profiles: List<SyncProfile>): List<SyncProfile> {
            val ids = profiles.map { it.id }.distinct()
            if (ids.size <= MAX_PROFILES) return profiles
            val kept = ids.sortedWith(compareBy<String> { it != MAIN }.thenBy { it }).take(MAX_PROFILES).toHashSet()
            return profiles.filter { it.id in kept }
        }
    }
}

/**
 * The data of a profile other than the main one (docs/SYNC.md, "Profiles"): its My List and favourite channels,
 * progress, lists, people and order of rows, with what was deleted in it, by the same keys as the snapshot's own.
 */
@Serializable
data class SyncProfileData(
    val profile: String,
    val favorites: List<SyncFavorite> = emptyList(),
    val progress: List<SyncProgress> = emptyList(),
    val lists: List<SyncList> = emptyList(),
    val people: List<SyncPerson> = emptyList(),
    val rows: List<SyncRows> = emptyList(),
    val deleted: Map<String, Long> = emptyMap(),
) {
    /** Its records as a snapshot of their own (to merge, normalise, date them with the snapshot's rules). */
    fun records(playlists: List<SyncPlaylist> = emptyList(), deletedPlaylists: Map<String, Long> = emptyMap(), replaced: Map<String, String> = emptyMap()) =
        SyncSnapshot(
            device = "", playlists = playlists, favorites = favorites, progress = progress, lists = lists, people = people, rows = rows,
            deleted = deleted + deletedPlaylists, replaced = replaced,
        )

    companion object {
        /** The bucket of [profile] from [records] (the playlists, keys and playlist deletions it carries left out). */
        fun of(profile: String, records: SyncSnapshot) = SyncProfileData(
            profile = profile,
            favorites = records.favorites,
            progress = records.progress,
            lists = records.lists,
            people = records.people,
            rows = records.rows,
            deleted = records.deleted.filterKeys { !it.startsWith("playlist:") },
        )
    }
}

/**
 * The lists shared with every profile (docs/SYNC.md, "Shared lists"): a space of their own, apart from any profile's
 * library, with what was deleted among them (a shared list deleted for every profile, an item taken out of one, a list
 * taken back into a profile). Merged by the same rules as a profile's lists.
 */
@Serializable
data class SyncHousehold(
    val lists: List<SyncList> = emptyList(),
    val deleted: Map<String, Long> = emptyMap(),
) {
    val isEmpty: Boolean get() = lists.isEmpty() && deleted.isEmpty()

    /** Its records as a snapshot of their own (to merge, normalise, date them with the snapshot's rules). */
    fun records(playlists: List<SyncPlaylist> = emptyList(), deletedPlaylists: Map<String, Long> = emptyMap(), replaced: Map<String, String> = emptyMap()) =
        SyncSnapshot(device = "", playlists = playlists, lists = lists, deleted = deleted + deletedPlaylists, replaced = replaced)

    companion object {
        /** The household of [records] (only their lists and the deletions of lists and their items). */
        fun of(records: SyncSnapshot) = SyncHousehold(
            lists = records.lists,
            deleted = records.deleted.filterKeys { it.startsWith("list:") || it.startsWith(SyncList.ITEM_PREFIX) },
        )
    }
}
