package com.markedoe.ondo.data

import java.util.concurrent.ConcurrentHashMap

/**
 * The moments of a launch and of a playlist's first download, timed where they happen (the repository, the view
 * model) and written where [sink] says: the system log in the app (`OndoLaunch`, read by the TV release check), the
 * report of `ColdStartTimingTest` in its run. Durations only, never a title or an address. Nothing is timed without a
 * sink, and a sink that fails stops nothing.
 */
object LaunchMarks {
    /** The marks' tag in the system log. */
    const val TAG = "OndoLaunch"

    /** Where the marks go; null: nowhere. */
    @Volatile var sink: ((String) -> Unit)? = null

    @Volatile private var origin = System.nanoTime()

    private val seen = ConcurrentHashMap<String, Boolean>()

    /** A launch began [agoMs] ago (the process's start in the app): the marks count from it, each once again. */
    fun begin(agoMs: Long = 0L) {
        origin = System.nanoTime() - agoMs * 1_000_000L
        seen.clear()
    }

    private fun sinceBegin(): Long = (System.nanoTime() - origin) / 1_000_000L

    /** [name], the first time only in a launch: how long after it began. */
    fun mark(name: String) {
        val write = sink ?: return
        if (seen.putIfAbsent(name, true) != null) return
        runCatching { write("$name +${sinceBegin()} ms") }
    }

    /** [name], each time: how long after the launch began, and after [startNanos] ([System.nanoTime]). */
    fun timed(name: String, startNanos: Long) {
        val write = sink ?: return
        runCatching { write("$name +${sinceBegin()} ms (${(System.nanoTime() - startNanos) / 1_000_000L} ms)") }
    }
}
