package com.markedoe.ondo.core.model

import java.util.UUID
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

/** Stable pointer to a channel, movie, series or episode inside a playlist. */
@Serializable
data class MediaReference(
    val playlistId: String,
    val type: ContentType,
    /** Channel / movie / series id. For episodes this is the series id. */
    val itemId: String,
    /** Set for series episodes. */
    val episodeId: String? = null,
) {
    /** Key that identifies the exact playable thing (episode level for series). */
    val playbackKey: String
        get() = listOf(playlistId, type.rawValue, itemId, episodeId.orEmpty()).joinToString("|")

    /** Key that identifies the browsable item (series level for episodes). */
    val itemKey: String
        get() = listOf(playlistId, type.rawValue, itemId).joinToString("|")
}

/** A favourite (or an item of a custom list). */
@Serializable
data class FavoriteEntry(
    val reference: MediaReference,
    val title: String,
    val imageUrl: String? = null,
    /** Epoch millis. */
    val addedAt: Long = 0L,
    /** Manual ordering inside the favourites list. */
    val sortIndex: Int = 0,
) {
    val id: String
        get() = reference.itemKey
}

/** A programme the user wants to be notified about. Times are epoch millis. */
@Serializable
data class ProgramReminder(
    val playlistId: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val start: Long,
    val end: Long,
    val createdAt: Long = 0L,
) {
    val id: String
        get() = id(playlistId, channelId, start)

    companion object {
        /** `playlistId|channelId|startSeconds`. */
        fun id(playlistId: String, channelId: String, start: Long): String = "$playlistId|$channelId|${start / 1000}"
    }
}

/** "Recently watched" / "Continue watching" entry. */
@Serializable
data class HistoryEntry(
    val reference: MediaReference,
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    /** Epoch millis. */
    val lastWatchedAt: Long = 0L,
    /** Seconds. Zero for live channels. */
    val position: Double = 0.0,
    /** Seconds. */
    val duration: Double = 0.0,
) {
    val id: String
        get() = reference.playbackKey

    val progress: Double
        get() = if (duration > 0) (position / duration).coerceIn(0.0, 1.0) else 0.0

    /** Worth offering "resume": started, not (almost) finished. */
    val isResumable: Boolean
        get() = reference.type != ContentType.LIVE && position > 30 && progress < 0.95

    val isFinished: Boolean
        get() = reference.type != ContentType.LIVE && progress >= 0.95
}

/**
 * Everything the player needs to start a stream.
 *
 * [urls] are the candidates in the order the player should try them (stream first, then fallbacks such
 * as the `.m3u8` variant of a `.ts` stream), without duplicates. [headers] is `null` when no header is needed.
 */
@Serializable
data class PlaybackItem(
    val title: String,
    val subtitle: String? = null,
    val urls: List<String>,
    val headers: HttpHeaders? = null,
    val reference: MediaReference,
    val isLive: Boolean,
    val artworkUrl: String? = null,
    /** Catch-up playback of a past programme of a live channel. */
    val isCatchup: Boolean = false,
    /** Seconds to resume from. */
    val startPosition: Double? = null,
) {
    init {
        require(urls.isNotEmpty()) { "a playback item needs at least one URL" }
    }

    val id: String
        get() = reference.playbackKey

    val streamUrl: String
        get() = urls.first()

    val fallbackUrls: List<String>
        get() = urls.drop(1)
}

/** A user made list of channels / movies / series ("list manager"). */
@Serializable
data class CustomList(
    /** Random UUID string; required when decoding. */
    @Required val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val items: List<FavoriteEntry> = emptyList(),
    /** Last edit (epoch millis), used to resolve conflicts between devices (last writer wins per list). */
    val updatedAt: Long = 0L,
) {
    fun contains(reference: MediaReference): Boolean = items.any { it.reference.itemKey == reference.itemKey }
}

/** A viewer profile: own favourites, history, lists. Kids profiles only see unlocked content. */
@Serializable
data class Profile(
    @Required val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    /** Index in the app's colour palette. */
    val colorIndex: Int = 0,
    val symbol: String = "person.fill",
    val isKids: Boolean = false,
    /** SHA-256 of the profile PIN, `null` when the profile is not protected. */
    val pinHash: String? = null,
) {
    val isProtected: Boolean
        get() = pinHash != null

    /** Name of the library document of this profile. */
    val libraryDocumentName: String
        get() = if (id == DEFAULT_ID) "library" else "library-$id"

    companion object {
        /** The profile created automatically; its library keeps the historical document name. */
        const val DEFAULT_ID = "00000000-0000-0000-0000-000000000001"
    }
}
