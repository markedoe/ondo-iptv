package com.markedoe.ondo.player

/**
 * A live channel paused for a while resumes where it was paused, from the provider's archive, rather than at what is
 * on air by then: a stream with no window to seek in loses what aired during the pause. A short pause resumes from
 * what the player kept.
 */
object LivePause {
    /** Pauses shorter than this resume from what the player kept. */
    const val THRESHOLD_MS = 10_000L

    /** How far the archive asked for reaches: an archive address needs an end. */
    const val WINDOW_MS = 4 * 3_600_000L

    /**
     * When a pause counts ([now]): only a live channel the player cannot seek in (a window to seek in keeps what aired),
     * paused by the viewer.
     */
    fun pausedAt(request: PlayRequest, seekable: Boolean, now: Long): Long? = now.takeIf { request.isLive && request.channelId != null && !seekable }

    /** Where to resume a pause that began at [pausedAt]: there, once it lasted [THRESHOLD_MS]; null to resume as it is. */
    fun resumeFrom(pausedAt: Long?, now: Long): Long? = pausedAt?.takeIf { now - it >= THRESHOLD_MS }
}
