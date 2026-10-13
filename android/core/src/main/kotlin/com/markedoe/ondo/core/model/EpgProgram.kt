package com.markedoe.ondo.core.model

import kotlinx.serialization.Serializable

/** One TV programme from the electronic programme guide. Times are epoch millis. */
@Serializable
data class EpgProgram(
    /** XMLTV channel id the programme belongs to. */
    val channelId: String,
    val start: Long,
    val end: Long,
    val title: String,
    val description: String? = null,
    val subtitle: String? = null,
    val category: String? = null,
    val iconUrl: String? = null,
    /** `"S02E05"` or the provider's on-screen numbering. */
    val episode: String? = null,
    val rating: String? = null,
) {
    /** `channelId|startSeconds`. */
    val id: String
        get() = "$channelId|${start / 1000}"

    val durationMillis: Long
        get() = end - start

    fun isLive(at: Long = System.currentTimeMillis()): Boolean = start <= at && at < end

    fun isPast(at: Long = System.currentTimeMillis()): Boolean = end <= at

    /** 0..1 progress of the programme at `at`. */
    fun progress(at: Long = System.currentTimeMillis()): Double {
        if (durationMillis <= 0) return 0.0
        return ((at - start).toDouble() / durationMillis).coerceIn(0.0, 1.0)
    }
}
