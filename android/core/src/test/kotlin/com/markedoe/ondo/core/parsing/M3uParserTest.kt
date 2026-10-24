package com.markedoe.ondo.core.parsing

import com.markedoe.ondo.core.Fixtures
import com.markedoe.ondo.core.MockHttpClient
import com.markedoe.ondo.core.library.PlaylistLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class M3uParserTest {
    @Test
    fun parsesHeaderAndEntries() {
        val playlist = M3uParser.parse(Fixtures.bytes("sample.m3u"))
        assertEquals(12, playlist.entries.size)
        assertEquals(listOf("http://epg.example.com/guide.xml.gz", "http://epg2.example.com/g.xml"), playlist.epgUrls)

        val tv1 = playlist.entries[0]
        assertEquals("TV1 HD", tv1.title)
        assertEquals("TV1.fr", tv1.tvgId)
        assertEquals("TV1", tv1.tvgName)
        assertEquals("France, TNT", tv1.groupTitle, "commas inside quoted attributes must not end the attribute list")
        assertEquals(1, tv1.channelNumber)
        assertEquals("http://logos.example.com/tv1.png", tv1.logo)
    }

    @Test
    fun singleQuotesUnquotedValuesAndHeaders() {
        val entries = M3uParser.parse(Fixtures.bytes("sample.m3u")).entries
        assertEquals("http://logos.example.com/f2.png", entries[1].logo)

        val stadia = entries[2]
        assertEquals("Sports", stadia.groupTitle)
        assertNull(stadia.tvgId, "empty tvg-id is treated as missing")
        assertEquals("MyAgent/1.0", stadia.headers.userAgent)
        assertEquals("http://ref.example.com/", stadia.headers.referrer)

        val aria = entries[3]
        assertEquals("http://aria.example.com/live.m3u8", aria.url)
        assertEquals("ExamplePlayer/20", aria.headers.userAgent)
        assertEquals("http://aria.example.com", aria.headers.referrer)
    }

    @Test
    fun extGroupAndBareExtInf() {
        val entries = M3uParser.parse(Fixtures.bytes("sample.m3u")).entries
        val radio = entries[4]
        assertEquals("Wave Radio", radio.title)
        assertEquals("Radio", radio.groupTitle)
    }

    @Test
    fun extInfTitleWithoutAttributes() {
        val info = M3uParser.parseExtInf("-1,Channel, with comma")
        assertEquals(-1.0, info.duration)
        assertEquals("Channel, with comma", info.title)
        assertTrue(info.attributes.isEmpty())
    }

    @Test
    fun playlistWithoutHeaderAndCrlf() {
        val text = "#EXTINF:-1 group-title=\"A\",One\r\nhttp://a.example.com/1.m3u8\r\n\r\n#EXTINF:-1,Two\r\nhttp://a.example.com/2.m3u8\r\n"
        val playlist = M3uParser.parse(text)
        assertEquals(listOf("One", "Two"), playlist.entries.map { it.title })
        assertEquals("A", playlist.entries.first().groupTitle)
    }

    @Test
    fun formatDetection() {
        assertEquals(PlaylistFormat.M3U, PlaylistFormat.detect(Fixtures.bytes("sample.m3u")))
        assertEquals(PlaylistFormat.XSPF, PlaylistFormat.detect(Fixtures.bytes("sample.xspf")))
        assertEquals(PlaylistFormat.JSON, PlaylistFormat.detect(Fixtures.bytes("sample.json")))
        assertEquals(PlaylistFormat.PLAIN_LIST, PlaylistFormat.detect("http://a/1.m3u8\nhttp://a/2.m3u8".encodeToByteArray()))
        assertNull(PlaylistFormat.detect("<html><body>Forbidden</body></html>".encodeToByteArray()))
    }

    @Test
    fun headerOptionsKodiPropsExtHttpAndAttributeHeaders() {
        val text = "\uFEFF" + """
            #EXTM3U x-tvg-url="http://a/g.xml,http://a/g.xml, http://b/g.xml"
            #EXTINF:-1 http-user-agent="AttrAgent" http-referrer="http://attr/",With attributes
            http://h.example.com/1.m3u8
            #EXTINF:-1,Kodi props
            #KODIPROP:inputstream.adaptive.stream_headers=User-Agent=ExamplePlayer%2F21&X-Token=abc+def
            #EXTVLCOPT:http-origin=http://origin
            #EXTVLCOPT:http-cookie=a=b
            http://h.example.com/2.m3u8
            #EXTINF:-1,Ext http
            #EXTHTTP:{"User-Agent":"JsonAgent","Referer":"http://json/","cookie":"c=d","n":1}
            http://h.example.com/3.m3u8
            #EXTINF:-1,Pipe wins over attributes
            http://h.example.com/4.m3u8|user-agent=PipeAgent&Referer=bad%zz
        """.trimIndent()
        val playlist = M3uParser.parse(text)
        assertEquals(listOf("http://a/g.xml", "http://b/g.xml"), playlist.epgUrls)
        val (attributes, kodi, extHttp, pipe) = playlist.entries
        assertEquals("AttrAgent", attributes.headers.userAgent)
        assertEquals("http://attr/", attributes.headers.referrer)
        assertEquals("ExamplePlayer/21", kodi.headers.userAgent)
        assertEquals(mapOf("X-Token" to "abc+def", "Origin" to "http://origin", "Cookie" to "a=b"), kodi.headers.extra)
        assertEquals("JsonAgent", extHttp.headers.userAgent)
        assertEquals("http://json/", extHttp.headers.referrer)
        assertEquals(mapOf("cookie" to "c=d"), extHttp.headers.extra)
        assertEquals("PipeAgent", pipe.headers.userAgent)
        assertEquals("bad%zz", pipe.headers.referrer, "invalid escapes are kept as-is")
    }

    @Test
    fun entriesWithoutExtInfLenientUrlsAndInvalidLines() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-name="Fallback",
            http://h.example.com/a b/ch 1.m3u8
            not a url
            #EXTINF:5 TVG-ID=Upper tvg-logo="" logo="http://ignored",After invalid
            http://h.example.com/é.ts
            http://h.example.com/bare.ts
        """.trimIndent()
        val entries = M3uParser.parse(text).entries
        assertEquals(3, entries.size)
        assertEquals("Fallback", entries[0].title, "empty title falls back to tvg-name")
        assertEquals("http://h.example.com/a%20b/ch%201.m3u8", entries[0].url)
        assertEquals("After invalid", entries[1].title, "invalid URL lines are skipped")
        assertEquals(5.0, entries[1].duration)
        assertEquals("Upper", entries[1].tvgId, "attribute keys are lowercased")
        assertNull(entries[1].logo, "an empty tvg-logo does not fall back to logo")
        assertEquals("http://h.example.com/%C3%A9.ts", entries[1].url)
        assertEquals("bare.ts", entries[2].title)
        assertEquals(-1.0, entries[2].duration)
    }

    /**
     * Playlists saved by Windows editors come in Windows-1252, or in UTF-16 with a byte order mark ("Unicode"): read
     * as UTF-8, "Télé" turned into "T\uFFFDl\uFFFD", and a UTF-16 file was not recognised as a playlist at all.
     */
    @Test
    fun playlistsInWindows1252OrUtf16AreRead() {
        val text = "#EXTM3U\n#EXTINF:-1 group-title=\"Cinéma\",Télé – l’œil\nhttp://example.com/1.m3u8\n"
        val loader = PlaylistLoader(MockHttpClient())

        val windows = text.toByteArray(charset("windows-1252"))
        assertEquals(listOf("Télé – l’œil"), M3uParser.parse(windows).entries.map { it.title })
        assertEquals(listOf("Cinéma"), M3uParser.parse(windows).entries.map { it.groupTitle })
        assertEquals(listOf("Télé – l’œil"), loader.parse(windows).channels.map { it.name })

        for ((bom, charset) in listOf(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) to Charsets.UTF_16LE, byteArrayOf(0xFE.toByte(), 0xFF.toByte()) to Charsets.UTF_16BE)) {
            val unicode = bom + text.toByteArray(charset)
            assertEquals(PlaylistFormat.M3U, PlaylistFormat.detect(unicode), charset.name())
            assertEquals(listOf("Télé – l’œil"), loader.parse(unicode).channels.map { it.name }, charset.name())
        }

        // UTF-8, and a UTF-8 file with one stray Windows-1252 byte: each character as it was written.
        assertEquals(listOf("Télé – l’œil"), M3uParser.parse(text.encodeToByteArray()).entries.map { it.title })
        val mixed = "#EXTM3U\n#EXTINF:-1,Télé ".encodeToByteArray() + byteArrayOf(0xE9.toByte()) + "\nhttp://example.com/1.m3u8\n".encodeToByteArray()
        assertEquals(listOf("Télé é"), M3uParser.parse(mixed).entries.map { it.title })
    }
}
