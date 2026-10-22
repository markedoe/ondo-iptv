package com.markedoe.ondo.core.playback

import kotlin.math.max

/** What the player measures every half second: cumulative counters, as the player reports them. */
data class PlaybackSample(
    /** Seconds since playback was opened (wall clock). */
    val clock: Double,
    /** Position of the picture on screen, in seconds of media; null while nothing plays. */
    val mediaTime: Double?,
    /** Bytes received from the network so far. */
    val receivedBytes: Long,
    val displayedPictures: Long,
    val lostPictures: Long,
    /** The player is meant to be playing (not paused, not opening). */
    val isPlaying: Boolean,
)

/**
 * Follows a playback to tell freezes apart: the network or the provider sending less than the video needs (too
 * slowly, or stopping), or the device not keeping up (data on time, pictures lost). Fed with [PlaybackSample]s,
 * shown in the player's Stream Statistics. Same rules as the Apple apps (`PlaybackHealth.swift`), without the parts
 * for libVLC: Media3's position is the picture's, so a stall of the position is a freeze from its first second.
 */
class PlaybackHealth {
    enum class Verdict {
        /** Too early to say (the first seconds of playback). */
        MEASURING,

        /** No freeze and no picture lost. */
        SMOOTH,

        /** Freezes while the data came in too slowly or stopped: network or provider. */
        NETWORK,

        /** Freezes or lost pictures while the data came in on time: the player or the device. */
        PLAYER,
    }

    /** A freeze: the picture stopped for [duration] seconds. */
    data class Freeze(
        val start: Double,
        val duration: Double,
        /** Bytes per second received during the freeze and the 5 seconds before it. */
        val receivedRate: Double,
        /** The longest time without a single byte received, during the freeze and the 3 seconds before it. */
        val longestGap: Double,
        /** The picture stopped while the position (the sound) went on: the video decoder did not keep up. */
        val picturesOnly: Boolean = false,
    )

    private class Entry(
        val sample: PlaybackSample,
        /** The picture has moved since the stream started (or since the last seek): waits before it are not freezes. */
        val started: Boolean,
    )

    private val recorded = mutableListOf<Freeze>()
    val freezes: List<Freeze> get() = recorded
    private val entries = ArrayDeque<Entry>()

    /** When the position stopped moving, while it should be playing: the start of the current freeze. */
    private var stillSince: PlaybackSample? = null

    /** The picture moves forward since the stream started or since the last seek ([restart]). */
    var hasMoved = false
        private set

    /** A seek was made and the position has not jumped to where it lands yet. */
    private var landing = false

    /** When the picture stopped while the position went on. */
    private var picturesStillSince: Double? = null

    fun add(sample: PlaybackSample) {
        entries.lastOrNull()?.sample?.let { last ->
            if (sample.receivedBytes < last.receivedBytes) {
                // A new connection: everything starts again.
                entries.clear()
                stillSince = null
                hasMoved = false
                landing = false
                picturesStillSince = null
            }
        }
        val last = entries.lastOrNull()?.sample
        if (last != null) notePictures(sample, last)
        val moved = last != null &&
            ((sample.mediaTime ?: 0.0) != (last.mediaTime ?: 0.0) || sample.displayedPictures != last.displayedPictures)
        val now = sample.mediaTime
        val before = last?.mediaTime
        if (now != null && before != null && now != before) {
            if (landing) {
                // The jump to where the seek lands (a 10 s skip looks like 10 s played): the picture moves after it.
                landing = false
            } else if (isPlayed(now - before)) {
                hasMoved = true
            }
        }
        if (last != null) {
            if (sample.isPlaying && !moved && sample.mediaTime != null && hasMoved) {
                // From the first sample that found the picture still (it stopped since the previous one).
                if (stillSince == null) stillSince = sample
            } else {
                stillSince?.let { still ->
                    stillSince = null
                    freeze(still.clock, sample.clock)?.let(recorded::add)
                }
            }
        }
        entries.addLast(Entry(sample, hasMoved && sample.isPlaying && sample.mediaTime != null))
        // Two minutes are enough for the rates and the verdict.
        while (entries.first().sample.clock < sample.clock - WINDOW) entries.removeFirst()
    }

    /**
     * The picture against the position: still pictures while the position goes on are a freeze of the picture
     * alone, from the first sample that found them still; when the position stops too, that freeze ends.
     */
    private fun notePictures(sample: PlaybackSample, last: PlaybackSample) {
        val advanced = isPlayed((sample.mediaTime ?: 0.0) - (last.mediaTime ?: 0.0))
        if (sample.isPlaying && hasMoved && advanced && sample.displayedPictures > 0 && sample.displayedPictures == last.displayedPictures) {
            if (picturesStillSince == null) picturesStillSince = sample.clock
        } else {
            endPictureFreeze(sample.clock)
        }
    }

    private fun endPictureFreeze(end: Double) {
        val start = picturesStillSince ?: return
        picturesStillSince = null
        if (end - start < MINIMUM_FREEZE) return
        recorded += Freeze(start, end - start, receivedRate(start - 5, end), longestGap(start - 3, end), picturesOnly = true)
    }

    /** A seek: the wait until the picture moves again is not a freeze. */
    fun restart() {
        stillSince = null
        hasMoved = false
        landing = true
        picturesStillSince = null
    }

    /** How long the picture has stood still while playing, in seconds (0 when it moves). */
    val currentFreeze: Double
        get() {
            val still = stillSince ?: return 0.0
            val last = entries.lastOrNull() ?: return 0.0
            return last.sample.clock - still.clock
        }

    /** The picture is stopped right now, for at least [MINIMUM_FREEZE]. */
    val isFrozen: Boolean get() = currentFreeze >= MINIMUM_FREEZE

