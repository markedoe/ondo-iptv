package com.markedoe.ondo.player

import com.markedoe.ondo.player.FrameRateMatching.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A film at 24 frames a second on a 60 Hz TV: the screen moves to 24 Hz, when the viewer allows it. */
class FrameRateMatchingTest {
    private val tv = listOf(60f, 59.94f, 50f, 30f, 25f, 24f, 23.976f)

    @Test
    fun aScreenFitsAFrameRateItRefreshesAWholeNumberOfTimesPerFrame() {
        assertTrue(FrameRateMatching.fits(60f, 30f))
        assertTrue(FrameRateMatching.fits(50f, 25f))
        assertTrue(FrameRateMatching.fits(24f, 23.976f))
        assertTrue(FrameRateMatching.fits(120f, 24f))
        assertTrue(FrameRateMatching.fits(59.94f, 29.97f))
        assertFalse("judder", FrameRateMatching.fits(60f, 24f))
        assertFalse(FrameRateMatching.fits(60f, 25f))
        assertFalse(FrameRateMatching.fits(50f, 24f))
        assertFalse("slower than the video", FrameRateMatching.fits(24f, 50f))
    }

    @Test
    fun aFilmThatDoesNotFitSwitchesTheScreenWhenTheViewerAllowsIt() {
        assertEquals(Plan.SWITCH, FrameRateMatching.plan(23.976f, long = true, refreshRate = 60f, refreshRates = tv, viewerAllows = true))
        assertEquals(Plan.SWITCH, FrameRateMatching.plan(25f, long = true, refreshRate = 60f, refreshRates = tv, viewerAllows = true))
        assertEquals("not allowed: seamless only", Plan.SEAMLESS, FrameRateMatching.plan(23.976f, long = true, refreshRate = 60f, refreshRates = tv, viewerAllows = false))
        assertEquals("already even", Plan.SEAMLESS, FrameRateMatching.plan(30f, long = true, refreshRate = 60f, refreshRates = tv, viewerAllows = true))
        assertEquals("no mode for it", Plan.SEAMLESS, FrameRateMatching.plan(24f, long = true, refreshRate = 60f, refreshRates = listOf(60f), viewerAllows = true))
    }

    @Test
    fun aLiveChannelOrAnUnknownRateAsksNothing() {
        assertEquals(Plan.NONE, FrameRateMatching.plan(25f, long = false, refreshRate = 60f, refreshRates = tv, viewerAllows = true))
        assertEquals(Plan.NONE, FrameRateMatching.plan(-1f, long = true, refreshRate = 60f, refreshRates = tv, viewerAllows = true))
    }
}
