package com.markedoe.ondo.core

import com.markedoe.ondo.core.catchup.CatchupUrlBuilder
import com.markedoe.ondo.core.epg.EpgFilter
import com.markedoe.ondo.core.epg.XmltvParser
import com.markedoe.ondo.core.library.CategoryTags
import com.markedoe.ondo.core.library.PlaylistLoader
import com.markedoe.ondo.core.library.PlotLanguage
import com.markedoe.ondo.core.library.SearchEngine
import com.markedoe.ondo.core.model.EpgData
import com.markedoe.ondo.core.model.EpgProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule

/** Guards against accidental quadratic behaviour on provider-sized inputs (generous limits for CI machines). */
class PerformanceTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

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

    /**
     * Hidden languages read every description of a catalogue of a large panel (100k titles) in one pass, off the main
     * thread: it must stay a fraction of a second, without a string per word.
     */
    @Test
    fun aHundredThousandDescriptionsAreReadQuickly() {
        val plots = listOf(
            "Un joven abogado descubre que su padre esconde un secreto que pondrá en peligro a toda la familia cuando regresa a su pueblo natal después de diez años.",
            "Um jovem advogado descobre que o pai esconde um segredo que vai colocar toda a família em perigo quando ele volta para a sua cidade natal.",
            "A young lawyer discovers that his father is hiding a secret that will put the whole family in danger when he returns to his hometown after ten years.",
            "يكتشف محامٍ شاب أن والده يخفي سرًا سيعرّض العائلة كلها للخطر عندما يعود إلى مدينته بعد عشر سنوات.",
        )
        // Long descriptions too: only their start is read.
        val titles = (0 until 100_000).map { i -> "Title $i (${if (i % 7 == 0) "ES" else "2024"})" to plots[i % plots.size].repeat(1 + i % 3) }
        val hidden = setOf("ES", "AR")
        val languages = PlotLanguage.hiddenLanguages(hidden)
        val start = System.nanoTime()
        var count = 0
        for ((name, plot) in titles) if (CategoryTags.isHiddenTitle(name, plot, null, hidden, languages)) count++
        val elapsed = (System.nanoTime() - start) / 1e9
        println("100k titles and descriptions read in ${elapsed}s")
        // Spanish and Arabic descriptions, and the English and Portuguese ones named "(ES)".
        assertEquals(50_000 + (0 until 100_000).count { it % 7 == 0 && it % 4 in 1..2 }, count)
        assertTrue(elapsed < 10, "took ${elapsed}s")
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

    /** A user guide and the provider's covering the same channels: overlap checks must not scan every programme. */
    @Test
    fun mergingTwoLargeGuidesIsQuick() {
        val base = utc(2025, 10, 9, 0)
        fun guide(first: Int) = EpgData(
            programs = (0 until 200).associate { c ->
                "ch$c" to (first until first + 1000).map { p ->
                    EpgProgram("ch$c", base + p * 1_800_000L, base + (p + 1) * 1_800_000L, "Show $p")
                }
            },
        )
        val merged = guide(0)
        val later = guide(500) // half of it overlaps, half is new
        val start = System.nanoTime()
        val result = merged.merged(later)
        val elapsed = (System.nanoTime() - start) / 1e9
        println("200 channels x 1000 programmes merged in ${elapsed}s")
        assertEquals(200 * 1500, result.programCount)
        assertEquals(listOf("Show 1498", "Show 1499"), result.programs["ch0"]?.map { it.title }?.takeLast(2))
        assertTrue(elapsed < 5, "took ${elapsed}s")

        // One channel with a long archive: scanning its programmes for each new one would take seconds.
        fun long(first: Int) = EpgData(
            programs = mapOf("ch" to (first until first + 60_000).map { p -> EpgProgram("ch", base + p * 600_000L, base + (p + 1) * 600_000L, "Show $p") }),
        )
        val longStart = System.nanoTime()
        val longResult = long(0).merged(long(30_000))
        val longElapsed = (System.nanoTime() - longStart) / 1e9
        println("60 000 + 60 000 programmes of one channel merged in ${longElapsed}s")
        assertEquals(90_000, longResult.programCount)
        assertTrue(longElapsed < 5, "took ${longElapsed}s")
    }

    private fun stamp(epochMillis: Long) = CatchupUrlBuilder.format(epochMillis, "YmdHMS") + " +0000"
}
