package com.markedoe.ondo.core.model

import kotlinx.serialization.Serializable

/** Xtream account status returned by `player_api.php`. */
@Serializable
data class AccountInfo(
    val username: String,
    val status: String,
    val isTrial: Boolean = false,
    /** Subscription end, epoch millis. */
    val expiresAt: Long? = null,
    /** Account creation, epoch millis. */
    val createdAt: Long? = null,
    val activeConnections: Int? = null,
    val maxConnections: Int? = null,
    val allowedOutputFormats: List<String> = emptyList(),
    val message: String? = null,
    /** IANA time zone of the panel (`Europe/Paris`), used for catch-up URLs. */
    val serverTimeZone: String? = null,
    val serverUrl: String? = null,
) {
    val isActive: Boolean
        get() = status.lowercase() == "active"
}
