package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.CoreJson
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * Versioned JSON documents in a directory (the app's files directory on Android).
 *
 * Every document is wrapped in `{"version": n, "savedAt": millis, "value": …}` and written atomically
 * (temporary file + rename); the previous file is kept as `name.json.bak` and used when the main file
 * cannot be read, so an update can never silently wipe user data. Thread-safe.
 */
class JsonFileStore(
    val directory: File,
    private val json: Json = CoreJson,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    init {
        directory.mkdirs()
    }

    fun file(name: String): File = File(directory, "$name.json")

    private fun backupFile(name: String): File = File(directory, "$name.json.bak")

    /** The stored value, from the backup if the main file is unreadable; `null` when neither can be decoded. */
    fun <T> load(serializer: KSerializer<T>, name: String): T? = synchronized(lock) {
        for (candidate in listOf(file(name), backupFile(name))) {
            if (!candidate.isFile) continue
            try {
                val envelope = json.parseToJsonElement(candidate.readText()) as? JsonObject ?: continue
                val value = envelope["value"] ?: continue
                return json.decodeFromJsonElement(serializer, value)
            } catch (_: SerializationException) {
            } catch (_: IllegalArgumentException) {
            } catch (_: IOException) {
            }
        }
        null
    }

    /** Writes `value` atomically, keeping the previous version as backup. */
    fun <T> save(serializer: KSerializer<T>, value: T, name: String) {
        val text = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("version", CURRENT_VERSION)
                put("savedAt", clock())
                put("value", json.encodeToJsonElement(serializer, value))
            },
        )
        synchronized(lock) {
            directory.mkdirs()
            val target = file(name)
            if (target.isFile) {
                Files.copy(target.toPath(), backupFile(name).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            val temporary = File.createTempFile("ondo-$name-", ".tmp", directory)
            try {
                FileOutputStream(temporary).use { stream ->
                    stream.write(text.encodeToByteArray())
                    stream.fd.sync()
                }
                try {
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                temporary.delete()
            }
        }
    }

    inline fun <reified T> load(name: String): T? = load(serializer<T>(), name)

    inline fun <reified T> save(value: T, name: String) = save(serializer<T>(), value, name)

    /** Deletes the document and its backup. */
    fun remove(name: String): Unit = synchronized(lock) {
        file(name).delete()
        backupFile(name).delete()
    }

    /** Last write of the document, epoch millis. */
    fun modificationTime(name: String): Long? = file(name).takeIf { it.isFile }?.lastModified()

    /** Total size of the store in bytes. */
    fun diskUsage(): Long = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val CURRENT_VERSION = 1
    }
}
