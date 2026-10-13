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

    /** Worth offering "resume": started, not (almost) finished. */
    val isResumable: Boolean
        get() = reference.type != ContentType.LIVE && position > 30 && progress < 0.95

    val isFinished: Boolean
        get() = reference.type != ContentType.LIVE && progress >= 0.95
}
