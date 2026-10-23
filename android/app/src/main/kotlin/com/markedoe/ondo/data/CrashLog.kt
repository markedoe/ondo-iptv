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
    fun record(file: File, about: String, thread: String, error: Throwable, at: Long) {
        try {
            file.parentFile?.mkdirs()
            file.writeText("$at\n" + redact("$about\nThread: $thread\n${error.stackTraceToString()}").take(MAX_LENGTH))
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
