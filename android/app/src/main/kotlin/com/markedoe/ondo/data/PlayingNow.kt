package com.markedoe.ondo.data

import java.io.File
import java.util.concurrent.Executors

/**
 * What plays, kept on the device while it plays, so that a restart of the whole device in the middle of a film (the
 * TV's video decoder, its temperature, the device's memory) leaves a trace: Android records no reason for the app
 * then. The player rewrites it every 30 s and when the format changes, and deletes it when playback stops as it
 * should (pause, Back, the end of the video). Found at the next start (`OndoApplication`), it becomes the last
 * crash if the device has started again since it was written. It holds the formats, decoders and memory figures,
 * never an address, a provider or a title.
 */
object PlayingNow {
    const val FILE_NAME = "playing-now.txt"
    const val RESTART_REASON = "the device restarted during playback"

    /** [at] is the wall clock when it was written; [bootCount] the system's count of device starts, if it keeps one. */
    class Record(val at: Long, val bootCount: Int?, val details: String)

    /** One thread for every write and deletion, in order: a write queued before a deletion cannot bring the record back. */
    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "ondo-playing-now").apply { isDaemon = true } }

    /** Runs [task] off the main thread, after the writes asked for before it. */
    fun later(task: () -> Unit) {
        runCatching { writer.execute { runCatching(task) } }
    }

    fun save(file: File, record: Record) {
        try {
            file.parentFile?.mkdirs()
            val temporary = File(file.path + ".new")
            temporary.writeText("${record.at}\n${record.bootCount ?: NO_COUNT}\n${CrashLog.redact(record.details)}")
            if (!temporary.renameTo(file)) {
                file.delete()
                temporary.renameTo(file)
            }
        } catch (_: Throwable) {
        }
    }

    fun clear(file: File) {
        runCatching { file.delete() }
    }

    fun read(file: File): Record? = try {
        if (!file.exists()) {
            null
        } else {
            val lines = file.readText().split('\n', limit = 3)
            val at = lines.getOrNull(0)?.toLongOrNull()
            if (at == null || lines.size < 3) null else Record(at, lines[1].toIntOrNull(), lines[2].trim())
        }
    } catch (_: Exception) {
        null
    }

    /** The record left by the last run, deleted: read once, at the start of the app. */
    fun take(file: File): Record? = read(file).also { clear(file) }

    /**
     * Whether the device has started again since [record] was written: its count of starts changed when both are
     * known (a TV box without a clock battery may read a wrong time until it reaches the network), or else the
     * record is older than the device's start, [bootAt] (`System.currentTimeMillis() - SystemClock.elapsedRealtime()`).
     */
    fun deviceRestarted(record: Record, bootAt: Long, bootCount: Int?): Boolean =
        if (record.bootCount != null && bootCount != null) bootCount != record.bootCount else record.at < bootAt

    /**
     * Records in [crashFile] that the device restarted while [record] played, if it did. A record newer than the
     * device's start is an app ended without a reboot: Android's own reason covers it (`recordLastExit`).
     */
    fun recordRestart(crashFile: File, about: String, record: Record, bootAt: Long, bootCount: Int?): Boolean {
        if (!deviceRestarted(record, bootAt, bootCount)) return false
        CrashLog.recordExit(crashFile, about, CrashLog.SystemExit(record.at, RESTART_REASON, null, null, null, details = record.details))
        return true
    }

    private const val NO_COUNT = "-"
}
