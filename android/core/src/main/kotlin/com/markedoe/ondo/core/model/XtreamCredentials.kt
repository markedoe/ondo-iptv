package com.markedoe.ondo.core.model

import com.markedoe.ondo.core.net.Urls
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Login for an Xtream Codes compatible panel. */
@Serializable
data class XtreamCredentials(
    /** Base URL of the panel, e.g. `http://example.com:8080` (no trailing slash, no path). */
    val server: String,
    val username: String,
    val password: String,
) {
    companion object {
        /**
         * Accepts what users typically paste: `host:port`, `http://host:port/`, or even a full
         * `get.php?username=..&password=..` link. Returns `scheme://authority`, or `null`.
         */
        fun normalizeServer(raw: String): String? {
            var text = raw.trim()
            if (text.isEmpty()) return null
            val lower = text.lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) text = "http://$text"
            if (!Urls.isValidAbsolute(text)) return null
            val parsed = Urls.parse(text) ?: return null
            val authority = parsed.authority
            if (authority.isNullOrEmpty() || parsed.host.isNullOrEmpty()) return null
            return "${parsed.scheme}://$authority"
        }

        /**
         * Extracts credentials from an M3U link produced by an Xtream panel
         * (`http://host:port/get.php?username=U&password=P&type=m3u_plus`).
         */
        fun fromM3uLink(url: String): XtreamCredentials? {
            val parsed = Urls.parse(url) ?: return null
            val path = parsed.path
            if (!path.endsWith("get.php") && !path.endsWith("player_api.php")) return null
            val items = Urls.queryItems(parsed.rawQuery)
            val user = items.firstOrNull { it.first == "username" }?.second?.takeIf { it.isNotEmpty() } ?: return null
            val pass = items.firstOrNull { it.first == "password" }?.second?.takeIf { it.isNotEmpty() } ?: return null
            val server = normalizeServer(url) ?: return null
            return XtreamCredentials(server, user, pass)
        }
    }
}

/** Stream container the Xtream panel should serve live channels in. */
@Serializable
enum class XtreamOutputFormat(
    /** File extension / `output` parameter value. */
    val rawValue: String,
) {
    @SerialName("m3u8") M3U8("m3u8"),
    @SerialName("ts") TS("ts"),
}
