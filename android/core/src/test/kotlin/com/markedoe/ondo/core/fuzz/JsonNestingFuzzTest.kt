package com.markedoe.ondo.core.fuzz

import com.markedoe.ondo.core.MockHttpClient
import com.markedoe.ondo.core.library.PlaylistLoader
import com.markedoe.ondo.core.library.PlaylistLoaderException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.Test

/**
 * kotlinx.serialization reads nested JSON arrays recursively: a few kilobytes of `[` overflow the stack, an Error
 * no caller expects. JSON from a playlist or an M3U `#EXTHTTP` line that nests deeper than any real one is refused.
 */
class JsonNestingFuzzTest {
    private val loader = PlaylistLoader(MockHttpClient())
    private val deep = 100_000

    @Test(timeout = 30_000)
    fun aDeeplyNestedJsonPlaylistIsNotAPlaylist() {
        for (document in listOf("[".repeat(deep), "[".repeat(deep) + "]".repeat(deep), "{\"channels\":" + "[".repeat(deep), "[{\"a\":".repeat(deep))) {
            assertFailsWith<PlaylistLoaderException.EmptyPlaylist>(describe(document.take(40))) { loader.parse(document.encodeToByteArray()) }
        }
        // A playlist nesting a few levels is read.
        val nested = "{\"channels\":[{\"name\":\"A\",\"url\":\"http://example.com/a.ts\",\"x\":" + "[".repeat(100) + "]".repeat(100) + "}]}"
        assertEquals(listOf("A"), loader.parse(nested.encodeToByteArray()).channels.map { it.name })
        // Brackets inside strings are text, escaped quotes included.
        val name = "[".repeat(1000) + "\\\"" + "{".repeat(1000)
        val inStrings = "[{\"name\":\"$name\",\"url\":\"http://example.com/a.ts\"}]"
        assertEquals(listOf("[".repeat(1000) + "\"" + "{".repeat(1000)), loader.parse(inStrings.encodeToByteArray()).channels.map { it.name })
    }

    @Test(timeout = 30_000)
    fun aDeeplyNestedExtHttpLineIsIgnored() {
        val playlist = "#EXTM3U\n#EXTINF:-1,A\n#EXTHTTP:" + "[".repeat(deep) + "\nhttp://example.com/a.ts\n" +
            "#EXTINF:-1,B\n#EXTHTTP:{\"User-Agent\":\"Player\"}\nhttp://example.com/b.ts\n"
        val channels = loader.parse(playlist.encodeToByteArray()).channels
        assertEquals(listOf("A", "B"), channels.map { it.name })
        assertNull(channels[0].headers)
        assertEquals("Player", channels[1].headers?.userAgent)
    }
}
