package com.markedoe.ondo.data

import com.markedoe.ondo.HangGuard
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The record of what plays: a restart of the whole device during playback becomes the last crash, with what played. */
class PlayingNowTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val folder = TemporaryFolder()

    private val about = "Ondo 1.4.0 (1100) · Android 11 (API 30) · Test TV\nmemory class 192 MB, large 384 MB, max heap 384 MB, low RAM false"
    private val details = listOf(
        "Playing: film",
        "Video: video/hevc, hvc1.2.4.L153.B0, 3840×2160, 23.98 fps",
        "Colour: HDR (PQ), BT.2020; Dolby Vision: yes",
        "Decoders: video c2.vendor.hevc.decoder, audio c2.android.aac.decoder",
        "Tunneling: off",
        "Device memory: 310 of 1900 MB available, low memory no (below 216 MB)",
        "Thermal status: moderate",
    ).joinToString("\n")

    private val record get() = File(folder.root, PlayingNow.FILE_NAME)
    private val crash get() = File(folder.root, "ondo/${CrashLog.FILE_NAME}")

    /** What the app does at its start, with the clock and the device's start given. */
    private fun start(now: Long, sinceBoot: Long, bootCount: Int? = null): Boolean {
        val playing = PlayingNow.take(record) ?: return false
        return PlayingNow.recordRestart(crash, about, playing, bootAt = now - sinceBoot, bootCount = bootCount)
    }

    @Test
    fun aRecordOlderThanTheDevicesStartIsARestartDuringPlayback() {
        PlayingNow.save(record, PlayingNow.Record(at = 1_760_000_000_000L, bootCount = null, details = details))
        // Started again 2 minutes later, up for 60 s.
        assertTrue(start(now = 1_760_000_120_000L, sinceBoot = 60_000L))
        val last = requireNotNull(CrashLog.read(crash))
        assertEquals(1_760_000_000_000L, last.at)
        assertEquals("$about\nStopped by Android: ${PlayingNow.RESTART_REASON}\nPlayback (last record before it):\n$details", last.report)
        // Read once: the next start records nothing more.
        assertFalse(record.exists())
        assertFalse(start(now = 1_760_000_300_000L, sinceBoot = 240_000L))
    }

    @Test
    fun aCleanStopLeavesNothing() {
        PlayingNow.save(record, PlayingNow.Record(at = 1_760_000_000_000L, bootCount = 12, details = details))
        PlayingNow.clear(record)
        assertFalse(start(now = 1_760_000_120_000L, sinceBoot = 60_000L, bootCount = 13))
        assertNull(CrashLog.read(crash))
    }

    /** The app ended without the device restarting: Android's own reason covers it, as before. */
    @Test
    fun aRecordNewerThanTheDevicesStartIsNoRestart() {
        PlayingNow.save(record, PlayingNow.Record(at = 1_760_000_000_000L, bootCount = null, details = details))
        // The device has been up for an hour.
        assertFalse(start(now = 1_760_000_120_000L, sinceBoot = 3_600_000L))
        assertNull(CrashLog.read(crash))
        assertFalse(record.exists())
    }

    /** A TV box without a clock battery can read a wrong time after a restart: the count of starts decides then. */
    @Test
    fun theCountOfStartsWinsOverTheClock() {
        val playing = PlayingNow.Record(at = 1_760_000_000_000L, bootCount = 41, details = details)
        assertTrue(PlayingNow.deviceRestarted(playing, bootAt = 1_000_000L, bootCount = 42))
        assertFalse(PlayingNow.deviceRestarted(playing, bootAt = 1_760_000_500_000L, bootCount = 41))
        assertTrue(PlayingNow.deviceRestarted(playing, bootAt = 1_760_000_500_000L, bootCount = null))
    }

    @Test
    fun theRecordKeepsNoAddress() {
        PlayingNow.save(record, PlayingNow.Record(at = 1L, bootCount = 3, details = "Playing: film\nerror at http://panel.example.com/movie/demo/secret/1.mkv"))
        val read = requireNotNull(PlayingNow.read(record))
        assertEquals(3, read.bootCount)
        assertFalse(read.details, read.details.contains("panel.example.com"))
        assertFalse(read.details, read.details.contains("secret"))
    }

    @Test
    fun anUnreadableRecordIsNone() {
        record.writeText("not a date\n-\nsomething")
        assertNull(PlayingNow.read(record))
    }

    /** A record written over another: the new one replaces it whole, and no temporary file stays behind. */
    @Test
    fun aRecordReplacesThePreviousOneWhole() {
        PlayingNow.save(record, PlayingNow.Record(at = 1L, bootCount = 3, details = "Playing: film"))
        PlayingNow.save(record, PlayingNow.Record(at = 2L, bootCount = 3, details = "Playing: live channel"))
        val read = requireNotNull(PlayingNow.read(record))
        assertEquals(2L, read.at)
        assertEquals("Playing: live channel", read.details)
        assertEquals(listOf(PlayingNow.FILE_NAME), folder.root.list()?.toList())
    }

    /** A burst of writes (formats, decoders) while one waits: only the latest runs, then the next one asked for. */
    @Test
    fun onlyTheLatestOfTheWritesWaitingRuns() {
        val queued = mutableListOf<Runnable>()
        val ran = mutableListOf<String>()
        val latest = LatestTask { queued += it }
        latest.submit { ran += "format" }
        latest.submit { ran += "decoder" }
        latest.submit { ran += "cleared" }
        assertEquals("one run is queued for the burst", 1, queued.size)
        queued.removeAt(0).run()
        assertEquals(listOf("cleared"), ran)

        latest.submit { ran += "again" }
        assertEquals(1, queued.size)
        queued.removeAt(0).run()
        assertEquals(listOf("cleared", "again"), ran)
    }

    /** A task that fails does not stop the next ones. */
    @Test
    fun aFailedWriteDoesNotStopTheNextOnes() {
        val queued = mutableListOf<Runnable>()
        val ran = mutableListOf<String>()
        val latest = LatestTask { queued += it }
        latest.submit { error("disk full") }
        queued.removeAt(0).run()
        latest.submit { ran += "next" }
        queued.removeAt(0).run()
        assertEquals(listOf("next"), ran)
    }
}
