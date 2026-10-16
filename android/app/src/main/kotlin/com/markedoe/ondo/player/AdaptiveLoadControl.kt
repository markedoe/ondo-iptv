package com.markedoe.ondo.player

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import com.markedoe.ondo.core.playback.RebufferPolicy

/**
 * The automatic buffer: Media3's own [DefaultLoadControl], with a longer wait after each rebuffering once the
 * provider has stopped twice ([RebufferPolicy]). Media3 keeps deciding: it is asked as if the longer wait were its
 * own, so its rule for a buffer already full still lets playback start. Called on the player's thread.
 *
 * Every method the player calls is forwarded by hand: Kotlin's `by` delegation skips Java default methods, and
 * `LoadControl`'s defaults throw.
 */
@UnstableApi
class AdaptiveLoadControl(
    private val base: DefaultLoadControl,
    private val policy: RebufferPolicy,
    private val onRaised: () -> Unit,
) : LoadControl {
    override fun onPrepared(playerId: PlayerId) = base.onPrepared(playerId)

    override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection?>) =
        base.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = base.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = base.onReleased(playerId)

    override fun getAllocator(playerId: PlayerId): Allocator = base.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = base.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = base.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean = base.shouldContinueLoading(parameters)

    override fun shouldContinuePreloading(playerId: PlayerId, timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, bufferedDurationUs: Long): Boolean =
        base.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val wait = policy.waitUs(
            rebuffering = parameters.rebuffering,
            rebufferStartedAtMs = parameters.lastRebufferRealtimeMs.takeIf { it != C.TIME_UNSET },
            targetLiveOffsetUs = parameters.targetLiveOffsetUs.takeIf { it != C.TIME_UNSET },
            onRaised = onRaised,
        )
        if (wait == null || wait <= MEDIA3_WAIT_US) return base.shouldStartPlayback(parameters)
        // Media3 plays again from 2 s buffered: the buffered length is scaled so that this happens at `wait`.
        val scaled = parameters.bufferedDurationUs * MEDIA3_WAIT_US / wait
        return base.shouldStartPlayback(
            LoadControl.Parameters(
                parameters.playerId,
                parameters.timeline,
                parameters.mediaPeriodId,
                parameters.playbackPositionUs,
                scaled,
                parameters.playbackSpeed,
                parameters.playWhenReady,
                parameters.rebuffering,
                parameters.targetLiveOffsetUs,
                parameters.lastRebufferRealtimeMs,
            ),
        )
    }

    private companion object {
        const val MEDIA3_WAIT_US = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS * 1_000L
    }
}
