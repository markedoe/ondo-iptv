package com.markedoe.ondo.core.fuzz

import com.markedoe.ondo.core.MockHttpClient
import com.markedoe.ondo.core.library.PlaylistLoader
import com.markedoe.ondo.core.model.XtreamCredentials
import com.markedoe.ondo.core.net.Gzip
import com.markedoe.ondo.core.net.GzipException
import com.markedoe.ondo.core.net.GzipTooLargeException
import com.markedoe.ondo.core.xtream.XtreamClient
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Playlists and Xtream answers served gzip compressed are inflated in memory. A few hundred kilobytes can inflate to
 * hundreds of megabytes: past the limit the answer is refused, before the app runs out of memory.
 */
class GzipFuzzTest {
    /** `megabytes` of spaces after `prefix`, gzip compressed in `members` concatenated members. */
    private fun bomb(prefix: String, megabytes: Int, members: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        val block = ByteArray(1024 * 1024) { ' '.code.toByte() }
        repeat(members) { member ->
            GZIPOutputStream(out).let { gz ->
                if (member == 0) gz.write(prefix.encodeToByteArray())
                repeat(megabytes / members) { gz.write(block) }
                gz.finish()
            }
        }
        return out.toByteArray()
    }

    /** About 330 KB that inflate to 330 MB (the limit is 320 MB): the test JVM has 512 MB. */
    @Test(timeout = 120_000)
    fun aSmallGzipBombIsRefusedWithoutRunningOutOfMemory() {
        val playlist = bomb("#EXTM3U\n", megabytes = 330)
        assert(playlist.size < 400_000) { "${playlist.size} bytes" }
        assertFailsWith<GzipTooLargeException> { Gzip.gunzip(playlist) }
        assertFailsWith<GzipTooLargeException> { PlaylistLoader(MockHttpClient()).parse(playlist) }
        // Concatenated members: the size in the gzip trailer covers only the last one.
        assertFailsWith<GzipTooLargeException> { Gzip.gunzip(bomb("#EXTM3U\n", megabytes = 330, members = 33)) }

        // The same answer from an Xtream panel.
        val http = MockHttpClient()
        http.routes["api:"] = bomb("{\"user_info\":{\"auth\":1}}", megabytes = 330)
        val client = XtreamClient(XtreamCredentials("http://example.com", "user", "pass"), http, parsingDispatcher = Dispatchers.Unconfined) // leak-guard: allow
        assertFailsWith<GzipTooLargeException> { runBlocking { client.authenticate() } }
    }

    /** Inflating keeps its behaviour: exact output, partial output of a truncated stream, nothing inflatable fails. */
    @Test(timeout = 60_000)
    fun inflatedDataIsUnchanged() {
        val fuzz = Fuzz(seed = 19)
        repeat(300) { case ->
            val data = fuzz.bytes(fuzz.int(200_000))
            val packed = gzip(data)
            assertContentEquals(data, Gzip.gunzip(packed), "case $case")
            val cut = packed.copyOf(fuzz.int(packed.size))
            withinContract("truncated case $case", { describe(cut) }) {
                val partial = Gzip.gunzip(cut)
                assertContentEquals(data.copyOf(partial.size), partial, "truncated case $case")
            }
            val damaged = fuzz.mutate(packed)
            withinContract("damaged case $case", { describe(damaged) }) { Gzip.gunzip(damaged) }
        }
        assertFailsWith<GzipException> { Gzip.gunzip(byteArrayOf(0x1f, 0x8b.toByte(), 0x08)) }
    }
}
