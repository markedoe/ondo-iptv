package com.markedoe.ondo.core.parsing

import com.markedoe.ondo.core.TextDecoding
import com.markedoe.ondo.core.model.HttpHeaders
import com.markedoe.ondo.core.net.Urls
import com.markedoe.ondo.core.nilIfBlank
import com.markedoe.ondo.core.parseJsonOrNull
import com.markedoe.ondo.core.toStrictDoubleOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One `#EXTINF` entry of an extended M3U playlist. */
data class M3uEntry(
    val duration: Double,
    val title: String,
    /** Attributes of the `#EXTINF` line (`tvg-id`, `tvg-logo`, `group-title`, …), keys lowercased. */
    val attributes: Map<String, String>,
    val url: String,
    /** `#EXTGRP:` value when present. */
    val extGroup: String? = null,
    /** Headers from `#EXTVLCOPT`, `#KODIPROP`, `#EXTHTTP` and `url|User-Agent=…` suffixes. */
    val headers: HttpHeaders = HttpHeaders.NONE,
) {
    val groupTitle: String?
        get() = attributes["group-title"]?.nilIfBlank() ?: extGroup?.nilIfBlank()

    val tvgId: String?
        get() = attributes["tvg-id"]?.nilIfBlank()

    val tvgName: String?
        get() = attributes["tvg-name"]?.nilIfBlank()

    val logo: String?
        get() = Urls.lenient(attributes["tvg-logo"] ?: attributes["logo"])

    val channelNumber: Int?
        get() = (attributes["tvg-chno"] ?: attributes["channel-number"] ?: attributes["tvg-num"])?.toIntOrNull()
}

/** A parsed playlist: header attributes and entries. */
data class M3uPlaylist(
    /** Attributes of the `#EXTM3U` header line (keys lowercased). */
    val headerAttributes: Map<String, String>,
    val entries: List<M3uEntry>,
) {
    /** Guide URLs advertised in the header (`url-tvg`, `x-tvg-url`, `tvg-url`), comma separated. */
    val epgUrls: List<String>
        get() {
            // A set: looking each address up in a list took quadratic time on long lists.
            val urls = LinkedHashSet<String>()
            for (key in listOf("url-tvg", "x-tvg-url", "tvg-url")) {
                val value = headerAttributes[key] ?: continue
                for (part in value.split(',')) {
                    if (part.isEmpty()) continue
                    urls.add(Urls.lenient(part) ?: continue)
                }
            }
            return urls.toList()
        }
}

/** `#EXTINF` line content: duration, display title and attributes. */
internal data class ExtInf(val duration: Double, val title: String, val attributes: Map<String, String>)

/**
 * Extended M3U parser. Byte level: large provider lists (100k+ lines) must parse in well under a second. Reads UTF-8
 * bytes, any byte that is not UTF-8 as Windows-1252 (a UTF-16 file is converted first, see `PlaylistLoader`).
 */
object M3uParser {
    private const val LF: Byte = 0x0A
    private const val CR: Byte = 0x0D
    private const val SPACE: Byte = 0x20
    private const val TAB: Byte = 0x09
    private const val HASH: Byte = 0x23
    private const val EQUALS: Byte = 0x3D
    private const val COMMA: Byte = 0x2C
    private const val DOUBLE_QUOTE: Byte = 0x22
    private const val SINGLE_QUOTE: Byte = 0x27

    fun parse(text: String): M3uPlaylist = parse(text.encodeToByteArray())

    fun parse(bytes: ByteArray): M3uPlaylist = M3uPlaylist(headerAttributes(bytes), entries(bytes).toList())

    /** Attributes of the `#EXTM3U` line (keys lowercased). */
    fun headerAttributes(bytes: ByteArray): Map<String, String> {
        val header = LinkedHashMap<String, String>()
        val count = bytes.size
        var lineStart = 0
        while (lineStart < count) {
            var lineEnd = lineStart
            while (lineEnd < count && bytes[lineEnd] != LF && bytes[lineEnd] != CR) lineEnd++
            var a = lineStart
            var b = lineEnd
            lineStart = lineEnd + 1
            // A byte order mark before the first line, spaces and tabs around.
            if (a == 0 && count >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) a = 3
            while (a < b && isBlank(bytes[a])) a++
            while (b > a && isBlank(bytes[b - 1])) b--
            if (b - a >= 7 && bytes[a] == HASH && hasPrefix(bytes, a, b, "#EXTM3U")) header.putAll(parseAttributes(bytes, a + 7, b))
        }
        return header
    }

