package com.markedoe.ondo.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The sleep timer pauses the player when its time is up, once; another choice starts it again, Off stops it. */
@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {
    private val start = 1_700_000_000_000L

    @Test
    fun thePlayerPausesOnceTheTimeIsUp() = runTest {
        var pauses = 0
        val timer = SleepTimer(this, now = { start }) { pauses++ }
        timer.set(30)
        assertEquals(30, timer.minutes)
        assertEquals(start + 30 * 60_000L, timer.endsAt)
        advanceTimeBy(30 * 60_000L - 1)
        runCurrent()
        assertEquals(0, pauses)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, pauses)
        // Done: the panel says Off again.
        assertNull(timer.minutes)
        assertNull(timer.endsAt)
        advanceTimeBy(120 * 60_000L)
        runCurrent()
        assertEquals(1, pauses)
    }

    @Test
    fun anotherChoiceStartsAgainAndOffStops() = runTest {
        var pauses = 0
        var now = start
        val timer = SleepTimer(this, now = { now }) { pauses++ }
        timer.set(15)
        advanceTimeBy(10 * 60_000L)
        now += 10 * 60_000L
        // 45 minutes from now, not from the first choice.
        timer.set(45)
        assertEquals(now + 45 * 60_000L, timer.endsAt)
        advanceTimeBy(44 * 60_000L)
        runCurrent()
        assertEquals("the first timer is gone", 0, pauses)
        timer.set(null)
        assertNull(timer.endsAt)
        advanceTimeBy(60 * 60_000L)
        runCurrent()
        assertEquals(0, pauses)
    }
}
