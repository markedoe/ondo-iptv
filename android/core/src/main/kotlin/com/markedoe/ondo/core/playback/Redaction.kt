package com.markedoe.ondo.core.playback

import com.markedoe.ondo.core.PortableRegex

/**
 * What the player's title data leaves out of a provider's text before showing it: addresses, logins and passwords,
 * query values, IP addresses and `host:port`, and the playlist's own login, password and server wherever they appear.
 * The same rules as Apple's `Redaction` (IPTVCore), tested on `fixtures/title-data/redaction.json`. A title's own colon
 * or at sign ("Copper Valley: Night @ Sea") stays. Whitespace is Unicode's (a no-break space too), as on Apple.
 */
object Redaction {
    const val MASK = "•••"

    // Unicode whitespace, digits and word boundaries spelled out: the same on Android (ICU) and in the unit tests (PortableRegex).
    private const val SPACE = PortableRegex.SPACE
    private const val D = PortableRegex.DIGIT
    private const val B = PortableRegex.BOUNDARY

    private val ADDRESS = Regex("""[A-Za-z][A-Za-z0-9+.\-]*://[^$SPACE]+""")
    private val LOGIN = Regex("""[^$SPACE/:@]+:[^$SPACE/@]+@""")
    private val QUERY = Regex("""([?&;][A-Za-z0-9_.\-]+=)[^&;#$SPACE]+""")
    private val IP = Regex("""$B$D{1,3}(?:\.$D{1,3}){3}(?::$D{1,5})?$B""")
    private val HOST_PORT = Regex("""$B[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)+:$D{2,5}$B""")

    /**
     * An id the panel may show: a provider's number ("x1234", "98765"). The M3U reader's ids are a hash of
     * the stream's address, login and password included: a photo of one would let anyone check guesses offline, so
     * they never show.
     */
    private val PROVIDER_ID = Regex("""[A-Za-z]{0,2}-?[0-9]{1,12}(?:-[0-9]{1,4})?""")

    /** [text] without anything that could be a credential or an address; [secrets] (shorter than 3 characters ignored) masked. */
    fun clean(text: String, secrets: Collection<String> = emptyList()): String {
        var result = text
        for (secret in secrets.map { it.trim() }.filter { it.length >= 3 }.distinct().sortedByDescending { it.length }) {
            result = result.replace(secret, MASK, ignoreCase = true)
        }
        result = ADDRESS.replace(result, MASK)
        result = LOGIN.replace(result, "$MASK@")
        result = QUERY.replace(result) { it.groupValues[1] + MASK }
        result = IP.replace(result, MASK)
        result = HOST_PORT.replace(result, MASK)
        return result
    }

    /** [id] when it is a provider's number, else null (see [PROVIDER_ID]). */
    fun shownId(id: String): String? = id.takeIf { PROVIDER_ID.matches(it) }

    /**
     * What a playlist's [addresses] (its link or server, its guides) and [login] are made of, to mask with [clean]: the
     * login and password, each address's host (with or without a scheme), its user part, and its query's values, as
     * written and percent-decoded.
     */
    fun secretsOf(addresses: Collection<String>, login: Collection<String?> = emptyList()): List<String> {
        val result = ArrayList<String>()
        login.filterNotNullTo(result)
        fun add(value: String) {
            result.add(value)
            result.add(percentDecoded(value))
        }
        for (address in addresses) {
            var text = address.trim()
            if (text.isEmpty()) continue
            if ("://" !in text) text = "http://$text"
            val rest = text.substringAfter("://")
            val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
            val at = authority.lastIndexOf('@')
            if (at >= 0) authority.substring(0, at).split(':').filter { it.isNotEmpty() }.forEach(::add)
            val hostPort = authority.substring(at + 1)
            result.add(if (hostPort.startsWith('[')) hostPort.drop(1).takeWhile { it != ']' } else hostPort.takeWhile { it != ':' })
            val query = rest.substringAfter('?', "").takeWhile { it != '#' }
            for (pair in query.split('&')) {
                if ('=' in pair) add(pair.substringAfter('='))
            }
        }
        return result.filter { it.isNotBlank() }.distinct()
    }

    /** `%XX` sequences as UTF-8; [text] as it is when one is not valid. */
    private fun percentDecoded(text: String): String {
        if ('%' !in text) return text
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%') {
                val byte = text.substring(i + 1, minOf(i + 3, text.length)).takeIf { it.length == 2 }?.toIntOrNull(16) ?: return text
                bytes.write(byte)
                i += 3
            } else {
                val end = if (Character.isHighSurrogate(c) && i + 1 < text.length) i + 2 else i + 1
                bytes.write(text.substring(i, end).toByteArray(Charsets.UTF_8))
                i = end
            }
        }
        val decoded = bytes.toByteArray()
        val decoder = Charsets.UTF_8.newDecoder()
        return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(decoded)).toString() }.getOrDefault(text)
    }
}
