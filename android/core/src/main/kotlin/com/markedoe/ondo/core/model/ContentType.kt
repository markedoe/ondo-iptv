package com.markedoe.ondo.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The three kinds of content an IPTV provider exposes. */
@Serializable
enum class ContentType(
    /** Persisted / key value (`"live"`, `"movie"`, `"series"`), identical to the Swift raw values. */
    val rawValue: String,
) {
    @SerialName("live") LIVE("live"),
    @SerialName("movie") MOVIE("movie"),
    @SerialName("series") SERIES("series");

    companion object {
        fun fromRawValue(value: String): ContentType? = entries.firstOrNull { it.rawValue == value }
    }
}
