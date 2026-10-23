package com.markedoe.ondo.core.fuzz

import com.markedoe.ondo.core.Fixtures
import com.markedoe.ondo.core.MockHttpClient
import com.markedoe.ondo.core.epg.EpgIndex
import com.markedoe.ondo.core.library.CategoryStyles
import com.markedoe.ondo.core.library.CategoryTags
import com.markedoe.ondo.core.library.PlaybackFactory
import com.markedoe.ondo.core.library.PlaylistLoader
import com.markedoe.ondo.core.library.SearchEngine
import com.markedoe.ondo.core.model.EpgData
import com.markedoe.ondo.core.model.Playlist
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.PlaylistSource
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

/**
 * Playlists as providers send them, and worse: [PlaylistLoader.parse] (format detection, M3U, XSPF, JSON and plain
 * lists, gzip, classification) fails only with the documented exceptions, never hangs, and what it returns keeps
 * the invariants the app's lists rely on.
 */
class PlaylistFuzzTest {
    private val loader = PlaylistLoader(MockHttpClient(), clock = { 0L })

    /** Parses within the contract; when it loads, checks the catalogue as the app uses it. */
    private fun load(label: String, bytes: ByteArray): PlaylistContent? {
        val input = { "$label: ${describe(bytes)}" }
        val content = withinContract(label, input) { loader.parse(bytes) } ?: return null
        checkContent(content, input)
        return content
    }

    private fun checkContent(content: PlaylistContent, input: () -> String) {
        val ids = content.channels.map { it.id } + content.movies.map { it.id } + content.series.map { it.id } +
            content.series.flatMap { series -> series.episodes.map { it.id } }
        assertEquals(ids.size, ids.toSet().size, "an id used twice, ${input()}")
        val categories = content.liveCategories + content.movieCategories + content.seriesCategories
        assertEquals(categories.size, categories.map { it.id }.toSet().size, "a category id used twice, ${input()}")
        val categoryIds = categories.mapTo(HashSet()) { it.id }
        assertTrue((content.channels.map { it.categoryId } + content.movies.map { it.categoryId } + content.series.map { it.categoryId }).all { it in categoryIds }, "an item without its category, ${input()}")
        neverThrows("catalogue", input) {
            val factory = PlaybackFactory(Playlist(name = "p", source = PlaylistSource.Remote("http://example.com/list.m3u")))
            for (channel in content.channels) {
                factory.item(channel, alternates = content.channels.take(3))
                factory.catchupItem(channel, 1_700_000_000_000, 1_700_003_600_000, now = 1_700_010_000_000)
            }
            for (movie in content.movies) factory.item(movie)
            for (series in content.series) for (episode in series.episodes) factory.item(episode, series).also { episode.code }
            SearchEngine(content).search("a s01")
            CategoryTags.detect(categories)
            CategoryStyles.detect(categories)
            categories.forEach { it.looksAdult }
            val guide = EpgIndex(EpgData())
            content.channels.forEach { guide.guideId(it) }
        }
    }

    // M3U

    private val attributeKeys = listOf(
        "tvg-id", "tvg-name", "tvg-logo", "logo", "group-title", "tvg-chno", "channel-number", "tvg-num", "catchup", "catchup-type",
        "catchup-days", "catchup-source", "timeshift", "tvg-rec", "tv_archive_duration", "tvg-type", "type", "user-agent",
        "http-user-agent", "http-referrer", "referrer", "url-tvg", "x-tvg-url", "tvg-url", "tvg-shift", "TVG-ID", "Group-Title", "",
    )

