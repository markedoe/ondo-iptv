package com.markedoe.ondo.player

import androidx.media3.common.Player
import com.markedoe.ondo.core.playback.PlaybackBuffer
import com.markedoe.ondo.core.playback.PlaybackLog
import com.markedoe.ondo.core.playback.ProblemReport
import java.time.Instant
import java.util.Locale

/**
 * The playback log behind a problem report (More, Report a Problem…): what was opened, each address tried (its
 * extension only), when the picture moved, how long each seek took, the rebufferings, dropped frames, errors and
 * fallbacks. [PlayerActivity] forwards the player's events; no address, login or title goes in (`PlaybackLog`
 * scrubs every line). One reporter per opened title; [clock] gives milliseconds (`SystemClock.elapsedRealtime`).
 */
class PlaybackReporter(private val request: PlayRequest, private val clock: () -> Long) {
    /** What the player shows when the report is made. */
    data class PlayerSnapshot(
        val app: String,
        val system: String,
        val engine: String,
        val bufferInUse: PlaybackBuffer.Durations,
        val video: String? = null,
        val durationMs: Long? = null,
        val positionMs: Long? = null,
        val state: String,
        /** The player's counters (formats, decoded frames…), one line each. */
        val statistics: List<String> = emptyList(),
    )

    private val log = PlaybackLog()
    private val openedAt = clock()
    private var url: String? = null

    /** Since when the first picture is awaited (opening, Try Again), until it is shown. */
    private var pictureAwaitedSince: Long? = openedAt
    private var seekSince: Long? = null
    private var rebufferSince: Long? = null

    /** The current address played once: buffering after that is a rebuffering, not the start. */
    private var hasPlayed = false
    private var rebuffers = 0
    private var rebufferMs = 0L
    private var droppedFrames = 0
    private var bandwidthEstimate: Long? = null

    private val kind: String get() = request.kind ?: if (request.isLive) "live channel" else "film"

    init {
        val resume = if (!request.isLive && request.startPositionMs > 5_000) ", from ${request.startPositionMs / 1_000} s" else ""
        val buffer = if (request.bufferSeconds > 0) "${request.bufferSeconds} s" else "automatic"
        note("open $kind$resume; buffer $buffer")
    }

    fun note(event: String) = log.add(event, (clock() - openedAt) / 1_000.0)

    /** An address of the request is opened (the first one, a fallback or Try Again). */
    fun attempt(index: Int, url: String) {
        this.url = url
        hasPlayed = false
        seekSince = null
        rebufferSince = null
        val format = ProblemReport.container(url)?.let { " (.$it)" }.orEmpty()
        note("address ${index + 1} of ${request.urls.size}$format")
    }

    /** Try Again: the next picture is timed from now. */
    fun retry() {
        note("try again")
        pictureAwaitedSince = clock()
    }

    /** Media3's `onRenderedFirstFrame`: the start, or a seek, is over. */
    fun firstFrame() {
        val now = clock()
        pictureAwaitedSince?.let { note("picture moving after ${seconds(now - it)} s") }
        pictureAwaitedSince = null
        endSeek(now)
    }

    fun seek(fromMs: Long, toMs: Long) {
        endRebuffer()
        note("seek from ${seconds(fromMs)} to ${seconds(toMs)} s")
        seekSince = clock()
    }

    fun playbackState(state: Int, playWhenReady: Boolean) {
        when (state) {
            Player.STATE_BUFFERING ->
                if (playWhenReady && hasPlayed && seekSince == null && rebufferSince == null) rebufferSince = clock()
            Player.STATE_READY -> {
                endSeek(clock())
                endRebuffer()
            }
            Player.STATE_ENDED -> {
                endRebuffer()
                note("ended")
            }
            else -> endRebuffer()
        }
    }

    fun isPlaying(playing: Boolean) {
        if (playing) hasPlayed = true
    }

    /** A pause while the player waits for data ends that rebuffering: the viewer waits by choice from then on. */
    fun playWhenReady(playWhenReady: Boolean) {
        if (!playWhenReady) endRebuffer()
    }

    fun droppedFrames(count: Int, elapsedMs: Long) {
        droppedFrames += count
        note("$count video frames dropped in ${seconds(elapsedMs)} s")
    }

    fun bandwidth(bitsPerSecond: Long) {
        if (bitsPerSecond > 0) bandwidthEstimate = bitsPerSecond
    }

    /** A player error, its code name (`ERROR_CODE_IO_BAD_HTTP_STATUS`…) and the messages of its causes. */
    fun error(codeName: String, messages: List<String>) {
        endRebuffer()
        seekSince = null
        note("error $codeName" + messages.joinToString(" / ").takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty())
    }

    fun report(snapshot: PlayerSnapshot, generated: Instant = Instant.now()): String {
        val now = clock()
        val rebuffering = rebufferMs + (rebufferSince?.let { now - it } ?: 0)
        val statistics = snapshot.statistics + listOfNotNull(
            "Rebufferings: $rebuffers, ${seconds(rebuffering)} s" + if (rebufferSince != null) " (one under way)" else "",
            "Dropped video frames: $droppedFrames",
            bandwidthEstimate?.let { "Bandwidth estimate: ${megabits(it)}" },
        )
        val context = ProblemReport.Context(
            app = snapshot.app,
            system = snapshot.system,
            engine = snapshot.engine,
            liveBufferSetting = request.liveBuffer,
            vodBufferSetting = request.vodBuffer,
            bufferInUse = snapshot.bufferInUse,
            kind = kind,
            container = url?.let(ProblemReport::container),
            video = snapshot.video,
            duration = snapshot.durationMs?.takeUnless { request.isLive }?.let { it / 1_000.0 },
            position = snapshot.positionMs?.takeUnless { request.isLive }?.let { it / 1_000.0 },
            state = snapshot.state,
            statistics = statistics,
        )
        return ProblemReport(context, log, generated).text
    }

    private fun endSeek(now: Long) {
        val since = seekSince ?: return
        seekSince = null
        note("seek done after ${seconds(now - since)} s")
    }

    private fun endRebuffer() {
        val since = rebufferSince ?: return
        rebufferSince = null
        val duration = clock() - since
        rebuffers += 1
        rebufferMs += duration
        note("rebuffering for ${seconds(duration)} s" + bandwidthEstimate?.let { ", bandwidth estimate ${megabits(it)}" }.orEmpty())
    }

    companion object {
        fun seconds(milliseconds: Long): String = ProblemReport.number(milliseconds / 1_000.0)

        fun megabits(bitsPerSecond: Long): String = String.format(Locale.ROOT, "%.2f Mb/s", bitsPerSecond / 1_000_000.0)
    }
}