    val frozenSeconds: Double get() = recorded.sumOf { it.duration } + if (isFrozen) currentFreeze else 0.0

    private class Played(val wall: Double, val media: Double, val received: Double)

    /** While playing: seconds of wall clock, seconds of video shown, bytes received. A freeze going on is left out. */
    private val played: Played
        get() {
            var wall = 0.0
            var media = 0.0
            var received = 0.0
            val end = stillSince?.clock ?: Double.POSITIVE_INFINITY
            for (index in 1 until entries.size) {
                val previous = entries[index - 1]
                val current = entries[index]
                if (!previous.started || !current.started || current.sample.clock >= end) continue
                wall += current.sample.clock - previous.sample.clock
                val step = (current.sample.mediaTime ?: 0.0) - (previous.sample.mediaTime ?: 0.0)
                // A longer jump is a seek, not video shown.
                if (isPlayed(step)) media += step
                received += (current.sample.receivedBytes - previous.sample.receivedBytes).toDouble()
            }
            return Played(wall, media, received)
        }

    /**
     * Bytes per second the video needs: what was received per second of video shown, container and tracks not
     * played included (the network has to carry them all).
     */
    val contentRate: Double?
        get() = played.let { if (it.media >= 5) it.received / it.media else null }

    /** Seconds of video shown per second of playing: 1 when smooth, less when the picture stops or stutters. */
    val pace: Double?
        get() = played.let { if (it.wall >= 5) it.media / it.wall else null }

    /** Bytes per second received over the last [seconds]. */
    fun receivedRate(seconds: Double = 10.0): Double? {
        val last = entries.lastOrNull() ?: return null
        return receivedRate(last.sample.clock - seconds, last.sample.clock)
    }

    /** Pictures lost by the player, since the stream started. */
    val lostPictures: Long get() = entries.lastOrNull()?.sample?.lostPictures ?: 0

    /** Freezes, stutter and lost pictures of the last two minutes, explained. */
    val verdict: Verdict
        get() {
            val first = entries.firstOrNull()?.sample ?: return Verdict.MEASURING
            val last = entries.last().sample
            if (last.clock - first.clock < 10) return Verdict.MEASURING
            val played = played
            val lost = last.lostPictures - first.lostPictures
            // A player that counts no picture shown: 25 a second of video stand for them.
            val shown = if (last.displayedPictures > first.displayedPictures) {
                last.displayedPictures - first.displayedPictures
            } else {
                (played.media * 25).toLong()
            }
            val pace = if (played.wall >= 5) played.media / played.wall else null
            // Lost pictures point at the device when the data kept up: a player starved of data loses some too.
            if (lost > 0 && lost.toDouble() / max(shown + lost, 1L) > LOST_SHARE && (pace ?: 1.0) >= SMOOTH_PACE) return Verdict.PLAYER
            val recent = recorded.filter { it.start >= first.clock }.toMutableList()
            stillSince?.let { still -> freeze(still.clock, last.clock)?.let(recent::add) }
            if (recent.isEmpty()) {
                // Stutter without a long freeze and without lost pictures: the player waits for data that comes in
                // slower than the video plays (a device that does not keep up loses pictures instead).
                return if ((pace ?: 1.0) >= SMOOTH_PACE) Verdict.SMOOTH else Verdict.NETWORK
            }
            val needed = (if (played.media >= 5) played.received / played.media else null)?.takeIf { it > 0 } ?: return Verdict.NETWORK
            val starved = recent.count { it.longestGap >= DATA_GAP || it.receivedRate < needed * ENOUGH_DATA }
            return if (starved * 2 >= recent.size) Verdict.NETWORK else Verdict.PLAYER
        }

    private fun window(start: Double, end: Double): List<PlaybackSample> =
        entries.map { it.sample }.filter { it.clock in start..end }

    private fun receivedRate(start: Double, end: Double): Double {
        val window = window(start, end)
        val first = window.firstOrNull() ?: return 0.0
        val last = window.last()
        if (last.clock <= first.clock) return 0.0
        return (last.receivedBytes - first.receivedBytes) / (last.clock - first.clock)
    }

    /** The longest time between [start] and [end] without a byte received. */
    private fun longestGap(start: Double, end: Double): Double {
        var longest = 0.0
        var since: Double? = null
        window(start, end).zipWithNext { previous, current ->
            if (current.receivedBytes == previous.receivedBytes) {
                val gapStart = since ?: previous.clock
                since = gapStart
                longest = max(longest, current.clock - gapStart)
            } else {
                since = null
            }
        }
        return longest
    }

    /** The freeze a stall caused, if it lasted at least [MINIMUM_FREEZE]. */
    private fun freeze(start: Double, end: Double): Freeze? {
        val duration = end - start
        if (duration < MINIMUM_FREEZE) return null
        return Freeze(start, duration, receivedRate(start - 5, end), longestGap(start - 3, end))
    }

    companion object {
        /** A pause in the picture shorter than this is not a freeze. */
        const val MINIMUM_FREEZE = 0.7

        /** The data must come in at least this fraction of what the video needs. */
        const val ENOUGH_DATA = 0.97

        /** Nothing received for this long before or during a freeze: the provider stopped sending. */
        const val DATA_GAP = 1.0

        /** Below this pace (seconds of video per second while playing), the data does not keep up with the video. */
        const val SMOOTH_PACE = 0.97

        /** More pictures lost than this share while the data keeps up: the device does not decode in time. */
        const val LOST_SHARE = 0.02

        /** Seconds of samples kept for the rates and the verdict. */
        private const val WINDOW = 120.0

        /** A move of the position between two samples that is playback, not a seek. */
        private fun isPlayed(step: Double) = step > 0 && step <= 30
    }
}
