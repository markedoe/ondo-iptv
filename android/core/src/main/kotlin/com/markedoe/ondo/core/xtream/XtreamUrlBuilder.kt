package com.markedoe.ondo.core.xtream

import com.markedoe.ondo.core.model.XtreamCredentials
import com.markedoe.ondo.core.model.XtreamOutputFormat
import com.markedoe.ondo.core.net.Urls
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/** Builds the URLs an Xtream Codes panel serves. */
class XtreamUrlBuilder(val credentials: XtreamCredentials) {
    private val base: String = credentials.server.trimEnd('/')
    private val user: String = encodePathComponent(credentials.username)
    private val pass: String = encodePathComponent(credentials.password)

    /**
     * `player_api.php?username=…&password=…[&action=…][&sorted parameters]`.
     * `+` is escaped as `%2B` here (panels decode it as a space).
     */
    fun apiUrl(action: String? = null, parameters: Map<String, String> = emptyMap()): String {
        val items = mutableListOf("username" to credentials.username, "password" to credentials.password)
        if (action != null) items.add("action" to action)
        for (key in parameters.keys.sorted()) items.add(key to parameters.getValue(key))
        return "$base/player_api.php?" + query(items)
    }

    fun liveUrl(streamId: Int, format: XtreamOutputFormat): String = "$base/live/$user/$pass/$streamId.${format.rawValue}"

    fun movieUrl(streamId: Int, containerExtension: String?): String =
        "$base/movie/$user/$pass/$streamId.${containerExtension ?: "mp4"}"

    fun episodeUrl(episodeId: String, containerExtension: String?): String =
        "$base/series/$user/$pass/${encodePathComponent(episodeId)}.${containerExtension ?: "mp4"}"

    /**
     * `/timeshift/{user}/{pass}/{minutes}/{yyyy-MM-dd:HH-mm}/{id}.{ext}` — start (epoch millis) in the
     * server's time zone (UTC when unknown).
     */
    fun timeshiftUrl(streamId: Int, start: Long, durationMinutes: Int, serverTimeZone: ZoneId?, format: XtreamOutputFormat): String {
        val stamp = timeshiftStamp(start, serverTimeZone ?: ZoneOffset.UTC)
        return "$base/timeshift/$user/$pass/${maxOf(durationMinutes, 1)}/$stamp/$streamId.${format.rawValue}"
    }

    /** Alternative endpoint some panels expose instead of the path form. */
    fun timeshiftPhpUrl(streamId: Int, start: Long, durationMinutes: Int, serverTimeZone: ZoneId?): String {
        val stamp = timeshiftStamp(start, serverTimeZone ?: ZoneOffset.UTC)
        return "$base/streaming/timeshift.php?" + query(
            listOf(
                "username" to credentials.username,
                "password" to credentials.password,
                "stream" to streamId.toString(),
                "start" to stamp,
                "duration" to maxOf(durationMinutes, 1).toString(),
            ),
        )
    }

    /** The panel's XMLTV guide. */
    val xmltvUrl: String
        get() = "$base/xmltv.php?" + query(listOf("username" to credentials.username, "password" to credentials.password))

    /** The panel's M3U export (`get.php`), used when "force M3U" is on. */
    fun m3uUrl(format: XtreamOutputFormat): String = "$base/get.php?" + query(
        listOf(
            "username" to credentials.username,
            "password" to credentials.password,
            "type" to "m3u_plus",
            "output" to format.rawValue,
        ),
    )

    /** Query string where `+` is escaped as `%2B`: PHP panels decode a bare `+` as a space. */
    private fun query(items: List<Pair<String, String>>): String = Urls.query(items).replace("+", "%2B")

    /** A live URL recognised inside a plain M3U playlist. */
    data class ParsedLiveUrl(
        val server: String,
        val username: String,
        val password: String,
        val streamId: Int,
        val fileExtension: String,
    )

    companion object {
        private val pathComponentAllowed = Urls.allowedTable(Urls.PATH_ALLOWED, minus = "/?#")

        private fun encodePathComponent(value: String): String = Urls.percentEncode(value, pathComponentAllowed)

        internal fun timeshiftStamp(epochMillis: Long, zone: ZoneId): String {
            val time = Instant.ofEpochMilli(epochMillis).atZone(zone)
            return String.format(
                Locale.ROOT,
                "%04d-%02d-%02d:%02d-%02d",
                time.year,
                time.monthValue,
                time.dayOfMonth,
                time.hour,
                time.minute,
            )
        }

        /** Recognises `http://host:port/live/user/pass/123.ts` and `http://host:port/user/pass/123`. */
        fun parseLiveUrl(url: String): ParsedLiveUrl? {
            // Fast reject: the last path component must start with a digit.
            val lastSlash = url.lastIndexOf('/')
            if (lastSlash < 0 || lastSlash + 1 >= url.length || url[lastSlash + 1] !in '0'..'9') return null
            val parsed = Urls.parse(url) ?: return null
            val scheme = parsed.scheme ?: return null
            val host = parsed.host?.takeIf { it.isNotEmpty() } ?: return null
            // Split the encoded path, then decode each part once: the decoded path would split a password at
            // its `/` and decode its `%` twice.
            val parts = parsed.rawPath.split('/').filter { it.isNotEmpty() }.toMutableList()
            if (parts.firstOrNull() == "live") parts.removeAt(0)
            if (parts.size != 3) return null
            val last = parts[2]
            val idPart = last.split('.').firstOrNull { it.isNotEmpty() } ?: last
            val id = idPart.toIntOrNull() ?: return null
            val ext = if (last.contains('.')) last.split('.').lastOrNull { it.isNotEmpty() }.orEmpty() else ""
            var server = "$scheme://$host"
            parsed.port?.let { server += ":$it" }
            return ParsedLiveUrl(
                server = server,
                username = Urls.percentDecode(parts[0]) ?: parts[0],
                password = Urls.percentDecode(parts[1]) ?: parts[1],
                streamId = id,
                fileExtension = ext,
            )
        }

        fun streamIdFromLiveUrl(url: String): Int? = parseLiveUrl(url)?.streamId

        /** For a `.ts` live URL of an Xtream panel, the `.m3u8` variant (and vice versa). */
        fun alternateFormatUrl(url: String): String? {
            val parsed = parseLiveUrl(url) ?: return null
            val builder = XtreamUrlBuilder(XtreamCredentials(parsed.server, parsed.username, parsed.password))
            return when (parsed.fileExtension.lowercase()) {
                "m3u8" -> builder.liveUrl(parsed.streamId, XtreamOutputFormat.TS)
                else -> builder.liveUrl(parsed.streamId, XtreamOutputFormat.M3U8)
            }
        }
    }
}
