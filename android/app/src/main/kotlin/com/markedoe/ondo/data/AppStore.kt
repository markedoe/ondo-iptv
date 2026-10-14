package com.markedoe.ondo.data

import com.markedoe.ondo.core.model.PlaylistContent
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

/**
 * JSON documents in the app's private storage. A write goes to a temporary file, synced to disk, then
 * renamed over the document; the previous version of the user's documents (settings, playlists, library)
 * is kept as `.bak`. A document that cannot be read is moved aside, never treated as empty: the next save
 * would otherwise write over the user's favourites and playlists.
 */
@OptIn(ExperimentalSerializationApi::class)
class AppStore(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    init {
        directory.mkdirs()
    }

    fun loadSettings(): AppSettings = read("settings.json", AppSettings.serializer()) ?: AppSettings()
    fun saveSettings(settings: AppSettings) = write("settings.json", AppSettings.serializer(), settings, keepPrevious = true)

    fun loadPlaylists(): List<SavedPlaylist> = read("playlists.json", ListSerializer(SavedPlaylist.serializer())) ?: emptyList()
    fun savePlaylists(playlists: List<SavedPlaylist>) =
        write("playlists.json", ListSerializer(SavedPlaylist.serializer()), playlists, keepPrevious = true)

    fun loadLibrary(): LibraryState = read("library.json", LibraryState.serializer()) ?: LibraryState()
    fun saveLibrary(library: LibraryState) = write("library.json", LibraryState.serializer(), library, keepPrevious = true)

    /** Catalogues can be downloaded again: no previous version is kept. */
    fun loadContent(playlistId: String): PlaylistContent? = read("content-$playlistId.json", PlaylistContent.serializer())
    fun saveContent(playlistId: String, content: PlaylistContent) =
        write("content-$playlistId.json", PlaylistContent.serializer(), content, keepPrevious = false)
    fun deleteContent(playlistId: String) {
        File(directory, "content-$playlistId.json").delete()
    }

    /** The document, else its previous version. One that cannot be read is renamed `name.corrupt-<time>`. */
    @Synchronized
    private fun <T> read(name: String, serializer: KSerializer<T>): T? {
        for (file in listOf(File(directory, name), File(directory, "$name.bak"))) {
            if (!file.exists()) continue
            val value = runCatching { file.inputStream().buffered().use { json.decodeFromStream(serializer, it) } }.getOrNull()
            if (value != null) return value
            file.renameTo(File(directory, "${file.name}.corrupt-${now()}"))
        }
        return null
    }

    @Synchronized
    private fun <T> write(name: String, serializer: KSerializer<T>, value: T, keepPrevious: Boolean) {
        directory.mkdirs() // the folder can be removed while the app runs (storage cleared)
        val target = File(directory, name)
        val temporary = File(directory, "$name.tmp")
        FileOutputStream(temporary).use { stream ->
            stream.buffered().let { buffered ->
                json.encodeToStream(serializer, value, buffered)
                buffered.flush()
            }
            stream.fd.sync()
        }
        if (keepPrevious && target.exists()) {
            val previous = File(directory, "$name.bak")
            previous.delete()
            target.renameTo(previous) // a crash right after leaves the previous version, which read() takes
        }
        if (!temporary.renameTo(target)) {
            target.delete()
            temporary.renameTo(target)
        }
    }
}
