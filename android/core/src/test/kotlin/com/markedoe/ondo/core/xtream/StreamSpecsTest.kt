package com.markedoe.ondo.core.xtream

import com.markedoe.ondo.core.CoreJson
import com.markedoe.ondo.core.HangGuard
import com.markedoe.ondo.core.library.FilmVersions
import com.markedoe.ondo.core.model.DolbyVisionFallback
import com.markedoe.ondo.core.model.ProviderInfo
import com.markedoe.ondo.core.model.PictureRange
import com.markedoe.ondo.core.model.StreamSpecs
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule

/**
 * What a stream is made of and how the apps say it: the table written by hand in `fixtures/stream-specs/specs.json`,
 * the same file Apple's `StreamSpecsTests` reads. In the xtream package for the provider's `get_vod_info` blocks.
 */
class StreamSpecsTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val table = Json.parseToJsonElement(File("../../fixtures/stream-specs/specs.json").readText()).jsonObject

    private fun specs(element: JsonElement?): StreamSpecs? =
        if (element == null || element is JsonNull) null else CoreJson.decodeFromJsonElement(StreamSpecs.serializer(), element)

    private fun JsonElement?.int(): Int? = if (this == null || this is JsonNull) null else jsonPrimitive.intOrNull

    private fun JsonElement?.text(): String? = if (this == null || this is JsonNull) null else jsonPrimitive.contentOrNull

    /** 1920×802 (scope) is 1080p, 1440×1080 (anamorphic) too, 3840×1600 4K; zero is unknown. */
    @Test
    fun resolutionClasses() {
        val cases = table["resolution"]!!.jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val width = case["width"].int()
            val height = case["height"].int()
            assertEquals(case["class"].text(), StreamSpecs.resolutionOf(width, height), "${width}x$height")
        }
    }

    @Test
    fun soundLabels() {
        for (case in table["audio"]!!.jsonArray.map { it.jsonObject }) {
            val specs = StreamSpecs(
                audioCodec = case["codec"].text(), channels = case["channels"].int(), channelLayout = case["layout"].text(),
                atmos = case["atmos"]!!.jsonPrimitive.content == "true",
            )
            assertEquals(case["facts"].text(), specs.audioLabel, "$case")
            assertEquals(case["chip"].text(), specs.chipAudio, "$case")
        }
    }

    @Test
    fun codecNames() {
        val codecs = table["codecs"]!!.jsonObject
        for (pair in codecs["video"]!!.jsonArray.map { it.jsonArray }) assertEquals(pair[1].text(), StreamSpecs.videoCodec(pair[0].text()), "$pair")
        for (pair in codecs["audio"]!!.jsonArray.map { it.jsonArray }) assertEquals(pair[1].text(), StreamSpecs.audioCodec(pair[0].text()), "$pair")
        for (pair in codecs["layouts"]!!.jsonArray.map { it.jsonArray }) assertEquals(pair[1].int(), StreamSpecs.channelsOfLayout(pair[0].text()), "$pair")
        for (pair in codecs["layoutNames"]!!.jsonArray.map { it.jsonArray }) assertEquals(pair[1].text(), StreamSpecs.layoutOf(pair[0].text()), "$pair")
    }

    /** What the player measured over the provider's: Atmos only ever added, a layout only where it agrees. */
    @Test
    fun measuredOverTheProviders() {
        for (case in table["merge"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals(specs(case["merged"]), specs(case["measured"])!!.orElse(specs(case["provider"])), case["about"]!!.jsonPrimitive.content)
        }
    }

    /** One stream read in turn: the first reading stays, unless the picture grew. */
    @Test
    fun readingsOfOneStream() {
        for (case in table["measured"]!!.jsonArray.map { it.jsonObject }) {
            val about = case["about"]!!.jsonPrimitive.content
            val store = com.markedoe.ondo.core.library.MeasuredSpecs()
            for (reading in case["readings"]!!.jsonArray.map { it.jsonObject }) {
                assertEquals(reading["kept"]!!.jsonPrimitive.content == "true", store.record("p|1", specs(reading["specs"])!!), "$about: $reading")
            }
            assertEquals(specs(case["final"]), store["p|1"], about)
        }
    }

    /** The sound the player measures: the default track, else the most channels; never the viewer's pick. */
    @Test
    fun theSoundMeasured() {
        for (case in table["audioPick"]!!.jsonArray.map { it.jsonObject }) {
            val defaults = case["defaults"]!!.jsonArray.map { it.jsonPrimitive.content == "true" }
            val channels = case["channels"]!!.jsonArray.map { it.int() }
            assertEquals(case["index"].int(), StreamSpecs.measuredAudio(defaults, channels), "$case")
        }
    }

    /** The facts line's badges: specs where they know, the name's tags elsewhere, nothing invented. */
    @Test
    fun badgesAndWhatAScreenReaderSays() {
        for (case in table["badges"]!!.jsonArray.map { it.jsonObject }) {
            val name = case["name"]!!.jsonPrimitive.content
            val specs = specs(case["specs"])
            val tags = FilmVersions.labelTags(name, null)
            assertEquals(case["badges"]!!.jsonArray.map { it.jsonPrimitive.content }, StreamSpecs.badges(specs, tags), "$name $specs")
            assertEquals(case["description"]!!.jsonPrimitive.content, StreamSpecs.description(specs, tags), "$name $specs")
        }
    }

    /** The `video`, `audio` and `bitrate` of a panel's `get_vod_info`, whatever their shape. */
    @Test
    fun providerBlocks() {
        val cases = table["provider"]!!.jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 8)
        for (case in cases) {
            val about = case["about"]!!.jsonPrimitive.content
            assertEquals(specs(case["specs"]), XtreamInfoDto.from(case["info"]!!).streamSpecs, about)
        }
    }

    /** The details of a film carry the provider's specs, kept with them. */
    @Test
    fun movieDetailsKeepTheSpecs() {
        val details = com.markedoe.ondo.core.model.MediaDetails(title = "Sand Harbor", streamSpecs = StreamSpecs(width = 1920, height = 1080, audioCodec = "AAC", channels = 6))
        val text = CoreJson.encodeToString(com.markedoe.ondo.core.model.MediaDetails.serializer(), details)
        assertEquals(details, CoreJson.decodeFromString(com.markedoe.ondo.core.model.MediaDetails.serializer(), text))
        // Details kept before specs existed still read.
        assertNull(CoreJson.decodeFromString(com.markedoe.ondo.core.model.MediaDetails.serializer(), """{"title":"Sand Harbor"}""").streamSpecs)
    }

    /** What Media3 names a track: its MIME type and codecs (Android's player). */
    @Test
    fun playerTrackNames() {
        assertEquals("HEVC", StreamSpecs.videoCodecOfMime("video/hevc", "hvc1.2.4.L153.B0"))
        assertEquals("H.264", StreamSpecs.videoCodecOfMime("video/avc", null))
        assertEquals("AV1", StreamSpecs.videoCodecOfMime("video/av01", null))
        assertEquals("VP9", StreamSpecs.videoCodecOfMime("video/x-vnd.on2.vp9", null))
        assertEquals("HEVC", StreamSpecs.videoCodecOfMime("video/dolby-vision", "dvhe.05.06"))
        assertEquals("AV1", StreamSpecs.videoCodecOfMime("video/dolby-vision", "dav1.10.06"))
        assertTrue(StreamSpecs.isDolbyVision("video/dolby-vision", null))
        assertTrue(StreamSpecs.isDolbyVision("video/hevc", "dvh1.08.06"))
        assertTrue(!StreamSpecs.isDolbyVision("video/hevc", "hvc1.2.4.L153"))
        assertEquals("AAC", StreamSpecs.audioCodecOfMime("audio/mp4a-latm", "mp4a.40.2"))
        assertEquals("E-AC-3", StreamSpecs.audioCodecOfMime("audio/eac3-joc", null))
        assertTrue(StreamSpecs.isAtmosMime("audio/eac3-joc"))
        assertEquals("AC-3", StreamSpecs.audioCodecOfMime("audio/ac3", null))
        assertEquals("DTS-HD", StreamSpecs.audioCodecOfMime("audio/vnd.dts.hd", null))
        assertEquals("TrueHD", StreamSpecs.audioCodecOfMime("audio/true-hd", null))
        assertEquals("PCM", StreamSpecs.audioCodecOfMime("audio/raw", null))
        assertNull(StreamSpecs.audioCodecOfMime("audio/x-unknown", null))
    }

    /** Measured first, the provider's where the player read nothing; nothing known is empty. */
    @Test
    fun mergingKeepsTheBestKnown() {
        val measured = StreamSpecs(width = 1920, height = 1080, range = PictureRange.SDR, source = com.markedoe.ondo.core.model.SpecSource.MEASURED)
        val provider = StreamSpecs(width = 3840, height = 2160, range = PictureRange.HDR10, audioCodec = "AC-3", channels = 6, atmos = true, bitrate = 9000)
        val merged = measured.orElse(provider)
        assertEquals("1080p", merged.resolution)
        assertEquals(PictureRange.SDR, merged.range)
        assertEquals("Atmos", merged.audioLabel)
        assertEquals(9000, merged.bitrate)
        assertEquals(com.markedoe.ondo.core.model.SpecSource.MEASURED, merged.source)
        assertTrue(StreamSpecs().isEmpty)
        assertTrue(StreamSpecs(bitrate = 3000).isEmpty)
        assertEquals("1920×802 · 1080p · SDR · H.264 · 5.1 AAC · 4521 kbit/s",
            StreamSpecs(1920, 802, PictureRange.SDR, "H.264", "AAC", 6, bitrate = 4521).summary())
    }

    /**
     * A Dolby Vision profile from a codec string (Media3's "dvhe.08.06") and what a screen without Dolby Vision shows of
     * it: its base layer for 8 and 9 (and a 10 with one), wrong colours for 5 (and a 10 without one), unknown for 7 or no
     * profile (a name saying "DV" only).
     */
    @Test
    fun dolbyVisionProfiles() {
        assertEquals(8, StreamSpecs.dolbyVisionProfileOf("dvhe.08.06"))
        assertEquals(5, StreamSpecs.dolbyVisionProfileOf("dvh1.05.06"))
        assertEquals(10, StreamSpecs.dolbyVisionProfileOf("dav1.10.09"))
        assertNull(StreamSpecs.dolbyVisionProfileOf("hvc1.2.4.L153"))
        assertNull(StreamSpecs.dolbyVisionProfileOf("dvhe"))
        assertNull(StreamSpecs.dolbyVisionProfileOf(null))
        val dv = StreamSpecs(range = PictureRange.DOLBY_VISION)
        assertEquals(DolbyVisionFallback.UNKNOWN, dv.dolbyVisionFallback)
        assertEquals(DolbyVisionFallback.BASE_LAYER, dv.copy(dolbyVisionProfile = 8).dolbyVisionFallback)
        assertEquals(DolbyVisionFallback.BASE_LAYER, dv.copy(dolbyVisionProfile = 8, dolbyVisionCompatibility = 1).dolbyVisionFallback)
        assertEquals(DolbyVisionFallback.WRONG_COLOURS, dv.copy(dolbyVisionProfile = 5).dolbyVisionFallback)
        assertEquals(DolbyVisionFallback.UNKNOWN, dv.copy(dolbyVisionProfile = 7).dolbyVisionFallback)
        assertEquals(DolbyVisionFallback.WRONG_COLOURS, dv.copy(dolbyVisionProfile = 10, dolbyVisionCompatibility = 0).dolbyVisionFallback)
        assertEquals(DolbyVisionFallback.BASE_LAYER, dv.copy(dolbyVisionProfile = 10, dolbyVisionCompatibility = 1).dolbyVisionFallback)
        assertEquals(DolbyVisionFallback.NONE, StreamSpecs(range = PictureRange.HDR10).dolbyVisionFallback)
        assertEquals(
            "3840×2160 · 4K · Dolby Vision P8.1 · HEVC",
            dv.copy(width = 3840, height = 2160, videoCodec = "HEVC", dolbyVisionProfile = 8, dolbyVisionCompatibility = 1).summary(),
        )
        // The name's tag says Dolby Vision; measured SDR, it is none.
        val tags = FilmVersions.labelTags("Sand Harbor 4K DV", null)
        assertEquals(DolbyVisionFallback.UNKNOWN, FilmVersions.dolbyVisionFallback(tags, null))
        assertEquals(DolbyVisionFallback.NONE, FilmVersions.dolbyVisionFallback(tags, StreamSpecs(width = 3840, height = 2160, range = PictureRange.SDR)))
    }

    /** The provider's names of `info`'s fields: plain names only, never a value nor an address. */
    @Test
    fun theProvidersFieldNames() {
        val info = XtreamInfoDto.from(Json.parseToJsonElement("""{"name":"A","plot":"B","video":{"codec_name":"h264"},"audio":[],"https://panel.example.com/x":1}"""))
        assertEquals(ProviderInfo(listOf("audio", "name", "plot", "video"), video = true, audio = false), info.providerInfo)
    }
}
