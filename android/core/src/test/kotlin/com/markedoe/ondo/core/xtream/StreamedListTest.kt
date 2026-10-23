package com.markedoe.ondo.core.xtream

import com.markedoe.ondo.core.FileHttp
import com.markedoe.ondo.core.LargeCatalogue
import com.markedoe.ondo.core.MockHttpClient
import com.markedoe.ondo.core.model.XtreamCredentials
import com.markedoe.ondo.core.model.XtreamOutputFormat
import com.markedoe.ondo.core.net.GzipException
import com.markedoe.ondo.core.net.HttpException
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The catalogue's lists, decoded one element at a time from the answer saved to disk (see [decodeList]). */
class StreamedListTest {
    private val folder = Files.createTempDirectory("lists").toFile()

    @AfterTest
    fun cleanUp() {
        folder.deleteRecursively()
    }

    private fun answer(bytes: ByteArray) = File.createTempFile("answer", ".json", folder).apply { writeBytes(bytes) }

    private fun ids(bytes: ByteArray): List<String> =
        decodeList(answer(bytes)) { (it as? JsonObject)?.get("id")?.jsonPrimitive?.content }

    private fun gzip(bytes: ByteArray) = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    @Test
    fun anArrayIsReadElementByElementAndRejectedElementsDropped() {
        assertEquals(listOf("1", "3"), ids("""[{"id":"1"}, 7, {"other":true}, {"id":3}]""".encodeToByteArray()))
        // Byte order mark, spaces and line breaks first; gzip.
        assertEquals(listOf("1"), ids(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + " \r\n\t[{\"id\":\"1\"}]".encodeToByteArray()))
        assertEquals(listOf("1", "2"), ids(gzip("""[{"id":"1"},{"id":"2"}]""".encodeToByteArray())))
        assertEquals(emptyList(), ids("[]".encodeToByteArray()))
    }

    /** Panels answer `{}`, `null` or `false` for an empty list, and sometimes an object keyed by index. */
    @Test
    fun otherAnswersAreReadAsBefore() {
        assertEquals(emptyList(), ids("{}".encodeToByteArray()))
        assertEquals(emptyList(), ids("null".encodeToByteArray()))
        assertEquals(emptyList(), ids("false".encodeToByteArray()))
        assertEquals(listOf("a", "b"), ids("""{"1":{"id":"b"},"0":{"id":"a"}}""".encodeToByteArray()))
    }

    @Test
    fun brokenAnswersFailWithTheSameErrorsAsBefore() {
        // A page instead of JSON reads as one unquoted value, as it did read whole: an empty list.
        assertEquals(emptyList(), ids("<html><body>Forbidden</body></html>".encodeToByteArray()))
        assertFailsWith<HttpException.EmptyResponse> { ids(ByteArray(0)) }
        assertFailsWith<HttpException.EmptyResponse> { ids(gzip(ByteArray(0))) }
        assertFailsWith<XtreamException.InvalidResponse> { ids("  \n ".encodeToByteArray()) }
        assertFailsWith<XtreamException.InvalidResponse> { ids("""[{"id":"1"},{"id":""".encodeToByteArray()) }
        assertFailsWith<XtreamException.InvalidResponse> { ids("""[{"id":"1"} {"id":"2"}]""".encodeToByteArray()) }
        assertFailsWith<XtreamException.InvalidResponse> { ids("""[{"id":"1"}] <!-- served by -->""".encodeToByteArray()) }
        val cut = gzip("""[{"id":"1"},{"id":"2"},{"id":"3"}]""".repeat(200).encodeToByteArray())
        assertFailsWith<XtreamException.InvalidResponse> { ids(cut.copyOf(cut.size / 2)) }
        assertFailsWith<GzipException> { ids(byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)) }
    }

    /** Through the client: the lists come out as before, and nothing downloaded stays behind. */
    @Test
    fun aCatalogueLoadsFromStreamedListsAndLeavesNothingBehind() = runTest {
        val routes = LargeCatalogue.xtream(File(folder, "api"), live = 300, movies = 1_000, series = 200)
        val downloads = File(folder, "downloads")
        val client = XtreamClient(XtreamCredentials("http://panel.example.com", "demo", "demo"), FileHttp(routes), downloadFolder = downloads)
        val content = client.fetchContent(XtreamOutputFormat.M3U8)
        assertEquals(300, content.channels.size)
        assertEquals(1_000, content.movies.size)
        assertEquals(200, content.series.size)
        assertEquals("x7", content.movies[6].id)
        assertTrue(content.movieCategories.size >= 200)
        assertEquals(emptyList(), downloads.listFiles()!!.map { it.name })
    }

    /** A fake answering through `get` only (as most test fakes do) still works: `download` defaults to it. */
    @Test
    fun clientsWithoutDownloadStillWork() = runTest {
        val http = MockHttpClient()
        http.routes["api:get_vod_streams"] = """[{"stream_id":5,"name":"Quiet Water (2021)","category_id":"2"}]""".encodeToByteArray()
        val movies = XtreamClient(XtreamCredentials("http://panel.example.com", "demo", "demo"), http, downloadFolder = folder).movies()
        assertEquals(listOf("Quiet Water"), movies.map { it.name })
    }
}
