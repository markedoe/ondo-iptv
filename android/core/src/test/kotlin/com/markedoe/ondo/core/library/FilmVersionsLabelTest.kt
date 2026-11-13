package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.HangGuard
import com.markedoe.ondo.core.model.Movie
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule

/**
 * What a film's versions say on their chips: the table written by hand in `fixtures/film-versions/labels.json`, the same
 * file Apple's `FilmVersionsLabelTests` reads, so both apps name versions alike. Invented titles only.
 */
class FilmVersionsLabelTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val cases = Json.parseToJsonElement(File("../../fixtures/film-versions/labels.json").readText()).jsonObject["cases"]!!.jsonArray

    private fun versions(names: List<String>, categories: List<String?> = names.map { null }): Pair<List<Movie>, (String) -> String?> {
        val movies = names.mapIndexed { index, name -> Movie(id = "v$index", name = name, categoryId = "c$index", streamUrl = "http://stream.example.com/$index") }
        val byId = movies.indices.associate { "c$it" to categories[it] }
        return movies to { id: String -> byId[id] }
    }

    @Test
    fun everyCaseReadsAsWritten() {
        assertTrue(cases.isNotEmpty())
        for (case in cases.map { it.jsonObject }) {
            val name = case["name"]!!.jsonPrimitive.content
            val rows = case["rows"]!!.jsonArray.map { it.jsonObject }
            val (movies, categoryName) = versions(rows.map { it["name"]!!.jsonPrimitive.content }, rows.map { it["category"]?.jsonPrimitive?.content })
            val expected = case["labels"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(expected, FilmVersions.labels(movies, categoryName), name)
        }
    }

    /** Whatever a film's versions are, two never read the same, and none is empty. */
    @Test
    fun twoVersionsNeverReadTheSame() {
        val pool = listOf(
            "Copper Valley", "Copper Valley", "Copper Valley 4K", "Copper Valley 4K", "Copper Valley 4K DV", "Copper Valley HD",
            "Copper Valley VOSTFR", "FR - Copper Valley", "EN - Copper Valley", "Copper Valley MULTI", "Copper Valley Standard",
            "Copper Valley 4K 2", "Copper Valley Version 1", "Copper Valley Extended", "Copper Valley 3D",
        )
        for (a in pool) for (b in pool) for (c in pool) {
            for (names in listOf(listOf(a, b), listOf(a, b, c))) {
                val (movies, categoryName) = versions(names)
                val labels = FilmVersions.labels(movies, categoryName)
                assertEquals(labels.size, labels.distinct().size, "$names -> $labels")
                assertTrue(labels.none { it.isBlank() }, "$names -> $labels")
            }
        }
    }

    /**
     * Every word the grouping reads as a provider's tag (`CatalogueMatcher`'s version and quality words) names its version
     * next to a plain one, but those that say nothing of the copy ("DUB": dubbed into what?) or name a language the apps
     * have no word for.
     */
    @Test
    fun everyTagWordOfTheGroupingNamesItsVersion() {
        val unnamed = setOf("dub", "dubbed", "sub", "tr", "nl", "pl")
        val words = listOf(
            "vf", "vff", "vfq", "vfi", "vo", "vost", "vostfr", "vosta", "stfr", "subfr", "frsub", "truefrench", "french", "english",
            "subbed", "dubbed", "dub", "sub", "dual", "en", "eng", "fr", "es", "esp", "de", "ger", "ita", "pt", "br", "ar", "tr", "nl", "pl",
            "multi", "4k", "uhd", "fhd", "hd", "sd", "hdr", "dv", "hevc", "x265", "h265", "1080p", "720p", "2160p", "imax",
        )
        for (word in words) {
            val tag = word.uppercase()
            assertTrue(CatalogueMatcher.isTagWord(word), word)
            val (movies, categoryName) = versions(listOf("Copper Valley $tag", "Copper Valley"))
            val labels = FilmVersions.labels(movies, categoryName)
            if (word in unnamed) {
                assertEquals(listOf("Version 1", "Version 2"), labels, word)
            } else {
                assertNotEquals("Standard", labels[0], "$tag -> $labels")
                assertTrue(!labels[0].startsWith("Version"), "$tag -> $labels")
            }
        }
    }

    /** The chip's tags read in words: no "DV", no "VOSTFR" left. */
    @Test
    fun abbreviationsAreSpelledOut() {
        val (movies, categoryName) = versions(listOf("Copper Valley 4K DV", "Copper Valley VOSTFR", "Copper Valley MULTI VFF"))
        val labels = FilmVersions.labels(movies, categoryName)
        for (abbreviation in listOf("DV", "VOSTFR", "MULTI", "VFF")) assertTrue(labels.none { abbreviation in it.split(' ', '·') }, "$abbreviation in $labels")
    }
}
