package com.markedoe.ondo.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackBufferTest {
    @Test
    fun automaticKeepsThePlayersOwnDurations() {
        assertNull(PlaybackBuffer.durations(0))
        assertNull(PlaybackBuffer.durations(-3), "an unexpected value in the settings file is automatic")
    }

    @Test
    fun aChosenLengthIsWaitedForAtTheStartAndAfterRebuffering() {
        assertEquals(PlaybackBuffer.Durations(minBufferMs = 15_000, maxBufferMs = 50_000, bufferForPlaybackMs = 1_000, bufferForPlaybackAfterRebufferMs = 1_000), PlaybackBuffer.durations(1))
        assertEquals(PlaybackBuffer.Durations(15_000, 50_000, 5_000, 5_000), PlaybackBuffer.durations(5))
        assertEquals(PlaybackBuffer.Durations(15_000, 50_000, 10_000, 10_000), PlaybackBuffer.durations(10))
        // Longer than the usual minimum: the minimum follows, so that the player never waits for more than it keeps.
        assertEquals(PlaybackBuffer.Durations(20_000, 50_000, 20_000, 20_000), PlaybackBuffer.durations(20))
        assertEquals(PlaybackBuffer.Durations(60_000, 60_000, 60_000, 60_000), PlaybackBuffer.durations(60))
    }

    /** Media3's DefaultLoadControl.Builder throws unless forPlayback ≤ min, afterRebuffer ≤ min ≤ max. */
    @Test
    fun everyChoiceIsAcceptedByThePlayer() {
        assertEquals(listOf(0, 1, 2, 3, 5, 10), PlaybackBuffer.CHOICES)
        for (seconds in PlaybackBuffer.CHOICES + listOf(15, 50, 120)) {
            val durations = PlaybackBuffer.durations(seconds) ?: continue
            with(durations) {
                assertTrue(bufferForPlaybackMs in 0..minBufferMs, "$seconds s: $this")
                assertTrue(bufferForPlaybackAfterRebufferMs in 0..minBufferMs, "$seconds s: $this")
                assertTrue(minBufferMs <= maxBufferMs, "$seconds s: $this")
            }
        }
    }

    @Test
    fun theSettingFollowsWhatIsPlayed() {
        assertEquals(3, PlaybackBuffer.setting(isLive = true, live = 3, vod = 5))
        assertEquals(5, PlaybackBuffer.setting(isLive = false, live = 3, vod = 5))
    }

    @Test
    fun automaticWaitsLongerOnceTheProviderHasStoppedTwice() {
        var raised = 0
        val policy = RebufferPolicy(raisedUs = 5_000_000)
        // The start and seeks are not rebufferings.
        assertNull(policy.waitUs(rebuffering = false, rebufferStartedAtMs = null, targetLiveOffsetUs = null) { raised++ })
        // The first rebuffering keeps the player's own wait, however often the player asks.
        assertNull(policy.waitUs(true, 1_000, null) { raised++ })
        assertNull(policy.waitUs(true, 1_000, null) { raised++ })
        assertEquals(1, policy.rebufferings)
        // The second one, and every one after, waits longer.
        assertEquals(5_000_000L, policy.waitUs(true, 9_000, null) { raised++ })
        assertEquals(5_000_000L, policy.waitUs(true, 9_000, null) { raised++ })
        assertEquals(5_000_000L, policy.waitUs(true, 20_000, null) { raised++ })
        assertTrue(policy.isRaised)
        assertEquals(1, raised, "noted once")
        assertNull(policy.waitUs(false, null, null), "a seek after that keeps the player's wait")
    }

    @Test
    fun aLiveChannelNeverWaitsForMoreThanItCanBuffer() {
        val policy = RebufferPolicy(raisedUs = PlaybackBuffer.RAISED_LIVE_MS * 1_000L)
        policy.waitUs(true, 1_000, 30_000_000)
        assertEquals(6_000_000L, policy.waitUs(true, 2_000, 30_000_000))
        // Four seconds from the live edge: two seconds at most.
        assertEquals(2_000_000L, policy.waitUs(true, 3_000, 4_000_000))
    }

    /**
     * The player keeps its buffer in the app's memory: half of what is left when it starts, never Media3's own 144 MB
     * on a TV that gives the app 128 MB (the picture stuttered, then Android stopped the app).
     */
    @Test
    fun theBufferFitsInTheMemoryTheAppHasLeft() {
        val mb = 1024L * 1024
        assertEquals((34 * mb).toInt(), PlaybackBuffer.targetBytes(maxHeapBytes = 128 * mb, usedHeapBytes = 60 * mb))
        assertTrue(PlaybackBuffer.targetBytes(128 * mb, 0) < 128 * mb, "never the whole memory")
        assertEquals(PlaybackBuffer.MAX_TARGET_BYTES, PlaybackBuffer.targetBytes(512 * mb, 50 * mb), "Media3's own on a roomy device")
        assertEquals(PlaybackBuffer.MIN_TARGET_BYTES, PlaybackBuffer.targetBytes(128 * mb, 125 * mb), "a few seconds whatever happens")
        assertEquals(144_179_200, PlaybackBuffer.MAX_TARGET_BYTES)
    }
}
