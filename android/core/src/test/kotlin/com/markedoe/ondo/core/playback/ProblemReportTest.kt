package com.markedoe.ondo.core.playback

import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.MediaReference
import com.markedoe.ondo.core.model.PlaybackItem
import java.net.URLDecoder
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A problem report leaves the device: it must never carry an address, a login, a host or a title. */
class ProblemReportTest {
    // Fictitious login on example.com, the very thing a report must never carry.
    private val secretUrl = "http://viewer:secret-pass@panel.example.com:8080/movie/viewer/secret-pass/42.mkv" // leak-guard: allow
    private val secrets = listOf("secret-pass", "viewer", "example.com", "example.net", "example.org", "203.0.113.7", "2001:db8", "abc123", "someone")

    @Test
    fun theScrubberRemovesAddressesLoginsHostsAndIps() {
        val samples = listOf(
            "Failed to open $secretUrl (error -1100)",
            "redirected to https://cdn.example.net/hls/index.m3u8?token=abc123",
            "login viewer:secret-pass@panel.example.com", // leak-guard: allow
            "server 203.0.113.7:8080 refused",
            "IPv6 2001:db8:0:0:0:0:0:1 unreachable",
            "contact someone@example.org",
            "host panel.example.com timed out",
        )
        for (text in samples) {
            val scrubbed = PrivacyScrubber.scrub(text)
            for (secret in secrets) assertFalse(secret in scrubbed, "$secret left in: $scrubbed")
        }
        assertEquals("redirected to <address>", PrivacyScrubber.scrub(samples[1]))
        assertEquals("server <ip> refused", PrivacyScrubber.scrub(samples[3]))
        assertEquals("IPv6 <ip> unreachable", PrivacyScrubber.scrub(samples[4]))
        assertEquals("contact <e-mail>", PrivacyScrubber.scrub(samples[5]))
        assertEquals("host <host> timed out", PrivacyScrubber.scrub(samples[6]))
    }

    @Test
    fun theScrubberKeepsTimingsAndVersions() {
        assertEquals("seek to 600.0 s done after 0.4 s", PrivacyScrubber.scrub("seek to 600.0 s done after 0.4 s"))
        assertEquals("Android 15, Media3 1.11.1", PrivacyScrubber.scrub("Android 15, Media3 1.11.1"))
        assertEquals("error ERROR_CODE_IO_BAD_HTTP_STATUS: Response code: 404", PrivacyScrubber.scrub("error ERROR_CODE_IO_BAD_HTTP_STATUS: Response code: 404"))
    }

    @Test
    fun theLogKeepsTheLastLinesScrubbed() {
        val log = PlaybackLog()
        for (index in 0 until PlaybackLog.LIMIT + 10) log.add("event $index", index.toDouble())
        assertEquals(PlaybackLog.LIMIT, log.entries.size)
        assertEquals("event 10", log.entries.first().event)
        log.add("opening $secretUrl", 1.0)
        assertEquals("opening <address>", log.entries.last().event)
    }

    @Test
    fun kindAndContainerNameNothingElseOfTheItem() {
        val reference = MediaReference("p", ContentType.MOVIE, "m1")
        val film = PlaybackItem(title = "The Secret Title", urls = listOf(secretUrl), reference = reference, isLive = false)
        assertEquals("film", ProblemReport.kind(film))
        assertEquals("episode", ProblemReport.kind(film.copy(reference = reference.copy(type = ContentType.SERIES, episodeId = "e1"))))
        assertEquals("live channel", ProblemReport.kind(film.copy(isLive = true)))
        assertEquals("catch-up", ProblemReport.kind(film.copy(isCatchup = true)))
        assertEquals("mkv", ProblemReport.container(secretUrl))
        assertEquals("m3u8", ProblemReport.container("https://cdn.example.net/hls/index.m3u8?token=abc123"))
        assertEquals("ts", ProblemReport.container("http://example.com/live/a/b/1.TS#start"))
        assertNull(ProblemReport.container("http://example.com/live/a/b/1"))
        assertEquals("php", ProblemReport.container("http://example.com/play.php?id=1.mkv"), "the query is not the address's extension")
        assertNull(ProblemReport.container("http://example.com/dots.in.folder/stream"))
        assertNull(ProblemReport.container("http://example.com/file.verylong"))
    }

