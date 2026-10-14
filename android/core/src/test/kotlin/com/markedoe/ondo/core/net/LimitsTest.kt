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
}
