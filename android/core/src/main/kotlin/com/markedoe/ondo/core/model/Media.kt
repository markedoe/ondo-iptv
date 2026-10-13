package com.markedoe.ondo.core.model

import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A group of channels / movies / series (`group-title` in M3U, category in Xtream). */
@Serializable
data class MediaCategory(
    val id: String,
    val name: String,
    val type: ContentType,
) {
    /** Heuristic used by parental control to lock adult groups by default. */
    val looksAdult: Boolean
        get() = AdultContentDetector.isAdult(name)
}

/** Keyword heuristic for adult category names. */
object AdultContentDetector {
    private val keywords = listOf("xxx", "adult", "adulte", "18+", "+18", "porn", "erotic", "érotique", "for adults")

    fun isAdult(text: String): Boolean {
        val lower = text.lowercase()
        return keywords.any { lower.contains(it) }
    }
}

/** How past programmes of a live channel can be replayed. */
@Serializable
data class CatchupInfo(
    val mode: Mode,
    /** How many days back the provider keeps the archive. */
    val days: Int,
    val source: String? = null,
) {
    @Serializable
    enum class Mode {
        /** `catchup-source` is a full URL template. */
        @SerialName("default") DEFAULT,

        /** `catchup-source` is appended to the live URL. */
        @SerialName("append") APPEND,

        /** `?utc={start}&lutc={now}` appended to the live URL (SIPTV style). */
        @SerialName("shift") SHIFT,

        /** Flussonic `timeshift_abs` / `index-{start}-{duration}` URLs. */
        @SerialName("flussonic") FLUSSONIC,

        /** Xtream Codes `/timeshift/` URLs. */
        @SerialName("xtream") XTREAM,
    }
}

/** A live channel. */
@Serializable
data class Channel(
    val id: String,
    val name: String,
    val number: Int? = null,
    val logoUrl: String? = null,
    val categoryId: String,
    val streamUrl: String,
    /** `tvg-id` / Xtream `epg_channel_id`. */
    val epgId: String? = null,
    val catchup: CatchupInfo? = null,
    val headers: HttpHeaders? = null,
    val xtreamStreamId: Int? = null,
    /** `tvg-name` (or the provider name of a renamed channel), sometimes a better guide key than [name]. */
    val epgName: String? = null,
) {
    val hasCatchup: Boolean
        get() = (catchup?.days ?: 0) > 0
}

/** A video on demand. */
@Serializable
data class Movie(
    val id: String,
    val name: String,
    val posterUrl: String? = null,
    val categoryId: String,
    val streamUrl: String,
    val rating: Double? = null,
    val year: Int? = null,
    /** When the provider added it, in epoch **seconds**. */
    val addedAt: Long? = null,
    val plot: String? = null,
    val xtreamStreamId: Int? = null,
    val containerExtension: String? = null,
    val headers: HttpHeaders? = null,
)

/** One episode of a [Series]. */
@Serializable
data class Episode(
    val id: String,
    val title: String,
    val season: Int,
    val number: Int,
    val streamUrl: String,
    val posterUrl: String? = null,
    val plot: String? = null,
    val durationSeconds: Int? = null,
    val releaseDate: String? = null,
    val rating: Double? = null,
    val headers: HttpHeaders? = null,
) {
    /** `"S01E05"`. */
    val code: String
        get() = String.format(Locale.ROOT, "S%02dE%02d", season, number)
}

/** A series. Episodes are known up-front for M3U playlists; Xtream series load them on demand. */
@Serializable
data class Series(
    val id: String,
    val name: String,
    val coverUrl: String? = null,
    val categoryId: String,
    val plot: String? = null,
    val rating: Double? = null,
    val year: Int? = null,
    val genre: String? = null,
    /** Last modification reported by the provider, in epoch **seconds** (same unit as [Movie.addedAt]). */
    val addedAt: Long? = null,
    val xtreamSeriesId: Int? = null,
    val episodes: List<Episode> = emptyList(),
)

/** A season of a series, as returned by the Xtream details call. */
@Serializable
data class Season(
    val number: Int,
    val name: String,
    val episodes: List<Episode>,
    val coverUrl: String? = null,
    val overview: String? = null,
) {
    val id: Int
        get() = number
}

/** Rich metadata shown on the movie / series detail screens. */
@Serializable
data class MediaDetails(
    val title: String,
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val durationSeconds: Int? = null,
    val rating: Double? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val trailerYouTubeId: String? = null,
    val originalTitle: String? = null,
    val tmdbId: String? = null,
)

/** Series metadata with its seasons and episodes. */
@Serializable
data class SeriesDetails(
    val info: MediaDetails,
    val seasons: List<Season>,
)

/** The user's changes to a playlist channel. */
@Serializable
data class ChannelEdit(
    val name: String? = null,
    val number: Int? = null,
) {
    val isEmpty: Boolean
        get() = name == null && number == null
}
