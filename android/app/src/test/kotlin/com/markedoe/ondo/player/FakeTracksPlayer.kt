package com.markedoe.ondo.player

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * A player with the given tracks, each in its own group, that selects them as ExoPlayer does with the track selection
 * parameters it is given: an override plays its track, a disabled type plays nothing.
 */
@OptIn(UnstableApi::class)
class FakeTracksPlayer(
    private val formats: List<Pair<Int, Format>>,
    /** The tracks playing before any choice (indices in [formats]). */
    private val initiallySelected: Set<Int>,
    private val durationMs: Long = 3_600_000,
    private val seekable: Boolean = true,
    var positionMs: Long = 600_000,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val groups = formats.mapIndexed { index, (_, format) -> TrackGroup("group-$index", format) }
    private var parameters = TrackSelectionParameters.DEFAULT
    private var playing = true
    private var speed = 1f

    /** Where the player was asked to go, in order. */
    val seeks = mutableListOf<Long>()

    val trackParameters: TrackSelectionParameters get() = parameters

    private fun selected(index: Int): Boolean {
        val type = formats[index].first
        if (type in parameters.disabledTrackTypes) return false
        val override = parameters.overrides.values.firstOrNull { it.type == type } ?: return index in initiallySelected
        return override.mediaTrackGroup == groups[index]
    }

    private fun tracks() = Tracks(
        groups.mapIndexed { index, group ->
            Tracks.Group(group, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(selected(index)))
        },
    )

    override fun getState(): State = State.Builder()
        .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
        .setPlaylist(
            listOf(
                MediaItemData.Builder("item")
                    .setMediaItem(MediaItem.fromUri("https://example.com/movie.mkv"))
                    .setTracks(tracks())
                    .setDurationUs(durationMs * 1_000)
                    .setIsSeekable(seekable)
                    .build(),
            ),
        )
        .setPlaybackState(Player.STATE_READY)
        .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        // Where it is, not where it would be by now: the clock of a test does not run as a video plays.
        .setContentPositionMs(PositionSupplier { positionMs })
        .setTrackSelectionParameters(parameters)
        .setPlaybackParameters(PlaybackParameters(speed))
        .build()

    override fun handleSetTrackSelectionParameters(trackSelectionParameters: TrackSelectionParameters): ListenableFuture<*> {
        parameters = trackSelectionParameters
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        playing = playWhenReady
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        this.positionMs = positionMs
        seeks += positionMs
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        speed = playbackParameters.speed
        return Futures.immediateVoidFuture()
    }

    companion object {
        fun audio(language: String?, mimeType: String, channels: Int, label: String? = null, roles: Int = 0) =
            C.TRACK_TYPE_AUDIO to Format.Builder().setSampleMimeType(mimeType).setLanguage(language).setChannelCount(channels).setLabel(label).setRoleFlags(roles).build()

        fun text(language: String?, label: String? = null, flags: Int = 0, roles: Int = 0) =
            C.TRACK_TYPE_TEXT to Format.Builder().setSampleMimeType("text/vtt").setLanguage(language).setLabel(label).setSelectionFlags(flags).setRoleFlags(roles).build()
    }
}
