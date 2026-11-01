package com.markedoe.ondo.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** What the record of a playback says about the video: the formats that ask the most of a TV's decoder. */
@RunWith(AndroidJUnit4::class)
class PlaybackDetailsTest {
    @Test
    fun hdrAndDolbyVisionAreNamed() {
        val dolbyVision = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1.2.4.L153.B0,dvh1.08.06")
            .setWidth(3840)
            .setHeight(2160)
            .setFrameRate(23.976f)
            .setColorInfo(ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setColorTransfer(C.COLOR_TRANSFER_ST2084).build())
            .build()
        assertEquals("Video: video/hevc, hvc1.2.4.L153.B0,dvh1.08.06, 3840×2160, 23.98 fps", PlaybackDetails.video(dolbyVision))
        assertEquals("Colour: HDR (PQ), BT.2020; Dolby Vision: yes", PlaybackDetails.colour(dolbyVision))

        val plain = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setCodecs("avc1.64001F").build()
        assertEquals("Colour: transfer not given, colour space not given; Dolby Vision: no", PlaybackDetails.colour(plain))
    }

    @Test
    fun decodersAndThermalStatus() {
        assertEquals("Decoders: video c2.vendor.hevc.decoder", PlaybackDetails.decoders("c2.vendor.hevc.decoder", null))
        assertNull(PlaybackDetails.decoders(null, null))
        assertEquals("severe", PlaybackDetails.thermalStatus(3))
        assertEquals("unknown (9)", PlaybackDetails.thermalStatus(9))
    }

    /** Dolby Vision named by its own type or by a layer in a list of codecs (AV1's too); HLG; the audio line. */
    @Test
    fun otherColoursAndTheAudioLine() {
        assertEquals(true, PlaybackDetails.isDolbyVision(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).build()))
        assertEquals(true, PlaybackDetails.isDolbyVision(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).setCodecs("av01.0.13M.10, DAV1.10.09").build()))
        assertEquals(false, PlaybackDetails.isDolbyVision(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("hvc1.2.4.L153.B0").build()))

        val hlg = Format.Builder()
            .setColorInfo(ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT709).setColorTransfer(C.COLOR_TRANSFER_HLG).build())
            .build()
        assertEquals("Colour: HDR (HLG), BT.709; Dolby Vision: no", PlaybackDetails.colour(hlg))

        val surround = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3).setCodecs("ec-3").setChannelCount(6).setSampleRate(48_000).build()
        assertEquals("Audio: audio/eac3, ec-3, 6 channels, 48000 Hz", PlaybackDetails.audio(surround))
        assertEquals("nothing known, nothing listed", "Audio: ", PlaybackDetails.audio(Format.Builder().build()))
    }
}
