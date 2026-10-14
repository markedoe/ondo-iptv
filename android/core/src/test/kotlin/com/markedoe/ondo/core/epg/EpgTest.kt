package com.markedoe.ondo.core.epg

import com.markedoe.ondo.core.Fixtures
import com.markedoe.ondo.core.HOUR
import com.markedoe.ondo.core.TextNormalizer
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.EpgData
import com.markedoe.ondo.core.model.EpgProgram
import com.markedoe.ondo.core.model.TimeWindow
import com.markedoe.ondo.core.net.Gzip
import com.markedoe.ondo.core.net.GzipException
import com.markedoe.ondo.core.utc
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpgTest {
    private val parser = XmltvParser()

    @Test
    fun dateParsing() {
        assertEquals(utc(2025, 10, 9, 18), XmltvParser.parseDate("20251009200000 +0200"))
        assertEquals(utc(2025, 10, 9, 18), XmltvParser.parseDate("20251009200000+0200"))
        assertEquals(utc(2025, 10, 9, 21, 30), XmltvParser.parseDate("20251009200000 -0130"))
        assertEquals(utc(2025, 10, 9, 20), XmltvParser.parseDate("20251009200000"))
        assertEquals(utc(2025, 10, 9, 20), XmltvParser.parseDate("202510092000"))
        assertEquals(utc(2024, 2, 29, 0), XmltvParser.parseDate("20240229000000 +0000"))
        assertEquals(utc(2025, 10, 9, 19), XmltvParser.parseDate("20251009200000 +01:00"))
        assertNull(XmltvParser.parseDate("garbage"))
        assertNull(XmltvParser.parseDate("20251309200000"))
    }

    @Test
    fun parsesGzipAndPlainIdentically() {
        val plain = parser.parseGuide(Fixtures.bytes("epg.xml"))
        val gzipped = parser.parseGuide(Fixtures.bytes("epg.xml.gz"))
        val streamed = parser.parseGuide(ByteArrayInputStream(Fixtures.bytes("epg.xml.gz")))
        assertEquals(plain.programs, gzipped.programs)
        assertEquals(plain.programs, streamed.programs)
        assertEquals(3, plain.channels.size)
        assertEquals(5, plain.programCount)
    }

    @Test
    fun programmeFields() {
        val data = parser.parseGuide(Fixtures.bytes("epg.xml"))
        val tv1 = assertNotNull(data.programs["TV1.fr"])
        assertEquals(listOf("Too old", "Le JT", "Film du soir"), tv1.map { it.title }, "sorted by start")
        val jt = tv1[1]
        assertEquals("L'actualité & la météo", jt.description)
        assertEquals("News", jt.category)
        assertEquals(utc(2025, 10, 9, 18), jt.start)
        assertEquals(utc(2025, 10, 9, 18, 45), jt.end)
        val film = tv1[2]
        assertEquals("Lighthouse", film.subtitle)
        assertEquals("S02E05", film.episode)
        assertEquals("-12", film.rating)
        assertEquals("http://epg.example.com/film.jpg", film.iconUrl)
        assertEquals("Chantez maintenant", data.programs["nation2"]?.first()?.title, "CDATA")
        assertEquals("http://epg.example.com/tv1.png", data.channels["TV1.fr"]?.iconUrl)
        assertEquals(listOf("Nation 2 HD", "Nation 2"), data.channels["nation2"]?.displayNames)
    }

    @Test
    fun filteringByWindowAndChannels() {
        val filter = EpgFilter(
            window = TimeWindow(utc(2025, 10, 9, 0), utc(2025, 10, 10, 0)),
            channelIds = setOf("tv1.FR"),
            channelNameKeys = setOf(TextNormalizer.channelMatchKey("FR: Nation 2 FHD")),
        )
        val data = parser.parseGuide(Fixtures.bytes("epg.xml.gz"), filter)
        assertEquals(2, data.programs["TV1.fr"]?.size, "old programme dropped by the window")
        assertEquals(1, data.programs["nation2"]?.size, "matched by display name")
        assertNull(data.programs["unused.fr"])
    }

    @Test
    fun contractParseWithWindowAndChannels() {
        val programs = parser.parse(
            ByteArrayInputStream(Fixtures.bytes("epg.xml.gz")),
            window = TimeWindow(utc(2025, 10, 9, 0), utc(2025, 10, 10, 0)),
            channels = setOf("tv1.fr", "UNUSED.FR"),
        )
        assertEquals(listOf("Le JT", "Film du soir", "Ignored"), programs.map { it.title })
        assertEquals(setOf("TV1.fr", "unused.fr"), programs.map { it.channelId }.toSet())
        assertEquals(5, parser.parse(ByteArrayInputStream(Fixtures.bytes("epg.xml"))).size)
    }

    @Test
    fun timeShift() {
        val data = parser.parseGuide(Fixtures.bytes("epg.xml"), timeShiftMillis = HOUR)
        assertEquals(utc(2025, 10, 9, 19), data.programs["nation2"]?.first()?.start)
    }

    @Test
    fun brokenGuides() {
        assertFailsWith<XmltvParseException> { parser.parseGuide("<html><body>Not found".encodeToByteArray()) }
        assertFailsWith<XmltvParseException> { parser.parseGuide("not xml at all".encodeToByteArray()) }
        val truncated = Fixtures.bytes("epg.xml").decodeToString().substringBefore("<programme start=\"20251009180000 +0000\" stop=\"20251009190000 +0000\" channel=\"unused.fr\">")
        val partial = parser.parseGuide(truncated.encodeToByteArray())
        assertEquals(3, partial.programCount, "what was read before the error is kept")
        val gzipTruncated = Fixtures.bytes("epg.xml.gz").copyOf(Fixtures.bytes("epg.xml.gz").size - 20)
        assertTrue(parser.parseGuide(ByteArrayInputStream(gzipTruncated)).channels.isNotEmpty(), "truncated gzip keeps the readable part")
    }

    @Test
    fun missingStopAndInvertedTimes() {
        val xml = """
            <tv>
              <programme start="20251009200000 +0000" channel="a"><title>No stop</title></programme>
              <programme start="20251009220000 +0000" stop="20251009210000 +0000" channel="a"><title></title></programme>
              <programme channel="a"><title>No start</title></programme>
              <programme start="20251009230000 +0000" stop="20251010000000 +0000" channel="a"><episode-num system="onscreen">E12</episode-num></programme>
            </tv>
        """.trimIndent()
        val programs = assertNotNull(parser.parseGuide(xml.encodeToByteArray()).programs["a"])
        assertEquals(listOf("No stop", "—", "—"), programs.map { it.title })
        assertEquals(utc(2025, 10, 9, 21), programs[0].end, "one hour by default")
        assertEquals(60_000L, programs[1].durationMillis, "stop before start: one minute")
        assertEquals("E12", programs[2].episode)
    }

    @Test
    fun indexMatchingAndLookups() {
        val index = EpgIndex(parser.parseGuide(Fixtures.bytes("epg.xml")))
        val byId = Channel(id = "1", name = "Whatever", categoryId = "c", streamUrl = "http://x/1", epgId = "tv1.fr")
        val byName = Channel(id = "2", name = "FR | Nation 2 HD", categoryId = "c", streamUrl = "http://x/2")
        val unknown = Channel(id = "3", name = "Nope", categoryId = "c", streamUrl = "http://x/3")

        assertEquals("TV1.fr", index.guideId(byId))
        assertEquals("nation2", index.guideId(byName))
        assertNull(index.guideId(unknown))
        assertEquals("http://epg.example.com/tv1.png", index.iconUrl(byId))

        val now = utc(2025, 10, 9, 18, 50)
        assertEquals("Film du soir", index.current(byId, now)?.title)
        assertEquals(listOf("Le JT", "Film du soir"), index.upcoming(byId, utc(2025, 10, 9, 18, 10)).map { it.title })
        assertEquals(listOf("Le JT", "Film du soir"), index.upcoming(byId, utc(2025, 10, 9, 17)).map { it.title }, "gap: next programmes")
        assertNull(index.current(byId, utc(2025, 10, 10, 12)))
        assertTrue(index.upcoming(byId, utc(2025, 10, 10, 12)).isEmpty())
        val program = assertNotNull(index.current(byId, now))
        assertEquals(0.5, program.progress(utc(2025, 10, 9, 19, 52) + 30_000), 0.0001)
        assertTrue(program.isLive(now))
        assertFalse(program.isPast(now))
        assertEquals(listOf("Le JT"), index.programs(byId, utc(2025, 10, 9, 18), utc(2025, 10, 9, 18, 45)).map { it.title })
    }

    @Test
    fun indexFromLooseProgrammes() {
        val index = EpgIndex(
            listOf(
                EpgProgram("c", utc(2025, 1, 1, 11), utc(2025, 1, 1, 12), "Second"),
                EpgProgram("c", utc(2025, 1, 1, 10), utc(2025, 1, 1, 11), "First"),
            ),
        )
        val channel = Channel(id = "x", name = "C", categoryId = "c", streamUrl = "http://x/1", epgId = "c")
        assertEquals(listOf("First", "Second"), index.upcoming(channel, utc(2025, 1, 1, 10, 30), count = 5).map { it.title })
    }

    @Test
    fun mergeKeepsFirstSourceOnOverlap() {
        val a = EpgData(programs = mapOf("c" to listOf(EpgProgram("c", utc(2025, 1, 1, 10), utc(2025, 1, 1, 11), "A"))))
        val b = EpgData(
            programs = mapOf(
                "c" to listOf(
                    EpgProgram("c", utc(2025, 1, 1, 10, 30), utc(2025, 1, 1, 11, 30), "B"),
                    EpgProgram("c", utc(2025, 1, 1, 11), utc(2025, 1, 1, 12), "C"),
                ),
                "d" to listOf(EpgProgram("d", utc(2025, 1, 1, 10), utc(2025, 1, 1, 11), "D")),
            ),
        )
        var merged = a.merged(b)
        assertEquals(listOf("A", "C"), merged.programs["c"]?.map { it.title })
        assertEquals(1, merged.programs["d"]?.size)
        merged = merged.pruned(before = utc(2025, 1, 1, 11, 30))
        assertEquals(listOf("C"), merged.programs["c"]?.map { it.title })
        assertNull(merged.programs["d"])
    }

    @Test
    fun insertShortEpg() {
        val channel = Channel(id = "x1", name = "TV1", categoryId = "c", streamUrl = "http://x/1", epgId = "TV1.fr")
        val index = EpgIndex.EMPTY.inserting(
            listOf(EpgProgram("TV1.fr", utc(2025, 1, 1, 10), utc(2025, 1, 1, 11), "JT")),
            channel,
        )
        assertEquals("JT", index.current(channel, utc(2025, 1, 1, 10, 30))?.title)
        assertTrue(EpgIndex.EMPTY.isEmpty)
    }

    @Test
    fun gzipRoundTripErrors() {
        assertFalse(Gzip.isGzipped("plain".encodeToByteArray()))
        assertContentEquals("plain".encodeToByteArray(), Gzip.gunzipIfNeeded("plain".encodeToByteArray()))
        assertFailsWith<GzipException> { Gzip.gunzip(byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 0xff.toByte(), 0xff.toByte())) }
    }
}
