package com.markedoe.ondo.data

import com.markedoe.ondo.core.model.PlaylistContent
import java.io.File
import java.io.FileOutputStream
import java.net.URI
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

    /**
     * Saves [playlists] once [removed] was deleted, leaving nothing of it behind: no previous version or damaged copy
     * still holding its login, and not the copy of the file it was imported from.
     */
    fun savePlaylists(playlists: List<SavedPlaylist>, removed: SavedPlaylist) {
        write("playlists.json", ListSerializer(SavedPlaylist.serializer()), playlists, keepPrevious = false)
        File(directory, "playlists.json.bak").delete()
        directory.listFiles { file -> file.name.startsWith("playlists.json.corrupt-") }?.forEach(File::delete)
        importedFile(removed.url)?.delete()
    }

    /** Where playlist files chosen on the device are copied (`ui/PlaylistFilePicker.kt`), next to this folder. */
    val imports: File get() = File(directory.absoluteFile.parentFile, "imports")

    private fun importedFile(url: String): File? =
        runCatching { File(URI(url)) }.getOrNull()?.takeIf { it.canonicalFile.parentFile == imports.canonicalFile }

    fun loadLibrary(): LibraryState = read("library.json", LibraryState.serializer()) ?: LibraryState()
    fun saveLibrary(library: LibraryState) = write("library.json", LibraryState.serializer(), library, keepPrevious = true)

    /** Catalogues can be downloaded again: no previous version is kept, and one that cannot be read is deleted. */
    fun loadContent(playlistId: String): PlaylistContent? = read("content-$playlistId.json", PlaylistContent.serializer(), disposable = true)
    fun saveContent(playlistId: String, content: PlaylistContent) =
        write("content-$playlistId.json", PlaylistContent.serializer(), content, keepPrevious = false)
    fun deleteContent(playlistId: String) {
        File(directory, "content-$playlistId.json").delete()
    }

    /** The movie and series details kept for a playlist; they can be fetched again: no previous version is kept. */
    fun loadDetails(playlistId: String): DetailsArchive? = read("details-$playlistId.json", DetailsArchive.serializer(), disposable = true)
    fun saveDetails(playlistId: String, archive: DetailsArchive) =
        write("details-$playlistId.json", DetailsArchive.serializer(), archive, keepPrevious = false)
    fun deleteDetails(playlistId: String) {
        File(directory, "details-$playlistId.json").delete()
    }

    /** The last error that stopped the app (see [CrashLog]), kept next to the documents. */
    val crashFile: File get() = File(directory, CrashLog.FILE_NAME)
    fun loadLastCrash(): LastCrash? = CrashLog.read(crashFile)

    /**
     * The document, else its previous version. One that cannot be read is renamed `name.corrupt-<time>`, or deleted
     * when [disposable] (it can be downloaded again). One too large for the memory left is not damaged: it stays.
     */
    @Synchronized
    private fun <T> read(name: String, serializer: KSerializer<T>, disposable: Boolean = false): T? {
        for (file in listOf(File(directory, name), File(directory, "$name.bak"))) {
            if (!file.exists()) continue
            val value = try {
                file.inputStream().buffered().use { json.decodeFromStream(serializer, it) }
            } catch (_: OutOfMemoryError) {
                return null
            } catch (_: Exception) {
                null
            }
            if (value != null) return value
            if (disposable) file.delete() else file.renameTo(File(directory, "${file.name}.corrupt-${now()}"))
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
