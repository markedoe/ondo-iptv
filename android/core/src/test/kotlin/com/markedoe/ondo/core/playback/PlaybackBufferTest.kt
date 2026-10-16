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
}
