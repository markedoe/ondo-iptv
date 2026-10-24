package com.markedoe.ondo.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The player's buffer stops growing at the bytes the app's memory holds, whatever length is chosen in Settings: a 4K
 * film fills Media3's own 144 MB in seconds, more than a TV gives the app.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlayerLoadControlTest {
    private val playerId = PlayerId("test")
    private val timeline = SinglePeriodTimeline(7_200_000_000, true, false, false, null, MediaItem.fromUri("https://example.com/film.mkv"))
    private val targetBytes = 20 * 1024 * 1024

    private fun parameters(bufferedMs: Long) = LoadControl.Parameters(
        playerId, timeline, MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)), 0, bufferedMs * 1_000, 1f, true, false, C.TIME_UNSET, C.TIME_UNSET,
    )

    /** The buffer filled up to [bytes], as the player's loading does. */
    private fun LoadControl.filledWith(bytes: Int) = apply {
        onPrepared(playerId)
        onTracksSelected(parameters(0), TrackGroupArray.EMPTY, emptyArray())
        val allocator = getAllocator(playerId)
        repeat(bytes / allocator.individualAllocationLength) { allocator.allocate() }
    }

    @Test
    fun aChosenLengthStopsLoadingAtTheMemoryLimit() {
        val control = playerLoadControl(bufferSeconds = 10, isLive = false, targetBytes = targetBytes) {}
        assertTrue("under the limit, loading goes on", control.filledWith(targetBytes / 2).shouldContinueLoading(parameters(3_000)))
        val full = playerLoadControl(bufferSeconds = 10, isLive = false, targetBytes = targetBytes) {}.filledWith(targetBytes)
        assertFalse("at the limit, loading waits, even under 15 s", full.shouldContinueLoading(parameters(3_000)))
        assertTrue("and the picture starts without the 10 s, which would not fit", full.shouldStartPlayback(parameters(3_000)))
    }

    @Test
    fun automaticStopsLoadingAtTheMemoryLimit() {
        val full = playerLoadControl(bufferSeconds = 0, isLive = false, targetBytes = targetBytes) {}.filledWith(targetBytes)
        assertFalse(full.shouldContinueLoading(parameters(3_000)))
        assertTrue(full.shouldStartPlayback(parameters(500)))
    }
}
