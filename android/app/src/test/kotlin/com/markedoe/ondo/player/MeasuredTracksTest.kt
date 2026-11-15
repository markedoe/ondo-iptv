package com.markedoe.ondo.player

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.markedoe.ondo.HangGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sound the player measures of a stream, as Media3 names its tracks (its own MIME types): AC-3 5.1, E-AC-3 JOC as
 * Atmos (by its MIME type, or an HLS playlist's "ec+3"), DTS Express and DTS:X, whose MIME types carry a profile.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class MeasuredTracksTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private fun sound(mime: String?, channels: Int, codecs: String? = null) =
        Format.Builder().setSampleMimeType(mime).setCodecs(codecs).setChannelCount(channels).setSampleRate(48_000).build()

    @Test
    fun theSoundIsNamedAsMedia3SaysIt() {
        val ac3 = requireNotNull(MeasuredTracks.of(null, sound(MimeTypes.AUDIO_AC3, 6)))
        assertEquals("AC-3" to 6, ac3.audioCodec to ac3.channels)
        assertEquals("5.1 AC-3", ac3.audioLabel)
        assertFalse(ac3.atmos)

        val joc = requireNotNull(MeasuredTracks.of(null, sound(MimeTypes.AUDIO_E_AC3_JOC, 6)))
        assertEquals("E-AC-3", joc.audioCodec)
        assertTrue(joc.atmos)
        val hls = requireNotNull(MeasuredTracks.of(null, sound(null, 6, codecs = "ec+3")))
        assertEquals("E-AC-3", hls.audioCodec)
        assertTrue("an HLS playlist's ec+3 is Atmos", hls.atmos)
        assertFalse(requireNotNull(MeasuredTracks.of(null, sound(MimeTypes.AUDIO_E_AC3, 6, codecs = "ec-3"))).atmos)

        // They had no codec: Media3's MIME types for them carry a profile.
        assertEquals("DTS-HD", MeasuredTracks.of(null, sound(MimeTypes.AUDIO_DTS_EXPRESS, 6))?.audioCodec)
        assertEquals("DTS:X", MeasuredTracks.of(null, sound(MimeTypes.AUDIO_DTS_UHD_P2, 8))?.audioCodec)
        assertEquals("7.1", MeasuredTracks.of(null, sound(MimeTypes.AUDIO_DTS_UHD_P2, 8))?.soundLayout)
        assertEquals("TrueHD", MeasuredTracks.of(null, sound(MimeTypes.AUDIO_TRUEHD, 8))?.audioCodec)
    }
}
