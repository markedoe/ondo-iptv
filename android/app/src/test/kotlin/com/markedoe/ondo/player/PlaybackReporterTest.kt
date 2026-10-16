package com.markedoe.ondo.player

import androidx.media3.common.Player
import com.markedoe.ondo.core.playback.PlaybackBuffer
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The report of a playback: its timings, and nothing of the address, the login or the title. */
class PlaybackReporterTest {
    // Fictitious login on example.com, the very thing a report must never carry.
    private val secretUrl = "http://viewer:secret-pass@panel.example.com:8080/movie/viewer/secret-pass/42.mkv" // leak-guard: allow
    private val request = PlayRequest(
        title = "The Secret Title",
        subtitle = "S01E02 · A Private Name",
        urls = listOf(secretUrl, "https://cdn.example.net/hls/index.m3u8?token=abc123"),
        isLive = false,
        artworkUrl = "https://images.example.org/poster.jpg",
        startPositionMs = 120_000,
        kind = "episode",
        liveBuffer = 0,
        vodBuffer = 5,
    )

    private var now = 1_000_000L
    private val reporter = PlaybackReporter(request) { now }

    private fun at(seconds: Double) {
        now = 1_000_000L + (seconds * 1_000).toLong()
    }

    private fun snapshot(state: String) = PlaybackReporter.PlayerSnapshot(
        app = "Ondo 1.0.0 (1)",
        system = "Android 15 (API 35), Google Pixel 8",
        engine = "Media3 1.11.1",
        bufferInUse = PlaybackBuffer.durations(request.bufferSeconds)!!,
        video = "1920×1080",
        durationMs = 2_700_000,
        positionMs = 600_000,
        state = state,
        statistics = listOf("Video: video/avc, avc1.64001F, 25.00 fps"),
    )

    @Test
    fun theReportTimesTheStartTheSeeksAndTheRebufferings() {
        reporter.attempt(0, request.urls[0])
        reporter.playbackState(Player.STATE_BUFFERING, playWhenReady = true)
        at(1.4)
        reporter.playbackState(Player.STATE_READY, playWhenReady = true)
        reporter.isPlaying(true)
        reporter.firstFrame()
        reporter.bandwidth(8_000_000)
        at(10.0)
        reporter.seek(130_000, 600_000)
        reporter.playbackState(Player.STATE_BUFFERING, playWhenReady = true)
        at(10.6)
        reporter.playbackState(Player.STATE_READY, playWhenReady = true)
        reporter.firstFrame()
        at(20.0)
        reporter.playbackState(Player.STATE_BUFFERING, playWhenReady = true)
        at(22.5)
        reporter.playbackState(Player.STATE_READY, playWhenReady = true)
        reporter.droppedFrames(12, 2_000)
        at(30.0)
        reporter.error("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", listOf("Unable to connect to $secretUrl", "connect timed out"))
        reporter.note("fallback to the next address")
        reporter.attempt(1, request.urls[1])
        reporter.playbackState(Player.STATE_BUFFERING, playWhenReady = true)

        val report = reporter.report(snapshot("buffering"), generated = Instant.parse("2026-10-16T10:00:00Z"))
        for (expected in listOf(
            "Buffer: 5.0 s to start, 5.0 s after rebuffering, 15.0 to 50.0 s ahead in use; live automatic, films and series 5 s",
            "Playing: episode, .m3u8, 1920×1080",
            "Duration: 2700.0 s",
            "Position: 600.0 s",
            "  0.0 open episode, from 120 s; buffer 5 s",
            "  0.0 address 1 of 2 (.mkv)",
            "  1.4 picture moving after 1.4 s",
            "  10.0 seek from 130.0 to 600.0 s",
            "  10.6 seek done after 0.6 s",
            "  22.5 rebuffering for 2.5 s, bandwidth estimate 8.00 Mb/s",
            "  22.5 12 video frames dropped in 2.0 s",
            "  30.0 error ERROR_CODE_IO_NETWORK_CONNECTION_FAILED: Unable to connect to <address> / connect timed out",
            "  30.0 address 2 of 2 (.m3u8)",
            "Rebufferings: 1, 2.5 s",
            "Dropped video frames: 12",
            "Bandwidth estimate: 8.00 Mb/s",
        )) {
            assertTrue("missing $expected in\n$report", expected in report)
        }
        // The start of an address and the wait after a seek are not rebufferings.
        assertFalse(report, "rebuffering for 0.6 s" in report || "rebuffering for 1.4 s" in report)
        for (secret in listOf("secret-pass", "viewer", "example", "http", "42.mkv", "index.m3u8", "token", "abc123", "Secret", "Private", "poster")) {
            assertFalse("$secret in the report:\n$report", secret in report)
        }
    }

    @Test
    fun aPauseEndsARebufferingAndALiveReportHasNoPosition() {
        val live = PlaybackReporter(request.copy(isLive = true, kind = "live channel", liveBuffer = 0, startPositionMs = 0)) { now }
        live.attempt(0, "http://example.com/live/1.ts")
        live.isPlaying(true)
        at(5.0)
        live.playbackState(Player.STATE_BUFFERING, playWhenReady = true)
        at(6.0)
        live.playWhenReady(false)
        at(60.0)
        live.playbackState(Player.STATE_READY, playWhenReady = false)
        val report = live.report(snapshot("paused").copy(bufferInUse = PlaybackBuffer.Durations(50_000, 50_000, 1_000, 2_000)))
        assertTrue(report, "  6.0 rebuffering for 1.0 s" in report)
        assertTrue(report, "Rebufferings: 1, 1.0 s" in report)
        assertTrue(report, "open live channel; buffer automatic" in report)
        assertTrue(report, "1.0 s to start, 2.0 s after rebuffering, 50.0 to 50.0 s ahead in use; live automatic" in report)
        assertFalse(report, "Position" in report || "Duration" in report)
    }
}
