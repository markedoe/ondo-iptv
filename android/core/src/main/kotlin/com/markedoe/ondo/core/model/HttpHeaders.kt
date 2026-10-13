package com.markedoe.ondo.core.model

import kotlinx.serialization.Serializable

/** Extra HTTP headers some providers require (User-Agent / Referer locks). */
@Serializable
data class HttpHeaders(
    val userAgent: String? = null,
    val referrer: String? = null,
    val extra: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = userAgent.isNullOrEmpty() && referrer.isNullOrEmpty() && extra.isEmpty()

    /** Header map suitable for an HTTP request or the player's data source. */
    fun toMap(): Map<String, String> {
        val result = LinkedHashMap(extra)
        if (!userAgent.isNullOrEmpty()) result["User-Agent"] = userAgent
        if (!referrer.isNullOrEmpty()) result["Referer"] = referrer
        return result
    }

    /** Item-level headers (`other`) win over playlist-level ones (`this`). */
    fun merging(other: HttpHeaders?): HttpHeaders {
        if (other == null) return this
        return HttpHeaders(
            userAgent = other.userAgent?.takeIf { it.isNotEmpty() } ?: userAgent,
            referrer = other.referrer?.takeIf { it.isNotEmpty() } ?: referrer,
            extra = extra + other.extra,
        )
    }

    companion object {
        val NONE = HttpHeaders()
    }
}
