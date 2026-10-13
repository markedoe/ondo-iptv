package com.markedoe.ondo.core.model

import kotlinx.serialization.Serializable

/** A time interval `[start, end]` in epoch millis (Foundation `DateInterval`). */
@Serializable
data class TimeWindow(val start: Long, val end: Long) {
    init {
        require(end >= start) { "end ($end) must not be before start ($start)" }
    }

    val durationMillis: Long
        get() = end - start

    operator fun contains(instant: Long): Boolean = instant in start..end

    companion object {
        fun of(start: Long, durationMillis: Long) = TimeWindow(start, start + durationMillis)
    }
}
