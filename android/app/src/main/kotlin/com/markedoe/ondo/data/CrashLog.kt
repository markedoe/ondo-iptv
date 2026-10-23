package com.markedoe.ondo.data

import java.io.File

/** When Ondo last stopped on an error, and what it was doing (see [CrashLog]). */
data class LastCrash(val at: Long, val report: String)

/**
 * The last error that stopped the app, kept on the device only (nothing is sent anywhere) and shown in Settings →
 * About, so a viewer can tell what happened without a computer. Addresses and logins are left out of it: the
 * viewer may photograph it to report the problem.
 */
object CrashLog {
    const val FILE_NAME = "last-crash.txt"
    private const val MAX_LENGTH = 12_000

    /** Records every uncaught error in [file] before the system's handler stops the app as usual. */
    fun install(file: File, about: String, now: () -> Long = System::currentTimeMillis) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(file, about, thread.name, error, now())
            previous?.uncaughtException(thread, error)
        }
    }

    /** Writes the report; a failure to write (disk full, no memory left) must not hide the error itself. */
    fun record(file: File, about: String, thread: String, error: Throwable, at: Long) =
        write(file, at, "$about\nThread: $thread\n${error.stackTraceToString()}")

    /**
     * How Android ended the app's last run while it was on screen, when no Kotlin error did: out of memory for the
     * whole device, not responding, a crash in native code (`ApplicationExitInfo`, Android 11 and later). [trace] is
     * the main thread's stack when the app did not respond.
     */
    class SystemExit(val at: Long, val reason: String, val description: String?, val pssKb: Long, val rssKb: Long, val trace: String? = null)

    /** Records [exit] unless the report on file is as recent (an error the app recorded itself, or this exit already). */
    fun recordExit(file: File, about: String, exit: SystemExit) {
        val previous = read(file)
        if (previous != null && previous.at >= exit.at) return
        val text = buildString {
            append(about).append('\n')
            append("Stopped by Android: ").append(exit.reason)
            exit.description?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(')') }
            append('\n')
            append("Memory at that moment: PSS ${exit.pssKb / 1024} MB, RSS ${exit.rssKb / 1024} MB\n")
            exit.trace?.let { append(it) }
        }
        write(file, exit.at, text)
    }

    /** The main thread's part of an ANR trace (its first lines), or null when the trace has none. */
    fun mainThread(trace: String, lines: Int = 40): String? {
        val start = trace.lineSequence().indexOfFirst { it.startsWith("\"main\"") }
        if (start < 0) return null
        return trace.lineSequence().drop(start).takeWhile { it.isNotBlank() }.take(lines).joinToString("\n")
    }

    private fun write(file: File, at: Long, text: String) {
        try {
            file.parentFile?.mkdirs()
            file.writeText("$at\n" + redact(text).take(MAX_LENGTH))
        } catch (_: Throwable) {
        }
    }

    fun read(file: File): LastCrash? = try {
        if (!file.exists()) {
            null
        } else {
            val text = file.readText()
            text.substringBefore('\n').toLongOrNull()?.let { LastCrash(it, text.substringAfter('\n').trim()) }
        }
    } catch (_: Exception) {
        null
    }

    private val address = Regex("""(?i)\b([a-z][a-z0-9+.-]*)://[^\s"'<>]+""")
    private val xtreamPath = Regex("""(?i)\S*/(live|movie|series|timeshift)/[^/\s]+/[^/\s]+/""")
    private val login = Regex("""(?i)\b(username|password|user|pass|token|key|auth)=[^&\s]*""")

    // Host names and addresses in the system's network errors ("Unable to resolve host", "failed to connect to").
    private val host = Regex("""(?i)\b(host(?:name)?) "?[a-z0-9.-]+"?""")
    private val connectTo = Regex("""(?i)\bto [a-z0-9-]+(\.[a-z0-9-]+)+(?=[/\s(])""")
    private val ipv4 = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""")

    /** Web addresses (panels, guides, streams), host names, IP addresses, Xtream paths and logins are replaced by `…`. */
    fun redact(text: String): String = text
        .replace(address) { "${it.groupValues[1]}://…" }
        .replace(xtreamPath) { "…/${it.groupValues[1]}/…/…/" }
        .replace(login) { "${it.groupValues[1]}=…" }
        .replace(host) { "${it.groupValues[1]} …" }
        .replace(connectTo, "to …")
        .replace(ipv4, "…")
}
