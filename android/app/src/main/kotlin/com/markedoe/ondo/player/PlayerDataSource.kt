package com.markedoe.ondo.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import java.io.File

/**
 * Where the player reads from: everything over the stream's HTTP source (the player's identity, the provider's
 * headers, the byte count), as before; and a subtitle downloaded from a service from its file, only when that file is
 * in [subtitles] (the app's folder of downloaded subtitles), its real path checked. Any other local address (a file
 * elsewhere in the app, `content:`, `asset:`, `android.resource:`, `data:`), which a playlist, an HLS key or a segment
 * could name, is refused: a stream reads none of the app's files.
 */
@OptIn(UnstableApi::class)
class PlayerDataSource(private val subtitles: File, private val http: DataSource) : DataSource {
    private val listeners = ArrayList<TransferListener>()
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        http.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme?.lowercase()
        val source = when {
            scheme == "http" || scheme == "https" -> http
            scheme == "file" && isDownloadedSubtitle(dataSpec.uri, subtitles) -> FileDataSource().also { file -> listeners.forEach(file::addTransferListener) }
            scheme == null || scheme in LOCAL_SCHEMES -> throw DataSourceException(PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
            // Any other (rtmp:, udp:…): as before, the HTTP source says what it makes of it.
            else -> http
        }
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }

    class Factory(private val subtitles: File, private val http: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = PlayerDataSource(subtitles, http.createDataSource())
    }

    companion object {
        /** Addresses that name something on the device rather than on the network. */
        private val LOCAL_SCHEMES = setOf("file", "content", "asset", "android.resource", "rawresource", "data", "jar")

        /** Whether [uri] is a file whose real path (links and `..` resolved) lies in [folder]. */
        fun isDownloadedSubtitle(uri: Uri, folder: File): Boolean {
            val path = uri.path ?: return false
            if (uri.authority?.isNotEmpty() == true) return false
            return try {
                val root = folder.canonicalFile
                val file = File(path).canonicalFile
                file.parentFile == root && file.isFile
            } catch (_: java.io.IOException) {
                false
            }
        }
    }
}
