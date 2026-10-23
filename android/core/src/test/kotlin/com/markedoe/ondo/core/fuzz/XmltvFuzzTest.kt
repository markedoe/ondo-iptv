package com.markedoe.ondo.core.fuzz

import com.markedoe.ondo.core.Fixtures
import com.markedoe.ondo.core.MockHttpClient
import com.markedoe.ondo.core.epg.EpgFilter
import com.markedoe.ondo.core.epg.EpgIndex
import com.markedoe.ondo.core.epg.XmltvParser
import com.markedoe.ondo.core.library.PlaylistLoader
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.EpgData
import com.markedoe.ondo.core.model.Playlist
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.PlaylistSource
import com.markedoe.ondo.core.model.TimeWindow
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

/**
 * XMLTV guides, damaged, truncated, compressed or hostile: [XmltvParser] fails only with the documented exceptions
 * (an `XmltvParseException` or another `IOException`), never hangs, never reads an entity from disk or network.
 */
class XmltvFuzzTest {
    private val parser = XmltvParser()
    private val now = 1_760_000_000_000L

    private val filters = listOf(
        EpgFilter.NONE,
        EpgFilter(window = TimeWindow(now - 86_400_000L, now + 86_400_000L)),
        EpgFilter(channelIds = setOf("tv1.fr", "TV2"), channelNameKeys = setOf("tv1", "news")),
        EpgFilter(window = TimeWindow(Long.MIN_VALUE, Long.MAX_VALUE), channelIds = emptySet(), channelNameKeys = emptySet()),
    )
    private val shifts = listOf(0L, 3_600_000L, -7_200_000L, 1_000_000_000_000_000_000L, -1_000_000_000_000_000_000L, Long.MAX_VALUE, Long.MIN_VALUE)

    /** Parses both ways (bytes and stream) within the contract; what loads is used as the app uses it. */
    private fun parse(label: String, bytes: ByteArray, fuzz: Fuzz? = null): EpgData? {
        val filter = fuzz?.pick(filters) ?: EpgFilter.NONE
        val shift = fuzz?.pick(shifts) ?: 0L
        val input = { "$label (shift $shift): ${describe(bytes)}" }
        val fromBytes = withinContract(label, input) { parser.parseGuide(bytes, filter, shift) }
        val fromStream = withinContract("$label, streamed", input) { parser.parseGuide(ByteArrayInputStream(bytes), filter, shift) }
        assertEquals(fromBytes, fromStream, "bytes and stream differ, ${input()}")
        if (fromBytes != null) use(fromBytes, input)
        return fromBytes
    }

    private fun use(data: EpgData, input: () -> String) = neverThrows("guide", input) {
        for ((_, list) in data.programs) assertEquals(list.sortedBy { it.start }, list, "programmes out of order, ${input()}")
        val index = EpgIndex(data)
        val channels = (data.channels.keys + data.programs.keys + listOf("tv1", "TV1.fr")).map { id ->
            Channel(id = "c$id", name = data.channels[id]?.displayNames?.firstOrNull() ?: id, categoryId = "x", streamUrl = "http://example.com/1", epgId = id)
        }
        for (channel in channels) {
            index.current(channel, now)
            index.upcoming(channel, now, 3)
            index.programs(channel, TimeWindow(now - 86_400_000L, now))
            index.suggestions(channel)
            index.iconUrl(channel)
        }
        index.guideChannelOptions()
        index.searchPrograms("news", TimeWindow(Long.MIN_VALUE, Long.MAX_VALUE))
        data.merged(data).pruned(now)
        data.allPrograms.forEach { it.progress(now); it.isLive(now); it.id }
    }

    @Test(timeout = 60_000)
    fun truncatedGuidesFailAsDocumented() {
        val full = Fixtures.bytes("epg.xml")
        val fuzz = Fuzz(seed = 43)
        for (size in 0..full.size) parse("epg.xml cut at $size", full.copyOf(size), fuzz)
        for (packed in listOf(Fixtures.bytes("epg.xml.gz"), gzip(full))) {
            for (size in 0..packed.size) parse("epg.xml.gz cut at $size", packed.copyOf(size), fuzz)
        }
        assertTrue(parse("whole guide", full)!!.programCount > 0)
    }

    private val dates = listOf(
        "20251009200000 +0200", "20251009200000+0200", "20251009200000", "202510092000", "2025100920", "20251009", "2025",
        "20251009200000 +2500", "20251009200000 -9999", "20251009200000 +02:00", "20251009200000 +", "20251009200000 -",
        "19700101000000 +0000", "00000101000000", "99991231235959 +0000", "20251309200000", "20251032200000", "20250000000000",
        "20251009996099", "-2025100920000", "", " ", "x", "2025-10-09 20:00:00", "20251009200000 +0200 junk", "２０２５１００９",
    )

