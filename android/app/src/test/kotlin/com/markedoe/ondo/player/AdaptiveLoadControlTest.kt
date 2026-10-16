package com.markedoe.ondo.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.markedoe.ondo.core.playback.PlaybackBuffer
import com.markedoe.ondo.core.playback.RebufferPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Media3 itself answers, with the longer wait once the provider has stopped twice. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class AdaptiveLoadControlTest {
    private val playerId = PlayerId("test")
    private var raised = 0
    private val control = AdaptiveLoadControl(DefaultLoadControl(), RebufferPolicy(PlaybackBuffer.RAISED_VOD_MS * 1_000L)) { raised++ }
        .apply { onPrepared(playerId) }

    /** A film from a provider (not a local file, which Media3 buffers differently). */
    private val timeline = SinglePeriodTimeline(7_200_000_000, true, false, false, null, MediaItem.fromUri("https://example.com/film.mkv"))

    private fun parameters(bufferedMs: Long, rebuffering: Boolean, rebufferAtMs: Long = C.TIME_UNSET, liveOffsetMs: Long? = null) =
        LoadControl.Parameters(
            playerId,
            timeline,
            MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)),
            0,
            bufferedMs * 1_000,
            1f,
            true,
            rebuffering,
            liveOffsetMs?.let { it * 1_000 } ?: C.TIME_UNSET,
            rebufferAtMs,
        )

    @Test
    fun startsAndResumesLikeMedia3UntilTheSecondRebuffering() {
        // Start: Media3's 1 s.
        assertFalse(control.shouldStartPlayback(parameters(900, rebuffering = false)))
        assertTrue(control.shouldStartPlayback(parameters(1_000, rebuffering = false)))
        // First rebuffering: Media3's 2 s.
        assertFalse(control.shouldStartPlayback(parameters(1_900, rebuffering = true, rebufferAtMs = 10_000)))
        assertTrue(control.shouldStartPlayback(parameters(2_000, rebuffering = true, rebufferAtMs = 10_000)))
        assertEquals(0, raised)
    }

    @Test
    fun waitsFiveSecondsAfterEachLaterRebuffering() {
        control.shouldStartPlayback(parameters(2_000, rebuffering = true, rebufferAtMs = 10_000))
        assertFalse(control.shouldStartPlayback(parameters(2_000, rebuffering = true, rebufferAtMs = 30_000)))
        assertFalse(control.shouldStartPlayback(parameters(4_900, rebuffering = true, rebufferAtMs = 30_000)))
        assertTrue(control.shouldStartPlayback(parameters(5_000, rebuffering = true, rebufferAtMs = 30_000)))
        assertEquals(1, raised)
        // A seek is not a rebuffering: back to Media3's 1 s.
        assertTrue(control.shouldStartPlayback(parameters(1_000, rebuffering = false)))
    }

    /** `LoadControl`'s default methods throw: each one the player calls must reach Media3's. */
    @Test
    fun everyCallOfThePlayerReachesMedia3() {
        val parameters = parameters(10_000, rebuffering = false)
        control.onTracksSelected(parameters, TrackGroupArray.EMPTY, emptyArray())
        assertTrue(control.shouldContinueLoading(parameters(0, rebuffering = false)))
        control.shouldContinuePreloading(playerId, timeline, parameters.mediaPeriodId, 0)
        assertEquals(DefaultLoadControl.DEFAULT_BACK_BUFFER_DURATION_MS * 1_000L, control.getBackBufferDurationUs(playerId))
        control.retainBackBufferFromKeyframe(playerId)
        control.getAllocator(playerId)
        control.onStopped(playerId)
        control.onReleased(playerId)
    }

    @Test
    fun aLiveChannelWaitsAtMostHalfItsDistanceFromTheLiveEdge() {
        control.shouldStartPlayback(parameters(2_000, rebuffering = true, rebufferAtMs = 10_000, liveOffsetMs = 6_000))
        assertTrue(control.shouldStartPlayback(parameters(3_000, rebuffering = true, rebufferAtMs = 30_000, liveOffsetMs = 6_000)))
    }
}
