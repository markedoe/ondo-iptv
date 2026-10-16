package com.markedoe.ondo.core.playback

/**
 * What happened during a playback, with the seconds since it was opened: the record of start and seek times,
 * rebufferings, retries and errors that a problem report carries. Every line goes through [PrivacyScrubber]: no
 * address, login or title. Not thread-safe: the player calls it from its own thread.
 */
class PlaybackLog {
    data class Entry(val time: Double, val event: String)

    private val lines = ArrayDeque<Entry>()

    val entries: List<Entry> get() = lines.toList()

    fun add(event: String, time: Double) {
        lines.addLast(Entry(time, PrivacyScrubber.scrub(event)))
        while (lines.size > LIMIT) lines.removeFirst()
    }

    companion object {
        /** The oldest lines go first beyond this many. */
        const val LIMIT = 200
    }
}

/**
 * Removes from free text what could identify a viewer or their subscription: addresses (with any login in them),
 * e-mail addresses, IP addresses and host names. Error messages from the system can quote the address that failed.
 * Same rules as the Apple apps.
 */
object PrivacyScrubber {
    private val rules = listOf(
        Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]*://\S+""") to "<address>",
        Regex("""\b[^\s@/:]+:[^\s@/]+@[^\s/]+""") to "<address>",
        Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b""") to "<e-mail>",
        Regex("""\b(?:\d{1,3}\.){3}\d{1,3}(?::\d+)?\b""") to "<ip>",
        Regex("""\b(?:[0-9A-Fa-f]{1,4}:){4,7}[0-9A-Fa-f]{1,4}\b""") to "<ip>",
        Regex(
            """\b(?:[A-Za-z0-9-]+\.)+(?:com|net|org|tv|io|me|xyz|info|biz|cc|live|stream|online|site|top|pro|club|ru|fr|de|uk|nl|es|it|pt|br|in|cn|eu)\b""",
        ) to "<host>",
    )

    fun scrub(text: String): String = rules.fold(text) { scrubbed, (pattern, replacement) -> pattern.replace(scrubbed, replacement) }
}
