package com.markedoe.ondo.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Text written by providers and editors: UTF-8 or, very often for French and other Western languages, Windows-1252
 * (read as UTF-8 its "é" turns into U+FFFD, read as Latin-1 its curly apostrophes and "œ" into control characters), and
 * from some Windows editors UTF-16, always with a byte order mark. Windows-1252 is decoded here rather than by the
 * platform's charset, whose mapping of the bytes it leaves undefined differs.
 */
internal object TextDecoding {
    /** Characters of bytes 0x80–0x9F in Windows-1252 (the rest matches Latin-1). */
    private const val WINDOWS_1252_HIGH =
        "\u20AC\uFFFD\u201A\u0192\u201E\u2026\u2020\u2021\u02C6\u2030\u0160\u2039\u0152\uFFFD\u017D\uFFFD" +
            "\uFFFD\u2018\u2019\u201C\u201D\u2022\u2013\u2014\u02DC\u2122\u0161\u203A\u0153\uFFFD\u017E\u0178"

    /** The Windows-1252 character of [byte] (0–255). */
    fun windows1252(byte: Int): Char = if (byte in 0x80..0x9F) WINDOWS_1252_HIGH[byte - 0x80] else byte.toChar()

    /** UTF-16 when [bytes] start with its byte order mark, else null. */
    fun utf16(bytes: ByteArray): Charset? {
        if (bytes.size < 2) return null
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        return when {
            first == 0xFF && second == 0xFE -> Charsets.UTF_16LE
            first == 0xFE && second == 0xFF -> Charsets.UTF_16BE
            else -> null
        }
    }

    /**
     * A whole document: UTF-16 after its byte order mark, else UTF-8 when all of it is valid UTF-8, else
     * Windows-1252. One encoding for the document, as an editor saves it.
     */
    fun decode(bytes: ByteArray): String {
        utf16(bytes)?.let { charset -> decodeStrict(bytes.copyOfRange(2, bytes.size), charset)?.let { return it } }
        if (validUtf8Prefix(bytes, 0, bytes.size) == bytes.size) return String(bytes, Charsets.UTF_8)
        val text = StringBuilder(bytes.size)
        for (byte in bytes) text.append(windows1252(byte.toInt() and 0xFF))
        return text.toString()
    }

    /**
     * `bytes[start, end)` as UTF-8, a byte that does not start a valid UTF-8 sequence read as Windows-1252: a playlist
     * saved in Windows-1252, or a UTF-8 one with a few such bytes pasted in, reads as written.
     */
    fun decode(bytes: ByteArray, start: Int, end: Int): String {
        if (start >= end) return ""
        var at = validUtf8Prefix(bytes, start, end)
        if (at == end) return String(bytes, start, end - start, Charsets.UTF_8)
        val text = StringBuilder(end - start).append(String(bytes, start, at - start, Charsets.UTF_8))
        while (at < end) {
            val valid = validUtf8Prefix(bytes, at, end)
            if (valid == at) {
                text.append(windows1252(bytes[at].toInt() and 0xFF))
                at++
            } else {
                text.append(String(bytes, at, valid - at, Charsets.UTF_8))
                at = valid
            }
        }
        return text.toString()
    }

    /** End of the valid UTF-8 run of `bytes[start, end)` that begins at [start]. */
    private fun validUtf8Prefix(bytes: ByteArray, start: Int, end: Int): Int {
        var at = start
        while (at < end) {
            if (bytes[at] >= 0) {
                at++
                continue
            }
            val length = utf8SequenceLength(bytes, at, end)
            if (length == 0) return at
            at += length
        }
        return end
    }

    /**
     * Length of the UTF-8 sequence of a character beyond ASCII starting at [at], or 0 when the bytes there are not one
     * (the strict rules: no overlong form, no surrogate, nothing past U+10FFFF, nothing cut by [end]).
     */
    fun utf8SequenceLength(bytes: ByteArray, at: Int, end: Int): Int {
        val lead = bytes[at].toInt() and 0xFF
        val length = when (lead) {
            in 0xC2..0xDF -> 2
            in 0xE0..0xEF -> 3
            in 0xF0..0xF4 -> 4
            else -> return 0
        }
        if (at + length > end) return 0
        // The second byte's range rules out the overlong forms, the surrogates and what lies past U+10FFFF.
        val second = bytes[at + 1].toInt() and 0xFF
        val low = if (lead == 0xE0) 0xA0 else if (lead == 0xF0) 0x90 else 0x80
        val high = if (lead == 0xED) 0x9F else if (lead == 0xF4) 0x8F else 0xBF
        if (second < low || second > high) return 0
        for (i in at + 2 until at + length) if ((bytes[i].toInt() and 0xC0) != 0x80) return 0
        return length
    }

    /**
     * The document as UTF-8 when it is saved in UTF-16 with a byte order mark, [bytes] itself otherwise: the
     * playlist parsers read UTF-8 bytes. Converted directly, measured first, so that the document is not held a
     * third time as a String.
     */
    fun utf8(bytes: ByteArray): ByteArray {
        val charset = utf16(bytes) ?: return bytes
        val littleEndian = charset == Charsets.UTF_16LE
        val units = (bytes.size - 2) / 2
        fun unit(index: Int): Int {
            val a = bytes[2 + 2 * index].toInt() and 0xFF
            val b = bytes[3 + 2 * index].toInt() and 0xFF
            return if (littleEndian) a or (b shl 8) else (a shl 8) or b
        }
        // Visits each character as a code point; a surrogate without its pair is U+FFFD.
        fun forEachCodePoint(visit: (Int) -> Unit) {
            var i = 0
            while (i < units) {
                val u = unit(i++)
                if (u in 0xD800..0xDBFF && i < units && unit(i) in 0xDC00..0xDFFF) {
                    visit(0x10000 + ((u - 0xD800) shl 10) + (unit(i++) - 0xDC00))
                } else {
                    visit(if (u in 0xD800..0xDFFF) 0xFFFD else u)
                }
            }
        }
        var size = 0
        forEachCodePoint { size += if (it < 0x80) 1 else if (it < 0x800) 2 else if (it < 0x10000) 3 else 4 }
        val out = ByteArray(size)
        var at = 0
        forEachCodePoint { c ->
            when {
                c < 0x80 -> out[at++] = c.toByte()
                c < 0x800 -> {
                    out[at++] = (0xC0 or (c shr 6)).toByte()
                    out[at++] = (0x80 or (c and 0x3F)).toByte()
                }
                c < 0x10000 -> {
                    out[at++] = (0xE0 or (c shr 12)).toByte()
                    out[at++] = (0x80 or ((c shr 6) and 0x3F)).toByte()
                    out[at++] = (0x80 or (c and 0x3F)).toByte()
                }
                else -> {
                    out[at++] = (0xF0 or (c shr 18)).toByte()
                    out[at++] = (0x80 or ((c shr 12) and 0x3F)).toByte()
                    out[at++] = (0x80 or ((c shr 6) and 0x3F)).toByte()
                    out[at++] = (0x80 or (c and 0x3F)).toByte()
                }
            }
        }
        return out
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}
