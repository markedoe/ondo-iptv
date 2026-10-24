package com.markedoe.ondo.core.epg

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Faults common in providers' guides, each of which stopped the parser at its first occurrence: a guide of 1,000
 * programmes kept the 5 before it, without saying so.
 */
class GuideRepairTest {
    private val parser = XmltvParser()
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)
    private val utf8 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"

    /** One channel, [count] programmes of ten minutes; programme [at] has [title] (bytes as written in the file). */
    private fun guide(title: ByteArray, at: Int = 5, count: Int = 1_000, declaration: String = utf8): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("$declaration\n<tv generator-info-name=\"test\"><channel id=\"TV1.fr\"><display-name>TV1</display-name></channel>\n".encodeToByteArray())
        val base = Instant.parse("2025-10-09T00:00:00Z").toEpochMilli()
        for (i in 0 until count) {
            val start = stamp.format(Instant.ofEpochMilli(base + i * 600_000L))
            val stop = stamp.format(Instant.ofEpochMilli(base + (i + 1) * 600_000L))
            out.write("<programme start=\"$start +0000\" stop=\"$stop +0000\" channel=\"TV1.fr\"><title>".encodeToByteArray())
            out.write(if (i == at) title else "Show $i".encodeToByteArray())
            out.write("</title></programme>\n".encodeToByteArray())
        }
        out.write("</tv>\n".encodeToByteArray())
        return out.toByteArray()
    }

    private fun titles(bytes: ByteArray) = parser.parseGuide(ByteArrayInputStream(bytes)).programs["TV1.fr"].orEmpty().map { it.title }

    private fun gzip(bytes: ByteArray) = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    @Test
    fun anAmpersandLeftUnescapedDoesNotStopTheGuide() {
        for (declaration in listOf(utf8, "")) {
            val bytes = guide("Tom & Jerry".encodeToByteArray(), declaration = declaration)
            val titles = titles(bytes)
            assertEquals(1_000, titles.size, declaration)
            assertEquals("Tom & Jerry", titles[5])
            assertEquals(1_000, titles(gzip(bytes)).size, "gzip")
        }
    }

    @Test
    fun aLatin1ByteInAUtf8GuideDoesNotStopIt() {
        // "Télé" written in Windows-1252 in the middle of a UTF-8 guide.
        val tele = byteArrayOf(0x54, 0xE9.toByte(), 0x6C, 0xE9.toByte())
        for (declaration in listOf(utf8, "<?xml version='1.0' encoding='utf-8'?>", "")) {
            val titles = titles(guide(tele, declaration = declaration))
            assertEquals(1_000, titles.size, declaration)
            assertEquals("Télé", titles[5])
        }
    }

    /** An `&` in an address, and a control character pasted into a description. */
    @Test
    fun anAmpersandInAnAttributeAndAControlCharacterDoNotStopTheGuide() {
        val icon = guide("A</title><icon src=\"http://img.example.com/logo?w=1&h=2\"/><title>B".encodeToByteArray())
        assertEquals(1_000, titles(icon).size)
        assertEquals("http://img.example.com/logo?w=1&h=2", parser.parseGuide(icon).programs["TV1.fr"]!![5].iconUrl)
        val control = guide("Tom\u001B and\u0000 Jerry".encodeToByteArray())
        assertEquals(1_000, titles(control).size)
        assertEquals("Tom and Jerry", titles(control)[5])
    }

    /** The encoding the guide declares, or its byte order mark, reads it as before; a UTF-8 guide that claims Latin-1 too. */
    @Test
    fun declaredEncodingsAreRead() {
        val text = "Télé – l’œil"
        val cases = listOf(
            "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" to "Télé".toByteArray(Charsets.ISO_8859_1),
            "<?xml version=\"1.0\" encoding=\"windows-1252\"?>" to text.toByteArray(charset("windows-1252")),
            "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" to text.encodeToByteArray(),
            "<?xml version=\"1.0\" encoding=\"x-unknown\"?>" to text.encodeToByteArray(),
            "<?xml version=\"1.0\" encoding=\"UTF-16\"?>" to text.encodeToByteArray(),
        )
        for ((declaration, title) in cases) {
            val expected = if (title.size == 4) "Télé" else text
            assertEquals(expected, titles(guide(title, declaration = declaration))[5], declaration)
        }
        val cyrillic = "Новости"
        assertEquals(cyrillic, titles(guide(cyrillic.toByteArray(charset("windows-1251")), declaration = "<?xml version=\"1.0\" encoding=\"windows-1251\"?>"))[5])
        // UTF-16 with its byte order mark, and without one.
        val document = guide(text.encodeToByteArray(), declaration = "<?xml version=\"1.0\" encoding=\"UTF-16\"?>").decodeToString()
        for ((charset, bom) in listOf(Charsets.UTF_16LE to "\uFEFF", Charsets.UTF_16BE to "\uFEFF", Charsets.UTF_16LE to "", Charsets.UTF_16BE to "")) {
            val titles = titles((bom + document).toByteArray(charset))
            assertEquals(1_000, titles.size, "$charset $bom")
            assertEquals(text, titles[5])
        }
    }

    /** At most [size] bytes per read, as a slow network or a pipe gives them. */
    private class Trickle(private val bytes: ByteArray, private val size: Int) : java.io.InputStream() {
        private var at = 0

        override fun read(): Int = if (at < bytes.size) bytes[at++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (at >= bytes.size) return -1
            val n = minOf(len, size, bytes.size - at)
            System.arraycopy(bytes, at, b, off, n)
            at += n
            return n
        }
    }

    /**
     * The repairs look ahead (a reference, a CDATA section, a UTF-8 sequence): what they make of a guide does not
     * depend on how its bytes arrive, nor on how many characters the parser asks for at a time.
     */
    @Test
    fun theRepairsDoNotDependOnHowTheGuideArrives() {
        // Runs of every length before what needs a look ahead: some end right at the end of what was read.
        val edges = (1..130).joinToString("") { "y".repeat(it) + "&amp;" + "z".repeat(it % 70) + "<![CDATA[&]]>Télé📺" }
        val title = "Tom & Jerry &amp; &#233; &#xE9; <![CDATA[a & b]]> Télé 📺 ".encodeToByteArray() + byteArrayOf(0xE9.toByte(), 0x01) +
            " &unknown; &".encodeToByteArray() + edges.encodeToByteArray()
        // Past the first kilobyte, which is read whole to find the encoding.
        val bytes = guide(title, at = 30, count = 50)
        val whole = com.markedoe.ondo.core.parsing.XmlText.reader(ByteArrayInputStream(bytes)).readText()
        val expected = "Tom &amp; Jerry &amp; &#233; &#xE9; <![CDATA[a & b]]> Télé 📺 é &unknown; &amp;$edges</title>"
        assertEquals(true, expected in whole, whole.take(600))
        for (size in listOf(1, 2, 3, 5, 7, 63, 64, 65, 1000)) {
            for (ask in listOf(1, 2, 3, 8192)) {
                val reader = com.markedoe.ondo.core.parsing.XmlText.reader(Trickle(bytes, size))
                val text = StringBuilder()
                val buffer = CharArray(ask)
                while (true) {
                    val n = reader.read(buffer, 0, ask)
                    if (n < 0) break
                    text.appendRange(buffer, 0, n)
                }
                assertEquals(whole, text.toString(), "$size bytes per read, $ask characters asked")
            }
            assertEquals(titles(bytes), titles(Trickle(bytes, size).readBytes()), "$size")
            assertEquals(parser.parseGuide(bytes), parser.parseGuide(Trickle(bytes, size)), "$size")
        }
    }

    /** What was escaped stays as written: the repairs change nothing in a guide written correctly. */
    @Test
    fun aGuideWrittenCorrectlyReadsAsBefore() {
        val written = listOf(
            "Tom &amp; Jerry" to "Tom & Jerry",
            "&#233;t&#xE9; &lt;3 &quot;x&apos;" to "été <3 \"x'",
            "<![CDATA[Tom & Jerry &amp; <b>]]>" to "Tom & Jerry &amp; <b>",
            "Télé – l’œil 中文" to "Télé – l’œil 中文",
        )
        for ((raw, title) in written) assertEquals(title, titles(guide(raw.encodeToByteArray()))[5], raw)
    }
}