    private fun value(fuzz: Fuzz): String = when (fuzz.int(12)) {
        0 -> ""
        1 -> fuzz.pick("-1", "0", "7", "2147483648", "-2147483649", "1e3", " 3 ", "NaN", "99999999999999999999")
        2 -> fuzz.pick("default", "append", "shift", "flussonic", "xc", "fs", "timeshift", "DEFAULT", "flussonic-hls")
        3 -> fuzz.pick("http://example.com/{utc}-{duration:60}.ts", "?utc={utc}&lutc={lutc}", "{Y}{m}{d}", "\${start}", "{utc:Y-m-d", "{", "}", "{duration:0}{offset:-5}")
        4 -> fuzz.pick("movie", "series", "live", "vod", "MOVIE", "Séries", "Films | VOD", "Saison 2")
        5 -> fuzz.pick("http://example.com/a.png", "http://example.com/a.xml,http://example.com/b.xml.gz,,", "not a url", "file:///storage/guide.xml")
        else -> fuzz.text(4)
    }

    private fun attributes(fuzz: Fuzz, count: Int): String = buildString {
        repeat(fuzz.int(count)) {
            append(fuzz.pick(" ", "  ", "\t", ""))
            append(fuzz.pick(attributeKeys))
            when (fuzz.int(8)) {
                0 -> append("=").append(value(fuzz).replace(' ', '_'))
                1 -> append("='").append(value(fuzz)).append("'")
                2 -> append("=\"").append(value(fuzz))
                3 -> Unit
                4 -> append("=")
                else -> append("=\"").append(value(fuzz)).append("\"")
            }
        }
    }

    private fun url(fuzz: Fuzz): String = fuzz.pick(
        "http://example.com/live/user/pass/${fuzz.int(1000)}.ts", "http://example.com/movie/user/pass/${fuzz.int(1000)}.mkv",
        "http://example.com/series/u/p/${fuzz.int(50)}.mp4", "https://example.com/stream.m3u8", "rtmp://example.com/live",
        "http://example.com/a b/é.mp4", "http://203.0.113.5:8080/${fuzz.int(9)}", "relative/path.ts", "", "   ", "http://", "://x",
        "http://[::1", "http://example.com/x.ts|User-Agent=VLC&Referer=http%3A%2F%2Fexample.com", "http://example.com/x.ts|%zz=&=&",
        "http://example.com/" + fuzz.text(3), fuzz.text(2), "file:///storage/x.m3u8", "http://example.com/dup.ts", "http://example.com/dup.ts",
        "http://example.com/vod/Show S01E0${fuzz.int(9)}.mkv",
    )

    private fun m3u(fuzz: Fuzz): ByteArray {
        val lines = ArrayList<String>()
        if (fuzz.chance(85)) lines += "#EXTM3U" + attributes(fuzz, 4)
        repeat(fuzz.int(25)) {
            if (fuzz.chance(90)) {
                val duration = fuzz.pick("-1", "0", "1e308", "-1e308", "99999999999999999999", "", ".5", "1e", "NaN", "-0", "1d", "0x1p3", " ", "abc", "3600")
                lines += "#EXTINF:" + duration + attributes(fuzz, 8) + (if (fuzz.chance(85)) "," + fuzz.text(5) else "")
            }
            if (fuzz.chance(15)) lines += "#EXTGRP:" + fuzz.text(3)
            if (fuzz.chance(10)) lines += "#EXTVLCOPT:" + fuzz.pick("http-user-agent", "http-referrer", "http-origin", "http-cookie", "x", "") + fuzz.pick("=", "", "==") + fuzz.text(3)
            if (fuzz.chance(8)) lines += "#KODIPROP:" + fuzz.pick("inputstream.adaptive.stream_headers", "inputstream.adaptive.manifest_headers", "x") + "=" + fuzz.pick("User-Agent=a&Referer=http%3A%2F%2Fexample.com", "%zz", "&&=&", fuzz.text(2))
            if (fuzz.chance(8)) lines += "#EXTHTTP:" + fuzz.pick("{\"User-Agent\":\"a\",\"cookie\":\"b\"}", "{", "[]", "null", "{\"a\":1}", "{\"User-Agent\":" + "[".repeat(600), "{\"x\":\"\\uD800\"}", fuzz.text(2))
            if (fuzz.chance(5)) lines += "#" + fuzz.text(2)
            if (fuzz.chance(5)) lines += ""
            lines += url(fuzz)
        }
        var bytes = lines.joinToString(fuzz.pick("\n", "\r\n", "\r", "\n\n")).encodeToByteArray()
        if (fuzz.chance(10)) bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes
        if (fuzz.chance(25)) bytes = fuzz.mutate(bytes)
        if (fuzz.chance(5)) bytes = gzip(bytes)
        return bytes
    }

