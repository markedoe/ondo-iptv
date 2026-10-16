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

    /**
     * Automatic: the wait before playing again once the provider has stopped twice in the same playback, in
     * milliseconds (Media3 waits 2 s after a rebuffering on its own). Same lengths as the Apple apps.
     */
    const val RAISED_LIVE_MS = 6_000
    const val RAISED_VOD_MS = 5_000

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

/**
 * Automatic buffer: the player's own waits until the playback rebuffers a second time, then a longer one after
 * each rebuffering, so that a provider that stops again and again freezes less often (the Apple apps' BufferPolicy).
 * One per playback; the player calls it from its own thread.
 */
class RebufferPolicy(private val raisedUs: Long) {
    private var rebufferStartedAt: Long? = null

    var rebufferings = 0
        private set

    val isRaised: Boolean get() = rebufferings >= 2

    /**
     * The wait, in microseconds, before playing again after the rebuffering that started at [rebufferStartedAtMs],
     * or null for the player's own. A live channel waits at most half its target distance from the live edge (more
     * cannot be buffered). [onRaised] runs once, when the longer wait starts to apply.
     */
    fun waitUs(rebuffering: Boolean, rebufferStartedAtMs: Long?, targetLiveOffsetUs: Long?, onRaised: () -> Unit = {}): Long? {
        if (!rebuffering) return null
        if (rebufferStartedAtMs != null && rebufferStartedAtMs != rebufferStartedAt) {
            rebufferStartedAt = rebufferStartedAtMs
            rebufferings += 1
            if (rebufferings == 2) onRaised()
        }
        if (!isRaised) return null
        return targetLiveOffsetUs?.let { minOf(raisedUs, it / 2) } ?: raisedUs
    }
}
