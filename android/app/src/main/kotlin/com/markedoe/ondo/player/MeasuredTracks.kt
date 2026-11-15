package com.markedoe.ondo.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.markedoe.ondo.core.model.PictureRange
import com.markedoe.ondo.core.model.SpecSource
import com.markedoe.ondo.core.model.StreamSpecs

/**
 * What the player reads of a film's stream from its tracks (`MeasuredSpecs`, docs/VERSIONS.md): the picture's size, range
 * and codec, the sound's codec, channels and Atmos. Only what Media3 says: a range it does not give stays unknown, and a
 * 10-bit profile alone is no HDR.
 */
@OptIn(UnstableApi::class)
internal object MeasuredTracks {
    /**
     * The specs of [tracks]: the best picture of the selected video group (an adaptive stream's highest rendition, not
     * the one playing now), the selected sound; null when nothing is known yet.
     */
    fun specs(tracks: Tracks): StreamSpecs? {
        var video: Format? = null
        var audio: Format? = null
        for (group in tracks.groups) {
            if (!group.isSelected) continue
            when (group.type) {
                C.TRACK_TYPE_VIDEO -> {
                    for (i in 0 until group.length) {
                        if (!group.isTrackSupported(i)) continue
                        val format = group.getTrackFormat(i)
                        val best = video
                        if (best == null || pixels(format) > pixels(best)) video = format
                    }
                }
                C.TRACK_TYPE_AUDIO -> if (audio == null) {
                    for (i in 0 until group.length) {
                        if (group.isTrackSelected(i)) {
                            audio = group.getTrackFormat(i)
                            break
                        }
                    }
                }
            }
        }
        return of(video, audio)
    }

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
            atmos = audio != null && StreamSpecs.isAtmosMime(audio.sampleMimeType),
            source = SpecSource.MEASURED,
        )
        return specs.takeUnless { it.isEmpty }
    }
}
