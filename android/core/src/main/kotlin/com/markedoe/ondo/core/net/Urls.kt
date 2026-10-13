package com.markedoe.ondo.core.net

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Components of a URL string, split with the RFC 3986 (Appendix B) regular expression.
 * Unlike `java.net.URI` it never rejects provider hosts (underscores…) and never resolves DNS.
 */
internal data class ParsedUrl(
    val scheme: String?,
    /** `user:pass@host:port`, `null` when the URL has no `//` part. */
    val authority: String?,
    val rawPath: String,
    val rawQuery: String?,
    val rawFragment: String?,
) {
    private val hostAndPort: String?
        get() = authority?.substringAfterLast('@')

    /** Host, with brackets for IPv6 literals. `null` when there is no authority. */
    val host: String?
        get() {
            val hp = hostAndPort ?: return null
            if (hp.startsWith("[")) {
                val end = hp.indexOf(']')
                return if (end >= 0) hp.substring(0, end + 1) else hp
            }
            val colon = hp.lastIndexOf(':')
            return if (colon >= 0) hp.substring(0, colon) else hp
        }

    val port: Int?
        get() {
            val hp = hostAndPort ?: return null
            val afterHost = if (hp.startsWith("[")) hp.substringAfter(']', "") else hp
            val colon = afterHost.lastIndexOf(':')
            if (colon < 0) return null
            val digits = afterHost.substring(colon + 1)
            return if (digits.isNotEmpty() && digits.all { it in '0'..'9' }) digits.toIntOrNull() else null
        }

    /** Percent-decoded path (Swift `URL.path`). */
    val path: String
        get() = Urls.percentDecode(rawPath) ?: rawPath

    /** Swift `URL.lastPathComponent`. */
    val lastPathComponent: String
        get() {
            val path = path
            if (path.isEmpty()) return ""
            val trimmed = path.trimEnd('/')
            if (trimmed.isEmpty()) return "/"
            return trimmed.substringAfterLast('/')
        }
}

/** URL helpers mirroring the Foundation behaviour the Swift code relies on. URLs are plain strings. */
internal object Urls {
    private val rfc3986 = Regex("""^(([^:/?#]+):)?(//([^/?#]*))?([^?#]*)(\?([^#]*))?(#(.*))?""", RegexOption.DOT_MATCHES_ALL)

    /** Foundation `CharacterSet.urlQueryAllowed`. */
    const val QUERY_ALLOWED = "!$&'()*+,-./0123456789:;=?@ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz~"

    /** Foundation `CharacterSet.urlPathAllowed`. */
    const val PATH_ALLOWED = "!$&'()*+,-./0123456789:=@ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz~"

    private val lenientAllowed = allowedTable("$QUERY_ALLOWED#%")
    private val validUrlCharacters = allowedTable(
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~:/?#[]@!$&'()*+,;=%",
    )

    fun allowedTable(characters: String): BooleanArray {
        val table = BooleanArray(128)
        for (c in characters) table[c.code] = true
        return table
    }

    fun allowedTable(characters: String, minus: String): BooleanArray {
        val table = allowedTable(characters)
        for (c in minus) table[c.code] = false
        return table
    }

    fun parse(url: String): ParsedUrl? {
        val match = rfc3986.find(url) ?: return null
        val groups = match.groups
        val scheme = groups[2]?.value?.takeIf { isValidScheme(it) }
        if (groups[2] != null && scheme == null) {
            // "TF1 HD:x" is not a scheme: the whole string is a relative reference.
            return ParsedUrl(null, null, url.substringBefore('?').substringBefore('#'), null, null)
        }
        return ParsedUrl(
            scheme = scheme,
            authority = groups[4]?.value,
            rawPath = groups[5]?.value.orEmpty(),
            rawQuery = groups[7]?.value,
            rawFragment = groups[9]?.value,
        )
    }

