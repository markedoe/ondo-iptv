package com.markedoe.ondo.player

import com.markedoe.ondo.player.LiveRecovery.Step
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the player does when a live stream stops: its server closed it (ended), or the player fell behind the
 * stream's window. A few tries on the same address, then the channel's other addresses, then the error.
 */
class LiveRecoveryTest {
    @Test
    fun anEndedStreamIsOpenedAgainThenTheOtherAddressesAreTried() {
        val recovery = LiveRecovery()
        repeat(3) { assertEquals(Step.OPEN_AGAIN, recovery.ended(now = 1_000L + it, nextAddress = true)) }
        // The server keeps closing it: the channel's other address (.ts or .m3u8, another version).
        assertEquals(Step.NEXT_ADDRESS, recovery.ended(now = 1_010, nextAddress = true))
        // A new address starts a new series.
        recovery.reset()
        assertEquals(Step.OPEN_AGAIN, recovery.ended(now = 1_020, nextAddress = false))
        repeat(2) { recovery.ended(now = 1_030L + it, nextAddress = false) }
        assertEquals(Step.ERROR, recovery.ended(now = 1_040, nextAddress = false))
    }

    /** A playlist whose numbering keeps jumping would send the player back to the live edge forever. */
    @Test
    fun fallingBehindTheWindowAgainAndAgainEndsInTheErrorOrTheNextAddress() {
        val recovery = LiveRecovery()
        repeat(3) { assertEquals(Step.BACK_TO_LIVE_EDGE, recovery.behindWindow(now = 1_000L + it, nextAddress = false)) }
        assertEquals(Step.ERROR, recovery.behindWindow(now = 1_010, nextAddress = false))
        val other = LiveRecovery()
        repeat(3) { other.behindWindow(now = 1_000L + it, nextAddress = true) }
        assertEquals(Step.NEXT_ADDRESS, other.behindWindow(now = 1_010, nextAddress = true))
        // A stream that played a minute since counts afresh.
        assertEquals(Step.BACK_TO_LIVE_EDGE, other.behindWindow(now = 1_010 + 61_000, nextAddress = true))
    }

    /** The two do not count against each other: a stream that fell behind once may still be opened again. */
    @Test
    fun endingAndFallingBehindAreCountedApart() {
        val recovery = LiveRecovery()
        repeat(3) { recovery.behindWindow(now = 1_000L + it, nextAddress = false) }
        assertEquals(Step.OPEN_AGAIN, recovery.ended(now = 1_010, nextAddress = false))
    }
}
