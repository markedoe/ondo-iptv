package com.markedoe.ondo.core.library

/**
 * Which picture a card asks for, and when, as its loads fail: the first of [urls] (the provider's), tried again a
 * moment later when the network failed ([MAX_RETRIES] times, waiting longer each time), then the next address (TMDb's
 * still for an episode), then nothing (the generated cover with its title). Without Coil or Nuke types: the same rules
 * as the Swift `ArtworkAttempts`. A card shown again starts over ([start]): a picture that failed is asked again.
 */
data class ArtworkAttempts(
    val urls: List<String>,
    /** The address asked now, in [urls]. */
    val index: Int = 0,
    /** How many times the address asked now was asked again. */
    val retries: Int = 0,
    /** How long to wait before asking ([url]) again: 0 for a first try. */
    val waitMs: Long = 0,
    /** No address left: the generated cover shows, with its title. */
    val gaveUp: Boolean = false,
) {
    /** The address to ask, null once every one failed. */
    val url: String? get() = if (gaveUp) null else urls.getOrNull(index)

    /** Changes with every request made: a new one starts when it does. */
    val attempt: Int get() = index * (MAX_RETRIES + 1) + retries

    /** After a failed load: [transient] when the network failed (the address may still be good). */
    fun failed(transient: Boolean): ArtworkAttempts = when {
        transient && retries < MAX_RETRIES -> copy(retries = retries + 1, waitMs = RETRY_DELAYS_MS[retries])
        index + 1 < urls.size -> copy(index = index + 1, retries = 0, waitMs = 0)
        else -> copy(gaveUp = true, waitMs = 0)
    }

    companion object {
        /** Tries again after a network error, before the next address: a picture's server busy for a moment. */
        const val MAX_RETRIES = 2

        /** How long each try again waits. */
        val RETRY_DELAYS_MS = longArrayOf(1_500, 5_000)

        /** The addresses of a picture in order, blank and repeated ones left out. */
        fun start(url: String?, fallbacks: List<String?> = emptyList()): ArtworkAttempts =
            ArtworkAttempts((listOf(url) + fallbacks).filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }.distinct())

        private val httpStatus = Regex("""\bHTTP (\d{3})\b""")

        /**
         * Whether a load failed for the network rather than the address: a timeout, a connection lost or refused, a
         * busy server (408, 429, 5xx). An address the server says does not exist (404) or that is not a picture is not
         * asked again. The image loader names a status in its message ("HTTP 503").
         */
        fun isTransient(error: Throwable?): Boolean {
            var cause = error
            while (cause != null) {
                httpStatus.find(cause.message.orEmpty())?.let { match ->
                    val code = match.groupValues[1].toInt()
                    return code == 408 || code == 429 || code in 500..599
                }
                if (cause is java.io.IOException) return true
                cause = cause.cause?.takeIf { it !== cause }
            }
            return false
        }
    }
}
