package com.markedoe.ondo.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Freezes told apart: the data came in too slowly (network or provider), or it was on time and the picture still
 * stopped or lost frames (player or device). Same cases as the Apple apps' PlaybackHealthTests.
 */
class PlaybackHealthTest {
    /** A 3 Mb/s stream (375 kB per second of media), sampled every half second. */
    private val bytesPerSecond = 375_000.0

    /**
     * Plays [seconds] of wall clock; [moving] says whether the picture moves at t, [receiving] the share of the
     * bitrate received at t, [lost] the pictures lost so far.
     */
    private fun play(
        seconds: Double,
        moving: (Double) -> Boolean = { true },
        receiving: (Double) -> Double = { 1.0 },
        lost: (Double) -> Long = { 0 },
    ): PlaybackHealth {
        val health = PlaybackHealth()
        var media = 0.0
        var received = 0.0
        var shown = 0L
        var clock = 0.0
        while (clock <= seconds) {
            if (moving(clock)) {
                media += 0.5
                shown += 12
            }
            received += bytesPerSecond / 2 * receiving(clock)
            health.add(PlaybackSample(clock, media, received.toLong(), shown, lost(clock), isPlaying = true))
            clock += 0.5
        }
        return health
    }

    @Test
    fun aSmoothPlaybackHasNoFreeze() {
        val health = play(30.0)
        assertTrue(health.freezes.isEmpty())
        assertEquals(PlaybackHealth.Verdict.SMOOTH, health.verdict)
        assertEquals(bytesPerSecond, assertNotNull(health.contentRate), 1.0)
        assertEquals(bytesPerSecond, assertNotNull(health.receivedRate()), 1.0)
        assertEquals(1.0, assertNotNull(health.pace), 0.01)
    }

    @Test
    fun theFirstSecondsAreStillBeingMeasured() {
        assertEquals(PlaybackHealth.Verdict.MEASURING, play(5.0).verdict)
        assertEquals(PlaybackHealth.Verdict.MEASURING, PlaybackHealth().verdict)
    }

    /** The provider sends 60 % of what the video needs: the picture stops every so often. */
    @Test
    fun freezesWithTooLittleDataAreTheNetworks() {
        val health = play(40.0, moving = { t -> t !in 10.0..12.9 && t !in 25.0..28.9 }, receiving = { t -> if (t < 8) 1.0 else 0.6 })
        assertEquals(listOf(3.0, 4.0), health.freezes.map { it.duration })
        assertEquals(7.0, health.frozenSeconds)
        assertEquals(PlaybackHealth.Verdict.NETWORK, health.verdict)
    }

    /** The data keeps coming at the full rate, yet the picture stops: the player is to blame. */
    @Test
    fun freezesWithDataOnTimeAreThePlayers() {
        val health = play(120.0, moving = { t -> t !in 60.0..61.9 })
        assertEquals(1, health.freezes.size)
        assertEquals(PlaybackHealth.Verdict.PLAYER, health.verdict)
    }

    /** The provider sends slightly less than the video needs (95 %): the picture stops for a second every 20 s. */
    @Test
    fun aSlowProviderIsTheNetwork() {
        val health = play(100.0, moving = { t -> t % 20 < 19 }, receiving = { 0.95 })
        assertEquals(5, health.freezes.size)
        assertEquals(PlaybackHealth.Verdict.NETWORK, health.verdict)
    }

    /** The picture keeps moving but stutters (short stops, under the minimum freeze): data too slow for a film. */
    @Test
    fun stutterWithoutLongFreezesIsTheNetwork() {
        val health = play(60.0, moving = { t -> t % 5 != 0.0 }, receiving = { 0.9 })
        assertTrue(health.freezes.isEmpty())
        assertEquals(0.9, assertNotNull(health.pace), 0.02)
        assertEquals(PlaybackHealth.Verdict.NETWORK, health.verdict)
    }

    /** Waiting for the first picture (the player fills its buffer) and after a seek is not a freeze. */
    @Test
    fun theWaitBeforeTheFirstPictureAndAfterASeekIsNotAFreeze() {
        val health = PlaybackHealth()
        var clock = 0.0
        fun add(media: Double, received: Long) {
            health.add(PlaybackSample(clock, media, received, (media * 25).toLong(), 0, isPlaying = true))
            clock += 0.5
        }
        for (step in 0 until 10) add(0.0, step * 1_000L)
        for (step in 0 until 20) add(step / 2.0, 10_000L + step * 1_000L)
        health.restart()
        for (step in 0 until 6) add(600.0, 30_000L + step * 1_000L)
        for (step in 0 until 10) add(600 + step / 2.0, 36_000L + step * 1_000L)
        assertTrue(health.freezes.isEmpty())
        assertFalse(health.isFrozen)
        assertEquals(PlaybackHealth.Verdict.SMOOTH, health.verdict)
    }

