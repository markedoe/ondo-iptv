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

    fun label(url: String): String? {
        val extension = ProblemReport.container(url) ?: return null
        return when (extension) {
            "m3u8" -> "HLS"
            "mpd" -> "DASH"
            in containers -> extension.uppercase()
            else -> null
        }
    }

    /** The one format of a set of streams (the episodes of a series), or null when there is none or several. */
    fun label(urls: List<String>): String? = urls.map(::label).toSet().singleOrNull()
}
