package com.markedoe.ondo.core.playback

/**
 * The format of a stream as its address names it, shown on movie and series pages: "MKV", "MP4", "HLS"… Nothing
 * for an address that names no known video format (a `.php` link, no extension). Same rule as the Apple apps.
 */
object MediaFormat {
    /** Video containers a viewer may want to know about. */
    private val containers = setOf(
        "mkv", "mp4", "m4v", "mov", "avi", "ts", "m2ts", "mts", "webm", "wmv", "flv", "mpg", "mpeg", "vob", "ogv",
        "3gp", "divx", "asf", "rmvb",
    )

    fun label(url: String): String? = ofExtension(ProblemReport.container(url) ?: return null)

    /** The format an extension names ("mkv", ".MKV"), as [label] says it; null for one it does not know. */
    fun ofExtension(extension: String): String? {
        val ext = extension.trim().trimStart('.').lowercase(java.util.Locale.ROOT)
        return when (ext) {
            "m3u8" -> "HLS"
            "mpd" -> "DASH"
            in containers -> ext.uppercase(java.util.Locale.ROOT)
            else -> null
        }
    }

    /** The one format of a set of streams (the episodes of a series), or null when there is none or several. */
    fun label(urls: List<String>): String? = urls.map(::label).toSet().singleOrNull()
}
