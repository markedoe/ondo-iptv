package com.markedoe.ondo.data

import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.Profile
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
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
class AppStore(
    private val directory: File,
    private val now: () -> Long = System::currentTimeMillis,
    /** How a document is opened for reading (tests make one too large for the memory left). */
    private val open: (File) -> InputStream = File::inputStream,
) {
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
        directory.listFiles { file -> file.name.startsWith("playlists.json.corrupt-") || file.name.startsWith("playlists.json.too-large-") }
            ?.forEach(File::delete)
        importedFile(removed.url)?.delete()
    }

    /** Where playlist files chosen on the device are copied (`ui/PlaylistFilePicker.kt`), next to this folder. */
    val imports: File get() = File(directory.absoluteFile.parentFile, "imports")

    /**
     * Deletes the copies of chosen files that no playlist uses (a form closed without saving, a copy that failed), once a
     * day old: a form may still be open on a fresh one.
     */
    fun forgetUnusedImports(playlists: List<SavedPlaylist>) {
        val used = playlists.mapNotNull { importedFile(it.url)?.canonicalFile }.toSet()
        val time = now()
        imports.listFiles()?.filter { it.isFile && it.canonicalFile !in used && time - it.lastModified() > UNUSED_IMPORT_MS }?.forEach(File::delete)
    }

    private fun importedFile(url: String): File? =
        runCatching { File(URI(url)) }.getOrNull()?.takeIf { it.canonicalFile.parentFile == imports.canonicalFile }

    /** The viewer profiles (docs/PROFILES.md); empty before profiles existed (the main one is made then). */
    fun loadProfiles(): List<Profile> = read("profiles.json", ListSerializer(Profile.serializer())) ?: emptyList()
    fun saveProfiles(profiles: List<Profile>) = write("profiles.json", ListSerializer(Profile.serializer()), profiles, keepPrevious = true)

    /** A profile's library, by [Profile.libraryDocumentName]: `library` is the main profile's (the one before profiles). */
    fun loadLibrary(document: String = MAIN_LIBRARY): LibraryState = read("$document.json", LibraryState.serializer()) ?: LibraryState()
    fun saveLibrary(library: LibraryState, document: String = MAIN_LIBRARY) =
        write("$document.json", LibraryState.serializer(), library, keepPrevious = true)

    /** A deleted profile's library, with its previous version and any damaged copy. */
    fun deleteLibrary(document: String) {
        require(document != MAIN_LIBRARY) { "The main profile's library is never deleted" }
        File(directory, "$document.json").delete()
        File(directory, "$document.json.bak").delete()
        directory.listFiles { file -> file.name.startsWith("$document.json.corrupt-") || file.name.startsWith("$document.json.too-large-") }
            ?.forEach(File::delete)
    }

    /** Catalogues can be downloaded again: no previous version is kept, and one that cannot be read is deleted. */
    fun loadContent(playlistId: String): PlaylistContent? = read("content-$playlistId.json", PlaylistContent.serializer(), disposable = true)
    fun saveContent(playlistId: String, content: PlaylistContent) =
        write("content-$playlistId.json", PlaylistContent.serializer(), content, keepPrevious = false)
    fun deleteContent(playlistId: String) {
        File(directory, "content-$playlistId.json").delete()
    }

    /** A playlist's guide as it was last downloaded ([StoredGuide]); it can be downloaded again: no previous version is kept. */
    fun loadGuide(playlistId: String): StoredGuide? = read("guide-$playlistId.json", StoredGuide.serializer(), disposable = true)
    fun saveGuide(playlistId: String, guide: StoredGuide) =
        write("guide-$playlistId.json", StoredGuide.serializer(), guide, keepPrevious = false)
    fun deleteGuide(playlistId: String) {
        File(directory, "guide-$playlistId.json").delete()
    }

    /**
     * The catalogue, guide and details kept for playlist [from], kept for [to] instead (the same account under another
     * ID): nothing is downloaded again. What [to] already has stays; [from]'s is then deleted.
     */
    fun movePlaylistData(from: String, to: String) {
        for (prefix in listOf("content", "guide", "details")) {
            val source = File(directory, "$prefix-$from.json")
            val target = File(directory, "$prefix-$to.json")
            if (!source.exists()) continue
            if (target.exists() || !source.renameTo(target)) source.delete()
        }
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
     * when [disposable] (it can be downloaded again). One too large for the memory left is not damaged: the previous
     * version is read instead, and the large one is then moved aside as `name.too-large-<time>`, so that the next save
     * keeps the version read as the previous one. With no other version to read, it stays where it is.
     */
    @Synchronized
    private fun <T> read(name: String, serializer: KSerializer<T>, disposable: Boolean = false): T? {
        val document = File(directory, name)
        var tooLarge = false
        for (file in listOf(document, File(directory, "$name.bak"))) {
            if (!file.exists()) continue
            val value = try {
                open(file).buffered().use { json.decodeFromStream(serializer, it) }
            } catch (_: OutOfMemoryError) {
                if (file != document) return null
                tooLarge = true
                continue
            } catch (_: Exception) {
                null
            }
            if (value != null) {
                if (tooLarge) document.renameTo(File(directory, "$name.too-large-${now()}"))
                return value
            }
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

    companion object {
        private const val UNUSED_IMPORT_MS = 24 * 3_600_000L

        /** The main profile's library document ([Profile.libraryDocumentName]). */
        const val MAIN_LIBRARY = "library"

        /** The collections shared with every profile (docs/PROFILES.md, "Shared lists"), as a library document of their own. */
        const val HOUSEHOLD = "household"
    }
}