    @Test(timeout = 60_000)
    fun m3uPlaylistsLoadOrFailAsDocumented() {
        val fuzz = Fuzz(seed = 31)
        var loaded = 0
        repeat(M3U_CASES) { case -> if (load("M3U case $case", m3u(fuzz)) != null) loaded++ }
        assertTrue(loaded > M3U_CASES / 2, "only $loaded playlists loaded: the generator no longer makes playlists")
    }

    // Other formats

    private fun xml(text: String, fuzz: Fuzz): String =
        if (fuzz.chance(85)) text.replace("&", "&amp;").replace("<", "&lt;") else text

    private fun xspf(fuzz: Fuzz): ByteArray {
        val tracks = buildString {
            repeat(fuzz.int(10)) {
                append("<track>")
                for (element in listOf("location", "title", "image", "album", "duration").shuffled(fuzz.random)) {
                    if (!fuzz.chance(80)) continue
                    val text = when (element) {
                        "location" -> url(fuzz)
                        "duration" -> fuzz.pick("1000", "-1", "1e999", "x", "", "99999999999999999999")
                        else -> fuzz.text(3)
                    }
                    append("<$element>").append(xml(text, fuzz)).append("</$element>")
                }
                if (fuzz.chance(10)) append("<track><title>nested</title></track>")
                append("</track>")
            }
        }
        val encoding = fuzz.pick("UTF-8", "utf-8", "ISO-8859-1", "UTF-16", "x-unknown", "")
        val declaration = if (fuzz.chance(80)) "<?xml version=\"1.0\" encoding=\"$encoding\"?>" else ""
        var bytes = "$declaration<playlist version=\"1\" xmlns=\"http://xspf.org/ns/0/\"><trackList>$tracks</trackList></playlist>".encodeToByteArray()
        if (fuzz.chance(25)) bytes = fuzz.mutate(bytes)
        return bytes
    }

    private val jsonKeys = listOf(
        "name", "title", "channel", "url", "link", "stream", "stream_url", "src", "source", "file", "logo", "icon", "image",
        "tvg_logo", "group", "category", "genre", "group_title", "tvg_id", "epg_id", "type", "tvg-type", "user_agent", "userAgent", "referrer",
    )

    private fun jsonValue(fuzz: Fuzz): String = when (fuzz.int(10)) {
        0, 1, 2 -> "\"" + url(fuzz).replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        3 -> fuzz.pick("0", "-1", "1.5", "1e308", "1e999", "123456789012345678901234567890", "-0", "true", "false", "null")
        4 -> fuzz.pick("[]", "{}", "[\"a\"]", "{\"a\":1}", "[null]", "\"\"", "\"\\uD800\"", "\"\\u0000\"")
        else -> "\"" + fuzz.text(3).replace("\\", "\\\\").replace("\"", "\\\"").filter { it >= ' ' } + "\""
    }

    private fun jsonPlaylist(fuzz: Fuzz): ByteArray {
        val items = List(fuzz.int(12)) {
            when (fuzz.int(10)) {
                0 -> fuzz.pick("null", "1", "\"x\"", "[]", "true")
                else -> (0 until fuzz.int(7)).joinToString(",", "{", "}") { "\"${fuzz.pick(jsonKeys)}\":${jsonValue(fuzz)}" }
            }
        }.joinToString(",", "[", "]")
        val document = when (fuzz.int(4)) {
            0 -> "{\"${fuzz.pick("channels", "items", "streams", "playlist", "data", "list", "other")}\":$items}"
            1 -> fuzz.pick("[]", "{}", "null", "\"x\"", "0", "[[[]]]", "{\"channels\":{}}")
            else -> items
        }
        var bytes = document.encodeToByteArray()
        if (fuzz.chance(10)) bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes
        if (fuzz.chance(20)) bytes = fuzz.mutate(bytes)
        return bytes
    }

