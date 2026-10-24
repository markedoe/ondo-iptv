package com.markedoe.ondo.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import com.markedoe.ondo.core.playback.PlaybackBuffer
import com.markedoe.ondo.core.playback.RebufferPolicy

/**
 * The player's buffer: the length chosen in Settings, or automatic (Media3's own durations, and a longer wait once
 * the provider has stopped twice, [AdaptiveLoadControl]). Either way at most [targetBytes] ahead
 * ([PlaybackBuffer.targetBytes]): the picture starts once they are there, even before the chosen length. [onRaised]
 * runs on the player's thread when the longer wait starts, with its length in milliseconds.
 */
@UnstableApi
fun playerLoadControl(bufferSeconds: Int, isLive: Boolean, targetBytes: Int, onRaised: (Int) -> Unit): LoadControl {
    PlaybackBuffer.durations(bufferSeconds)?.let { buffer ->
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(buffer.minBufferMs, buffer.maxBufferMs, buffer.bufferForPlaybackMs, buffer.bufferForPlaybackAfterRebufferMs)
            .setTargetBufferBytes(targetBytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
    }
    val raisedMs = if (isLive) PlaybackBuffer.RAISED_LIVE_MS else PlaybackBuffer.RAISED_VOD_MS
    return AdaptiveLoadControl(DefaultLoadControl.Builder().setTargetBufferBytes(targetBytes).build(), RebufferPolicy(raisedMs * 1_000L)) { onRaised(raisedMs) }
}
