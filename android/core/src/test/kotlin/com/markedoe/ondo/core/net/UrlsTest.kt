package com.markedoe.ondo.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UrlsTest {
    @Test
    fun lenientKeepsValidUrlsAndEscapesTheRest() {
        assertEquals("http://a.example.com/x.m3u8?t=1", Urls.lenient("  http://a.example.com/x.m3u8?t=1 \n"))
        assertEquals("http://a.example.com/a%20b%7Cc.ts", Urls.lenient("http://a.example.com/a b|c.ts"))
        assertEquals("http://a.example.com/t%C3%A9l%C3%A9.png", Urls.lenient("http://a.example.com/télé.png"))
        assertEquals("http://a.example.com/x#frag", Urls.lenient("http://a.example.com/x#frag"))
        assertEquals("rtmp://a.example.com/live", Urls.lenient("rtmp://a.example.com/live"))
        assertNull(Urls.lenient(null))
        assertNull(Urls.lenient("   "))
        assertNull(Urls.lenient("no scheme here"))
        assertNull(Urls.lenient("http://a.example.com/bad%zzescape"), "invalid escapes are not repaired")
        assertNull(Urls.lenient("TV1 HD: live"))
    }

    @Test
    fun parsing() {
        val url = Urls.parse("http://user:pw@my_host.example.com:8080/a%20b/c.m3u8?x=1&y=%2B#top")!!
        assertEquals("http", url.scheme)
        assertEquals("my_host.example.com", url.host)
        assertEquals(8080, url.port)
        assertEquals("/a b/c.m3u8", url.path)
        assertEquals("c.m3u8", url.lastPathComponent)
        assertEquals("x=1&y=%2B", url.rawQuery)
        assertEquals("top", url.rawFragment)
        assertEquals("[::1]", Urls.parse("http://[::1]:80/x")?.host)
        assertEquals(80, Urls.parse("http://[::1]:80/x")?.port)
        assertNull(Urls.parse("http://h/x")?.port)
        assertEquals("/", Urls.lastPathComponent("http://h/"))
        assertEquals("", Urls.lastPathComponent("http://h"))
        assertEquals("dir", Urls.lastPathComponent("http://h/dir/"))
    }

    @Test
    fun percentEncodingAndDecoding() {
        assertEquals("a b+c/é", Urls.percentDecode("a%20b+c%2F%C3%A9"))
        assertNull(Urls.percentDecode("bad%zz"))
        assertNull(Urls.percentDecode("bad%"))
        assertNull(Urls.percentDecode("%C3%28"), "invalid UTF-8")
        assertEquals("plain", Urls.percentDecode("plain"))
        assertEquals("a%20b%2Fc", Urls.percentEncode("a b/c", Urls.allowedTable(Urls.PATH_ALLOWED, minus = "/")))
    }

    @Test
    fun queryItemsLikeUrlComponents() {
        assertEquals("a=1%262&b%3Dc=x=y&s=%20+/?", Urls.query(listOf("a" to "1&2", "b=c" to "x=y", "s" to " +/?")))
        assertEquals(
            listOf("username" to "u", "password" to "p+q", "flag" to null),
            Urls.queryItems("username=u&password=p%2Bq&flag"),
        )
    }

    @Test
    fun strictValidity() {
        assertTrue(Urls.isValidAbsolute("http://a/b?c=d#e"))
        assertFalse(Urls.isValidAbsolute("/relative/path"))
        assertFalse(Urls.isValidAbsolute("http://a/b c"))
        assertFalse(Urls.isValidAbsolute("http://a/é"))
        assertFalse(Urls.isValidAbsolute("1http://a"))
    }
}