    private fun isValidScheme(scheme: String): Boolean =
        scheme.isNotEmpty() && scheme[0].isAsciiLetter() &&
            scheme.all { it.isAsciiLetter() || it in '0'..'9' || it == '+' || it == '-' || it == '.' }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    /** Would Foundation's strict `URL(string:)` accept this string, with a scheme? */
    fun isValidAbsolute(url: String): Boolean {
        if (url.isEmpty()) return false
        var i = 0
        while (i < url.length) {
            val c = url[i]
            if (c.code >= 128 || !validUrlCharacters[c.code]) return false
            if (c == '%') {
                if (i + 2 >= url.length || !url[i + 1].isHexDigit() || !url[i + 2].isHexDigit()) return false
                i += 2
            }
            i++
        }
        return parse(url)?.scheme != null
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /**
     * Lenient URL creation for provider data (spaces, unicode in paths…): trims, then accepts the string
     * as-is when it is a valid absolute URL, otherwise percent-encodes what the query set does not allow.
     */
    fun lenient(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        if (trimmed.isEmpty()) return null
        if (isValidAbsolute(trimmed)) return trimmed
        val escaped = percentEncode(trimmed, lenientAllowed)
        return if (isValidAbsolute(escaped)) escaped else null
    }

    /** Percent-encodes (uppercase hex, UTF-8) every character outside `allowed`. */
    fun percentEncode(value: String, allowed: BooleanArray): String {
        var needsEncoding = false
        for (c in value) {
            if (c.code >= 128 || !allowed[c.code]) {
                needsEncoding = true
                break
            }
        }
        if (!needsEncoding) return value
        val bytes = value.encodeToByteArray()
        val builder = StringBuilder(bytes.size + 16)
        for (byte in bytes) {
            val b = byte.toInt() and 0xFF
            if (b < 128 && allowed[b]) {
                builder.append(b.toChar())
            } else {
                builder.append('%').append(HEX[b shr 4]).append(HEX[b and 0x0F])
            }
        }
        return builder.toString()
    }

    private const val HEX = "0123456789ABCDEF"

    /**
     * Swift `removingPercentEncoding`: decodes `%XX` sequences (not `+`). Returns `null` when a sequence
     * is malformed or the bytes are not valid UTF-8.
     */
    fun percentDecode(value: String): String? {
        if (value.indexOf('%') < 0) return value
        val out = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%') {
                if (i + 2 >= value.length) return null
                val high = Character.digit(value[i + 1], 16)
                val low = Character.digit(value[i + 2], 16)
                if (high < 0 || low < 0) return null
                out.write((high shl 4) or low)
                i += 3
            } else {
                val end = run {
                    var j = i
                    while (j < value.length && value[j] != '%') j++
                    j
                }
                out.write(value.substring(i, end).encodeToByteArray())
                i = end
            }
        }
        return decodeUtf8Strict(out.toByteArray())
    }

    /** UTF-8 decoding that fails (returns `null`) on malformed input instead of substituting. */
    fun decodeUtf8Strict(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** Swift `URL.lastPathComponent` of a URL string. */
    fun lastPathComponent(url: String): String = parse(url)?.lastPathComponent.orEmpty()

    /**
     * Query string built like Foundation's `URLComponents.queryItems`: names and values are
     * percent-encoded with the query set minus `&` (and `=` for names). `+` is left alone, as Foundation does.
     */
    fun query(items: List<Pair<String, String>>): String =
        items.joinToString("&") { (name, value) ->
            percentEncode(name, queryNameAllowed) + "=" + percentEncode(value, queryValueAllowed)
        }

    private val queryNameAllowed = allowedTable(QUERY_ALLOWED, minus = "&=")
    private val queryValueAllowed = allowedTable(QUERY_ALLOWED, minus = "&")

    /** Foundation `URLComponents.queryItems` getter: decoded name/value pairs. */
    fun queryItems(query: String?): List<Pair<String, String?>> {
        if (query.isNullOrEmpty()) return emptyList()
        return query.split('&').map { item ->
            val eq = item.indexOf('=')
            if (eq < 0) {
                (percentDecode(item) ?: item) to null
            } else {
                val name = item.substring(0, eq)
                val value = item.substring(eq + 1)
                (percentDecode(name) ?: name) to (percentDecode(value) ?: value)
            }
        }
    }
}
