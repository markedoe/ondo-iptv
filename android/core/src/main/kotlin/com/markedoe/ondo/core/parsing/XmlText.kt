package com.markedoe.ondo.core.parsing

import com.markedoe.ondo.core.TextDecoding
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.Charset

/**
 * The characters of an XML document from a provider (guides, XSPF playlists), with the faults their files commonly
 * have mended on the way. Each of them stopped the parser at its first occurrence, losing the rest of the file:
 * - an `&` that starts no reference ("Tom & Jerry", `?a=1&b=2` in an address) is read as `&amp;`, except inside
 *   CDATA, where it is text;
 * - a byte that is not UTF-8 is read as Windows-1252 ("Télé" written by a Latin-1 tool into a UTF-8 guide);
 * - a character XML does not allow (a control character pasted into a description) is left out.
 * A document written correctly reads as before: the repairs only touch what the parser refuses.
 *
 * The parser is given characters: the encoding is chosen here, from the byte order mark or the declaration. A guide
 * that says UTF-8, ASCII, Latin-1 or Windows-1252, or nothing, is read as UTF-8 with Windows-1252 for any byte that
 * is not UTF-8, which reads each of them right, and a UTF-8 guide that claims Latin-1 too.
 */
internal object XmlText {
    /** Bytes looked at for the byte order mark and the declaration. */
    private const val HEAD = 1024

    /** Names under which guides are UTF-8, or single-byte text that the Windows-1252 fallback reads. */
    private val utf8OrLatin = setOf(
        "utf-8", "utf8", "us-ascii", "ascii", "iso-8859-1", "iso8859-1", "iso_8859-1", "latin1", "l1",
        "windows-1252", "cp1252", "x-cp1252",
    )

    private val encodingAttribute = Regex("""encoding\s*=\s*["']([A-Za-z0-9._:-]+)["']""")

    fun reader(input: InputStream): Reader = XmlRepairReader(decoded(input))

    private fun decoded(input: InputStream): Reader {
        // Read directly, not through a BufferedInputStream: its reads go on past a first piece, and a gzip file cut
        // short then loses that piece with the error.
        val head = ByteArray(HEAD)
        var count = 0
        var error: IOException? = null
        try {
            while (count < HEAD) {
                val read = input.read(head, count, HEAD - count)
                if (read < 0) break
                count += read
            }
        } catch (e: IOException) {
            // A file cut short this early: the parser gets the bytes before the error, then the error.
            error = e
        }
        fun byte(i: Int) = if (i < count) head[i].toInt() and 0xFF else -1
        fun rest(skipped: Int): InputStream = Replay(head, skipped, count, error, input)
        // A byte order mark, or the first characters of `<?xml` in UTF-16.
        when {
            byte(0) == 0xEF && byte(1) == 0xBB && byte(2) == 0xBF -> return Utf8Reader(rest(3))
            byte(0) == 0xFF && byte(1) == 0xFE -> return InputStreamReader(rest(2), Charsets.UTF_16LE)
            byte(0) == 0xFE && byte(1) == 0xFF -> return InputStreamReader(rest(2), Charsets.UTF_16BE)
            byte(0) == 0x3C && byte(1) == 0 && byte(2) == 0x3F && byte(3) == 0 -> return InputStreamReader(rest(0), Charsets.UTF_16LE)
            byte(0) == 0 && byte(1) == 0x3C && byte(2) == 0 && byte(3) == 0x3F -> return InputStreamReader(rest(0), Charsets.UTF_16BE)
        }
        val text = String(head, 0, count, Charsets.ISO_8859_1)
        val declaration = if (text.startsWith("<?xml")) text.substringBefore("?>") else ""
        val name = encodingAttribute.find(declaration)?.groupValues?.get(1)?.lowercase()
        // A declaration read one byte per character is in a single-byte encoding, whatever it names (UTF-16…).
        if (name == null || name in utf8OrLatin || name.startsWith("utf") || name.startsWith("ucs")) return Utf8Reader(rest(0))
        val charset = try {
            Charset.forName(name)
        } catch (_: IllegalArgumentException) { // unknown or invalid name
            null
        }
        return if (charset == null) Utf8Reader(rest(0)) else InputStreamReader(rest(0), charset)
    }
}

/** `head[from, count)`, read from [rest] already, then the rest of it; [error], met while reading them, comes after them. */
private class Replay(
    private val head: ByteArray,
    private var from: Int,
    private val count: Int,
    private val error: IOException?,
    private val rest: InputStream,
) : InputStream() {
    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (from < count) {
            val n = minOf(len, count - from)
            System.arraycopy(head, from, b, off, n)
            from += n
            return n
        }
        error?.let { throw it }
        return rest.read(b, off, len)
    }

    override fun close() = rest.close()
}

/** UTF-8, a byte that starts no valid UTF-8 sequence read as Windows-1252 ([TextDecoding]). */
private class Utf8Reader(private val input: InputStream) : Reader() {
    private val bytes = ByteArray(64 * 1024)
    private var start = 0
    private var end = 0
    private var ended = false

    /** The second half of a character past U+FFFF, when the room given ended between its two halves. */
    private var low = -1

