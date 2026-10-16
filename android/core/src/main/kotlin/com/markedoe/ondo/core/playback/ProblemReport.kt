package com.markedoe.ondo.core.playback

import com.markedoe.ondo.core.model.PlaybackItem
import java.net.URLEncoder
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * A playback problem report: the app and system, the player settings, what is playing (its kind and format, never
 * its address or title), the player's statistics and the playback log (start and seek times, rebufferings, errors).
 * Plain text, to share or paste into an issue; every line goes through [PrivacyScrubber].
 */
class ProblemReport(context: Context, log: PlaybackLog, generated: Instant = Instant.now()) {
    data class Context(
        /** "Ondo 1.2.0 (34)". */
        val app: String,
        /** The system and the device model. */
        val system: String,
        /** "Media3 1.11.1". */
        val engine: String,
        /** Buffer settings in seconds, 0 for automatic, and the durations in use. */
        val liveBufferSetting: Int,
        val vodBufferSetting: Int,
        val bufferInUse: PlaybackBuffer.Durations,
        /** "live channel", "film", "episode", "catch-up" (see [kind]). */
        val kind: String,
        /** The container named by the address ("mkv", "m3u8"…, see [container]), never the address. */
        val container: String? = null,
        /** "1920×1080". */
        val video: String? = null,
        /** Seconds; left out for live channels. */
        val duration: Double? = null,
        val position: Double? = null,
        val state: String,
        /** The player's counters, one line each ("Video: video/avc, 25 fps", "Dropped video frames: 3"…). */
        val statistics: List<String> = emptyList(),
    )

    val text: String

    init {
        val lines = ArrayList<String>()
        fun add(line: String) {
            lines.add(PrivacyScrubber.scrub(line))
        }
        fun setting(seconds: Int) = if (seconds > 0) "$seconds s" else "automatic"
        val buffer = context.bufferInUse
        add("Ondo playback report, ${generated.truncatedTo(ChronoUnit.SECONDS)}")
        add("App: ${context.app}")
        add("System: ${context.system}")
        add("Engine: ${context.engine}")
        add(
            "Buffer: ${seconds(buffer.bufferForPlaybackMs)} s to start, ${seconds(buffer.bufferForPlaybackAfterRebufferMs)} s after rebuffering, " +
                "${seconds(buffer.minBufferMs)} to ${seconds(buffer.maxBufferMs)} s ahead in use; " +
                "live ${setting(context.liveBufferSetting)}, films and series ${setting(context.vodBufferSetting)}",
        )
        add("Playing: ${listOfNotNull(context.kind, context.container?.let { ".$it" }, context.video).joinToString(", ")}")
        context.duration?.takeIf { it > 0 }?.let { add("Duration: ${number(it)} s") }
        context.position?.let { add("Position: ${number(it)} s") }
        add("State: ${context.state}")
        if (context.statistics.isNotEmpty()) {
            add("")
            add("Statistics:")
            context.statistics.forEach { add("  $it") }
        }
        add("")
        add("Events (seconds since opening):")
        for (entry in log.entries) add("  ${number(entry.time)} ${entry.event}")
        text = lines.joinToString("\n")
    }

    companion object {
        const val ISSUE_TITLE = "Playback problem"

        /** Characters of the report put in a new issue's address (an address has a length limit). */
        const val ISSUE_REPORT_LIMIT = 2_500

        private const val ISSUE_URL = "https://github.com/markedoe/ondo-iptv/issues/new"

        /** What is played, in the report's words. */
        fun kind(item: PlaybackItem): String = when {
            item.isCatchup -> "catch-up"
            item.isLive -> "live channel"
            item.reference.episodeId != null -> "episode"
            else -> "film"
        }

        /** The extension of the address's path ("mkv", "m3u8"…), nothing else of it. */
        fun container(url: String): String? {
            val path = url.substringAfter("://").substringBefore('?').substringBefore('#')
            val name = path.substringAfterLast('/', missingDelimiterValue = "")
            if ('.' !in name) return null
            val extension = name.substringAfterLast('.').lowercase(Locale.ROOT)
            return extension.takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }
        }

        /** A new issue with the start of the report: the rest is pasted from Copy Report. */
        fun issueUrl(report: String): String {
            val body = if (report.length > ISSUE_REPORT_LIMIT) report.take(ISSUE_REPORT_LIMIT) + "\n…" else report
            return "$ISSUE_URL?title=${encode(ISSUE_TITLE)}&body=${encode("<!-- Describe what you saw. -->\n\n```\n$body\n```")}"
        }

        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        /** Seconds with one decimal, whatever the device's language ("12.5", never "12,5"). */
        fun number(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

        private fun seconds(milliseconds: Int) = number(milliseconds / 1_000.0)
    }
}
