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
 * e-mail addresses, IP addresses and host names. Error messages from the system can quote the address that failed,
 * and a report can be shared publicly. Same rules as the Apple apps.
 */
object PrivacyScrubber {
    private val rules: List<Pair<Regex, (MatchResult, CharSequence) -> String>> = listOf(
        Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]*://\S+""") to { _, _ -> "<address>" },
        Regex("""\b[^\s@/:]+:[^\s@/]+@[^\s/]+""") to { _, _ -> "<address>" },
        Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b""") to { _, _ -> "<e-mail>" },
        Regex("""\b(?:\d{1,3}\.){3}\d{1,3}(?::\d+)?\b""") to { _, _ -> "<ip>" },
        // IPv6, full or compressed ("2001:db8::7", "fe80::1%wlan0"), bare or in brackets with a port.
        Regex("""\[([0-9A-Fa-f:]+)(?:%[^\]\s]*)?](?::\d+)?|(?<![\w:.])([0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,8})(?:%\w+)?(?![\w:])""") to
            { match, _ -> if (isIpv6(match.groups[1]?.value ?: match.groups[2]!!.value)) "<ip>" else match.value },
        // Dotted names, kept only when they cannot be a host (see `isHostName`).
        Regex("""(?<![\p{L}\p{N}_.-])[\p{L}\p{N}_-]+(?:\.[\p{L}\p{N}_-]+)+(?![\p{L}\p{N}_-])""") to
            { match, text -> if (isHostName(match.value) && !followsMediaType(text, match.range.first)) "<host>" else match.value },
    )

    fun scrub(text: String): String =
        rules.fold(text) { scrubbed, (pattern, replacement) -> pattern.replace(scrubbed) { replacement(it, scrubbed) } }

    /**
     * Whether a dotted name can be a host: its last part is a top-level domain (letters, or `xn--` for a
     * non-Latin one). Versions (`1.11.0`) and codecs (`avc1.64001F`, `mp4a.40.2`) end with digits; a class name
     * (`java.net.UnknownHostException`) ends with a CamelCase name; Android decoders are named `c2.…` or `OMX.…`
     * (`c2.android.avc.decoder`). A file name (`stream.ts`) passes for a host.
     */
    private fun isHostName(name: String): Boolean {
        val first = name.substringBefore('.')
        if (first == "c2" || first.equals("omx", ignoreCase = true)) return false
        val last = name.substringAfterLast('.')
        val topLevel = last.startsWith("xn--", ignoreCase = true) || (last.length >= 2 && last.all(Char::isLetter))
        return topLevel && !isCamelCase(last)
    }

    /** `UnknownHostException`, `HttpDataSource`, `EOFException`: a capital, then a small letter and another capital. */
    private fun isCamelCase(word: String): Boolean =
        word.first().isUpperCase() && word.any(Char::isLowerCase) && word.drop(1).any(Char::isUpperCase)

    /** Media types name their formats with dots (`audio/vnd.dts`, `application/vnd.apple.mpegurl`). */
    private fun followsMediaType(text: CharSequence, start: Int): Boolean =
        MEDIA_TYPES.any { type -> start >= type.length && text.regionMatches(start - type.length, type, 0, type.length, ignoreCase = true) }

    private val MEDIA_TYPES = listOf("audio/", "video/", "text/", "application/", "image/")

    /** One `::` with seven groups at most, or five to eight groups in full, of one to four hexadecimal digits. */
    private fun isIpv6(candidate: String): Boolean {
        fun groups(part: String): Int? =
            if (part.isEmpty()) 0 else part.split(':').takeIf { list -> list.all { it.length in 1..4 } }?.size
        val halves = candidate.split("::")
        return when (halves.size) {
            1 -> (groups(candidate) ?: 0) in 5..8
            2 -> {
                val before = groups(halves[0]) ?: return false
                val after = groups(halves[1]) ?: return false
                before + after in 1..7
            }
            else -> false
        }
    }
}
