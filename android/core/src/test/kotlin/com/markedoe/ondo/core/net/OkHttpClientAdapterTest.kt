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

    /**
     * A body whose length is known (a file, an answer with its Content-Length) is read straight into an array of that
     * size: no copy of it. Read on the calling thread here, where the JVM counts what it allocates.
     */
    @Test
    fun aBodyOfKnownLengthIsReadIntoItsOwnArray() {
        val size = 24 * 1024 * 1024 + 5
        val file = Files.createTempFile("ondo", ".m3u").toFile()
        try {
            val body = ByteArray(size) { (it * 7).toByte() }
            file.writeBytes(body)
            val threads = java.lang.management.ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
            org.junit.Assume.assumeTrue("needs the count of allocated bytes", threads != null && threads.isThreadAllocatedMemorySupported)
            threads!!.isThreadAllocatedMemoryEnabled = true
            val sameThread = OkHttpClientAdapter(ioDispatcher = Dispatchers.Unconfined)
            val before = threads.currentThreadAllocatedBytes
            val read = runBlocking { sameThread.get(file.toURI().toString()) }
            val allocated = threads.currentThreadAllocatedBytes - before
            kotlin.test.assertContentEquals(body, read)
            assertTrue(allocated < size + 1024 * 1024, "$allocated bytes allocated for a body of $size")

            // An answer with its Content-Length comes whole too.
            server.enqueue(MockResponse().setBody(okio.Buffer().write(body)))
            kotlin.test.assertContentEquals(body, runBlocking { client.get(server.url("/list.m3u").toString()) })
        } finally {
            file.delete()
        }
    }

    /** A guide goes to a file, never whole in memory; a file address is copied the same way. */
    @Test
    fun downloadsWriteTheBodyToAFile(): Unit = runBlocking(Dispatchers.IO) {
        val target = Files.createTempFile("ondo", ".xml").toFile()
        val local = Files.createTempFile("ondo", ".xml").toFile()
        try {
            server.enqueue(MockResponse().setBody("<tv></tv>"))
            client.download(server.url("/xmltv.php").toString(), mapOf("Referer" to "http://ref"), target)
            assertEquals("<tv></tv>", target.readText())
            assertEquals("http://ref", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Referer"))

            local.writeText("<tv>local</tv>")
            client.download(local.toURI().toString(), file = target)
            assertEquals("<tv>local</tv>", target.readText())

            server.enqueue(MockResponse().setResponseCode(404))
            assertFailsWith<HttpException.BadStatus> { client.download(server.url("/gone.xml").toString(), file = target) }
        } finally {
            target.delete()
            local.delete()
        }
    }

    /**
     * A body OkHttp cannot read for another reason than a network error (here a negative `Content-Length`) fails the
     * request. Thrown on OkHttp's own thread, it would stop the app and leave the caller waiting.
     */
    @Test
    fun anUnreadableBodyFailsTheRequestNotOkHttpsThread(): Unit = runBlocking(Dispatchers.IO) {
        val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught += error }
        val raw = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val answering = kotlin.concurrent.thread {
            raw.accept().use { connection ->
                connection.getInputStream().read(ByteArray(16 * 1024))
                connection.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: -5\r\n\r\n#EXTM3U".encodeToByteArray())
                    flush()
                }
                Thread.sleep(2_000)
            }
        }
        try {
            val result = runCatching { withTimeout(5_000) { client.get("http://localhost:${raw.localPort}/list.m3u") } }
            assertEquals(emptyList(), uncaught.map { it.toString() }, "nothing thrown on OkHttp's thread")
            assertTrue(result.exceptionOrNull() is java.io.IOException, "an error for the caller: $result")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
            raw.close()
            answering.join()
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
