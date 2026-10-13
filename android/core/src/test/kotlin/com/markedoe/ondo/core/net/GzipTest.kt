package com.markedoe.ondo.core.net

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GzipTest {
    private fun gzip(text: String): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.encodeToByteArray()) } }.toByteArray()

    @Test
    fun roundTripAndConcatenatedMembers() {
        val text = "hello ".repeat(10_000)
        assertEquals(text, Gzip.gunzip(gzip(text)).decodeToString())
        assertEquals("first second", Gzip.gunzip(gzip("first ") + gzip("second")).decodeToString(), "concatenated members")
        val zlib = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write("zlib".encodeToByteArray()) } }.toByteArray()
        assertEquals("zlib", Gzip.gunzip(zlib).decodeToString())
        assertContentEquals(ByteArray(0), Gzip.gunzip(ByteArray(0)))
    }

    @Test
    fun truncatedDataKeepsWhatWasInflated() {
        val full = gzip("abcdefghij".repeat(50_000))
        val partial = Gzip.gunzip(full.copyOf(full.size / 2))
        assertTrue(partial.isNotEmpty())
        assertTrue("abcdefghij".repeat(50_000).startsWith(partial.decodeToString()))
        assertFailsWith<GzipException> { Gzip.gunzip(byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 0xff.toByte(), 0xff.toByte())) }
        assertFailsWith<GzipException> { Gzip.gunzip("not compressed".encodeToByteArray()) }
    }

    @Test
    fun decompressingStreamDetectsGzip() {
        assertEquals("plain", Gzip.decompressingStream(ByteArrayInputStream("plain".encodeToByteArray())).readBytes().decodeToString())
        assertEquals("packed", Gzip.decompressingStream(ByteArrayInputStream(gzip("packed"))).readBytes().decodeToString())
        assertEquals("", Gzip.decompressingStream(ByteArrayInputStream(ByteArray(0))).readBytes().decodeToString())
    }
}
