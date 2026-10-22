package com.markedoe.ondo.player

/**
 * A live stream has no end: when its server closes it, the player opens it again, [max] times in a row at most (a
 * stream that played for [freshAfterMs] since the last time counts afresh); after that, the error shows.
 */
class LiveReopening(private val max: Int = 3, private val freshAfterMs: Long = 60_000) {
    private var count = 0
    private var lastAt = 0L

    /** A new attempt (another address, Try Again): the count starts again. */
    fun reset() {
        count = 0
    }

    /** True: open the stream again; false: show the error. */
    fun reopens(now: Long): Boolean {
        if (count > 0 && now - lastAt > freshAfterMs) count = 0
        if (count >= max) return false
        count += 1
        lastAt = now
        return true
    }
}
