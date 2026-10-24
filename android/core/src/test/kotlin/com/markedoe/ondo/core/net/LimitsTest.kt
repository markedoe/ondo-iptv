package com.markedoe.ondo.core.net

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A small gzip file that inflates without end, or an answer that never stops, must not exhaust the memory. */
class LimitsTest {
    private val bomb: ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { gzip -> repeat(64) { gzip.write(ByteArray(1024 * 1024)) } }
    }.toByteArray()

    @Test
    fun gunzipStopsAtItsLimit() {
        assertEquals(64 * 1024 * 1024, Gzip.gunzip(bomb).size, "under the default limit")
        assertFailsWith<GzipTooLargeException> { Gzip.gunzip(bomb, limit = 8L * 1024 * 1024) }
    }

    @Test
    fun aStreamedGuideStopsAtTheLimitToo() {
        val stream = LimitedInputStream(ByteArrayInputStream(ByteArray(2048)), limit = 1024)
        assertFailsWith<GzipTooLargeException> { stream.readBytes() }
    }

    @Test
    fun aResponseBodyIsReadUpToItsLimit() {
        assertEquals(1000, OkHttpClientAdapter.readLimited(ByteArrayInputStream(ByteArray(1000)), 1000).size)
        assertFailsWith<HttpException.TooLarge> { OkHttpClientAdapter.readLimited(ByteArrayInputStream(ByteArray(1001)), 1000) }
    }

    @Test
    fun aDownloadIsCopiedUpToItsLimit() {
        val file = java.nio.file.Files.createTempFile("ondo", ".xml").toFile()
        try {
            OkHttpClientAdapter.copyLimited(ByteArrayInputStream(ByteArray(1000)), file, 1000)
            assertEquals(1000L, file.length())
            assertFailsWith<HttpException.TooLarge> { OkHttpClientAdapter.copyLimited(ByteArrayInputStream(ByteArray(1001)), file, 1000) }
        } finally {
            file.delete()
        }
    }

    /** Bytes this thread allocates while [block] runs, by the JVM's count; null when the JVM does not count them. */
    private fun allocatedBy(block: () -> Unit): Long? {
        val threads = java.lang.management.ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        if (threads == null || !threads.isThreadAllocatedMemorySupported) return null
        threads.isThreadAllocatedMemoryEnabled = true
        val before = threads.currentThreadAllocatedBytes
        block()
        return threads.currentThreadAllocatedBytes - before
    }

    /**
     * A body of unknown length (no Content-Length, or OkHttp inflating it) is read in pieces copied once into an array
     * of its size: twice the body at most. A buffer doubled as it grew, then copied, took close to four times: a
     * 40 MB playlist needed about 100 MB at once on a TV that gives an app 128.
     */
    @Test
    fun aBodyOfUnknownLengthIsCopiedOnce() {
        val size = 24 * 1024 * 1024 + 5
        val body = ByteArray(size) { (it * 7).toByte() }
        var read: ByteArray? = null
        val allocated = allocatedBy { read = OkHttpClientAdapter.readLimited(ByteArrayInputStream(body), OkHttpClientAdapter.MAX_BODY_SIZE) }
        kotlin.test.assertContentEquals(body, read)
        org.junit.Assume.assumeTrue("needs the count of allocated bytes", allocated != null)
        kotlin.test.assertTrue(allocated!! < 2.2 * size, "$allocated bytes allocated for a body of $size")
    }
}
