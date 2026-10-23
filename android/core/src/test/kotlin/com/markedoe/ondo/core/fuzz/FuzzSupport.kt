package com.markedoe.ondo.core.fuzz

import com.markedoe.ondo.core.library.PlaylistLoaderException
import com.markedoe.ondo.core.xtream.XtreamException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Random
import java.util.zip.GZIPOutputStream

/*
 * Contract of the loaders: they fail only with PlaylistLoaderException, XtreamException or an IOException
 * (HttpException, XmltvParseException, GzipException and GzipTooLargeException are IOExceptions), never with
 * anything else, Errors included. Every case is generated from a fixed seed, so a failure names an input that
 * can be replayed.
 */

/** Runs `block` and fails when it throws anything outside the loaders' contract; returns its result, or `null`. */
internal inline fun <T> withinContract(label: String, input: () -> String, block: () -> T): T? = try {
    block()
} catch (e: Throwable) {
    if (e is IOException || e is PlaylistLoaderException || e is XtreamException) {
        null
    } else {
        throw AssertionError("$label: ${e::class.qualifiedName}: ${e.message?.take(300)}\ninput: ${input()}", e)
    }
}

/** Runs `block` and fails when it throws anything at all (helpers the app calls on its main thread). */
internal inline fun <T> neverThrows(label: String, input: () -> String, block: () -> T): T = try {
    block()
} catch (e: Throwable) {
    throw AssertionError("$label: ${e::class.qualifiedName}: ${e.message?.take(300)}\ninput: ${input()}", e)
}

/** Printable prefix of `bytes`: ASCII as is, everything else as `\xNN`, with the total size. */
internal fun describe(bytes: ByteArray, max: Int = 240): String {
    val text = StringBuilder()
    for (i in 0 until minOf(bytes.size, max)) {
        val b = bytes[i].toInt() and 0xFF
        when {
            b == '\\'.code -> text.append("\\\\")
            b in 0x20..0x7E -> text.append(b.toChar())
            b == 0x0A -> text.append("\\n")
            b == 0x0D -> text.append("\\r")
            else -> text.append("\\x").append(String.format("%02X", b))
        }
    }
    if (bytes.size > max) text.append("… (${bytes.size} bytes)")
    return text.toString()
}

/** Printable prefix of a string: non-ASCII characters as `\uXXXX`. */
internal fun describe(text: String, max: Int = 240): String {
    val out = StringBuilder()
    for (c in text.take(max)) {
        if (c.code in 0x20..0x7E) out.append(c) else out.append(String.format("\\u%04X", c.code))
    }
    if (text.length > max) out.append("… (${text.length} chars)")
    return out.toString()
}

internal fun gzip(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { it.write(bytes) }
    return out.toByteArray()
}

/** Seeded generators of awkward provider data. */
internal class Fuzz(seed: Long) {
    val random = Random(seed)

    fun int(bound: Int) = random.nextInt(bound)

    fun chance(percent: Int) = random.nextInt(100) < percent

    fun <T> pick(values: List<T>): T = values[random.nextInt(values.size)]

    fun <T> pick(vararg values: T): T = values[random.nextInt(values.size)]

    /** Text mixing title words, separators, numbering, unicode oddities and occasional long runs. */
    fun text(maxParts: Int = 12): String {
        val out = StringBuilder()
        repeat(1 + int(maxParts)) {
            when (int(10)) {
                0 -> out.append(pick(SEPARATORS).repeat(1 + int(if (chance(10)) 3000 else 8)))
                1 -> out.append(pick(UNICODE))
                2 -> out.append(randomChars(1 + int(6)))
                3 -> out.append(pick(NUMBERING))
                else -> out.append(pick(WORDS))
            }
        }
        return out.toString()
    }

    /** Any UTF-16 code units, lone surrogates and controls included. */
    fun randomChars(count: Int): String {
        val chars = CharArray(count) {
            when (int(4)) {
                0 -> (int(0x80)).toChar()
                1 -> (0xD800 + int(0x800)).toChar()
                else -> int(0x10000).toChar()
            }
        }
        return String(chars)
    }

    fun bytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }

    /** Byte-level damage: flips, insertions of awkward bytes, deletions and truncation. */
    fun mutate(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(input)
        var data = out.toByteArray()
        repeat(1 + int(4)) {
            if (data.isEmpty()) return@repeat
            val at = int(data.size)
            data = when (int(5)) {
                0 -> data.copyOf().also { it[at] = random.nextInt(256).toByte() }
                1 -> data.copyOfRange(0, at) + pick(AWKWARD_BYTES) + data.copyOfRange(at, data.size)
                2 -> data.copyOfRange(0, at) + data.copyOfRange(minOf(data.size, at + 1 + int(16)), data.size)
                3 -> data.copyOfRange(0, at)
                else -> data.copyOfRange(0, at) + data.copyOfRange(at, minOf(data.size, at + 1 + int(64))) + data.copyOfRange(at, data.size)
            }
        }
        return data
    }

    companion object {
        val SEPARATORS = listOf(" ", ".", "-", "_", "|", ":", "\t", " - ", "–", "(", "[", ",", "\"", "'", "=")
        val WORDS = listOf(
            "News", "Sport", "Movie", "Series", "Show", "Film", "Season", "Saison", "Temporada", "Staffel", "Episode",
            "Épisode", "Folge", "Ep.", "HD", "FHD", "4K", "FR:", "UK |", "vod", "live", "xxx", "adult", "18+", "(2010)",
            "[1999]", "2024", "1900", "Café", "Straße", "Télé", "TV1", "S", "E", "x", "season", "Capitulo",
        )
        val NUMBERING = listOf(
            "S01E02", "S1 E2", "s9e9999", "S99E99999", "1x02", "99x999", "Season 2 Episode 3", "Saison 1 Épisode 12",
            "S01", "E01", "S1.E2", "S01E02E03", "S2147483647E2147483647", "1x", "x1", "S00E00", "Temporada 3 Capitulo 4",
        )
        val UNICODE = listOf(
            "📺", "👨\u200D👩\u200D👧", "\u202E", "\u200F", "قناة", "चैनल", "频道", "e\u0301", "ß", "℡", "ﷺ", "\uD800", "\uDC00",
            "\u0000", "\uFEFF", "\u2028", "\u0085", "\u000B", "İ", "ﬃ", "Ⅻ", "①", "\uFFFD", "\u0301\u0301\u0301",
        )
        val AWKWARD_BYTES: List<ByteArray> = listOf(
            byteArrayOf(0), byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), byteArrayOf(0xC0.toByte(), 0x80.toByte()),
            byteArrayOf(0xFF.toByte()), byteArrayOf(0x80.toByte()), byteArrayOf(0xE2.toByte(), 0x82.toByte()),
            byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x93.toByte(), 0xBA.toByte()), byteArrayOf(0x0D), byteArrayOf(0x0A),
            byteArrayOf(0x0D, 0x0A), "\"".toByteArray(), "'".toByteArray(), ",".toByteArray(), "<".toByteArray(),
            "&".toByteArray(), "]]>".toByteArray(), "{".toByteArray(), "}".toByteArray(), "[".toByteArray(),
        )
    }
}
