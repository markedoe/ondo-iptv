package com.markedoe.ondo.core

import com.markedoe.ondo.core.catchup.CatchupUrlBuilder
import com.markedoe.ondo.core.epg.EpgFilter
import com.markedoe.ondo.core.epg.XmltvParser
import com.markedoe.ondo.core.library.PlaylistLoader
import com.markedoe.ondo.core.library.SearchEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Guards against accidental quadratic behaviour on provider-sized inputs (generous limits for CI machines). */
class PerformanceTest {
    @Test
    fun largePlaylistParsesQuickly() {
        val text = StringBuilder("#EXTM3U\n")
        for (i in 0 until 60_000) {
            val group = when (i % 3) {
                0 -> "Films ${i % 40}"
                1 -> "Séries ${i % 30}"
                else -> "Live ${i % 50}"
            }
            val title = if (i % 3 == 1) "Show ${i % 500} S0${i % 9 + 1}E${i % 20 + 1}" else "Item $i"
            val ext = if (i % 3 == 2) "ts" else "mkv"
            text.append("#EXTINF:-1 tvg-id=\"id$i\" tvg-logo=\"http://img.example.com/$i.png\" group-title=\"$group\",$title\n")
            text.append("http://host.example.com:8080/x/u/p/$i.$ext\n")
        }
        val bytes = text.toString().encodeToByteArray()
        val start = System.nanoTime()
        val content = PlaylistLoader(MockHttpClient()).parse(bytes)
        val elapsed = (System.nanoTime() - start) / 1e9
        assertEquals(60_000, content.channels.size + content.movies.size + content.series.sumOf { it.episodes.size })
        println("60k entries parsed in ${elapsed}s")
        assertTrue(elapsed < 20, "took ${elapsed}s")

        val searchStart = System.nanoTime()
        val engine = SearchEngine(content)
        engine.search("item 59")
        val searchElapsed = (System.nanoTime() - searchStart) / 1e9
        println("search index + query in ${searchElapsed}s")
        assertTrue(searchElapsed < 10, "took ${searchElapsed}s")
    }

    @Test
    fun largeGuideParsesQuickly() {
        val xml = StringBuilder("<tv>")
        val base = utc(2025, 10, 9, 0)
        for (c in 0 until 500) xml.append("<channel id=\"ch$c\"><display-name>Channel $c</display-name></channel>")
        for (c in 0 until 500) {
            for (p in 0 until 200) {
                val start = base + p * 1_800_000L
                val stop = start + 1_800_000L
                xml.append("<programme start=\"${stamp(start)}\" stop=\"${stamp(stop)}\" channel=\"ch$c\"><title>Show $p</title>")
                xml.append("<desc>Description of show $p on channel $c</desc></programme>")
            }
        }
        xml.append("</tv>")
        val bytes = xml.toString().encodeToByteArray()
        val start = System.nanoTime()
        val data = XmltvParser().parseGuide(bytes, EpgFilter(channelIds = (0 until 250).map { "ch$it" }.toSet()))
        val elapsed = (System.nanoTime() - start) / 1e9
        assertEquals(250 * 200, data.programCount)
        println("100k programmes parsed (50k kept) in ${elapsed}s")
        assertTrue(elapsed < 30, "took ${elapsed}s")
    }

    private fun stamp(epochMillis: Long) = CatchupUrlBuilder.format(epochMillis, "YmdHMS") + " +0000"
}
