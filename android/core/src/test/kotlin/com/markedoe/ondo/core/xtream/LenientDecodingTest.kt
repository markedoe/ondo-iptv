package com.markedoe.ondo.core.xtream

import com.markedoe.ondo.core.MockHttpClient
import com.markedoe.ondo.core.model.XtreamCredentials
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class LenientDecodingTest {
    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    private fun field(json: String): JsonElement? = obj("""{"v":$json}""")["v"]

    @Test
    fun strings() {
        assertEquals("abc", field("\"abc\"").lenientString())
        assertEquals("7", field("7").lenientString())
        assertEquals("4", field("4.0").lenientString())
        assertEquals("4.75", field("4.75").lenientString())
        assertEquals("1", field("true").lenientString())
        assertEquals("0", field("false").lenientString())
        assertEquals("first", field("""["first","second"]""").lenientString())
        assertNull(field("""["a",1]""").lenientString(), "an array must only hold strings")
        assertNull(field("null").lenientString())
        assertNull(field("{}").lenientString())
        assertNull(obj("{}")["missing"].lenientString())
    }

    @Test
    fun integers() {
        assertEquals(7, field("7").lenientInt())
        assertEquals(7, field("\" 7 \"").lenientInt())
        assertEquals(7, field("\"7.9\"").lenientInt(), "numeric strings are truncated")
        assertEquals(4, field("4.5").lenientInt())
        assertEquals(1, field("true").lenientInt())
        assertNull(field("\"abc\"").lenientInt())
        assertNull(field("\"\"").lenientInt())
        assertNull(field("[]").lenientInt())
        assertEquals(4_102_444_800L, field("\"4102444800\"").lenientLong(), "2100-01-01: past the 32-bit range")
        assertNull(field("\"4102444800\"").lenientInt(), "ids out of the Int range are rejected")
        assertNull(field("\"NaN\"").lenientInt())
    }

    @Test
    fun doublesAndBooleans() {
        assertEquals(8.8, field("\"8.8\"").lenientDouble())
        assertEquals(8.8, field("\"8,8\"").lenientDouble())
        assertEquals(9.0, field("9").lenientDouble())
        assertNull(field("\"\"").lenientDouble())
        assertNull(field("true").lenientDouble(), "booleans are not numbers")
        assertEquals(true, field("1").lenientBool())
        assertEquals(false, field("\"0\"").lenientBool())
        assertEquals(true, field("\"Active\"").lenientBool())
        assertEquals(false, field("\"nope\"").lenientBool())
        assertEquals(true, field("true").lenientBool())
        assertNull(field("null").lenientBool())
    }

    @Test
    fun arraysAndDates() {
        assertEquals(listOf("a", "b"), field("""["a","b"]""").lenientStringArray())
        assertEquals(listOf("a"), field("\"a\"").lenientStringArray())
        assertEquals(emptyList(), field("\"\"").lenientStringArray())
        assertEquals(emptyList(), field("[1]").lenientStringArray())
        assertEquals(1_700_000_000L, field("\"1700000000\"").lenientEpochSeconds())
        assertNull(field("0").lenientEpochSeconds())
        assertNull(field("\"-5\"").lenientEpochSeconds())
    }

    @Test
    fun lossyLists() {
        assertEquals(listOf(1, 3), field("""[1,"x",3]""").lossyList { it.lenientInt() }, "undecodable elements are dropped")
        assertEquals(listOf("c", "b", "a"), field("""{"2":"a","1":"b","x":"c"}""").lossyList { it.lenientString() }, "objects keyed by index")
        assertEquals(emptyList(), field("false").lossyList { it.lenientString() })
        assertEquals(emptyList(), field("null").lossyList { it.lenientString() })
    }

    @Test
    fun base64() {
        assertEquals("Le JT", XtreamEpgListingDto.decodeBase64("TGUgSlQ="))
        assertEquals("Le JT", XtreamEpgListingDto.decodeBase64("Le JT"), "not base64 (invalid UTF-8 once decoded): raw text")
        assertEquals("Hello!", XtreamEpgListingDto.decodeBase64("Hello!"), "length not a multiple of 4: raw text")
        assertEquals("", XtreamEpgListingDto.decodeBase64(""))
        assertNull(XtreamEpgListingDto.decodeBase64(null))
        assertContentEquals(byteArrayOf(0x66, 0x6f), Base64Lenient.decode("Zm8="))
        assertContentEquals(byteArrayOf(0x66, 0x6f, 0x6f), Base64Lenient.decode("Zm\n9v"))
        assertNull(Base64Lenient.decode("Zm8"))
        assertNull(Base64Lenient.decode("Zm=8"))
    }
}

