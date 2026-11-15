package com.markedoe.ondo.data

import com.markedoe.ondo.core.library.FilmVersions
import com.markedoe.ondo.core.model.Movie
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * The grouping of a catalogue's films into versions ([FilmVersions.groups]) kept between two starts of the app, next to
 * the saved catalogue: grouping reads every title of the catalogue (a moment on a large one, before Home's rows can
 * show), and gives the same groups for the same movies. One file, for the catalogue last grouped: numbers only (each
 * stream's group), no title and no address. It is read back only for the very same movies (their count, and a digest
 * of every id, name, year and TMDb id the grouping reads, in their order), the same rules ([FilmVersions.RULES]) and the
 * same build of the app ([build]); anything else, or a file cut short, groups the catalogue again.
 */
class FilmGroupsFile(private val file: File, private val build: Int) {
    /** The groups kept for exactly these [movies], or null. */
    fun read(movies: List<Movie>): IntArray? {
        if (movies.isEmpty() || !file.isFile) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != FilmVersions.RULES || input.readInt() != build) return null
                if (input.readInt() != FilmVersions.LATEST_YEAR) return null
                val count = input.readInt()
                if (count != movies.size || input.readLong() != digest(movies)) return null
                val groups = IntArray(count)
                var sum = 0L
                for (index in 0 until count) {
                    val group = input.readInt()
                    groups[index] = group
                    sum = sum * 31 + group
                }
                // The end of the file as it was written: one cut short, or mixed with another, is not read.
                if (input.readLong() != sum) null else groups
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Keeps [groups] for [movies] (null or of another size: nothing kept). A failed write (disk full) keeps nothing. */
    fun write(movies: List<Movie>, groups: IntArray?) {
        if (groups == null || groups.size != movies.size || movies.isEmpty()) return
        // Never a folder of its own: without the app's folder (storage cleared, a test's folder gone), nothing is kept.
        val folder = file.parentFile ?: return
        if (!folder.isDirectory) return
        val temporary = File(folder, file.name + ".tmp")
        try {
            DataOutputStream(temporary.outputStream().buffered()).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(FilmVersions.RULES)
                output.writeInt(build)
                output.writeInt(FilmVersions.LATEST_YEAR)
                output.writeInt(groups.size)
                output.writeLong(digest(movies))
                var sum = 0L
                for (group in groups) {
                    output.writeInt(group)
                    sum = sum * 31 + group
                }
                output.writeLong(sum)
            }
            if (!temporary.renameTo(file)) {
                file.delete()
                temporary.renameTo(file)
            }
        } catch (_: Exception) {
            temporary.delete()
        }
    }

    companion object {
        const val FILE_NAME = "film-groups.bin"

        private const val MAGIC = 0x4F46_4731 // "OFG1"

        /** What the grouping reads of each movie, in the catalogue's order: another movie, name, year or id is another digest. */
        internal fun digest(movies: List<Movie>): Long {
            var digest = 1125899906842597L
            for (movie in movies) {
                digest = digest * 31 + movie.id.hashCode()
                digest = digest * 31 + movie.name.hashCode()
                digest = digest * 31 + (movie.year ?: -1)
                digest = digest * 31 + (movie.tmdbId ?: -1)
            }
            return digest
        }
    }
}
