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

    /**
     * Now on this clock, without giving a time: the later of the wall clock and [last], but never more than
     * [MAX_AHEAD_MS] after a wall clock that is set, nor after [EARLIEST_WALL_MS] when it is not. What prunes deletions
     * and dates sessions and learned deletions: one time far ahead cannot make every deletion look old at once.
     */
    fun now(wall: Long): Long = maxOf(wall, minOf(last, if (isSet(wall)) saturated(wall, MAX_AHEAD_MS) else EARLIEST_WALL_MS))

    /** Takes in a time stored here before (this device's own data, read at launch): no later than it from now on. */
    @Synchronized
    fun advance(to: Long?) {
        if (to != null && to > last) last = to
    }

    /**
     * The latest time this device takes from another one (docs/SYNC.md, "Clocks"): [MAX_AHEAD_MS] after its wall clock
     * when that is set; else [UNSET_LATEST_MS], or [UNSET_AHEAD_MS] after [last] when that is later.
     */
    fun latestAccepted(wall: Long): Long =
        if (isSet(wall)) saturated(wall, MAX_AHEAD_MS) else maxOf(UNSET_LATEST_MS, saturated(last, UNSET_AHEAD_MS))

    /** Takes in a time received from another device, unless it is after [latestAccepted]. */
    fun observe(time: Long?, wall: Long) {
        if (time != null && time <= latestAccepted(wall)) advance(time)
    }

    /**
     * What this device takes of a snapshot received: without the records and deletions dated after [latestAccepted]
     * ([SyncSnapshot.withoutRecordsAfter]); the latest time left is taken in. No time is moved.
     */
    fun receive(snapshot: SyncSnapshot, wall: Long): SyncSnapshot =
        snapshot.withoutRecordsAfter(latestAccepted(wall)).also { advance(it.latestTime()) }

    companion object {
        /** How far after the receiver's wall clock a time received may be (docs/SYNC.md, "Clocks"): one day. */
        const val MAX_AHEAD_MS = 86_400_000L

        /** 2024-01-01 (UTC): a wall clock before this is not set (a box before it reaches a time server, at 1970). */
        const val EARLIEST_WALL_MS = 1_704_067_200_000L

        /** 2029-01-01 (UTC): the latest time a device whose clock is not set takes in, unless it holds later ones. */
        const val UNSET_LATEST_MS = 1_861_920_000_000L

        /** Two years: how far after the latest time it holds a device whose clock is not set takes times in. */
        const val UNSET_AHEAD_MS = 2 * 365 * MAX_AHEAD_MS

        fun isSet(wall: Long): Boolean = wall >= EARLIEST_WALL_MS
    }
}
