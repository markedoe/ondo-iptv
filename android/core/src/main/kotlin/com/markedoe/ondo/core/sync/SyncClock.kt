package com.markedoe.ondo.core.sync

/**
 * This device's hybrid logical clock (docs/SYNC.md, "Clocks"): every time it gives a change of its own is later than
 * every time it gave or saw before, whatever its wall clock says. A box whose clock reads 1970, or hours behind,
 * thus dates a change it makes after what it has seen; and sync never moves a time once it is stored.
 *
 * [last] is the latest time given or seen; [stamp] gives the next one, [observe] takes in the times received. The wall
 * clock is passed in each time (milliseconds since 1970). Thread-safe.
 */
class SyncClock(last: Long = Long.MIN_VALUE) {
    @Volatile
    var last: Long = last
        private set

    /** The time of a change made now: the wall clock, or just after [last] when that is later. */
    @Synchronized
    fun stamp(wall: Long): Long = maxOf(wall, saturated(last, 1)).also { last = it }

    /** Now on this clock, without giving a time: the later of the wall clock and [last] (to compare with, prune by). */
    fun now(wall: Long): Long = maxOf(wall, last)

    /** Takes in a time stored here before (this device's own data, read at launch): no later than it from now on. */
    @Synchronized
    fun advance(to: Long?) {
        if (to != null && to > last) last = to
    }

    /**
     * Takes in a time received from another device, unless it is more than [MAX_AHEAD_MS] after [wall] (then the
     * record is left out too, [SyncSnapshot.withoutRecordsAhead]): one device far ahead does not carry the others along.
     */
    fun observe(time: Long?, wall: Long) {
        if (time != null && time <= latestAccepted(wall)) advance(time)
    }

    /** [observe] for each time of [snapshot]: call it with what is left of a received snapshot once filtered. */
    fun observe(snapshot: SyncSnapshot, wall: Long) = observe(snapshot.times().filter { it <= latestAccepted(wall) }.maxOrNull(), wall)

    companion object {
        /** How far after the receiver's wall clock a time received may be (docs/SYNC.md, "Clocks"): one day. */
        const val MAX_AHEAD_MS = 86_400_000L

        /**
         * 2024-01-01 (UTC): a wall clock before this is not set (a box before it reaches a time server, at 1970). Such a
         * device has no idea of the time, so it leaves nothing out of what it receives.
         */
        const val EARLIEST_WALL_MS = 1_704_067_200_000L

        fun isSet(wall: Long): Boolean = wall >= EARLIEST_WALL_MS

        /** The latest time a device whose wall clock reads [wall] takes from another one; any time when its clock is not set. */
        fun latestAccepted(wall: Long): Long = if (isSet(wall)) saturated(wall, MAX_AHEAD_MS) else Long.MAX_VALUE
    }
}
