package com.markedoe.ondo.player

import androidx.annotation.OptIn
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A side panel of the TV player, opened from its button. */
enum class TvPanel { SUBTITLES, AUDIO, SPEED, MORE }

/**
 * What the TV player shows over the video, and what it knows of the player for that ([refresh]). The controls show on
 * a key and go away [TIMEOUT_MS] after the last one while the video plays; paused, they stay. A peek shows the title and
 * where the video is, without the buttons, at the start and after a skip with the controls hidden: the remote's keys
 * keep their hidden-controls meaning meanwhile. The timers run on [scope] (the player's, on the main thread).
 */
@Stable
@OptIn(UnstableApi::class)
class TvControls(private val scope: CoroutineScope, private val onShownChange: (Boolean) -> Unit = {}) {
    /** The buttons are on screen and the remote moves through them. */
    var shown by mutableStateOf(false)
        private set

    var panel by mutableStateOf<TvPanel?>(null)
        private set

    /** The title and the progress, for a moment, without the buttons. */
    var peeking by mutableStateOf(false)
        private set

    /** The direction of the last skip made with the controls hidden (-1, 1), 0 for none: its icon shows meanwhile. */
    var skipped by mutableIntStateOf(0)
        private set

    /** Where the progress bar is being moved to, before the player goes there. */
    var scrubMs by mutableStateOf<Long?>(null)
        private set

    /** Counts the times the controls came on screen: each time, Play/Pause takes the focus. */
    var shows by mutableIntStateOf(0)
        private set

    // What the player is doing, kept up to date by [refresh].
    var positionMs by mutableLongStateOf(0)
        private set
    var bufferedMs by mutableLongStateOf(0)
        private set

    /** The length of the video (or of a live stream's window), null while unknown. */
    var durationMs by mutableStateOf<Long?>(null)
        private set
    var playWhenReady by mutableStateOf(false)
        private set
    var seekable by mutableStateOf(false)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    var tracks by mutableStateOf(Tracks.EMPTY)
        private set
    var trackParameters by mutableStateOf(TrackSelectionParameters.DEFAULT)
        private set

    /** The time of day, for the clock and a live programme's progress. */
    var clockMs by mutableLongStateOf(System.currentTimeMillis())
        private set

    /** Whether the controls may go away by themselves (the video plays); set by the player screen. */
    var playing: () -> Boolean = { playWhenReady }

    private var hideJob: Job? = null
    private var peekJob: Job? = null
    private var scrubJob: Job? = null

    fun refresh(player: Player) {
        positionMs = player.currentPosition.coerceAtLeast(0)
        bufferedMs = player.bufferedPosition.coerceAtLeast(0)
        durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        if (playWhenReady != player.playWhenReady) {
            playWhenReady = player.playWhenReady
            // Playing again: the controls go away after a while.
            if (playWhenReady) touch()
        }
        seekable = player.isCurrentMediaItemSeekable
        speed = player.playbackParameters.speed
        tracks = player.currentTracks
        trackParameters = player.trackSelectionParameters
        clockMs = System.currentTimeMillis()
    }

    /** The controls, with the focus on Play/Pause (or on [panel]); a panel already open stays (a media key). */
    fun show(panel: TvPanel? = null) {
        endPeek()
        if (panel != null || !shown) this.panel = panel
        if (!shown) {
            shown = true
            shows++
            onShownChange(true)
        }
        touch()
    }

    fun hide() {
        cancelScrub()
        panel = null
        hideJob?.cancel()
        endPeek()
        if (shown) {
            shown = false
            onShownChange(false)
        }
    }

    /** A key was pressed: the controls stay [TIMEOUT_MS] more. */
    fun touch() {
        hideJob?.cancel()
        if (!shown) return
        hideJob = scope.launch {
            delay(TIMEOUT_MS)
            if (panel == null && scrubMs == null && playing()) hide()
        }
    }

    fun open(panel: TvPanel) = show(panel)

    fun closePanel() {
        panel = null
        touch()
    }

    /** Back: closes the panel, else drops the bar's move, else hides the controls; false when nothing was shown. */
    fun back(): Boolean {
        when {
            panel != null -> closePanel()
            scrubMs != null -> cancelScrub()
            shown -> hide()
            else -> return false
        }
        return true
    }

    /** The title and the progress for [ms], without the buttons (nothing when the controls are shown). */
    fun peek(ms: Long = PEEK_MS, skip: Int = 0) {
        if (shown) return
        peeking = true
        skipped = skip
        peekJob?.cancel()
        peekJob = scope.launch {
            delay(ms)
            endPeek()
        }
    }

    private fun endPeek() {
        peekJob?.cancel()
        peeking = false
        skipped = 0
    }

    /** [byMs] forward (or back), within the video: the buttons and, with the controls hidden, Left and Right. */
    fun skip(player: Player, byMs: Long) {
        cancelScrub()
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val target = (player.currentPosition + byMs).coerceAtLeast(0).let { if (duration != null) it.coerceAtMost(duration) else it }
        player.seekTo(target)
        positionMs = target
        if (shown) touch() else peek(SKIP_PEEK_MS, if (byMs < 0) -1 else 1)
    }

    /**
     * Left or Right on the progress bar: the target moves, in larger steps while the key is held ([repeat] is the key's
     * repeat count), and the player goes there once the remote stops ([SCRUB_APPLY_MS]) or on OK ([commitScrub]): one
     * seek for a move, not one per press, each seek being a new request to the provider and a wait for the picture.
     */
    fun scrub(player: Player, forward: Boolean, repeat: Int) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val step = when {
            repeat < 5 -> 10_000L
            repeat < 15 -> 30_000L
            else -> maxOf(60_000L, duration / 60)
        }
        val from = scrubMs ?: player.currentPosition
        scrubMs = (from + if (forward) step else -step).coerceIn(0, duration)
        touch()
        scrubJob?.cancel()
        scrubJob = scope.launch {
            delay(SCRUB_APPLY_MS)
            commitScrub(player)
        }
    }

    fun commitScrub(player: Player) {
        val target = scrubMs ?: return
        scrubJob?.cancel()
        scrubMs = null
        player.seekTo(target)
        positionMs = target
        touch()
    }

    fun cancelScrub() {
        scrubJob?.cancel()
        scrubMs = null
    }

    companion object {
        /** How long the controls stay without a key while the video plays. */
        const val TIMEOUT_MS = 5_000L

        /** The title and the progress at the start. */
        const val PEEK_MS = 4_000L

        /** The progress after a skip with the controls hidden. */
        const val SKIP_PEEK_MS = 2_000L

        /** The pause after the last move on the progress bar before the player goes there. */
        const val SCRUB_APPLY_MS = 800L

        /** The buttons' and the remote's skip. */
        const val SKIP_MS = 10_000L
    }
}
