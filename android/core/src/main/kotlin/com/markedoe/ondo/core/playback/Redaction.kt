package com.markedoe.ondo.core.playback

/**
 * What the player's title data leaves out of a provider's text before showing it: addresses, logins and passwords,
 * query values, IP addresses and `host:port`, and the playlist's own login, password and server wherever they appear.
 * The same rules as Apple's `Redaction` (IPTVCore), tested on `fixtures/title-data/redaction.json`. A title's own colon
 * or at sign ("Copper Valley: Night @ Sea") stays.
 */
object Redaction {
    const val MASK = "•••"

    private val ADDRESS = Regex("""[A-Za-z][A-Za-z0-9+.\-]*://\S+""")
    private val LOGIN = Regex("""[^\s/:@]+:[^\s/@]+@""")
    private val QUERY = Regex("""([?&;][A-Za-z0-9_.\-]+=)[^&;#\s]+""")
    private val IP = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}(?::\d{1,5})?\b""")
    private val HOST_PORT = Regex("""\b[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)+:\d{2,5}\b""")

    /** [text] without anything that could be a credential or an address; [secrets] (shorter than 3 characters ignored) masked. */
    fun clean(text: String, secrets: Collection<String> = emptyList()): String {
        var result = text
        for (secret in secrets.map { it.trim() }.filter { it.length >= 3 }.sortedByDescending { it.length }) {
            result = result.replace(secret, MASK, ignoreCase = true)
        }
        result = ADDRESS.replace(result, MASK)
        result = LOGIN.replace(result, "$MASK@")
        result = QUERY.replace(result) { it.groupValues[1] + MASK }
        result = IP.replace(result, MASK)
        result = HOST_PORT.replace(result, MASK)
        return result
    }
}
