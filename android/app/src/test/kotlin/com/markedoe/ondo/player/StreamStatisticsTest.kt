package com.markedoe.ondo.player

import com.markedoe.ondo.core.playback.PlaybackHealth
import com.markedoe.ondo.core.playback.PlaybackSample
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The figures of More, Stream Statistics, as they read. */
class StreamStatisticsTest {
    /** 30 s of a stream received at 1 MB/s (8 Mb/s) that never stops. */
    private fun smooth(): PlaybackHealth {
        val health = PlaybackHealth()
        var clock = 0.0
        while (clock <= 30) {
            health.add(PlaybackSample(clock, clock, (clock * 1_000_000).toLong(), (clock * 25).toLong(), 0, isPlaying = true))
            clock += 0.5
        }
        return health
    }

    @Test
    fun aSmoothStreamReadsInFull() {
        val statistics = StreamStatistics.of(smooth(), "ExoPlayer", "MKV", "1920×1080", Locale.US)
        assertEquals("ExoPlayer", statistics.engine)
        assertEquals("MKV", statistics.format)
        assertEquals("1920×1080", statistics.video)
        assertEquals("8.0 Mb/s", statistics.videoBitrate)
        assertEquals("8.0 Mb/s", statistics.received)
        assertEquals("100%", statistics.smoothness)
        assertEquals("0 · 0.0 s", statistics.freezes)
        assertEquals("0", statistics.lostPictures)
        assertEquals(PlaybackHealth.Verdict.SMOOTH, statistics.verdict)
    }

    /** Numbers follow the app's language: a decimal comma in French. */
    @Test
    fun numbersFollowTheLanguage() {
        val statistics = StreamStatistics.of(smooth(), "ExoPlayer", null, null, Locale.FRANCE)
        assertEquals("8,0 Mb/s", statistics.videoBitrate)
        assertEquals("0 · 0,0 s", statistics.freezes)
        assertTrue(statistics.smoothness, statistics.smoothness.startsWith("100"))
    }

    /** Before anything is measured: dashes, and the panel says it is measuring. */
    @Test
    fun nothingMeasuredYetShowsDashes() {
        val statistics = StreamStatistics.of(PlaybackHealth(), "ExoPlayer", null, null, Locale.US)
        assertEquals("–", statistics.videoBitrate)
        assertEquals("–", statistics.received)
        assertEquals("–", statistics.smoothness)
        assertEquals("0 · 0.0 s", statistics.freezes)
        assertEquals(PlaybackHealth.Verdict.MEASURING, statistics.verdict)
    }

    /** A freeze going on is counted with its length so far. */
    @Test
    fun aFreezeGoingOnIsCounted() {
        val health = smooth()
        // The picture stops at 30 s while the data stops too.
        var clock = 30.5
        while (clock <= 33) {
            health.add(PlaybackSample(clock, 30.0, 30_000_000, 750, 0, isPlaying = true))
            clock += 0.5
        }
        val statistics = StreamStatistics.of(health, "ExoPlayer", null, null, Locale.US)
        assertEquals("1 · 2.5 s", statistics.freezes)
        assertEquals(PlaybackHealth.Verdict.NETWORK, statistics.verdict)
    }
}
