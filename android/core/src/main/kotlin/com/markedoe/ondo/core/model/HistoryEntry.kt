package com.markedoe.ondo.core.model

import kotlinx.serialization.Serializable

/** "Recently watched" / "Continue watching" entry. */
@Serializable
data class HistoryEntry(
    val reference: MediaReference,
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    /** Epoch millis. */
    val lastWatchedAt: Long = 0L,
    /** Seconds. Zero for live channels. */
    val position: Double = 0.0,
    /** Seconds. */
    val duration: Double = 0.0,
) {
    val id: String
        get() = reference.playbackKey

    val progress: Double
        get() = if (duration > 0) (position / duration).coerceIn(0.0, 1.0) else 0.0

    /** Worth offering "resume": started, not finished. */
    val isResumable: Boolean
        get() = reference.type != ContentType.LIVE && position > 30 && !isFinished

    /** Watched: the "watched" badge, the hidden watched movies, out of "Continue watching". Movies and episodes alike. */
    val isFinished: Boolean
        get() = reference.type != ContentType.LIVE && isFinished(position, duration)

    companion object {
        /** Watched at 95 % of the duration. */
        const val FINISHED_PROGRESS = 0.95

        /** Also watched with this many seconds or less left (a film's end credits)… */
        const val FINISHED_TIME_LEFT = 600.0

        /** …once this share is seen, so a short film still needs 95 %. */
        const val FINISHED_TIME_LEFT_MINIMUM_PROGRESS = 0.75

        /** The rule of [isFinished] (positions and durations in seconds); with no known duration, never finished. */
        fun isFinished(position: Double, duration: Double): Boolean {
            if (!(duration > 0)) return false
            val progress = (position / duration).coerceIn(0.0, 1.0)
            return progress >= FINISHED_PROGRESS ||
                (duration - position <= FINISHED_TIME_LEFT && progress >= FINISHED_TIME_LEFT_MINIMUM_PROGRESS)
        }
    }
}
