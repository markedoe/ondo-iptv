package com.markedoe.ondo.data

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

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

    /** Only the last of the writes and deletions asked for meanwhile runs: a burst (formats, decoders) writes once. */
    private val latest = LatestTask(writer::execute)

    /**
     * Runs [task] off the main thread, after the one running now. A task still waiting is replaced by [task]: only the
     * latest state of the record matters (a deletion asked after a write wins over it, a write after a deletion too).
     */
    fun later(task: () -> Unit) = latest.submit(task)

    /**
     * Writes [record] to a temporary file, synced to the disk, then renamed over [file]: a restart of the device right
     * then leaves the previous record or this one, never an empty or half-written file.
     */
    fun save(file: File, record: Record) {
        try {
            file.parentFile?.mkdirs()
            val temporary = File(file.path + ".new")
            FileOutputStream(temporary).use { stream ->
                stream.write("${record.at}\n${record.bootCount ?: NO_COUNT}\n${CrashLog.redact(record.details)}".encodeToByteArray())
                stream.fd.sync()
            }
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

/**
 * Runs the tasks [submit]ted through [execute] one at a time, keeping only the latest of those not started yet: at most
 * one waits, and a newer one takes its place.
 */
internal class LatestTask(private val execute: (Runnable) -> Unit) {
    private val waiting = AtomicReference<(() -> Unit)?>(null)

    fun submit(task: () -> Unit) {
        // One run is queued for whatever waits; a task arriving while one waits only takes its place.
        if (waiting.getAndSet(task) != null) return
        try {
            execute(Runnable { waiting.getAndSet(null)?.let { runCatching(it) } })
        } catch (_: Exception) {
            // Refused (never with the app's thread): nothing waits for a run that will not come.
            waiting.set(null)
        }
    }
}
