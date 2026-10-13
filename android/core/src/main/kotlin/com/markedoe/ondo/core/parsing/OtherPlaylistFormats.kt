package com.markedoe.ondo.core.parsing

import com.markedoe.ondo.core.model.HttpHeaders
import com.markedoe.ondo.core.net.Urls
import com.markedoe.ondo.core.nonEmptyLines
import com.markedoe.ondo.core.numberString
import com.markedoe.ondo.core.toStrictDoubleOrNull
import java.io.ByteArrayInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/** Playlist file formats the loader understands. */
enum class PlaylistFormat {
    M3U,
    XSPF,
    JSON,

    /** One URL per line without `#EXTM3U`. */
    PLAIN_LIST;

    companion object {
        /** Sniffs the first 4 KiB. `null` when it is not a playlist (an HTML error page, for instance). */
        fun detect(bytes: ByteArray): PlaylistFormat? {
            val prefix = String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8)
                .trim { it.isWhitespace() || it == '﻿' }
            if (prefix.startsWith("#EXTM3U", ignoreCase = true) || prefix.contains("#EXTINF")) return M3U
            if (prefix.startsWith("<") && prefix.lowercase().contains("<playlist")) return XSPF
            if (prefix.startsWith("[") || prefix.startsWith("{")) return JSON
            val firstLine = prefix.nonEmptyLines().firstOrNull()?.lowercase()
            if (firstLine != null && (firstLine.startsWith("http") || firstLine.startsWith("rtmp") || firstLine.startsWith("rtsp"))) {
                return PLAIN_LIST
            }
            return null
        }
    }
}

/** XSPF (`<playlist><trackList><track>`) -> M3U entries. Malformed XML keeps the tracks read so far. */
object XspfParser {
    fun parse(bytes: ByteArray): M3uPlaylist {
        val handler = XspfHandler()
        try {
            Sax.parse(ByteArrayInputStream(bytes), handler)
        } catch (_: Exception) {
            // Like XMLParser: stop at the error, keep what was parsed.
        }
        return M3uPlaylist(emptyMap(), handler.entries)
    }

    private class XspfHandler : DefaultHandler() {
        val entries = ArrayList<M3uEntry>()
        private var inTrack = false
        private var location: String? = null
        private var title: String? = null
        private var image: String? = null
        private var album: String? = null
        private var duration = -1.0
        private val text = StringBuilder()

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            if (qName == "track") {
                inTrack = true
                location = null
                title = null
                image = null
                album = null
                duration = -1.0
            }
            text.setLength(0)
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.appendRange(ch, start, start + length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            val value = text.toString().trim()
            text.setLength(0)
            if (!inTrack) return
            when (qName) {
                "location" -> location = location ?: value
                "title" -> title = value
                "image" -> image = value
                "album" -> album = value
                "duration" -> duration = (value.toStrictDoubleOrNull() ?: -1000.0) / 1000
                "track" -> {
                    inTrack = false
                    val url = Urls.lenient(location) ?: return
                    val attributes = HashMap<String, String>()
                    image?.let { attributes["tvg-logo"] = it }
                    album?.let { attributes["group-title"] = it }
                    entries.add(M3uEntry(duration, title ?: Urls.lastPathComponent(url), attributes, url))
                }
            }
        }
    }
}

/**
 * Generic JSON playlists: an array (or `{channels|items|streams|playlist|data|list: [...]}`) of objects with
 * `name|title`, `url|link|stream|src`, `logo|icon|image`, `group|category|genre`, `tvg_id|epg_id`.
 */
object JsonPlaylistParser {
    fun parse(bytes: ByteArray): M3uPlaylist {
        val root = try {
            Json.parseToJsonElement(bytes.decodeToString())
        } catch (_: IllegalArgumentException) {
            return M3uPlaylist(emptyMap(), emptyList())
        }
        val items: List<JsonObject> = when (root) {
            is JsonArray -> objects(root).orEmpty()
            is JsonObject -> listOf("channels", "items", "streams", "playlist", "data", "list")
                .firstNotNullOfOrNull { key -> (root[key] as? JsonArray)?.let(::objects) }
                .orEmpty()
            else -> emptyList()
        }
        val entries = items.mapNotNull { item ->
            val url = Urls.lenient(string(item, "url", "link", "stream", "stream_url", "src", "source", "file")) ?: return@mapNotNull null
            val attributes = HashMap<String, String>()
            string(item, "logo", "icon", "image", "tvg_logo", "tvg-logo", "poster")?.let { attributes["tvg-logo"] = it }
            string(item, "group", "category", "genre", "group_title", "group-title")?.let { attributes["group-title"] = it }
            string(item, "tvg_id", "tvg-id", "epg_id", "epg")?.let { attributes["tvg-id"] = it }
            string(item, "type", "tvg-type")?.let { attributes["tvg-type"] = it }
            M3uEntry(
                duration = -1.0,
                title = string(item, "name", "title", "channel") ?: Urls.lastPathComponent(url),
                attributes = attributes,
                url = url,
                headers = HttpHeaders(
                    userAgent = string(item, "user_agent", "user-agent", "userAgent"),
                    referrer = string(item, "referrer", "referer"),
                ),
            )
        }
        return M3uPlaylist(emptyMap(), entries)
    }

    /** The array when every element is an object (`as? [[String: Any]]`). */
    private fun objects(array: JsonArray): List<JsonObject>? =
        if (array.all { it is JsonObject }) array.map { it as JsonObject } else null

    /** First non-empty string, or number / boolean rendered as text, among `keys`. */
    private fun string(item: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val value: JsonElement = item[key] ?: continue
            if (value !is JsonPrimitive || value is JsonNull) continue
            if (value.isString) {
                if (value.content.isNotEmpty()) return value.content
                continue
            }
            when (value.content) {
                "true" -> return "1"
                "false" -> return "0"
            }
            value.content.toLongOrNull()?.let { return it.toString() }
            value.content.toDoubleOrNull()?.let { return numberString(it) }
        }
        return null
    }
}

/** One URL per line (lines starting with `#` are ignored). */
object PlainListParser {
    fun parse(bytes: ByteArray): M3uPlaylist {
        val entries = bytes.decodeToString().nonEmptyLines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) return@mapNotNull null
            val url = Urls.lenient(trimmed) ?: return@mapNotNull null
            M3uEntry(-1.0, Urls.lastPathComponent(url), emptyMap(), url)
        }
        return M3uPlaylist(emptyMap(), entries)
    }
}
