package com.markedoe.ondo.core.net

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/** Thrown when compressed data cannot be inflated at all. */
class GzipException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Gzip helpers for guides and playlists served as `.gz` files (`.xml.gz`, `.m3u.gz`), which HTTP
 * clients do not inflate themselves because they are not `Content-Encoding: gzip`.
 */
object Gzip {
    private const val BUFFER_SIZE = 256 * 1024

    /**
     * Largest decompressed size accepted (as on Apple): more than any real guide, and a ceiling for files that
     * inflate without end (a few megabytes of gzip can expand to gigabytes and stop the app every launch).
     */
    const val MAX_GUNZIPPED_SIZE = 320L * 1024 * 1024

    /** gzip magic number `1f 8b`. */
    fun isGzipped(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

    /** Inflates gzip data; returns `bytes` unchanged when they are not gzip compressed. */
    fun gunzipIfNeeded(bytes: ByteArray): ByteArray = if (isGzipped(bytes)) gunzip(bytes) else bytes

    /**
     * Inflates gzip (concatenated members included) or zlib data. A truncated stream keeps what could be
     * inflated (a partial guide is better than none); nothing inflatable throws [GzipException].
     *
     * The inflated size is measured first, keeping nothing, then the data is inflated into an array of that size: a
     * few hundred kilobytes can inflate to the limit, and a buffer grown towards it (doubled, then copied) needed
     * several times the limit in memory, so the app ran out of memory before the limit was reached.
     */
    fun gunzip(bytes: ByteArray, limit: Long = MAX_GUNZIPPED_SIZE): ByteArray {
        if (bytes.isEmpty()) return bytes
        val size = inflate(bytes, limit, into = null)
        return ByteArray(size).also { inflate(bytes, limit, into = it) }
    }

    /** Inflates `bytes` into [into] (until it is full), or only counts the inflated bytes when it is null. */
    private fun inflate(bytes: ByteArray, limit: Long, into: ByteArray?): Int {
        val ceiling = minOf(limit, Int.MAX_VALUE - 8L)
        var count = 0
        try {
            val input: InputStream = if (isGzipped(bytes)) {
                GZIPInputStream(ByteArrayInputStream(bytes), BUFFER_SIZE)
            } else {
                InflaterInputStream(ByteArrayInputStream(bytes))
            }
            input.use { stream ->
                val buffer = into ?: ByteArray(BUFFER_SIZE)
                while (true) {
                    val offset = if (into == null) 0 else count
                    val length = if (into == null) buffer.size else into.size - count
                    if (length == 0) break
                    val read = stream.read(buffer, offset, length)
                    if (read < 0) break
                    if (count.toLong() + read > ceiling) throw GzipTooLargeException(limit)
                    count += read
                }
            }
        } catch (e: GzipTooLargeException) {
            throw e
        } catch (e: IOException) {
            if (count == 0) throw GzipException("Could not inflate the data: ${e.message}", e)
        }
        return count
    }

    /**
     * Wraps `input` so gzip compressed streams are inflated on the fly (detected by the magic number). Reading
     * more than `limit` inflated bytes throws [GzipTooLargeException].
     */
    fun decompressingStream(input: InputStream, limit: Long = MAX_GUNZIPPED_SIZE): InputStream {
        val buffered = if (input.markSupported()) input else BufferedInputStream(input, BUFFER_SIZE)
        buffered.mark(2)
        val first = buffered.read()
        val second = buffered.read()
        buffered.reset()
        return if (first == 0x1f && second == 0x8b) LimitedInputStream(GZIPInputStream(buffered, BUFFER_SIZE), limit) else buffered
    }
}

/** Thrown when inflated data goes past the limit (a "gzip bomb", or a file far larger than any real one). */
class GzipTooLargeException(val limit: Long) : IOException("The decompressed data is larger than ${limit / (1024 * 1024)} MB")

/** Fails once more than [limit] bytes were read. */
class LimitedInputStream(input: InputStream, private val limit: Long) : java.io.FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0 && ++count > limit) throw GzipTooLargeException(limit)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = super.read(buffer, offset, length)
        if (read > 0) {
            count += read
            if (count > limit) throw GzipTooLargeException(limit)
        }
        return read
    }
}
