package com.markedoe.ondo.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How past programmes of a live channel can be replayed. */
@Serializable
data class CatchupInfo(
    val mode: Mode,
    /** How many days back the provider keeps the archive. */
    val days: Int,
    val source: String? = null,
) {
    @Serializable
    enum class Mode {
        /** `catchup-source` is a full URL template. */
        @SerialName("default") DEFAULT,

        /** `catchup-source` is appended to the live URL. */
        @SerialName("append") APPEND,

        /** `?utc={start}&lutc={now}` appended to the live URL (SIPTV style). */
        @SerialName("shift") SHIFT,

        /** Flussonic `timeshift_abs` / `index-{start}-{duration}` URLs. */
        @SerialName("flussonic") FLUSSONIC,

        /** Xtream Codes `/timeshift/` URLs. */
        @SerialName("xtream") XTREAM,
    }
}
