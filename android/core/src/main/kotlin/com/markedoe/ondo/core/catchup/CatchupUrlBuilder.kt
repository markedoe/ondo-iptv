package com.markedoe.ondo.core.catchup

import com.markedoe.ondo.core.model.CatchupInfo
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.EpgProgram
import com.markedoe.ondo.core.model.XtreamCredentials
import com.markedoe.ondo.core.model.XtreamOutputFormat
import com.markedoe.ondo.core.net.Urls
import com.markedoe.ondo.core.xtream.XtreamUrlBuilder
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.ceil

/** What the catch-up builder needs to know about the playlist. */
data class CatchupContext(
    /** Credentials when the playlist is an Xtream account. */
    val xtream: XtreamCredentials? = null,
    /** The panel's time zone (Xtream timeshift URLs carry local times); UTC when unknown. */
    val serverTimeZone: ZoneId? = null,
    val outputFormat: XtreamOutputFormat = XtreamOutputFormat.M3U8,
)

/**
 * Builds replay URLs for past programmes, following the common M3U conventions (the
 * `catchup`, `catchup-source`, `catchup-days` attributes) and Xtream Codes `timeshift`.
 * Times are epoch millis.
 */
object CatchupUrlBuilder {
    /** Can `program` be replayed on `channel`? */
    fun isAvailable(channel: Channel, program: EpgProgram, now: Long = System.currentTimeMillis()): Boolean =
        isAvailable(channel, program.start, now)

    /** Can `channel` be played from `start` (a past instant within its archive, plus one hour of slack)? */
    fun isAvailable(channel: Channel, start: Long, now: Long = System.currentTimeMillis()): Boolean {
        val catchup = channel.catchup ?: return false
        if (catchup.days <= 0 || start >= now) return false
        return now - start <= catchup.days * 86_400_000L + 3_600_000L
    }

    /** Best URL to replay `program`, or `null` when the channel has no catch-up. */
    fun url(
        channel: Channel,
        program: EpgProgram,
        context: CatchupContext = CatchupContext(),
        now: Long = System.currentTimeMillis(),
    ): String? = urls(channel, program.start, program.end, now, context).firstOrNull()

    /** Candidate URLs (best first) to replay `[start, end]`. Empty when the channel has no catch-up. */
    fun urls(
        channel: Channel,
        start: Long,
        end: Long,
        now: Long = System.currentTimeMillis(),
        context: CatchupContext = CatchupContext(),
    ): List<String> {
        val catchup = channel.catchup ?: return emptyList()
        val durationSeconds = maxOf((end - start) / 1000, 60L).toInt()
        val live = channel.streamUrl

        return when (catchup.mode) {
            CatchupInfo.Mode.XTREAM -> xtreamUrls(channel, start, durationSeconds, context)

            CatchupInfo.Mode.DEFAULT -> {
                val source = catchup.source ?: return listOfNotNull(shiftUrl(live, start, now))
                val filled = fill(source, start, end, now)
                Urls.lenient(filled)?.let { return listOf(it) }
                // Relative template: treat as append.
                listOfNotNull(Urls.lenient(live + filled))
            }

            CatchupInfo.Mode.APPEND -> {
                val suffix = fill(catchup.source ?: "?utc={utc}&lutc={lutc}", start, end, now)
                val url = if (suffix.startsWith("?") && live.contains("?")) live + "&" + suffix.drop(1) else live + suffix
                listOfNotNull(Urls.lenient(url))
            }

            CatchupInfo.Mode.SHIFT -> listOfNotNull(shiftUrl(live, start, now))

            CatchupInfo.Mode.FLUSSONIC -> listOfNotNull(flussonicUrl(live, start, durationSeconds))
        }
    }

    // Modes

    private fun xtreamUrls(channel: Channel, start: Long, durationSeconds: Int, context: CatchupContext): List<String> {
        val minutes = ceil(durationSeconds / 60.0).toInt()
        var streamId = channel.xtreamStreamId
        val builder = if (context.xtream != null) {
            XtreamUrlBuilder(context.xtream)
        } else {
            val parsed = XtreamUrlBuilder.parseLiveUrl(channel.streamUrl) ?: return emptyList()
            streamId = streamId ?: parsed.streamId
            XtreamUrlBuilder(XtreamCredentials(parsed.server, parsed.username, parsed.password))
        }
        val id = streamId ?: return emptyList()
        val primary = context.outputFormat
        val secondary = if (primary == XtreamOutputFormat.M3U8) XtreamOutputFormat.TS else XtreamOutputFormat.M3U8
        return listOf(
            builder.timeshiftUrl(id, start, minutes, context.serverTimeZone, primary),
            builder.timeshiftUrl(id, start, minutes, context.serverTimeZone, secondary),
            builder.timeshiftPhpUrl(id, start, minutes, context.serverTimeZone),
        )
    }

    private fun shiftUrl(live: String, start: Long, now: Long): String? {
        val separator = if (live.contains("?")) "&" else "?"
        val url = live + separator + "utc=${start / 1000}&lutc=${now / 1000}"
        return if (Urls.isValidAbsolute(url)) url else null
    }

    private val pathAllowed = Urls.allowedTable(Urls.PATH_ALLOWED)

