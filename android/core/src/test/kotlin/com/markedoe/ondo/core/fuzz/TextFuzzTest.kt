package com.markedoe.ondo.core.fuzz

import com.markedoe.ondo.core.TextNormalizer
import com.markedoe.ondo.core.catchup.CatchupContext
import com.markedoe.ondo.core.catchup.CatchupUrlBuilder
import com.markedoe.ondo.core.epg.EpgIndex
import com.markedoe.ondo.core.epg.XmltvParser
import com.markedoe.ondo.core.library.CategoryStyles
import com.markedoe.ondo.core.library.CategoryTags
import com.markedoe.ondo.core.library.People
import com.markedoe.ondo.core.library.PeopleIndex
import com.markedoe.ondo.core.library.PlaybackFactory
import com.markedoe.ondo.core.library.SearchEngine
import com.markedoe.ondo.core.model.AccountInfo
import com.markedoe.ondo.core.model.AdultContentDetector
import com.markedoe.ondo.core.model.CatchupInfo
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.EpgChannel
import com.markedoe.ondo.core.model.EpgData
import com.markedoe.ondo.core.model.EpgProgram
import com.markedoe.ondo.core.model.Episode
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.Playlist
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.PlaylistSource
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.core.model.TimeWindow
import com.markedoe.ondo.core.model.XtreamCredentials
import com.markedoe.ondo.core.model.XtreamOutputFormat
import com.markedoe.ondo.core.net.Urls
import com.markedoe.ondo.core.parsing.ContentClassifier
import com.markedoe.ondo.core.parsing.M3uContentBuilder
import com.markedoe.ondo.core.parsing.M3uEntry
import com.markedoe.ondo.core.parsing.SubtitleParser
import com.markedoe.ondo.core.parsing.TitleParser
import com.markedoe.ondo.core.xtream.XtreamUrlBuilder
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import org.junit.Test

/**
 * Helpers the app calls on its main thread with provider text (titles, category and cast fields, catch-up
 * templates): any exception there stops the app, and a slow one freezes it. They must never throw and must stay
 * linear in the length of their input.
 */
