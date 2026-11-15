package com.markedoe.ondo.player

import android.media.MediaCodecInfo
import android.view.Display
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.library.DefaultReason
import com.markedoe.ondo.core.library.FilmVersions
import com.markedoe.ondo.core.library.PictureSupport
import com.markedoe.ondo.core.library.ScreenNote
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.PictureRange
import com.markedoe.ondo.core.model.StreamSpecs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * What the app reads of a screen, with fake displays: its largest mode (not the mode of the moment, nor the size the
 * interface is drawn at), the HDR formats it reports, the decoders; and the version of a film that plays first on it.
 * Invented names only.
 */
class DisplayFactsTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val hdr10 = Display.HdrCapabilities.HDR_TYPE_HDR10
    private val hdr10Plus = Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS
    private val hlg = Display.HdrCapabilities.HDR_TYPE_HLG
    private val dolbyVision = Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION

    /** A 4K TV with HDR10, HDR10+ and HLG and no Dolby Vision, behind a box whose mode reads 1080p, with a DV decoder. */
    private val samsung4k = DisplayFacts.of(
        current = 1920 to 1080,
        modes = listOf(1920 to 1080, 1280 to 720, 3840 to 2160, 4096 to 2160),
        hdrTypes = listOf(hdr10, hdr10Plus, hlg),
        dolbyVisionDecoder = true,
        hevcMain10Decoder = true,
    )

    /** A 1080p screen without HDR. */
    private val hd = DisplayFacts.of(current = 1920 to 1080, modes = listOf(1920 to 1080, 1280 to 720), hdrTypes = emptyList(), dolbyVisionDecoder = false, hevcMain10Decoder = true)

    /** A 4K screen with Dolby Vision, HDR10 and HLG, and a decoder for it. */
    private val dolbyVision4k = DisplayFacts.of(current = 3840 to 2160, modes = listOf(3840 to 2160), hdrTypes = listOf(dolbyVision, hdr10, hlg), dolbyVisionDecoder = true)

    @Test
    fun theLargestModeMakesA4kScreenWhateverTheModeOfTheMoment() {
        assertEquals(1920, samsung4k.currentWidth)
        assertEquals(4096 to 2160, samsung4k.largestWidth to samsung4k.largestHeight)
        assertEquals(PictureSupport(hdr = true, dolbyVision = false, uhd = true), samsung4k.pictureSupport())
        assertTrue(samsung4k.hdr10 && samsung4k.hdr10Plus && samsung4k.hlg)
        assertFalse("a box's decoder does not make the screen show Dolby Vision", samsung4k.dolbyVisionDisplay)

        assertEquals(PictureSupport(hdr = false, dolbyVision = false, uhd = false), hd.pictureSupport())
        assertEquals(PictureSupport(hdr = true, dolbyVision = true, uhd = true), dolbyVision4k.pictureSupport())
        // A screen that says Dolby Vision without a decoder for it.
        assertFalse(dolbyVision4k.copy(dolbyVisionDecoder = false).pictureSupport().dolbyVision)
        // The decoders not read yet: the display's word.
        assertTrue(dolbyVision4k.copy(dolbyVisionDecoder = null).pictureSupport().dolbyVision)
        // A box that reports no mode list: the mode of the moment.
        assertTrue(DisplayFacts.of(current = 3840 to 2160, modes = emptyList(), hdrTypes = emptyList()).pictureSupport().uhd)
    }

    @Test
    fun theDecodersAreReadFromTheirTypesAndProfiles() {
        val main10 = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
        val main = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
        assertEquals(true to true, DisplayFacts.decoders(listOf(mapOf("video/avc" to listOf(1)), mapOf("video/dolby-vision" to listOf(256)), mapOf("video/hevc" to listOf(main, main10)))))
        assertEquals(false to false, DisplayFacts.decoders(listOf(mapOf("video/hevc" to listOf(main)))))
    }

    private fun movie(id: String, name: String) = Movie(id = id, name = name, categoryId = "c", streamUrl = "http://stream.example.com/$id.mkv", year = 2026)

    private val plain = movie("x1", "|FR| Quartz Garden")
    private val dv = movie("x2", "|FR-4K DV| Quartz Garden")
    private val uhd = movie("x3", "|FR| Quartz Garden 4K")
    private val names: (String) -> String? = { null }

    private fun first(versions: List<Movie>, facts: DisplayFacts, specs: Map<String, StreamSpecs> = emptyMap()) =
        FilmVersions.defaultChoice(versions, null, emptyList(), facts.pictureSupport(), names) { specs[it] }

    private fun notes(movie: Movie, facts: DisplayFacts, specs: StreamSpecs? = null) =
        FilmVersions.screenNotes(FilmVersions.tags(movie.name, null), facts.pictureSupport(), specs)

    private fun dolbyVision(profile: Int?, compatibility: Int? = null) = StreamSpecs(
        width = 3840, height = 2160, range = PictureRange.DOLBY_VISION, videoCodec = "HEVC", dolbyVisionProfile = profile, dolbyVisionCompatibility = compatibility,
    )

    /** A 4K screen with HDR10 and no Dolby Vision: 4K first; Dolby Vision first only when its base layer shows right. */
    @Test
    fun a4kHdr10ScreenWithoutDolbyVision() {
        // A name saying "DV" only: profile unknown, not first; a 4K version without Dolby Vision is.
        assertEquals(plain to DefaultReason.BEST_FOR_SCREEN, first(listOf(plain, dv), samsung4k))
        assertEquals(listOf(ScreenNote.DOLBY_VISION_UNKNOWN), notes(dv, samsung4k))
        assertEquals(uhd to DefaultReason.BEST_FOR_SCREEN, first(listOf(plain, dv, uhd), samsung4k))
        // Profile 8.1: its HDR10 base layer plays.
        assertEquals(dv, first(listOf(plain, dv), samsung4k, mapOf(dv.id to dolbyVision(8, 1)))?.first)
        assertEquals(listOf(ScreenNote.DOLBY_VISION_BASE_LAYER), notes(dv, samsung4k, dolbyVision(8, 1)))
        // Profile 5: wrong colours, never first, even with a decoder on the box.
        assertEquals(plain, first(listOf(plain, dv), samsung4k, mapOf(dv.id to dolbyVision(5, 0)))?.first)
        assertEquals(listOf(ScreenNote.DOLBY_VISION_WRONG_COLOURS), notes(dv, samsung4k, dolbyVision(5, 0)))
        // A plain version measured in 1080p stays below a 4K one.
        val measured = mapOf(plain.id to StreamSpecs(width = 1920, height = 1080), uhd.id to StreamSpecs(width = 3840, height = 2160, range = PictureRange.HDR10))
        assertEquals(uhd, first(listOf(plain, uhd), samsung4k, measured)?.first)
        assertEquals(emptyList<ScreenNote>(), notes(uhd, samsung4k, measured[uhd.id]))
    }

    /** A 1080p screen without HDR: the plain version; the others say why. */
    @Test
    fun a1080pScreenWithoutHdr() {
        assertEquals(plain, first(listOf(plain, dv), hd)?.first)
        assertEquals(listOf(ScreenNote.DOLBY_VISION_UNKNOWN, ScreenNote.UHD_UNSUPPORTED), notes(dv, hd))
        assertEquals(plain, first(listOf(plain, dv), hd, mapOf(dv.id to dolbyVision(8, 1)))?.first)
        assertEquals(
            listOf(ScreenNote.DOLBY_VISION_BASE_LAYER, ScreenNote.UHD_UNSUPPORTED),
            notes(dv, hd, dolbyVision(8, 1)),
        )
        assertEquals(listOf(ScreenNote.HDR_UNSUPPORTED, ScreenNote.UHD_UNSUPPORTED), notes(uhd, hd, StreamSpecs(width = 3840, height = 2160, range = PictureRange.HDR10)))
    }

    /** A 4K screen with Dolby Vision: the Dolby Vision version, whatever its profile. */
    @Test
    fun a4kDolbyVisionScreen() {
        assertEquals(dv, first(listOf(plain, dv, uhd), dolbyVision4k)?.first)
        assertEquals(dv, first(listOf(plain, dv, uhd), dolbyVision4k, mapOf(dv.id to dolbyVision(5, 0)))?.first)
        assertEquals(emptyList<ScreenNote>(), notes(dv, dolbyVision4k, dolbyVision(5, 0)))
    }
}