    override fun read(out: CharArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var count = 0
        if (low >= 0) {
            out[offset] = low.toChar()
            low = -1
            count = 1
        }
        while (count < length) {
            // A sequence is at most 4 bytes: never cut by the end of what is read. What is decoded is given first,
            // so that an error further on (a gzip file cut short) comes after it, not instead of it.
            if (end - start < 4 && !ended) {
                if (count > 0) break
                fill()
            }
            if (start == end) break
            // ASCII, most of a guide, in a run: such bytes need nothing after them.
            val stop = minOf(end, start + length - count)
            var at = start
            while (at < stop && bytes[at] >= 0) out[offset + count++] = bytes[at++].toInt().toChar()
            start = at
            // The run may end close to the end of what was read: the next sequence waits until it can be read whole.
            if (at == stop || (end - start < 4 && !ended)) continue
            val lead = bytes[start].toInt()
            val size = TextDecoding.utf8SequenceLength(bytes, start, end)
            if (size == 0) {
                out[offset + count++] = TextDecoding.windows1252(lead and 0xFF)
                start++
                continue
            }
            var point = (lead and (0x7F shr size))
            for (i in start + 1 until start + size) point = (point shl 6) or (bytes[i].toInt() and 0x3F)
            start += size
            if (point < 0x10000) {
                out[offset + count++] = point.toChar()
            } else {
                out[offset + count++] = (0xD800 + ((point - 0x10000) shr 10)).toChar()
                val second = 0xDC00 + ((point - 0x10000) and 0x3FF)
                if (count < length) out[offset + count++] = second.toChar() else low = second
            }
        }
        return if (count == 0) -1 else count
    }

    private fun fill() {
        if (start > 0) {
            System.arraycopy(bytes, start, bytes, 0, end - start)
            end -= start
            start = 0
        }
        while (!ended && end < 4) {
            val read = input.read(bytes, end, bytes.size - end)
            if (read < 0) ended = true else end += read
        }
    }

    override fun close() = input.close()
}

/** Mends what would stop the XML parser on the way (see [XmlText]). */
private class XmlRepairReader(private val input: Reader) : Reader() {
    private val chars = CharArray(64 * 1024)
    private var start = 0
    private var end = 0
    private var ended = false
    private var inCdata = false

    /** Characters of `amp;` still to give after an `&` that starts no reference. */
    private var ampLeft = 0

    override fun read(out: CharArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var count = 0
        while (count < length) {
            if (ampLeft > 0) {
                out[offset + count++] = AMP[AMP.length - ampLeft--]
                continue
            }
            // Enough ahead to tell a reference or a CDATA section from the rest; what is ready is given first.
            if (end - start < LOOKAHEAD && !ended) {
                if (count > 0) break
                fill()
            }
            if (start == end) break
            // Characters that need no repair, most of a guide, in a run.
            val stop = minOf(end, start + length - count)
            var at = start
            while (at < stop) {
                val plain = chars[at]
                if (plain == '&' || plain == '<' || plain == ']' || plain < ' ' || plain >= '\uFFFE') break
                out[offset + count++] = plain
                at++
            }
            start = at
            // The run may end close to the end of what was read: what follows waits until enough is read after it.
            if (at == stop || (end - start < LOOKAHEAD && !ended)) continue
            val c = chars[start++]
            when {
                c == '&' && !inCdata -> {
                    out[offset + count++] = c
                    if (!startsReference(start)) ampLeft = AMP.length
                }
                c == '<' && !inCdata && startsWith(start, "![CDATA[") -> {
                    inCdata = true
                    out[offset + count++] = c
                }
                c == ']' && inCdata && startsWith(start, "]>") -> {
                    inCdata = false
                    out[offset + count++] = c
                }
                // XML 1.0 allows tab, line feed and carriage return below the space, and neither U+FFFE nor U+FFFF.
                (c < ' ' && c != '\t' && c != '\n' && c != '\r') || c == '\uFFFE' || c == '\uFFFF' -> Unit
                else -> out[offset + count++] = c
            }
        }
        return if (count == 0) -1 else count
    }

    private fun startsWith(at: Int, text: String): Boolean {
        if (end - at < text.length) return false
        for (i in text.indices) if (chars[at + i] != text[i]) return false
        return true
    }

    /** Whether the characters at [at] (after an `&`) are `name;`, `#123;` or `#x1F;`. */
    private fun startsReference(at: Int): Boolean {
        val limit = minOf(end, at + LOOKAHEAD - 1)
        var i = at
        if (i < limit && chars[i] == '#') {
            i++
            val hex = i < limit && chars[i] == 'x'
            if (hex) i++
            val digits = i
            while (i < limit && (chars[i] in '0'..'9' || (hex && (chars[i] in 'a'..'f' || chars[i] in 'A'..'F')))) i++
            return i > digits && i < limit && chars[i] == ';'
        }
        if (i >= limit || !(chars[i].isLetter() || chars[i] == '_' || chars[i] == ':')) return false
        i++
        while (i < limit && (chars[i].isLetterOrDigit() || chars[i] in NAME_PUNCTUATION)) i++
        return i < limit && chars[i] == ';'
    }

    private fun fill() {
        if (start > 0) {
            System.arraycopy(chars, start, chars, 0, end - start)
            end -= start
            start = 0
        }
        while (!ended && end < LOOKAHEAD) {
            val read = input.read(chars, end, chars.size - end)
            if (read < 0) ended = true else end += read
        }
    }

    override fun close() = input.close()

    private companion object {
        const val AMP = "amp;"
        const val NAME_PUNCTUATION = "-._:\u00B7"

        /** Longest reference recognised: entity names are short. */
        const val LOOKAHEAD = 64
    }
}