    private fun text(fuzz: Fuzz): String {
        val raw = fuzz.text(4)
        return when (fuzz.int(10)) {
            0 -> raw
            1 -> "<![CDATA[$raw]]>"
            2 -> fuzz.pick("&amp;", "&lt;", "&#233;", "&#x1F4FA;", "&#0;", "&#xD800;", "&unknown;", "&")
            else -> raw.replace("&", "&amp;").replace("<", "&lt;").replace("]]>", "]]&gt;")
        }
    }

    private fun attribute(fuzz: Fuzz): String = text(fuzz).replace("\"", "&quot;").replace("<![CDATA[", "").replace("]]>", "")

    private fun guide(fuzz: Fuzz): ByteArray {
        val ids = List(1 + fuzz.int(4)) { fuzz.pick("TV1.fr", "tv1.fr", "TV2", "News", "", fuzz.text(2)) }
        val body = buildString {
            for (id in ids) {
                if (!fuzz.chance(80)) continue
                append("<channel id=\"${attribute(fuzz).takeIf { fuzz.chance(10) } ?: id.replace("\"", "&quot;").replace("&", "&amp;").replace("<", "&lt;")}\">")
                repeat(fuzz.int(3)) { append("<display-name lang=\"fr\">${text(fuzz)}</display-name>") }
                if (fuzz.chance(50)) append("<icon src=\"${fuzz.pick("http://example.com/a.png", "", "x y", attribute(fuzz))}\"/>")
                append("</channel>")
            }
            repeat(fuzz.int(12)) {
                append("<programme")
                if (fuzz.chance(95)) append(" start=\"${fuzz.pick(dates)}\"")
                if (fuzz.chance(85)) append(" stop=\"${fuzz.pick(dates)}\"")
                if (fuzz.chance(95)) append(" channel=\"${fuzz.pick(ids).replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")}\"")
                append(">")
                for (element in listOf("title", "sub-title", "desc", "category", "episode-num", "rating")) {
                    if (!fuzz.chance(60)) continue
                    when (element) {
                        "rating" -> append("<rating system=\"x\"><value>${text(fuzz)}</value></rating>")
                        "episode-num" -> append("<episode-num system=\"xmltv_ns\">${fuzz.pick("1.4.0/1", "2147483647.2147483647.", "-5.-5.", "..", "x.y", "0 . 1 . 2", text(fuzz))}</episode-num>")
                        else -> append("<$element>${text(fuzz)}</$element>")
                    }
                }
                if (fuzz.chance(30)) append("<icon src=\"${attribute(fuzz)}\"/>")
                if (fuzz.chance(5)) append("<programme start=\"20251009200000\" channel=\"TV1.fr\"><title>nested</title></programme>")
                append("</programme>")
            }
        }
        val declaration = if (fuzz.chance(80)) "<?xml version=\"1.0\" encoding=\"${fuzz.pick("UTF-8", "ISO-8859-1", "UTF-16", "x-unknown", "")}\"?>" else ""
        val doctype = if (fuzz.chance(20)) "<!DOCTYPE tv SYSTEM \"xmltv.dtd\">" else ""
        val root = fuzz.pick("tv", "tv", "tv", "html", "TV")
        var bytes = "$declaration$doctype<!-- guide --><$root generator-info-name=\"x\">$body</$root>".encodeToByteArray()
        if (fuzz.chance(25)) bytes = fuzz.mutate(bytes)
        when (fuzz.int(10)) {
            0 -> bytes = gzip(bytes)
            1 -> bytes = gzip(bytes).let { it.copyOf(fuzz.int(it.size)) }
            2 -> bytes = gzip(bytes).let { packed -> packed.copyOf().also { if (packed.size > 12) it[10 + fuzz.int(packed.size - 10)] = fuzz.int(256).toByte() } }
        }
        return bytes
    }

    @Test(timeout = 60_000)
    fun damagedGuidesFailAsDocumented() {
        val fuzz = Fuzz(seed = 47)
        var loaded = 0
        repeat(CASES) { case -> if (parse("guide case $case", guide(fuzz), fuzz)?.programCount?.let { it > 0 } == true) loaded++ }
        // Most cases are filtered, shifted out of the window or damaged on purpose.
        assertTrue(loaded > CASES / 20, "only $loaded guides had programmes: the generator no longer makes guides")
    }

    /** The loader keeps a guide that fails as a failure of that source, whatever the time shift. */
    @Test(timeout = 60_000)
    fun guideSourcesFailAsFailures() {
        val fuzz = Fuzz(seed = 53)
        repeat(CASES / 10) { case ->
            val http = MockHttpClient()
            val urls = List(1 + fuzz.int(3)) { "http://example.com/guide$it.xml" }
            for (url in urls) http.routes[url] = guide(fuzz)
            val playlist = Playlist(
                name = "p",
                source = PlaylistSource.Remote("http://example.com/list.m3u"),
                epgUrls = urls + "http://example.com/missing.xml",
                epgTimeShiftHours = fuzz.pick(0.0, 1.5, -12.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e300),
                epgChannelOverrides = mapOf("c1" to fuzz.pick("", "TV2", "unknown")),
            )
            val content = PlaylistContent(
                channels = listOf(
                    Channel(id = "c1", name = "TV1 HD", categoryId = "x", streamUrl = "http://example.com/1", epgId = "TV1.fr"),
                    Channel(id = "c2", name = fuzz.text(2), categoryId = "x", streamUrl = "http://example.com/2", epgName = "News"),
                ),
            )
            val result = neverThrows("loadEpg case $case", { "case $case" }) {
                runBlocking { PlaylistLoader(http, Dispatchers.Unconfined, Dispatchers.Unconfined, clock = { now }).loadEpg(playlist, content) }
            }
            assertTrue("http://example.com/missing.xml" in result.failures, "case $case")
            for ((url, error) in result.failures) {
                assertTrue(error is java.io.IOException, "case $case: $url failed with ${error::class.qualifiedName}: ${error.message}")
            }
            use(result.data) { "case $case" }
        }
    }

    /**
     * External entities and DTDs are never fetched nor read from disk; entity expansion is bounded; deep or huge
     * documents stay within the contract. Shown on the JVM's parser; Android's is Expat, where the empty entity
     * resolver of `Sax` is the shared defence.
     */
    @Test(timeout = 60_000)
    fun guidesNeverResolveEntities() {
        val secret = File.createTempFile("ondo-entity", ".txt").apply {
            writeText("ENTITY-MARKER")
            deleteOnExit()
        }
        val server = MockWebServer()
        server.start()
        try {
            val remote = server.url("/x.dtd").toString()
            val programme = "<programme start=\"20251009200000\" stop=\"20251009210000\" channel=\"TV1.fr\"><title>%s</title></programme>"
            val laughs = (1..9).joinToString("") { level -> "<!ENTITY lol$level \"" + "&lol${level - 1};".repeat(10) + "\">" }
            val long = "x".repeat(1_000_000)
            val documents = listOf(
                "<!DOCTYPE tv [<!ENTITY xxe SYSTEM \"${secret.toURI()}\">]><tv>${programme.format("&xxe;")}</tv>",
                "<!DOCTYPE tv [<!ENTITY xxe SYSTEM \"$remote\">]><tv>${programme.format("&xxe;")}</tv>",
                "<!DOCTYPE tv SYSTEM \"$remote\"><tv>${programme.format("A")}</tv>",
                "<!DOCTYPE tv [<!ENTITY % ext SYSTEM \"$remote\"> %ext;]><tv>${programme.format("A")}</tv>",
                "<?xml-stylesheet href=\"$remote\"?><tv>${programme.format("A")}</tv>",
                "<!DOCTYPE tv [<!ENTITY lol0 \"lol\">$laughs]><tv>${programme.format("&lol9;")}</tv>",
                "<!DOCTYPE tv [<!ENTITY a \"${"a".repeat(50_000)}\">]><tv>${programme.format("&a;".repeat(50_000))}</tv>",
                "<tv>" + "<programme>".repeat(100_000) + "</tv>",
                "<tv>" + "<x>".repeat(100_000) + "</x>".repeat(100_000) + programme.format("A") + "</tv>",
                "<tv><programme start=\"$long\" stop=\"$long\" channel=\"$long\"><title>$long</title></programme></tv>",
                "<tv><channel id=\"$long\"><display-name>$long</display-name></channel>${programme.format("A")}</tv>",
                "<tv>" + programme.format("A").repeat(20_000) + "</tv>",
                "<tv " + (0 until 20_000).joinToString(" ") { "a$it=\"1\"" } + ">${programme.format("A")}</tv>",
            )
            for ((index, document) in documents.withIndex()) {
                for (filter in filters) {
                    val data = withinContract("entity document $index", { describe(document) }) { parser.parseGuide(document.encodeToByteArray(), filter) } ?: continue
                    for (program in data.allPrograms) {
                        assertTrue("ENTITY-MARKER" !in program.title, "a file was read into document $index")
                        assertTrue(program.title.length <= 1_000_000, "document $index expanded to ${program.title.length} characters")
                    }
                }
            }
            assertEquals(0, server.requestCount, "an external entity or DTD was fetched")
        } finally {
            server.shutdown()
        }
    }

    private companion object {
        const val CASES = 2_000
    }
}