class TextFuzzTest {
    /** Every helper the app runs on provider text, over random and awkward strings: nothing may throw. */
    @Test(timeout = 60_000)
    fun randomTextNeverThrows() {
        val fuzz = Fuzz(seed = 20261023)
        val credentials = XtreamCredentials("http://example.com:8080", "user", "pass") // leak-guard: allow
        val zones = listOf(null, ZoneOffset.UTC, ZoneId.of("Europe/Paris"), ZoneOffset.ofHours(18), ZoneOffset.ofHours(-18), ZoneId.of("Pacific/Kiritimati"))
        val instants = listOf(0L, -1L, 1L, Long.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE / 1000, Long.MAX_VALUE / 1000, 1_700_000_000_000L, -62_135_596_800_000L, 253_402_300_799_000L)
        repeat(CASES) { case ->
            val a = fuzz.text()
            val b = fuzz.text()
            val url = fuzz.pick("http://example.com/", "https://example.com:8080/live/u/p/", "rtmp://example.com/", "", "file:///", "http://[::1]:x/", "http://203.0.113.7/") + fuzz.text(4)
            val input = { "case $case: a=${describe(a)} b=${describe(b)} url=${describe(url)}" }
            neverThrows("TitleParser", input) {
                TitleParser.parseEpisode(a)
                TitleParser.splitYear(a)
                TitleParser.mayContainEpisodeMarker(a)
            }
            neverThrows("ContentClassifier", input) {
                val attributes = mapOf("group-title" to a, "tvg-type" to b, "type" to a)
                ContentClassifier.classify(M3uEntry(fuzz.pick(-1.0, 0.0, 1e308, -1e308, Double.NaN), a, attributes, url))
                ContentClassifier.fileExtension(url)
                ContentClassifier.fileExtension(a)
            }
            neverThrows("TextNormalizer", input) {
                val normalized = TextNormalizer.normalize(a)
                TextNormalizer.channelMatchKey(a)
                TextNormalizer.matches(normalized, TextNormalizer.tokens(TextNormalizer.normalize(b)))
                TextNormalizer.isChannelTag(a)
            }
            neverThrows("categories", input) {
                AdultContentDetector.isAdult(a)
                CategoryTags.tagOf(a)
                CategoryStyles.matches(a, fuzz.pick("manga", "kids", b))
                CategoryStyles.ruleForWord(b)
            }
            neverThrows("People", input) {
                People.names(a)
                People.mentions(a, b)
                PeopleIndex(listOf(a, b)).names(b)
            }
            neverThrows("Urls", input) {
                Urls.lenient(url)
                Urls.lenient(a)
                Urls.parse(url)?.let { it.host; it.port; it.path; it.lastPathComponent }
                Urls.percentDecode(a)
                Urls.queryItems(a)
                Urls.lastPathComponent(url)
            }
            neverThrows("XtreamUrlBuilder", input) {
                XtreamUrlBuilder.parseLiveUrl(url)
                XtreamUrlBuilder.alternateFormatUrl(url)
                XtreamUrlBuilder(XtreamCredentials(url, a, b)).let { builder ->
                    builder.apiUrl(a, mapOf(b to a))
                    builder.episodeUrl(a, b)
                    builder.xmltvUrl
                    builder.timeshiftUrl(fuzz.int(Int.MAX_VALUE), fuzz.pick(instants), fuzz.pick(-1, 0, 1, Int.MAX_VALUE, Int.MIN_VALUE), fuzz.pick(zones), XtreamOutputFormat.TS)
                    builder.timeshiftPhpUrl(-1, fuzz.pick(instants), 30, fuzz.pick(zones))
                }
            }
            neverThrows("XMLTV fields", input) {
                XmltvParser.parseDate(a)
                XmltvParser.parseDate(fuzz.pick("20240101203000", "99991231235959", "00000101000000", "1970") + fuzz.pick(" +2500", "+", " -", "+01:", "", " +0100") + b)
                XmltvParser.formatEpisode(a)
                XmltvParser.formatEpisode("${fuzz.int(Int.MAX_VALUE)}.${fuzz.pick("2147483647", "-1", "", "x")}.0/1")
            }
            neverThrows("catch-up", input) {
                val attributes = mapOf("catchup" to a, "catchup-days" to b, "catchup-source" to b, "timeshift" to a)
                val info = M3uContentBuilder.catchupInfo(attributes, url) ?: CatchupInfo(fuzz.pick(CatchupInfo.Mode.entries), fuzz.int(30) - 5, b)
                val channel = Channel(id = "c", name = a, categoryId = "x", streamUrl = url, catchup = info, xtreamStreamId = fuzz.pick(null, -1, 7))
                val start = fuzz.pick(instants)
                val end = fuzz.pick(instants)
                val now = fuzz.pick(instants)
                val context = CatchupContext(xtream = fuzz.pick(null, credentials), serverTimeZone = fuzz.pick(zones), outputFormat = fuzz.pick(XtreamOutputFormat.entries))
                CatchupUrlBuilder.urls(channel, start, end, now, context)
                CatchupUrlBuilder.isAvailable(channel, start, now)
                CatchupUrlBuilder.fill(b, start, end, now)
                CatchupUrlBuilder.fill("{utc:$a}{duration:${fuzz.pick("0", "-5", "2147483648", a)}}{offset:-1}\${now}{end:YmdHMS}", start, end, now)
                val playlist = Playlist(name = "p", source = PlaylistSource.Remote("http://example.com/list.m3u"))
                val factory = PlaybackFactory(playlist, AccountInfo(username = "u", status = "Active", serverTimeZone = a))
                factory.item(channel, alternates = listOf(channel.copy(id = "d", streamUrl = "http://example.com/2")))
                factory.catchupItem(channel, start, end, now = now)
            }
        }
    }

