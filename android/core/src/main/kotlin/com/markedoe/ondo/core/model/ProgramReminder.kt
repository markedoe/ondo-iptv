package com.markedoe.ondo.core.model

import kotlinx.serialization.Serializable

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
