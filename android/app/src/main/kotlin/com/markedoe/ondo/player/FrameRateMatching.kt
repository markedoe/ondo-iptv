package com.markedoe.ondo.player

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A film on a TV at its own frame rate (24, 25, 50 frames a second…): the screen moves to a refresh rate that is a
 * multiple of it, so that the picture moves evenly. Android blanks the screen a moment for a switch that is not
 * seamless, and makes it only if the viewer allowed it (Match content frame rate, in the TV's settings): the player
 * then waits for the screen, as Google recommends for movies. Live channels keep the screen as it is: each channel
 * change would blank it.
 */
object FrameRateMatching {
    enum class Plan {
        /** Nothing to ask: a live channel, or the frame rate is not known. */
        NONE,

        /** The frame rate is told to Android, which changes the screen only where it is seamless. */
        SEAMLESS,

        /** The screen switches: the player pauses until it has. */
        SWITCH,
    }

    /** How long the player waits for the screen at most, and once it switched (it may still be dark). */
    const val WAIT_MS = 5_000L
    const val SETTLE_MS = 1_500L

    /**
     * What to do for a video of [frameRate] frames a second, [long] (a film, an episode, a replay), on a screen at
     * [refreshRate] that offers [refreshRates] at its resolution; [viewerAllows]: Match content frame rate is on.
     */
    fun plan(frameRate: Float, long: Boolean, refreshRate: Float, refreshRates: List<Float>, viewerAllows: Boolean): Plan {
        if (!long || frameRate < 1f) return Plan.NONE
        if (fits(refreshRate, frameRate) || !viewerAllows || refreshRates.none { fits(it, frameRate) }) return Plan.SEAMLESS
        return Plan.SWITCH
    }

    /** Whether a screen at [refreshRate] shows [frameRate] evenly: a whole number of refreshes per frame. */
    fun fits(refreshRate: Float, frameRate: Float): Boolean {
        if (frameRate < 1f || refreshRate < frameRate * 0.99f) return false
        val ratio = refreshRate / frameRate
        // 23.976 frames on 24 Hz, 59.94 Hz on 60 frames: within a thousandth.
        return abs(ratio - ratio.roundToInt()) <= 0.002f * ratio
    }
}