    /** Guide lookups and search over random names and programmes with any times. */
    @Test(timeout = 60_000)
    fun guideAndSearchNeverThrow() {
        val fuzz = Fuzz(seed = 1023)
        repeat(CASES / 10) { case ->
            val names = List(1 + fuzz.int(12)) { fuzz.text(6) }
            val times = listOf(Long.MIN_VALUE, Long.MAX_VALUE, 0L, -1L, 1_700_000_000_000L)
            val programs = names.mapIndexed { index, name ->
                val start = if (fuzz.chance(30)) fuzz.pick(times) else fuzz.random.nextLong()
                EpgProgram(channelId = names[(index + 1) % names.size], start = start, end = if (fuzz.chance(30)) fuzz.pick(times) else start + fuzz.random.nextLong(), title = name, subtitle = fuzz.pick(null, name))
            }
            val data = EpgData(
                channels = names.associateWith { EpgChannel(it, listOf(fuzz.text(4), it), fuzz.pick(null, it)) },
                programs = programs.groupBy { it.channelId }.mapValues { (_, list) -> list.sortedBy { it.start } },
            )
            val channels = names.mapIndexed { index, name ->
                Channel(id = "c$index", name = name, categoryId = fuzz.text(2), streamUrl = "http://example.com/$index", epgId = fuzz.pick(null, name, names.first()), epgName = fuzz.pick(null, fuzz.text(3)))
            }
            val query = fuzz.text(4)
            val input = { "case $case: names=${names.map(::describe)} query=${describe(query)}" }
            neverThrows("EpgIndex", input) {
                val index = EpgIndex(data, overrides = mapOf("c0" to fuzz.pick("", names.last())))
                val now = fuzz.pick(times)
                for (channel in channels) {
                    index.guideId(channel)
                    index.current(channel, now)
                    index.upcoming(channel, now, 3)
                    index.programs(channel, Long.MIN_VALUE, Long.MAX_VALUE)
                    index.suggestions(channel)
                    index.iconUrl(channel)
                }
                index.guideChannelOptions()
                index.searchPrograms(query, TimeWindow(Long.MIN_VALUE, Long.MAX_VALUE))
                index.inserting(programs, channels.first())
                EpgIndex(programs)
                data.merged(data).pruned(now)
            }
            neverThrows("SearchEngine", input) {
                val content = PlaylistContent(
                    channels = channels,
                    movies = names.mapIndexed { i, n -> Movie(id = "m$i", name = n, categoryId = "x", streamUrl = "http://example.com/m$i") },
                    series = names.mapIndexed { i, n ->
                        Series(id = "s$i", name = n, categoryId = "y", cast = fuzz.text(5), episodes = listOf(Episode("e$i", n, fuzz.random.nextInt(), fuzz.random.nextInt(), "http://example.com/e$i")))
                    },
                )
                SearchEngine(content).search(query)
                SearchEngine(content).search(names.first())
                content.series.forEach { series -> series.episodes.forEach { it.code } }
            }
        }
    }

    /** External subtitles (SRT, WebVTT) in any encoding, damaged or not, and lookups at any time. */
    @Test(timeout = 60_000)
    fun subtitlesNeverThrow() {
        val fuzz = Fuzz(seed = 59)
        repeat(CASES / 3) { case ->
            val text = buildString {
                repeat(1 + fuzz.int(6)) {
                    if (fuzz.chance(30)) append(fuzz.int(100)).append('\n')
                    append(fuzz.pick("00:00:01,000", "00:01.500", "1e999:00:00", "-1:00", "::", "99:99:99.999", "x", "00:00:02.000 align:start", ""))
                    append(fuzz.pick(" --> ", "-->", " -> ", " --> --> "))
                    append(fuzz.pick("00:00:02,000", "x", "", "00:00:03.000 position:10%"))
                    append('\n').append(fuzz.text(3)).append(fuzz.pick("<i>", "{\\an8}", "</i>", "<", "{", ""))
                    append(fuzz.pick("\n\n", "\r\n\r\n", "\r\r", "\n \n", "\n", "\n\u00A0\n"))
                }
            }
            val bytes = when (fuzz.int(5)) {
                0 -> byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
                1 -> byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE).let { if (fuzz.chance(30)) it.copyOf(it.size - 1) else it }
                2 -> fuzz.mutate(text.encodeToByteArray())
                3 -> fuzz.bytes(fuzz.int(2_000))
                else -> ("WEBVTT\n\n" + text).encodeToByteArray()
            }
            neverThrows("subtitles case $case", { describe(bytes) }) {
                val cues = SubtitleParser.parse(bytes)
                for (time in listOf(Double.NaN, -1.0, 0.0, 1.5, Double.POSITIVE_INFINITY)) SubtitleParser.cueAt(time, cues)
            }
        }
        val cue = "00:00:01,000 --> 00:00:02,000\n"
        for (long in listOf(cue + "<".repeat(1_000_000), "\n".repeat(1_000_000), "-->".repeat(300_000), cue.repeat(30_000), " \n".repeat(500_000))) {
            neverThrows("long subtitles", { describe(long) }) { SubtitleParser.parse(long.encodeToByteArray()) }
        }
    }

    private companion object {
        const val CASES = 3_000
    }
}