    /**
     * `http://host/ch/index.m3u8?token=x` -> `http://host/ch/index-{start}-{duration}.m3u8?token=x`;
     * `http://host/ch/mpegts?token=x` -> `http://host/ch/timeshift_abs-{start}.ts?token=x`.
     */
    private fun flussonicUrl(live: String, start: Long, durationSeconds: Int): String? {
        val parsed = Urls.parse(live) ?: return null
        val stamp = start / 1000
        val path = parsed.path
        val last = NsPath.lastComponent(path)
        val directory = NsPath.deletingLastComponent(path)
        val newPath = when {
            last.endsWith(".m3u8") -> NsPath.appending(directory, "${NsPath.deletingExtension(last)}-$stamp-$durationSeconds.m3u8")
            last == "mpegts" || last.endsWith(".ts") -> NsPath.appending(directory, "timeshift_abs-$stamp.ts")
            else -> NsPath.appending(path, "timeshift_abs-$stamp.ts")
        }
        // With a host, the path must be absolute (URLComponents returns no URL otherwise).
        if (parsed.authority != null && !newPath.startsWith("/")) return null
        val builder = StringBuilder()
        parsed.scheme?.let { builder.append(it).append(':') }
        parsed.authority?.let { builder.append("//").append(it) }
        builder.append(Urls.percentEncode(newPath, pathAllowed))
        parsed.rawQuery?.let { builder.append('?').append(it) }
        parsed.rawFragment?.let { builder.append('#').append(it) }
        return builder.toString()
    }

    // Templates

    private val tokenRegex = Regex("""\$?\{([a-zA-Z_]+)(?::([^}]*))?\}""")

    /**
     * Replaces catch-up template tokens:
     * `{utc}` `{start}` `${start}` start (unix) · `{utcend}` `{end}` `${end}` end · `{lutc}` `{now}` `${now}`
     * `{timestamp}` now · `{timestamp_start}` start · `{duration}` seconds · `{duration:60}` minutes ·
     * `{offset}` / `{offset:60}` now - start · `{Y}` `{m}` `{d}` `{H}` `{M}` `{S}` start components (UTC) ·
     * `{utc:Y-m-d:H-M}` `{end:YmdHMS}` formatted. Unknown tokens are left as they are.
     */
    fun fill(template: String, start: Long, end: Long, now: Long): String {
        val startTs = start / 1000
        val endTs = end / 1000
        val nowTs = now / 1000
        val duration = maxOf(endTs - startTs, 0)
        val offset = maxOf(nowTs - startTs, 0)
        // A token ends with `}`, so nothing past the last one is searched: there, each `{` of a long template
        // scanned the rest of it again.
        val tokensEnd = template.lastIndexOf('}') + 1
        return tokenRegex.replace(template.substring(0, tokensEnd)) { match ->
            val name = match.groupValues[1]
            val argument = match.groups[2]?.value
            val divider = argument?.toIntOrNull()?.let { maxOf(it, 1) }
            val pattern = if (argument != null && divider == null) argument else null
            when (name) {
                "utc", "start", "timestamp_start" -> pattern?.let { format(start, it) } ?: startTs.toString()
                "utcend", "end" -> pattern?.let { format(end, it) } ?: endTs.toString()
                "lutc", "now", "timestamp", "current" -> pattern?.let { format(now, it) } ?: nowTs.toString()
                "duration" -> (duration / (divider ?: 1)).toString()
                "offset" -> (offset / (divider ?: 1)).toString()
                "Y", "m", "d", "H", "M", "S" -> format(start, name)
                else -> match.value
            }
        } + template.substring(tokensEnd)
    }

    /** Formats with strftime-like letters `Y m d H M S` (UTC). Other characters are kept. */
    internal fun format(epochMillis: Long, pattern: String): String {
        val time = Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC)
        val output = StringBuilder()
        for (c in pattern) {
            when (c) {
                'Y' -> output.append(String.format(Locale.ROOT, "%04d", time.year))
                'm' -> output.append(String.format(Locale.ROOT, "%02d", time.monthValue))
                'd' -> output.append(String.format(Locale.ROOT, "%02d", time.dayOfMonth))
                'H' -> output.append(String.format(Locale.ROOT, "%02d", time.hour))
                'M' -> output.append(String.format(Locale.ROOT, "%02d", time.minute))
                'S' -> output.append(String.format(Locale.ROOT, "%02d", time.second))
                else -> output.append(c)
            }
        }
        return output.toString()
    }
}

/** `NSString` path helpers, as used by the Swift Flussonic builder. */
private object NsPath {
    private fun stripTrailingSlashes(path: String): String {
        val trimmed = path.trimEnd('/')
        return if (trimmed.isEmpty() && path.startsWith("/")) "/" else trimmed
    }

    fun lastComponent(path: String): String {
        val clean = stripTrailingSlashes(path)
        if (clean == "/") return "/"
        return clean.substringAfterLast('/')
    }

    fun deletingLastComponent(path: String): String {
        val clean = stripTrailingSlashes(path)
        if (clean == "/") return "/"
        val slash = clean.lastIndexOf('/')
        return when {
            slash < 0 -> ""
            slash == 0 -> "/"
            else -> clean.substring(0, slash)
        }
    }

    fun appending(path: String, component: String): String = when {
        path.isEmpty() -> component
        path.endsWith("/") -> path + component
        else -> "$path/$component"
    }

    fun deletingExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }
}
