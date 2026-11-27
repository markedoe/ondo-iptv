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

    /** After a failed load: [transient] when it is worth another try ([isRetryable], [ArtworkBreaker]). */
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

        /**
         * Whether a load that failed is worth another try of the same address: the server could not be reached for a
         * moment (a connection refused, reset or cut, a connect timeout, a failed DNS lookup) or said it is busy
         * ([status] 408, 429, 5xx, read by the caller from the image loader's answer). Not a picture that stopped
         * arriving (a read or whole-call timeout: asked again, a slow picture would start over from nothing), not an
         * address the server says does not exist (404), not something that is not a picture.
         */
        fun isRetryable(error: Throwable?, status: Int? = null): Boolean {
            if (status != null) return status == 408 || status == 429 || status in 500..599
            var cause = error
            var depth = 0
            while (cause != null && depth++ < 8) {
                when (cause) {
                    is java.net.SocketTimeoutException -> return cause.message.orEmpty().contains("connect", ignoreCase = true)
                    is java.io.InterruptedIOException -> return false
                    is java.net.SocketException, is java.net.UnknownHostException, is java.io.EOFException -> return true
                }
                cause = cause.cause?.takeIf { it !== cause }
            }
            return false
        }

        /** The host of [url], for [ArtworkBreaker]: null for an address without one. */
        fun hostOf(url: String?): String? = url?.let { runCatching { java.net.URI(it).host }.getOrNull() }?.lowercase()
    }
}

/**
 * Stops the tries again for a host that keeps failing: after [threshold] failures worth another try within [windowMs],
 * none for [pauseMs] (a picture failing then goes to its next address, or its cover). A dead image server then holds
 * no place of the image loader's queue with tries that cannot succeed; a picture that loads from it closes it again.
 * Thread-safe; [now] is the clock.
 */
class ArtworkBreaker(
    private val threshold: Int = 5,
    private val windowMs: Long = 30_000,
    private val pauseMs: Long = 60_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val failures = HashMap<String, ArrayDeque<Long>>()
    private val pausedUntil = HashMap<String, Long>()

    /** Whether [host] may be asked again now. */
    @Synchronized
    fun allowsRetry(host: String?): Boolean {
        host ?: return true
        val until = pausedUntil[host] ?: return true
        if (now() < until) return false
        pausedUntil.remove(host)
        return true
    }

    /** A failure worth another try ([ArtworkAttempts.isRetryable]) on [host]. */
    @Synchronized
    fun failed(host: String?) {
        host ?: return
        val at = now()
        anyFailure = true
        val recent = failures.getOrPut(host) { ArrayDeque() }
        recent.addLast(at)
        while (recent.isNotEmpty() && at - recent.first() > windowMs) recent.removeFirst()
        if (recent.size >= threshold) {
            pausedUntil[host] = at + pauseMs
            recent.clear()
        }
    }

    /** Whether any host has failures kept: a picture that loads costs nothing more while none has. */
    @Volatile private var anyFailure = false

    /** The picture at [url] loaded: its host's failures are forgotten. */
    fun succeeded(url: String?) {
        if (!anyFailure) return
        val host = ArtworkAttempts.hostOf(url) ?: return
        synchronized(this) {
            failures.remove(host)
            pausedUntil.remove(host)
            anyFailure = failures.isNotEmpty() || pausedUntil.isNotEmpty()
        }
    }

    companion object {
        /** The app's, shared by every card. */
        val shared = ArtworkBreaker()
    }
}

/**
 * [attempts] after a failed load of its address: another try when [ArtworkAttempts.isRetryable] and [breaker] allow
 * it, else the next address, else the cover. The failure counts against the address' host.
 */
fun ArtworkAttempts.afterFailure(error: Throwable?, status: Int?, breaker: ArtworkBreaker = ArtworkBreaker.shared): ArtworkAttempts {
    val host = ArtworkAttempts.hostOf(url)
    val retryable = ArtworkAttempts.isRetryable(error, status)
    if (retryable) breaker.failed(host)
    return failed(transient = retryable && breaker.allowsRetry(host))
}
