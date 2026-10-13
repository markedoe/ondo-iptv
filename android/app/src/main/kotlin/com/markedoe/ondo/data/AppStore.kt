package com.markedoe.ondo.data

import com.markedoe.ondo.core.model.PlaylistContent
import java.io.File
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** JSON documents in the app's private storage, written atomically (temporary file, then rename). */
class AppStore(private val directory: File) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    init {
        directory.mkdirs()
    }

    fun loadSettings(): AppSettings = read("settings.json", AppSettings.serializer()) ?: AppSettings()
    fun saveSettings(settings: AppSettings) = write("settings.json", AppSettings.serializer(), settings)

    fun loadPlaylists(): List<SavedPlaylist> = read("playlists.json", ListSerializer(SavedPlaylist.serializer())) ?: emptyList()
    fun savePlaylists(playlists: List<SavedPlaylist>) = write("playlists.json", ListSerializer(SavedPlaylist.serializer()), playlists)

    fun loadLibrary(): LibraryState = read("library.json", LibraryState.serializer()) ?: LibraryState()
    fun saveLibrary(library: LibraryState) = write("library.json", LibraryState.serializer(), library)

    fun loadContent(playlistId: String): PlaylistContent? = read("content-$playlistId.json", PlaylistContent.serializer())
    fun saveContent(playlistId: String, content: PlaylistContent) = write("content-$playlistId.json", PlaylistContent.serializer(), content)
    fun deleteContent(playlistId: String) {
        File(directory, "content-$playlistId.json").delete()
    }

    private fun <T> read(name: String, serializer: KSerializer<T>): T? {
        val file = File(directory, name)
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(serializer, file.readText()) }.getOrNull()
    }

    @Synchronized
    private fun <T> write(name: String, serializer: KSerializer<T>, value: T) {
        directory.mkdirs() // the folder can be removed while the app runs (storage cleared)
        val target = File(directory, name)
        val temporary = File(directory, "$name.tmp")
        temporary.writeText(json.encodeToString(serializer, value))
        if (!temporary.renameTo(target)) {
            target.delete()
            temporary.renameTo(target)
        }
    }
}
