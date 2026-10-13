package com.markedoe.ondo.core.catchup

import com.markedoe.ondo.core.DAY
import com.markedoe.ondo.core.HOUR
import com.markedoe.ondo.core.library.PlaybackFactory
import com.markedoe.ondo.core.model.CatchupInfo
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.EpgProgram
import com.markedoe.ondo.core.model.HttpHeaders
import com.markedoe.ondo.core.model.Playlist
import com.markedoe.ondo.core.model.PlaylistSource
import com.markedoe.ondo.core.model.XtreamCredentials
import com.markedoe.ondo.core.model.XtreamOutputFormat
import com.markedoe.ondo.core.utc
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatchupTest {
    private val start = utc(2025, 10, 9, 18, 0)
    private val end = utc(2025, 10, 9, 18, 45)
    private val now = utc(2025, 10, 9, 20, 0)

    private fun channel(url: String, catchup: CatchupInfo?, xtreamId: Int? = null) =
        Channel(id = "c", name = "C", categoryId = "x", streamUrl = url, catchup = catchup, xtreamStreamId = xtreamId)

    @Test
    fun templateTokens() {
        val template = "{utc}|\${start}|{utcend}|{lutc}|{duration}|{duration:60}|{offset}|{offset:60}|{Y}-{m}-{d} {H}:{M}:{S}|{utc:Y-m-d:H-M}|{end:YmdHMS}|{unknown}"
        assertEquals(
            "1760032800|1760032800|1760035500|1760040000|2700|45|7200|120|2025-10-09 18:00:00|2025-10-09:18-00|20251009184500|{unknown}",
            CatchupUrlBuilder.fill(template, start, end, now),
        )
    }

    @Test
    fun moreTemplateTokens() {
        assertEquals(
            "1760035500|1760040000|1760040000|1760040000|2700|1760032800|2025|7200",
            CatchupUrlBuilder.fill("\${end}|{now}|{timestamp}|{current}|{duration:0}|{timestamp_start}|{utc:Y}|{offset:x}", start, end, now),
            "a non-numeric divider is ignored for durations",
        )
        assertEquals("a{b}c", CatchupUrlBuilder.fill("a{b}c", start, end, now))
    }

    @Test
    fun defaultMode() {
        val c = channel(
            "http://a.example.com/live.m3u8",
            CatchupInfo(CatchupInfo.Mode.DEFAULT, 3, "http://archive.example.com/arte?start={utc}&end={utcend}"),
        )
        assertEquals(
            listOf("http://archive.example.com/arte?start=1760032800&end=1760035500"),
            CatchupUrlBuilder.urls(c, start, end, now),
        )
    }

    @Test
    fun defaultModeWithRelativeTemplateOrNoSource() {
        val relative = channel("http://a.example.com/live.m3u8", CatchupInfo(CatchupInfo.Mode.DEFAULT, 3, "?archive={utc}"))
        assertEquals(listOf("http://a.example.com/live.m3u8?archive=1760032800"), CatchupUrlBuilder.urls(relative, start, end, now))
        val noSource = channel("http://a.example.com/live.m3u8", CatchupInfo(CatchupInfo.Mode.DEFAULT, 3))
        assertEquals(listOf("http://a.example.com/live.m3u8?utc=1760032800&lutc=1760040000"), CatchupUrlBuilder.urls(noSource, start, end, now))
    }

    @Test
    fun appendMode() {
        val c = channel("http://a.example.com/live.m3u8?token=1", CatchupInfo(CatchupInfo.Mode.APPEND, 3, "?utc={utc}&lutc={lutc}"))
        assertEquals(
            "http://a.example.com/live.m3u8?token=1&utc=1760032800&lutc=1760040000",
            CatchupUrlBuilder.urls(c, start, end, now).first(),
        )
        val noSource = channel("http://a.example.com/live.m3u8", CatchupInfo(CatchupInfo.Mode.APPEND, 3))
        assertEquals("http://a.example.com/live.m3u8?utc=1760032800&lutc=1760040000", CatchupUrlBuilder.urls(noSource, start, end, now).first())
    }

    @Test
    fun shiftMode() {
        val c = channel("http://a.example.com/live.m3u8", CatchupInfo(CatchupInfo.Mode.SHIFT, 3))
        assertEquals("http://a.example.com/live.m3u8?utc=1760032800&lutc=1760040000", CatchupUrlBuilder.urls(c, start, end, now).first())
    }

    @Test
    fun flussonicModes() {
        val hls = channel("http://fl.example.com/france2/index.m3u8?token=abc", CatchupInfo(CatchupInfo.Mode.FLUSSONIC, 3))
        assertEquals("http://fl.example.com/france2/index-1760032800-2700.m3u8?token=abc", CatchupUrlBuilder.urls(hls, start, end, now).first())
        val ts = channel("http://fl.example.com/france2/mpegts?token=abc", CatchupInfo(CatchupInfo.Mode.FLUSSONIC, 3))
        assertEquals("http://fl.example.com/france2/timeshift_abs-1760032800.ts?token=abc", CatchupUrlBuilder.urls(ts, start, end, now).first())
        val other = channel("http://fl.example.com/france2/", CatchupInfo(CatchupInfo.Mode.FLUSSONIC, 3))
        assertEquals("http://fl.example.com/france2/timeshift_abs-1760032800.ts", CatchupUrlBuilder.urls(other, start, end, now).first())
    }

    @Test
    fun xtreamModeFromAccount() {
        val credentials = XtreamCredentials("http://p.example.com:8080", "u", "p")
        val c = channel("http://p.example.com:8080/live/u/p/101.m3u8", CatchupInfo(CatchupInfo.Mode.XTREAM, 7), xtreamId = 101)
        val context = CatchupContext(credentials, ZoneId.of("Europe/Paris"), XtreamOutputFormat.M3U8)
        val urls = CatchupUrlBuilder.urls(c, start, end, now, context)
        assertEquals("http://p.example.com:8080/timeshift/u/p/45/2025-10-09:20-00/101.m3u8", urls.first())
        assertEquals(3, urls.size)
        assertTrue(urls[2].contains("timeshift.php"))
        val program = EpgProgram("c", start, end, "Show")
        assertEquals(urls.first(), CatchupUrlBuilder.url(c, program, context, now))
    }

    @Test
    fun xtreamModeFromPlainUrl() {
        val c = channel("http://p.example.com:8080/u/p/7", CatchupInfo(CatchupInfo.Mode.XTREAM, 7))
        val urls = CatchupUrlBuilder.urls(c, start, end, now, CatchupContext(outputFormat = XtreamOutputFormat.TS))
        assertEquals("http://p.example.com:8080/timeshift/u/p/45/2025-10-09:18-00/7.ts", urls.first())
        assertEquals("http://p.example.com:8080/timeshift/u/p/45/2025-10-09:18-00/7.m3u8", urls[1])
        val notXtream = channel("http://a.example.com/live.m3u8", CatchupInfo(CatchupInfo.Mode.XTREAM, 7))
        assertTrue(CatchupUrlBuilder.urls(notXtream, start, end, now).isEmpty())
    }

    @Test
    fun noCatchupNoUrl() {
        val c = channel("http://a.example.com/live.m3u8", null)
        assertTrue(CatchupUrlBuilder.urls(c, start, end, now).isEmpty())
        assertNull(CatchupUrlBuilder.url(c, EpgProgram("c", start, end, "Show"), now = now))
    }

    @Test
    fun availability() {
        val c = channel("http://x/1.m3u8", CatchupInfo(CatchupInfo.Mode.SHIFT, 1))
        val past = EpgProgram("c", now - 3 * HOUR, now - 2 * HOUR, "Old")
        val tooOld = EpgProgram("c", now - 3 * DAY, now - 3 * DAY + HOUR, "Too old")
        val future = EpgProgram("c", now + HOUR, now + 2 * HOUR, "Later")
        assertTrue(CatchupUrlBuilder.isAvailable(c, past, now))
        assertFalse(CatchupUrlBuilder.isAvailable(c, tooOld, now))
        assertFalse(CatchupUrlBuilder.isAvailable(c, future, now))
        assertFalse(CatchupUrlBuilder.isAvailable(channel("http://x/1.m3u8", null), past, now))
        assertTrue(CatchupUrlBuilder.isAvailable(c, now - DAY - HOUR, now), "one hour of slack")
        assertFalse(CatchupUrlBuilder.isAvailable(c, now - DAY - HOUR - 1, now))
    }

    @Test
    fun playbackFactory() {
        val playlist = Playlist(name = "P", source = PlaylistSource.Remote("http://x/list.m3u"), userAgent = "Global")
        val factory = PlaybackFactory(playlist)
        val c = channel("http://p.example.com:8080/live/u/p/101.ts", null).copy(headers = HttpHeaders(referrer = "http://ref"))
        val item = factory.item(c)
        assertTrue(item.isLive)
        assertEquals(listOf("http://p.example.com:8080/live/u/p/101.ts", "http://p.example.com:8080/live/u/p/101.m3u8"), item.urls)
        assertEquals(mapOf("User-Agent" to "Global", "Referer" to "http://ref"), item.headers?.toMap())
        assertEquals(ContentType.LIVE, item.reference.type)

        val backup = channel("http://backup.example.com/tf1.m3u8", null).copy(id = "backup")
        val withAlternates = factory.item(c, alternates = listOf(c, backup))
        assertEquals(
            listOf(
                "http://p.example.com:8080/live/u/p/101.ts",
                "http://p.example.com:8080/live/u/p/101.m3u8",
                "http://backup.example.com/tf1.m3u8",
            ),
            withAlternates.urls,
        )
    }
}
