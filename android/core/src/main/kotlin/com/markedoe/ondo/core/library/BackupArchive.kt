package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.CoreJson
import com.markedoe.ondo.core.model.Playlist
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Portable backup of the user's setup (playlists, without passwords, and library), for export / import. */
@Serializable
data class BackupArchive(
    val version: Int = JsonFileStore.CURRENT_VERSION,
    /** Epoch millis. */
    val exportedAt: Long,
    val playlists: List<Playlist>,
    val library: UserLibrary,
) {
    /** Pretty printed JSON. */
    fun encoded(): ByteArray = prettyJson.encodeToString(serializer(), this).encodeToByteArray()

    companion object {
        private val prettyJson = Json(CoreJson) { prettyPrint = true }

        fun decode(bytes: ByteArray): BackupArchive = CoreJson.decodeFromString(serializer(), bytes.decodeToString())
    }
}
