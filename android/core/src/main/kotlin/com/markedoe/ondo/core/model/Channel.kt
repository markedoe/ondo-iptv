package com.markedoe.ondo.core.model

import kotlinx.serialization.Serializable

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

/** The user's changes to a playlist channel. */
@Serializable
data class ChannelEdit(
    val name: String? = null,
    val number: Int? = null,
) {
    val isEmpty: Boolean
        get() = name == null && number == null
}