class XtreamDtoTest {
    private val credentials = XtreamCredentials("http://p.example.com", "u", "p")

    @Test
    fun flatEpisodeArrayIsDecoded() = runTest {
        // The Swift decoder never reached its flat-array branch (an array of objects decodes as empty
        // per-season lists first); the port implements the intended behaviour.
        val http = MockHttpClient()
        http.routes["api:get_series_info"] = """
            {"seasons":[{"season_number":"2","name":"Deux"}],"info":{"name":"Flat"},
             "episodes":[{"id":"1","episode_num":2,"title":"B","season":1},{"id":"2","episode_num":1,"title":"A","season":1},
                         {"id":"3","title":"C","season":"2"},{"title":"no id"},7]}
        """.trimIndent().encodeToByteArray()
        val details = XtreamClient(credentials, http).seriesDetails(5)
        assertEquals("Flat", details.info.title)
        assertEquals(listOf(1, 2), details.seasons.map { it.number })
        assertEquals(listOf("A", "B"), details.seasons[0].episodes.map { it.title }, "sorted by episode number")
        assertEquals("Deux", details.seasons[1].name)
        assertEquals(1, details.seasons[1].episodes.single().number, "position when the number is missing")
    }

    @Test
    fun episodesKeyedByIndexAndEmptyInfo() = runTest {
        val http = MockHttpClient()
        http.routes["api:get_series_info"] = """
            {"seasons":{},"info":[],"episodes":{"1":{"0":{"id":"a","title":"Show - S01E01 - Opening"},"1":{"id":"b"}}}}
        """.trimIndent().encodeToByteArray()
        val details = XtreamClient(credentials, http).seriesDetails(5)
        assertEquals("", details.info.title)
        assertEquals(listOf("Opening", "Episode 2"), details.seasons.single().episodes.map { it.title })
        assertEquals("http://p.example.com/series/u/p/a.mp4", details.seasons.single().episodes[0].streamUrl)
    }

    @Test
    fun nonObjectAnswersAreInvalid() = runTest {
        val http = MockHttpClient()
        http.routes["api:get_vod_info"] = "[]".encodeToByteArray()
        http.routes["api:get_short_epg"] = "\"nothing\"".encodeToByteArray()
        assertFailsWith<XtreamException.InvalidResponse> { XtreamClient(credentials, http).movieDetails(1) }
        assertFailsWith<XtreamException.InvalidResponse> { XtreamClient(credentials, http).shortEpg(1, "c") }
        http.routes["api:get_vod_info"] = ByteArray(0)
        assertFailsWith<com.markedoe.ondo.core.net.HttpException.EmptyResponse> { XtreamClient(credentials, http).movieDetails(1) }
        http.routes["api:get_live_categories"] = "{}".encodeToByteArray()
        assertTrue(XtreamClient(credentials, http).liveCategories().isEmpty(), "panels answer {} for empty lists")
    }

    @Test
    fun movieDetailsFallBackToMovieData() = runTest {
        val http = MockHttpClient()
        http.routes["api:get_vod_info"] = """{"info":[],"movie_data":{"stream_id":"9","name":"From data"}}""".encodeToByteArray()
        assertEquals("From data", XtreamClient(credentials, http).movieDetails(9).title)
    }

    @Test
    fun orphanCategoriesAreNumbered() {
        val known = listOf(com.markedoe.ondo.core.model.MediaCategory("live-1", "One", com.markedoe.ondo.core.model.ContentType.LIVE))
        val result = XtreamClient.categoriesWithOrphans(known, listOf("live-1", "live-7", "live-8", "live-7"), com.markedoe.ondo.core.model.ContentType.LIVE)
        assertEquals(listOf("One", "Other", "Other 2"), result.map { it.name })
        assertEquals(listOf("live-1", "live-7", "live-8"), result.map { it.id })
    }
}
