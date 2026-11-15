package com.markedoe.ondo.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.markedoe.ondo.core.model.PictureRange
import com.markedoe.ondo.core.model.SpecSource
import com.markedoe.ondo.core.model.StreamSpecs
import com.markedoe.ondo.data.ItemKind

/**
 * What the player reads of a film's stream from its tracks (`MeasuredSpecs`, docs/VERSIONS.md): the picture's size, range
 * and codec, the sound's codec, channels and Atmos. Only what Media3 says: a range it does not give stays unknown, and a
 * 10-bit profile alone is no HDR; Atmos only when Media3 names it (E-AC-3 JOC), the provider's kept otherwise.
 */
@OptIn(UnstableApi::class)
internal object MeasuredTracks {
    /**
     * The playlist and the film whose stream [request] plays from its address [urlIndex], as the film's page reads them
     * (`MeasuredSpecs.key(playlist, movie id)`: "x5001" in an Xtream catalogue); null for a channel, a replay, an
     * episode, or another version's address (a fallback past the first).
     */
    fun recordedFilm(request: PlayRequest, urlIndex: Int): Pair<String, String>? {
        if (request.isLive || request.replayStart != null || urlIndex != 0) return null
        val parts = request.progressKey?.split('|') ?: return null
        if (parts.size != 4 || parts[1] != ItemKind.MOVIE.name || parts[0].isEmpty() || parts[2].isEmpty()) return null
        return parts[0] to parts[2]
    }

    /**
     * The specs of [tracks]: the best picture of the selected video group (an adaptive stream's highest rendition, not
     * the one playing now), and the stream's own sound: its default track, else the one of the most channels
     * ([StreamSpecs.measuredAudio]), never the viewer's choice of the moment; null when nothing is known yet.
     */
    fun specs(tracks: Tracks): StreamSpecs? {
        var video: Format? = null
        val sounds = ArrayList<Format>()
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    if (group.isTrackSupported(i)) sounds.add(group.getTrackFormat(i))
                }
            } else if (group.type == C.TRACK_TYPE_VIDEO && group.isSelected) {
                for (i in 0 until group.length) {
                    if (!group.isTrackSupported(i)) continue
                    val format = group.getTrackFormat(i)
                    val best = video
                    if (best == null || pixels(format) > pixels(best)) video = format
                }
            }
        }
        return of(video, sounds.getOrNull(audioIndex(sounds)))
    }

    /** The sound a stream offers first ([StreamSpecs.measuredAudio]) among [sounds]. */
    fun audioIndex(sounds: List<Format>): Int = StreamSpecs.measuredAudio(
        sounds.map { (it.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0 },
        sounds.map { format -> format.channelCount.takeIf { it > 0 } },
    )

    private fun pixels(format: Format): Long = maxOf(format.width, 0).toLong() * maxOf(format.height, 0)

    /** The specs of a video format and an audio format; null when neither says anything. */
    fun of(video: Format?, audio: Format?): StreamSpecs? {
        val range = video?.let { format ->
            when {
                StreamSpecs.isDolbyVision(format.sampleMimeType, format.codecs) || PlaybackDetails.isDolbyVision(format) -> PictureRange.DOLBY_VISION
                else -> when (format.colorInfo?.colorTransfer) {
                    C.COLOR_TRANSFER_ST2084 -> PictureRange.HDR10
                    C.COLOR_TRANSFER_HLG -> PictureRange.HLG
                    C.COLOR_TRANSFER_SDR -> PictureRange.SDR
                    else -> null
                }
            }
        }
        val specs = StreamSpecs(
            width = video?.width?.takeIf { it > 0 },
            height = video?.height?.takeIf { it > 0 },
            range = range,
            videoCodec = video?.let { StreamSpecs.videoCodecOfMime(it.sampleMimeType, it.codecs) },
            audioCodec = audio?.let { StreamSpecs.audioCodecOfMime(it.sampleMimeType, it.codecs) },
            channels = audio?.channelCount?.takeIf { it > 0 },
            atmos = audio != null && StreamSpecs.isAtmos(audio.sampleMimeType, audio.codecs),
            source = SpecSource.MEASURED,
            // Its codec string's ("dvhe.08.06": 8); Media3 gives no base layer's compatibility.
            dolbyVisionProfile = video?.codecs?.let(StreamSpecs::dolbyVisionProfileOf)?.takeIf { range == PictureRange.DOLBY_VISION },
        )
        return specs.takeUnless { it.isEmpty }
    }
}
