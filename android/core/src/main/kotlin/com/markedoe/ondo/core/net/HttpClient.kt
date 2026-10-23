package com.markedoe.ondo.core.net

import java.io.File
import java.io.IOException

/**
 * Minimal HTTP abstraction so loaders can be unit-tested with fixtures.
 * Implementations must throw [HttpException.BadStatus] for non-2xx answers.
 */
interface HttpClient {
    /** Downloads `url` (http, https or file) with the given request headers. */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): ByteArray

    /**
     * Downloads `url` into [file], for answers too large to hold in memory (a guide can be hundreds of megabytes
     * once inflated, more than a TV gives an app). The default goes through [get].
     */
    suspend fun download(url: String, headers: Map<String, String> = emptyMap(), file: File) {
        file.writeBytes(get(url, headers))
    }
}

/** Default user agent: many panels block unknown agents, VLC's is widely accepted. */
const val DEFAULT_IPTV_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

/** HTTP level failures, with messages meant for the user. */
sealed class HttpException(message: String) : IOException(message) {
    /** The server answered with a non-2xx status. */
    class BadStatus(val code: Int) : HttpException(messageFor(code))

    /** The server answered with an empty body where data was expected. */
    class EmptyResponse : HttpException("The server returned an empty response.")

    /** The address cannot be requested. */
    class InvalidUrl(val url: String) : HttpException("The address is not valid.")

    /** The answer is larger than any real playlist or guide (or never ends). */
    class TooLarge(val limit: Long) : HttpException("The server sent more than ${limit / (1024 * 1024)} MB.")

    private companion object {
        fun messageFor(code: Int): String = when (code) {
            401, 403 -> "Access denied by the server (HTTP $code). Check your credentials or subscription."
            404 -> "Not found on the server (HTTP 404)."
            512, 884 -> "The provider refused the connection (HTTP $code). Too many connections?"
            else -> "The server answered with HTTP $code."
        }
    }
}