    private fun plainList(fuzz: Fuzz): ByteArray =
        List(1 + fuzz.int(20)) { if (fuzz.chance(15)) "# " + fuzz.text(2) else url(fuzz) }
            .joinToString(fuzz.pick("\n", "\r\n", "\r", "\u2028", "\u0085"), prefix = fuzz.pick("http://example.com/first.ts\n", "rtsp://example.com/a\n", "")).encodeToByteArray()
            .let { if (fuzz.chance(25)) fuzz.mutate(it) else it }

    @Test(timeout = 60_000)
    fun otherFormatsLoadOrFailAsDocumented() {
        val fuzz = Fuzz(seed = 37)
        repeat(OTHER_CASES) { case ->
            load("XSPF case $case", xspf(fuzz))
            load("JSON case $case", jsonPlaylist(fuzz))
            load("plain list case $case", plainList(fuzz))
        }
    }

    @Test(timeout = 60_000)
    fun randomBytesFailAsDocumented() {
        val fuzz = Fuzz(seed = 41)
        val prefixes = listOf("", "", "#EXTM3U\n", "#EXTINF:", "<playlist>", "<?xml version=\"1.0\"?><playlist>", "[", "{\"channels\":", "http://", "\uFEFF")
            .map { it.encodeToByteArray() } + listOf(byteArrayOf(0x1f, 0x8b.toByte()), byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00))
        repeat(RANDOM_CASES) { case ->
            val bytes = fuzz.pick(prefixes) + fuzz.bytes(fuzz.int(3_000))
            load("random case $case", bytes)
        }
        for (size in 0..8) load("short random", fuzz.bytes(size))
    }

    /** Every prefix of each sample playlist, plain and gzip compressed: a download cut anywhere. */
    @Test(timeout = 60_000)
    fun truncatedPlaylistsFailAsDocumented() {
        for (name in listOf("sample.m3u", "sample.xspf", "sample.json")) {
            val full = Fixtures.bytes(name)
            for (size in 0..full.size) load("$name cut at $size", full.copyOf(size))
            val packed = gzip(full)
            for (size in 0..packed.size) load("$name.gz cut at $size", packed.copyOf(size))
        }
    }

    // Lines and lists far longer than real ones

    @Test(timeout = 30_000)
    fun megabyteLinesLoadQuickly() {
        val long = "x".repeat(1_000_000)
        val cases = mapOf(
            "title" to "#EXTM3U\n#EXTINF:-1,$long\nhttp://example.com/a.ts\n",
            "movie title" to "#EXTM3U\n#EXTINF:-1 group-title=\"Movies\",$long (2010)\nhttp://example.com/a.mp4\n",
            "episode title" to "#EXTM3U\n#EXTINF:-1 group-title=\"Series\",Show ${" ".repeat(1_000_000)}S01E01\nhttp://example.com/a.mp4\n",
            "group" to "#EXTM3U\n#EXTINF:-1 group-title=\"${"|".repeat(1_000_000)}\",A\nhttp://example.com/a.ts\n",
            "attribute" to "#EXTM3U\n#EXTINF:-1 tvg-logo=\"$long\" catchup-source=\"${"{utc:".repeat(200_000)}\" catchup=\"default\",A\nhttp://example.com/a.ts\n",
            "unclosed quote" to "#EXTM3U\n#EXTINF:-1 tvg-id=\"$long,A\nhttp://example.com/a.ts\n",
            "attributes" to "#EXTM3U\n#EXTINF:-1" + " a=b".repeat(250_000) + ",A\nhttp://example.com/a.ts\n",
            "url" to "#EXTM3U\n#EXTINF:-1,A\nhttp://example.com/$long.ts\n",
            "url options" to "#EXTM3U\n#EXTINF:-1,A\nhttp://example.com/a.ts|" + "a=%41&".repeat(150_000) + "\n",
            "vlc option" to "#EXTM3U\n#EXTVLCOPT:http-user-agent=$long\n#EXTINF:-1,A\nhttp://example.com/a.ts\n",
            "http header json" to "#EXTM3U\n#EXTHTTP:{\"User-Agent\":\"$long\"}\n#EXTINF:-1,A\nhttp://example.com/a.ts\n",
            "duration" to "#EXTM3U\n#EXTINF:${"9".repeat(1_000_000)},A\nhttp://example.com/a.ts\n",
            "no line break" to "#EXTM3U " + "#EXTINF:-1,A http://example.com/a.ts ".repeat(25_000),
            "plain list" to "http://example.com/$long\n",
            "json" to "[{\"name\":\"$long\",\"url\":\"http://example.com/a.ts\"}]",
            "xspf" to "<playlist><trackList><track><location>http://example.com/a.ts</location><title>$long</title></track></trackList></playlist>",
        )
        for ((label, playlist) in cases) load(label, playlist.encodeToByteArray())
    }

    // XML entities

    /**
     * XSPF goes through the same SAX setup as XMLTV: external entities and DTDs are never fetched, nor read from
     * disk, and entity expansion is bounded. Shown here on the JVM's parser; Android's is Expat, where the empty
     * entity resolver of `Sax` is the shared defence.
     */
    @Test(timeout = 30_000)
    fun xspfNeverResolvesEntities() {
        val secret = File.createTempFile("ondo-entity", ".txt").apply {
            writeText("ENTITY-MARKER")
            deleteOnExit()
        }
        val server = MockWebServer()
        server.start()
        try {
            val remote = server.url("/x.dtd").toString()
            val track = "<track><location>http://example.com/a.ts</location><title>%s</title></track>"
            val laughs = (1..9).joinToString("") { level -> "<!ENTITY lol$level \"" + "&lol${level - 1};".repeat(10) + "\">" }
            val documents = listOf(
                "<!DOCTYPE playlist [<!ENTITY xxe SYSTEM \"${secret.toURI()}\">]><playlist><trackList>${track.format("&xxe;")}</trackList></playlist>",
                "<!DOCTYPE playlist [<!ENTITY xxe SYSTEM \"$remote\">]><playlist><trackList>${track.format("&xxe;")}</trackList></playlist>",
                "<!DOCTYPE playlist SYSTEM \"$remote\"><playlist><trackList>${track.format("A")}</trackList></playlist>",
                "<!DOCTYPE playlist [<!ENTITY % ext SYSTEM \"$remote\"> %ext;]><playlist><trackList>${track.format("A")}</trackList></playlist>",
                "<!DOCTYPE playlist [<!ENTITY lol0 \"lol\">$laughs]><playlist><trackList>${track.format("&lol9;")}</trackList></playlist>",
                "<!DOCTYPE playlist [<!ENTITY a \"${"a".repeat(50_000)}\">]><playlist><trackList>${track.format("&a;".repeat(50_000))}</trackList></playlist>",
                "<playlist><trackList>" + "<track>".repeat(100_000) + "</trackList></playlist>",
            )
            for ((index, document) in documents.withIndex()) {
                val content = load("entity document $index", document.encodeToByteArray()) ?: continue
                for (channel in content.channels) {
                    assertTrue("ENTITY-MARKER" !in channel.name, "a file was read into document $index")
                    assertTrue(channel.name.length < 100_000, "document $index expanded to ${channel.name.length} characters")
                }
            }
            assertEquals(0, server.requestCount, "an external entity or DTD was fetched")
        } finally {
            server.shutdown()
        }
    }

    private companion object {
        const val M3U_CASES = 3_000
        const val OTHER_CASES = 1_500
        const val RANDOM_CASES = 3_000
    }
}
