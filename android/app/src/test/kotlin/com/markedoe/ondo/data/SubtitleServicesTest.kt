package com.markedoe.ondo.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.net.HttpClient
import com.markedoe.ondo.core.net.HttpException
import com.markedoe.ondo.core.subtitles.OnlineSubtitle
import com.markedoe.ondo.core.subtitles.SubtitleQuery
import com.markedoe.ondo.core.subtitles.SubtitleService
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A subtitle downloaded for a title is kept on the device: the title played again (resumed) finds it. */
@RunWith(AndroidJUnit4::class)
class SubtitleServicesTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val srt = "1\n00:00:01,000 --> 00:00:02,000\nHello\n"

    private val http = object : HttpClient {
        override suspend fun get(url: String, headers: Map<String, String>): ByteArray {
            if (!url.startsWith("https://dl.subdl.com/")) throw HttpException.BadStatus(404)
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("Copper.Valley.S03E03.srt"))
                zip.write(srt.toByteArray())
                zip.closeEntry()
            }
            return bytes.toByteArray()
        }
    }

    @Test
    fun aDownloadedSubtitleIsKeptForItsTitle() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(SubtitleServices.FILE, Context.MODE_PRIVATE).apply { edit().clear().commit() }
        prefs.edit().putString(SubtitleCredential.SUBDL_KEY.key, "example-subdl-key").commit()
        val folder = File(context.filesDir, "subtitles")
        val services = SubtitleServices(prefs, http, "Ondo test", folder, io = Dispatchers.Unconfined)
        val query = SubtitleQuery("Copper Valley", 2018, 3, 3, listOf("en"))
        val chosen = services.download(OnlineSubtitle(SubtitleService.SUBDL, "/subtitle/1-2.zip", "en", "Copper.Valley.S03E03"), query, "playlist|series|7|9001")
        assertEquals(srt, File(chosen.path).readText())
        assertEquals("srt", chosen.format)
        assertEquals("SubDL", chosen.label)
        assertTrue(File(chosen.path).parentFile == folder)
        // The title opened again: the same subtitle.
        assertEquals(chosen, SubtitleServices(prefs, http, "Ondo test", folder).chosen("playlist|series|7|9001"))
        assertNull(services.chosen("another title"))
        // Its file gone: nothing to load.
        File(chosen.path).delete()
        assertNull(services.chosen("playlist|series|7|9001"))
    }

    /** Subtitles downloaded for no title (named after the subtitle) are bounded too: the oldest go. */
    @Test
    fun subtitlesOfNoTitleAreBounded() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(SubtitleServices.FILE, Context.MODE_PRIVATE).apply { edit().clear().commit() }
        prefs.edit().putString(SubtitleCredential.SUBDL_KEY.key, "example-subdl-key").commit()
        val folder = File(context.filesDir, "bounded").apply { deleteRecursively() }
        val services = SubtitleServices(prefs, http, "Ondo test", folder, io = Dispatchers.Unconfined, maxKept = 2)
        val query = SubtitleQuery("Copper Valley", 2018, 3, 3, listOf("en"))
        val last = (1..4).map { services.download(OnlineSubtitle(SubtitleService.SUBDL, "/subtitle/1-$it.zip", "en", "r$it"), query, null) }.last()
        assertEquals(2, folder.listFiles()!!.size)
        assertTrue(File(last.path).isFile)
    }
}
