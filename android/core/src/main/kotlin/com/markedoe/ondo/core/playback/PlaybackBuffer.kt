package com.markedoe.ondo.core.playback

/**
 * The buffer settings (Settings, Player): seconds of video the player gathers before it shows the picture and
 * after a seek or a rebuffering. 0 is automatic (the player's own durations). A longer buffer covers longer
 * stops of the provider; the picture then appears later and seeks wait longer.
 */
object PlaybackBuffer {
    /** The choices offered, in seconds; 0 is automatic. */
    val CHOICES = listOf(0, 1, 2, 3, 5, 10)

    /** Media3's `DefaultLoadControl` buffer durations, in milliseconds. */
    data class Durations(
        val minBufferMs: Int,
        val maxBufferMs: Int,
        val bufferForPlaybackMs: Int,
        val bufferForPlaybackAfterRebufferMs: Int,
    )

    /** The setting that applies: live channels have theirs, movies, episodes and catch-up the other. */
    fun setting(isLive: Boolean, live: Int, vod: Int): Int = if (isLive) live else vod

    /**
     * What the player keeps for a chosen length: it waits for `seconds` at the start and after a rebuffering, and
     * gathers at least 15 s and at most 50 s ahead (more when the chosen length is longer). `null` for automatic.
     * The order Media3 requires holds: forPlayback ≤ min, afterRebuffer ≤ min ≤ max.
     */
    fun durations(seconds: Int): Durations? {
        if (seconds <= 0) return null
        val wait = seconds * 1_000
        return Durations(
            minBufferMs = maxOf(wait, 15_000),
            maxBufferMs = maxOf(wait, 50_000),
            bufferForPlaybackMs = wait,
            bufferForPlaybackAfterRebufferMs = wait,
        )
    }
}
