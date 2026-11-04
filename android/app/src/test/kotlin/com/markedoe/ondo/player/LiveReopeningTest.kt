package com.markedoe.ondo.player

import com.markedoe.ondo.HangGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LiveReopeningTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @Test
    fun aLiveStreamIsOpenedAgainThreeTimesInARowThenGivesUp() {
        val reopening = LiveReopening()
        repeat(3) { assertTrue(reopening.reopens(now = 1_000L + it)) }
        assertFalse(reopening.reopens(now = 1_010))
        // Try Again starts a new series.
        reopening.reset()
        assertTrue(reopening.reopens(now = 1_020))
    }

    @Test
    fun aStreamThatPlayedAMinuteSinceCountsAfresh() {
        val reopening = LiveReopening()
        repeat(3) { assertTrue(reopening.reopens(now = it * 1_000L)) }
        assertTrue(reopening.reopens(now = 2_000L + 61_000L))
    }
}