    /**
     * A 10 s skip: the position jumps at once, then waits for the picture. Neither the jump (it is no video shown)
     * nor the wait (the picture had not moved yet) counts.
     */
    @Test
    fun aSkipIsNeitherPlaybackNorAFreeze() {
        val health = PlaybackHealth()
        var clock = 0.0
        var received = 0L
        fun add(media: Double) {
            received += 1_000
            health.add(PlaybackSample(clock, media, received, (media * 25).toLong(), 0, isPlaying = true))
            clock += 0.5
        }
        for (step in 0 until 20) add(step / 2.0)
        health.restart()
        repeat(8) { add(19.5) }
        for (step in 1..20) add(19.5 + step / 2.0)
        assertTrue(health.freezes.isEmpty())
        assertFalse(health.isFrozen)
        assertEquals(1.0, assertNotNull(health.pace), 0.05, "the jump is not counted as video shown")
        assertEquals(PlaybackHealth.Verdict.SMOOTH, health.verdict)
    }

    /** A provider sending 70 % of what the film needs: the player loses pictures too as it runs out. They are the data's. */
    @Test
    fun picturesLostWhileTheDataIsShortAreTheNetworks() {
        val health = play(60.0, moving = { t -> t % 5 >= 1.5 }, receiving = { 0.7 }, lost = { t -> t.toLong() })
        assertTrue(health.freezes.isNotEmpty())
        assertEquals(PlaybackHealth.Verdict.NETWORK, health.verdict)
    }

    /** Pictures dropped (the device cannot decode in time) without any freeze. */
    @Test
    fun lostPicturesAreThePlayers() {
        val health = play(30.0, lost = { t -> (t * 2).toLong() })
        assertTrue(health.freezes.isEmpty())
        assertEquals(60L, health.lostPictures)
        assertEquals(PlaybackHealth.Verdict.PLAYER, health.verdict)
    }

    /** A freeze still going on counts, and short hiccups (under 0.7 s) are not freezes. */
    @Test
    fun aFreezeInProgressAndShortHiccups() {
        val ongoing = play(30.0, moving = { t -> t < 24 }, receiving = { t -> if (t < 20) 1.0 else 0.0 })
        assertTrue(ongoing.isFrozen)
        assertEquals(6.0, ongoing.currentFreeze, 0.01)
        assertEquals(6.0, ongoing.frozenSeconds, 0.01)
        assertEquals(PlaybackHealth.Verdict.NETWORK, ongoing.verdict)
        val hiccup = play(30.0, moving = { t -> t != 12.0 })
        assertTrue(hiccup.freezes.isEmpty())
        assertFalse(hiccup.isFrozen)
    }

    /** A pause is no freeze: the picture is meant to stand still. */
    @Test
    fun aPauseIsNotAFreeze() {
        val health = PlaybackHealth()
        var clock = 0.0
        var media = 0.0
        repeat(30) {
            media += 0.5
            health.add(PlaybackSample(clock, media, (media * bytesPerSecond).toLong(), (media * 25).toLong(), 0, isPlaying = true))
            clock += 0.5
        }
        repeat(20) {
            health.add(PlaybackSample(clock, null, (media * bytesPerSecond).toLong(), (media * 25).toLong(), 0, isPlaying = false))
            clock += 0.5
        }
        assertTrue(health.freezes.isEmpty())
        assertFalse(health.isFrozen)
    }

    /** The sound goes on (the position moves) while the picture stands still: a freeze of the picture alone. */
    @Test
    fun aPictureThatStopsWhileTheSoundGoesOnIsAFreeze() {
        val health = PlaybackHealth()
        var clock = 0.0
        var media = 0.0
        var shown = 0L
        while (clock <= 60) {
            media += 0.5
            if (clock !in 30.0..32.9) shown += 12
            health.add(PlaybackSample(clock, media, (media * bytesPerSecond).toLong(), shown, 0, isPlaying = true))
            clock += 0.5
        }
        val freeze = health.freezes.single()
        assertTrue(freeze.picturesOnly)
        assertEquals(3.0, freeze.duration, 0.01)
        assertEquals(PlaybackHealth.Verdict.PLAYER, health.verdict, "the data came in on time")
    }

    /** A reconnection starts the counters again: the rates do not go negative. */
    @Test
    fun aReconnectionStartsOver() {
        val health = play(20.0)
        health.add(PlaybackSample(21.0, 0.0, 1_000, 0, 0, isPlaying = true))
        assertTrue((health.receivedRate() ?: 0.0) >= 0)
        assertEquals(PlaybackHealth.Verdict.MEASURING, health.verdict)
    }

    /** Only the last two minutes count: an old freeze leaves the verdict once it is out of the window. */
    @Test
    fun onlyTheLastTwoMinutesCount() {
        val health = play(200.0, moving = { t -> t !in 20.0..23.9 }, receiving = { t -> if (t in 15.0..23.9) 0.0 else 1.0 })
        assertEquals(1, health.freezes.size, "the freeze stays in the count")
        assertEquals(PlaybackHealth.Verdict.SMOOTH, health.verdict)
    }
}