    /**
     * The entries, each parsed when the sequence reaches it: a playlist of 100,000 entries is never all in memory as
     * entries (with their attributes) next to the catalogue built from them.
     */
    fun entries(bytes: ByteArray): Sequence<M3uEntry> = sequence {
        var pendingInfo: ExtInf? = null
        var pendingGroup: String? = null
        var pendingHeaders = HttpHeaders.NONE

        val count = bytes.size
        // Skip UTF-8 BOM.
        var lineStart = if (count >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0

        while (lineStart < count) {
            var lineEnd = lineStart
            while (lineEnd < count && bytes[lineEnd] != LF && bytes[lineEnd] != CR) lineEnd++
            var a = lineStart
            var b = lineEnd
            lineStart = lineEnd + 1
            // Trim spaces / tabs.
            while (a < b && isBlank(bytes[a])) a++
            while (b > a && isBlank(bytes[b - 1])) b--
            if (a >= b) continue

            if (bytes[a] == HASH) {
                when {
                    hasPrefix(bytes, a, b, "#EXTINF:") -> pendingInfo = parseExtInf(bytes, a + 8, b)
                    hasPrefix(bytes, a, b, "#EXTGRP:") -> pendingGroup = string(bytes, a + 8, b).trim()
                    hasPrefix(bytes, a, b, "#EXTVLCOPT:") -> pendingHeaders = applyOption(string(bytes, a + 11, b), pendingHeaders)
                    hasPrefix(bytes, a, b, "#KODIPROP:") -> pendingHeaders = applyKodiProp(string(bytes, a + 10, b), pendingHeaders)
                    hasPrefix(bytes, a, b, "#EXTHTTP:") -> pendingHeaders = applyExtHttp(string(bytes, a + 9, b), pendingHeaders)
                }
                continue
            }

            // URL line.
            var urlString = string(bytes, a, b)
            var headers = pendingHeaders
            val pipe = urlString.indexOf('|')
            if (pipe >= 0) {
                headers = applyKodiUrlOptions(urlString.substring(pipe + 1), headers)
                urlString = urlString.substring(0, pipe)
            }
            val url = Urls.lenient(urlString)
            if (url == null) {
                pendingInfo = null
                pendingGroup = null
                pendingHeaders = HttpHeaders.NONE
                continue
            }
            val info = pendingInfo ?: ExtInf(-1.0, Urls.lastPathComponent(url), emptyMap())
            if (headers.userAgent == null) {
                val ua = info.attributes["user-agent"] ?: info.attributes["http-user-agent"]
                if (ua != null) headers = headers.copy(userAgent = ua)
            }
            if (headers.referrer == null) {
                val referrer = info.attributes["http-referrer"] ?: info.attributes["referrer"]
                if (referrer != null) headers = headers.copy(referrer = referrer)
            }
            val title = info.title.ifEmpty { info.attributes["tvg-name"] ?: Urls.lastPathComponent(url) }
            yield(M3uEntry(info.duration, title, info.attributes, url, pendingGroup, headers))
            pendingInfo = null
            pendingGroup = null
            pendingHeaders = HttpHeaders.NONE
        }
    }

    private fun isBlank(byte: Byte) = byte == SPACE || byte == TAB

    /** Case-insensitive ASCII prefix test of `bytes[start, end)`. */
    private fun hasPrefix(bytes: ByteArray, start: Int, end: Int, prefix: String): Boolean {
        if (end - start < prefix.length) return false
        for (i in prefix.indices) {
            var c = bytes[start + i].toInt()
            if (c in 0x61..0x7A) c -= 0x20
            if (c != prefix[i].code) return false
        }
        return true
    }

    /**
     * Field by field, so that a playlist saved in Windows-1252 (or a UTF-8 one with a few such bytes) reads as written
     * without a decoded copy of the whole file next to its bytes.
     */
    private fun string(bytes: ByteArray, start: Int, end: Int): String = TextDecoding.decode(bytes, start, end)

    internal fun parseExtInf(body: String): ExtInf {
        val bytes = body.encodeToByteArray()
        return parseExtInf(bytes, 0, bytes.size)
    }

    /** Parses `-1 tvg-id="x" group-title="A, B",Display Name` in `bytes[start, end)`. */
    private fun parseExtInf(bytes: ByteArray, start: Int, end: Int): ExtInf {
        // The title starts after the first comma that is not inside a quoted attribute value.
        var quote: Byte = 0
        var comma = -1
        var i = start
        while (i < end) {
            val c = bytes[i]
            if (quote != 0.toByte()) {
                if (c == quote) quote = 0
            } else if ((c == DOUBLE_QUOTE || c == SINGLE_QUOTE) && i > start && bytes[i - 1] == EQUALS) {
                quote = c
            } else if (c == COMMA) {
                comma = i
                break
            }
            i++
        }
        val metaEnd = if (comma >= 0) comma else end
        var title = ""
        if (comma >= 0) {
            var t0 = comma + 1
            var t1 = end
            while (t0 < t1 && isBlank(bytes[t0])) t0++
            while (t1 > t0 && isBlank(bytes[t1 - 1])) t1--
            if (t0 < t1) title = string(bytes, t0, t1)
        }
        // Duration is the first token.
        var a = start
        while (a < metaEnd && isBlank(bytes[a])) a++
        var d = a
        while (d < metaEnd && !isBlank(bytes[d])) d++
        val duration = string(bytes, a, d).toStrictDoubleOrNull() ?: -1.0
        return ExtInf(duration, title, parseAttributes(bytes, d, metaEnd))
    }

    internal fun parseAttributes(text: String): Map<String, String> {
        val bytes = text.encodeToByteArray()
        return parseAttributes(bytes, 0, bytes.size)
    }

    /** Parses `key="value" key2='value' key3=value` pairs of `bytes[start, end)`. Keys are lowercased. */
    private fun parseAttributes(bytes: ByteArray, start: Int, end: Int): Map<String, String> {
        val result = HashMap<String, String>(8)
        var i = start
        while (i < end) {
            while (i < end && isBlank(bytes[i])) i++
            if (i >= end) break
            val keyStart = i
            while (i < end && bytes[i] != EQUALS && !isBlank(bytes[i])) i++
            val keyEnd = i
            if (i >= end || bytes[i] != EQUALS) continue // bare token
            i++
            var valueStart = i
            var valueEnd = i
            if (i < end && (bytes[i] == DOUBLE_QUOTE || bytes[i] == SINGLE_QUOTE)) {
                val quote = bytes[i]
                i++
                valueStart = i
                while (i < end && bytes[i] != quote) i++
                valueEnd = i
                if (i < end) i++
            } else {
                while (i < end && !isBlank(bytes[i])) i++
                valueEnd = i
            }
            if (keyEnd <= keyStart) continue
            var key = string(bytes, keyStart, keyEnd)
            if ((keyStart until keyEnd).any { bytes[it] in 0x41..0x5A }) key = key.lowercase()
            while (valueStart < valueEnd && bytes[valueStart] == SPACE) valueStart++
            while (valueEnd > valueStart && bytes[valueEnd - 1] == SPACE) valueEnd--
            result[key] = string(bytes, valueStart, valueEnd)
        }
        return result
    }

    /** `#EXTVLCOPT:http-user-agent=…`. */
    internal fun applyOption(option: String, headers: HttpHeaders): HttpHeaders {
        val eq = option.indexOf('=')
        if (eq < 0) return headers
        val key = option.substring(0, eq).trim().lowercase()
        val value = option.substring(eq + 1).trim()
        return when (key) {
            "http-user-agent" -> headers.copy(userAgent = value)
            "http-referrer", "http-referer" -> headers.copy(referrer = value)
            "http-origin" -> headers.copy(extra = headers.extra + ("Origin" to value))
            "http-cookie" -> headers.copy(extra = headers.extra + ("Cookie" to value))
            else -> headers
        }
    }

    /** `#KODIPROP:inputstream.adaptive.stream_headers=User-Agent=foo&Referer=bar`. */
    internal fun applyKodiProp(prop: String, headers: HttpHeaders): HttpHeaders {
        val eq = prop.indexOf('=')
        if (eq < 0) return headers
        val key = prop.substring(0, eq).lowercase()
        val value = prop.substring(eq + 1)
        if (key.endsWith("stream_headers") || key.endsWith("manifest_headers")) {
            return applyKodiUrlOptions(value, headers)
        }
        return headers
    }

    /** `#EXTHTTP:{"cookie":"…","User-Agent":"…"}`. */
    internal fun applyExtHttp(json: String, headers: HttpHeaders): HttpHeaders {
        val obj = parseJsonOrNull(json) as? JsonObject ?: return headers
        var result = headers
        for ((key, value) in obj) {
            if (value is JsonPrimitive && value.isString) result = setHeader(key, value.content, result)
        }
        return result
    }

    /** `User-Agent=foo&Referer=http%3A%2F%2Fbar` (values percent-decoded, `+` kept). */
    internal fun applyKodiUrlOptions(options: String, headers: HttpHeaders): HttpHeaders {
        var result = headers
        for (pair in options.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            val key = pair.substring(0, eq)
            val rawValue = pair.substring(eq + 1)
            result = setHeader(key, Urls.percentDecode(rawValue) ?: rawValue, result)
        }
        return result
    }

    private fun setHeader(key: String, value: String, headers: HttpHeaders): HttpHeaders = when (key.lowercase()) {
        "user-agent" -> headers.copy(userAgent = value)
        "referer", "referrer" -> headers.copy(referrer = value)
        else -> headers.copy(extra = headers.extra + (key to value))
    }
}
