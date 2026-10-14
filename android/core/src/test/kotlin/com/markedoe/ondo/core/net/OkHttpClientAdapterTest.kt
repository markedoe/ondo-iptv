package com.markedoe.ondo.core.net

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class OkHttpClientAdapterTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClientAdapter()

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun returnsTheBodyAndSendsHeaders(): Unit = runBlocking(Dispatchers.IO) {
        server.enqueue(MockResponse().setBody("#EXTM3U"))
        val body = client.get(server.url("/list.m3u").toString(), mapOf("Referer" to "http://ref"))
        assertEquals("#EXTM3U", body.decodeToString())
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/list.m3u", request.path)
        assertEquals("http://ref", request.getHeader("Referer"))
        assertEquals(DEFAULT_IPTV_USER_AGENT, request.getHeader("User-Agent"), "default user agent")
    }

    /** The image loader calls the shared client directly, without the adapter's headers. */
    @Test
    fun theSharedClientSendsThePlayerUserAgentToo(): Unit = runBlocking(Dispatchers.IO) {
        server.enqueue(MockResponse().setBody("png"))
        OkHttpClientAdapter.defaultClient().newCall(okhttp3.Request.Builder().url(server.url("/logo.png")).build()).execute().close()
        assertEquals(DEFAULT_IPTV_USER_AGENT, server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("User-Agent"))
    }

    @Test
    fun customUserAgentWins(): Unit = runBlocking(Dispatchers.IO) {
        server.enqueue(MockResponse().setBody("ok"))
        client.get(server.url("/").toString(), mapOf("user-agent" to "Custom/1"))
        assertEquals("Custom/1", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("User-Agent"))
    }

    @Test
    fun nonAsciiValuesAreSentAndInvalidNamesSkipped(): Unit = runBlocking(Dispatchers.IO) {
        server.enqueue(MockResponse().setBody("ok"))
        client.get(server.url("/").toString(), mapOf("User-Agent" to "Télé/1", "Bad Name" to "x", "Referer" to "http://réf"))
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("Télé/1", request.getHeader("User-Agent"))
        assertEquals("http://réf", request.getHeader("Referer"))
        assertEquals(null, request.getHeader("Bad Name"))
    }

    @Test
    fun badStatusesBecomeHttpExceptions(): Unit = runBlocking(Dispatchers.IO) {
        server.enqueue(MockResponse().setResponseCode(403).setBody("denied"))
        val error = assertFailsWith<HttpException.BadStatus> { client.get(server.url("/").toString()) }
        assertEquals(403, error.code)
        assertTrue(error.message!!.contains("Access denied"))
        assertFailsWith<HttpException.InvalidUrl> { client.get("not a url") }
    }

    @Test
    fun transparentGzipAndFileUrls(): Unit = runBlocking(Dispatchers.IO) {
        val compressed = okio.Buffer().also { buffer ->
            okio.GzipSink(buffer).use { sink -> okio.Buffer().writeUtf8("guide").let { sink.write(it, it.size) } }
        }
        server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(compressed))
        assertEquals("guide", client.get(server.url("/epg.xml").toString()).decodeToString())

        val file = Files.createTempFile("ondo", ".m3u").toFile()
        try {
            file.writeText("#EXTM3U local")
            assertEquals("#EXTM3U local", client.get(file.toURI().toString()).decodeToString())
        } finally {
            file.delete()
        }
    }

    @Test
    fun cancellationCancelsTheCall(): Unit = runBlocking(Dispatchers.IO) {
        server.enqueue(MockResponse().setBody("late").setBodyDelay(10, TimeUnit.SECONDS))
        val started = System.nanoTime()
        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
            withTimeout(300) { client.get(server.url("/slow").toString()) }
        }
        assertTrue((System.nanoTime() - started) / 1e9 < 5, "the request does not keep the caller waiting")
    }
}
