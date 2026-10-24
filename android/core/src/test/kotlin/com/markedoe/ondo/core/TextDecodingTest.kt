package com.markedoe.ondo.core

import com.markedoe.ondo.core.net.Urls
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class TextDecodingTest {
    /**
     * What the JDK's strict decoder reads, sequence by sequence: a byte that starts no valid UTF-8 sequence is its
     * Windows-1252 character. Used as the reference for the hand-written check.
     */
    private fun reference(bytes: ByteArray): String {
        val text = StringBuilder()
        var at = 0
        while (at < bytes.size) {
            val one = (2..4).firstNotNullOfOrNull { length ->
                if (bytes[at] >= 0 || at + length > bytes.size) return@firstNotNullOfOrNull null
                Urls.decodeUtf8Strict(bytes.copyOfRange(at, at + length))?.takeIf { it.codePointCount(0, it.length) == 1 }?.let { it to length }
            }
            when {
                bytes[at] >= 0 -> text.append(bytes[at].toInt().toChar()).also { at++ }
                one != null -> text.append(one.first).also { at += one.second }
                else -> text.append(TextDecoding.windows1252(bytes[at].toInt() and 0xFF)).also { at++ }
            }
        }
        return text.toString()
    }

    /** Valid UTF-8 reads exactly as the JDK's strict decoder reads it; anything it refuses falls back byte by byte. */
    @Test
    fun utf8IsWhatTheStrictDecoderAccepts() {
        val random = Random(11)
        val samples = listOf(
            "ascii", "Télé", "l’œil", "中文", "📺", "\uFFFD", "\uFEFFbom",
        ).map { it.encodeToByteArray() } + listOf(
            byteArrayOf(0xC0.toByte(), 0xAF.toByte()), // overlong "/"
            byteArrayOf(0xE0.toByte(), 0x80.toByte(), 0xAF.toByte()), // overlong
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()), // surrogate
            byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()), // past U+10FFFF
            byteArrayOf(0xF5.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xE2.toByte(), 0x82.toByte()), // cut short
            byteArrayOf(0x80.toByte()), byteArrayOf(0xFF.toByte()),
        ) + List(20_000) { ByteArray(random.nextInt(1, 8)) { random.nextInt(256).toByte() } }
        for (bytes in samples) {
            val label = bytes.joinToString(" ") { "%02X".format(it) }
            assertEquals(reference(bytes), TextDecoding.decode(bytes, 0, bytes.size), label)
            // A whole document is one encoding or the other.
            val strict = Urls.decodeUtf8Strict(bytes)
            assertEquals(strict ?: String(CharArray(bytes.size) { TextDecoding.windows1252(bytes[it].toInt() and 0xFF) }), TextDecoding.decode(bytes), label)
        }
        assertEquals("Télé €", TextDecoding.decode(byteArrayOf(0x54, 0xE9.toByte(), 0x6C, 0xE9.toByte(), 0x20, 0x80.toByte()), 0, 6))
        assertEquals("é and é", ("é and ".encodeToByteArray() + byteArrayOf(0xE9.toByte())).let { TextDecoding.decode(it, 0, it.size) })
    }

    @Test
    fun utf16IsConvertedToUtf8() {
        val text = "a📺é\u0000中"
        for ((bom, charset) in listOf(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) to Charsets.UTF_16LE, byteArrayOf(0xFE.toByte(), 0xFF.toByte()) to Charsets.UTF_16BE)) {
            assertContentEquals(text.encodeToByteArray(), TextDecoding.utf8(bom + text.toByteArray(charset)), charset.name())
            // A surrogate without its pair, and an odd byte at the end.
            val high = if (charset == Charsets.UTF_16LE) byteArrayOf(0x3D, 0xD8.toByte()) else byteArrayOf(0xD8.toByte(), 0x3D)
            val broken = bom + "x".toByteArray(charset) + high + byteArrayOf(0x41)
            assertEquals("x\uFFFD", TextDecoding.utf8(broken).decodeToString(), charset.name())
        }
        val plain = "#EXTM3U".encodeToByteArray()
        assertEquals(plain, TextDecoding.utf8(plain), "UTF-8 is not copied")
    }
}
