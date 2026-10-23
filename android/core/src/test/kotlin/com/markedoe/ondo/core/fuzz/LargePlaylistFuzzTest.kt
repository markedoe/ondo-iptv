package com.markedoe.ondo.core.fuzz

import com.markedoe.ondo.core.MockHttpClient
import com.markedoe.ondo.core.library.PlaylistLoader
import com.markedoe.ondo.core.parsing.M3uParser
import kotlin.test.assertEquals
import org.junit.Test

/** Shapes of large playlists: loading stays linear in the number of entries. */
class LargePlaylistFuzzTest {
    private val loader = PlaylistLoader(MockHttpClient(), clock = { 0L })

    /** Providers reuse one placeholder address for separator entries; 40 000 of them make a 2 MB playlist. */
    @Test(timeout = 20_000)
    fun entriesSharingOneAddressLoadQuickly() {
        val entries = 40_000
        val playlist = buildString {
            append("#EXTM3U\n")
            repeat(entries) { append("#EXTINF:-1,----- $it -----\nhttp://example.com/separator.ts\n") }
        }
        val channels = loader.parse(playlist.encodeToByteArray()).channels
        assertEquals(entries, channels.map { it.id }.toSet().size)
        assertEquals(channels[0].id + "-2", channels[1].id)
        assertEquals(channels[0].id + "-$entries", channels.last().id)
    }

    @Test(timeout = 20_000)
    fun aSeriesOfUnnumberedEpisodesLoadsQuickly() {
        val entries = 200_000
        val playlist = buildString {
            append("#EXTM3U\n")
            append("#EXTINF:-1 tvg-name=\"Daily\" group-title=\"Series\",Daily S01E05\nhttp://example.com/d/numbered.mp4\n")
            repeat(entries) { append("#EXTINF:-1 tvg-name=\"Daily\" group-title=\"Series\",Daily $it\nhttp://example.com/d/$it.mp4\n") }
            append("#EXTINF:-1 tvg-name=\"Daily\" group-title=\"Series\",Daily S02E07\nhttp://example.com/d/s2.mp4\n")
        }
        val series = loader.parse(playlist.encodeToByteArray()).series.single()
        // An unnumbered episode takes the number after every episode of its season so far, numbered ones included.
        assertEquals((listOf(5) + (2..entries + 1)).sorted(), series.episodes.filter { it.season == 1 }.map { it.number })
        assertEquals(listOf(7), series.episodes.filter { it.season == 2 }.map { it.number })
    }

    @Test(timeout = 20_000)
    fun aLongGuideListLoadsQuickly() {
        val guides = 200_000
        val playlist = "#EXTM3U url-tvg=\"" + (0 until guides).joinToString(",") { "http://example.com/$it.xml" } +
            ",http://example.com/0.xml\"\n#EXTINF:-1,A\nhttp://example.com/a.ts\n"
        val urls = M3uParser.parse(playlist).epgUrls
        assertEquals(guides, urls.size)
        assertEquals("http://example.com/0.xml", urls.first())
        assertEquals("http://example.com/${guides - 1}.xml", urls.last())
    }

    @Test(timeout = 20_000)
    fun manyGroupsLoadQuickly() {
        val entries = 200_000
        val playlist = buildString {
            append("#EXTM3U\n")
            repeat(entries) { append("#EXTINF:-1 group-title=\"G$it\",A\nhttp://example.com/$it.ts\n") }
        }
        assertEquals(entries, loader.parse(playlist.encodeToByteArray()).liveCategories.size)
    }
}
