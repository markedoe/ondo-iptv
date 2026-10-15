package com.markedoe.ondo.data

import java.io.File

/**
 * Newer awards data: the "Awards data" workflow publishes it once a month (from Wikidata) on the project's
 * `awards-data` branch; the app looks for it once a week, unless turned off in Settings, and keeps the last download
 * in [file]. Nothing about the viewer is sent.
 */
class AwardsDownload(val url: String, val file: File) {
    companion object {
        const val URL = "https://raw.githubusercontent.com/markedoe/ondo-iptv/awards-data/awards.json"
        const val INTERVAL = 7 * 24 * 3_600_000L

        /** The file is about half a megabyte: anything far larger is not it. */
        const val MAX_SIZE = 8 shl 20

        /** A file with fewer films is a partial answer. */
        const val MIN_FILMS = 1_000
    }
}