    @Test
    fun theReportHoldsTimingsAndNoPrivateData() {
        // A fixture item: the title and the address go in, only its kind and container come out.
        val item = PlaybackItem(
            title = "The Secret Title",
            urls = listOf(secretUrl, "https://cdn.example.net/hls/index.m3u8?token=abc123"),
            reference = MediaReference("p", ContentType.MOVIE, "m1"),
            isLive = false,
        )
        val log = PlaybackLog()
        log.add("open film, from 120 s; buffer 5 s", 0.0)
        log.add("address 1 of 2 (.${ProblemReport.container(item.streamUrl)})", 0.0)
        log.add("picture moving after 1.4 s", 1.4)
        log.add("seek from 10.0 to 20.0 s", 12.0)
        log.add("seek done after 0.6 s", 12.6)
        log.add("error ERROR_CODE_IO_NETWORK_CONNECTION_FAILED: The server ${item.streamUrl} did not answer", 15.0)
        log.add("fallback to address 2 of 2: ${item.urls[1]}", 15.0)
        val context = ProblemReport.Context(
            app = "Ondo 1.0.0 (12)",
            system = "Android 15 (API 35), Google Pixel 8",
            engine = "Media3 1.11.1",
            liveBufferSetting = 0,
            vodBufferSetting = 5,
            bufferInUse = PlaybackBuffer.durations(5)!!,
            kind = ProblemReport.kind(item),
            container = ProblemReport.container(item.streamUrl),
            video = "1920×1080",
            duration = 5400.0,
            position = 20.0,
            state = "failed: The stream ${item.streamUrl} could not be opened",
            statistics = listOf("Video: video/avc, avc1.64001F, 25 fps", "Bandwidth estimate: 12.34 Mb/s from ${item.urls[1]}"),
        )
        val report = ProblemReport(context, log, generated = Instant.parse("2026-10-16T10:00:00.250Z")).text
        for (expected in listOf(
            "Ondo playback report, 2026-10-16T10:00:00Z",
            "Engine: Media3 1.11.1",
            "live automatic, films and series 5 s",
            "5.0 s to start, 5.0 s after rebuffering, 15.0 to 50.0 s ahead",
            "Playing: film, .mkv, 1920×1080",
            "Duration: 5400.0 s",
            "Video: video/avc, avc1.64001F, 25 fps",
            "  1.4 picture moving after 1.4 s",
            "  12.6 seek done after 0.6 s",
        )) {
            assertTrue(expected in report, "missing $expected in\n$report")
        }
        for (secret in secrets + listOf("panel", "http", "42.mkv", "index.m3u8", "token", "The Secret Title", "Secret")) {
            assertFalse(secret in report, "$secret in the report:\n$report")
        }
    }

    @Test
    fun aLiveReportLeavesOutDurationAndPosition() {
        val context = ProblemReport.Context(
            app = "Ondo", system = "Android", engine = "Media3", liveBufferSetting = 3, vodBufferSetting = 0,
            bufferInUse = PlaybackBuffer.durations(3)!!, kind = "live channel", state = "playing",
        )
        val report = ProblemReport(context, PlaybackLog()).text
        assertTrue("Buffer: 3.0 s to start, 3.0 s after rebuffering, 15.0 to 50.0 s ahead in use; live 3 s, films and series automatic" in report, report)
        assertTrue("Playing: live channel\n" in report, report)
        assertFalse("Duration" in report)
        assertFalse("Position" in report)
    }

    @Test
    fun theIssueAddressCarriesTheStartOfTheReport() {
        val report = "Ondo playback report\n" + "x".repeat(3_000)
        val url = ProblemReport.issueUrl(report)
        assertTrue(url.startsWith("https://github.com/markedoe/ondo-iptv/issues/new?title=Playback%20problem&body="), url)
        assertFalse(" " in url || "\n" in url || "+" in url, url)
        val body = URLDecoder.decode(url.substringAfter("&body="), "UTF-8")
        assertTrue(body.startsWith("<!-- Describe what you saw. -->\n\n```\nOndo playback report\n"), body)
        assertTrue(body.endsWith("x\n…\n```"), body.takeLast(20))
        assertEquals(ProblemReport.ISSUE_REPORT_LIMIT, body.count { it == 'x' } + "Ondo playback report\n".length)
        val short = URLDecoder.decode(ProblemReport.issueUrl("short").substringAfter("&body="), "UTF-8")
        assertEquals("<!-- Describe what you saw. -->\n\n```\nshort\n```", short)
    }
}
