package com.markedoe.ondo

import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.markedoe.ondo.core.library.FilmVersions
import com.markedoe.ondo.core.library.VersionTag
import com.markedoe.ondo.core.model.Movie
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A film version's chip in each of the app's seven languages: the abbreviations spelled out in the viewer's words, and
 * two versions never read the same (a word of one tag never another's). Apple's `VersionWordsAppTests` does the same.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = OndoApplication::class)
class VersionWordsAppTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val app: OndoApplication = ApplicationProvider.getApplicationContext()

    private fun words(tag: String) =
        app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }).resources.versionWords()

    private fun labels(names: List<String>, tag: String): List<String> {
        val movies = names.mapIndexed { index, name -> Movie(id = "v$index", name = name, categoryId = "c", streamUrl = "http://stream.example.com/$index") }
        return FilmVersions.labels(movies, { null }, words(tag))
    }

    private val languages = listOf("en", "fr", "es", "pt", "zh-CN", "ar", "hi")

    @Test
    fun everyTagReadsDifferentlyInEveryLanguage() {
        for (tag in languages) {
            val words = words(tag)
            val all = VersionTag.entries.map(words::word) + words.standard + words.number(1) + words.number(2)
            assertEquals("$tag: $all", all.size, all.distinct().size)
            assertTrue(tag, all.none { it.isBlank() })
            if (tag != "en") assertTrue("$tag is translated", words.frenchSubtitles != "Original, French subtitles" && words.standard.isNotEmpty())
        }
    }

    @Test
    fun theReportedFilmReadsInEachLanguage() {
        val expected = mapOf(
            "en" to listOf("Standard", "4K Dolby Vision", "Original, French subtitles"),
            "fr" to listOf("Standard", "4K Dolby Vision", "VO, sous-titres français"),
            "es" to listOf("Estándar", "4K Dolby Vision", "VO, subtítulos en francés"),
            "pt" to listOf("Padrão", "4K Dolby Vision", "Original, legendas em francês"),
            "zh-CN" to listOf("标准", "4K Dolby Vision", "原声，法语字幕"),
            "ar" to listOf("قياسية", "4K Dolby Vision", "الأصلية، ترجمة فرنسية"),
            "hi" to listOf("स्टैंडर्ड", "4K Dolby Vision", "मूल, फ़्रेंच सबटाइटल"),
        )
        for ((tag, labels) in expected) {
            assertEquals(tag, labels, labels(listOf("Copper Valley (2026)", "Copper Valley 4K DV (2026)", "Copper Valley VOSTFR (2026)"), tag))
        }
        assertEquals(listOf("Version 1", "Version 2"), labels(listOf("Copper Valley", "Copper Valley"), "fr"))
        assertEquals(listOf("Versión 1", "Versión 2"), labels(listOf("Copper Valley", "Copper Valley"), "es"))
    }

    /** Whatever a film's versions are, two never read the same, in any language. */
    @Test
    fun twoVersionsNeverReadTheSameInAnyLanguage() {
        val pool = listOf(
            "Copper Valley", "Copper Valley 4K", "Copper Valley 4K", "Copper Valley 4K DV", "Copper Valley 1080p", "Copper Valley SD",
            "Copper Valley VOSTFR", "Copper Valley VOST", "Copper Valley VO", "FR - Copper Valley", "EN - Copper Valley",
            "Copper Valley MULTI VFF", "Copper Valley VFQ", "Copper Valley VFI", "Copper Valley Standard", "Copper Valley 3D",
        )
        for (tag in languages) {
            for (a in pool) for (b in pool) for (c in pool) {
                val labels = labels(listOf(a, b, c), tag)
                assertEquals("$tag: $a, $b, $c -> $labels", labels.size, labels.distinct().size)
            }
        }
    }
}
