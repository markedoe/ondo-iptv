package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.HangGuard
import com.markedoe.ondo.core.HtmlEntities
import com.markedoe.ondo.core.TextNormalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Genres in any language as one genre (`fixtures/genres`, the same files and cases as the Swift `GenresTests`): the
 * fields of the cases give their keys, the tags of an earlier version read as this version's, and a selection made on
 * one device matches the titles of another whatever the language of their genres.
 */
class GenresTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val cases: JsonObject = Json.parseToJsonElement(File("../../fixtures/genres/cases.json").readText()).jsonObject

    @Test
    fun theTableIsTheSharedOne() {
        assertEquals(File("../../fixtures/genres/table.txt").readText().trim(), Genres.TABLE.trim())
        // Short lines (the linters' limits): a genre with many names goes on over several lines.
        assertTrue(Genres.TABLE.lines().all { it.length <= 150 })
    }

    @Test
    fun eachCanonicalKeyIsItsEnglishNameNormalised() {
        // An earlier version keys a genre by its name normalised: an English name matches the same key there.
        val english = Genres.TABLE.trim().lines().map { it.substringBefore('|') }.distinct()
        assertEquals(english.map(TextNormalizer::normalize), Genres.canonical)
        assertEquals(25, Genres.canonical.size)
        assertTrue(Genres.isCanonical("science fiction"))
        assertFalse(Genres.isCanonical("aventure"))
    }

    @Test
    fun eachNameMeansOneGenre() {
        val seen = HashMap<String, String>()
        for (line in Genres.TABLE.trim().lines()) {
            val parts = line.split('|')
            for (name in parts.drop(1)) {
                val previous = seen.put(TextNormalizer.normalize(name), parts[0])
                assertTrue("$name in ${parts[0]} and $previous", previous == null || previous == parts[0])
            }
        }
    }

    @Test
    fun fieldsGiveTheirKeys() {
        for (case in cases["fields"]!!.jsonArray) {
            val field = case.jsonObject["field"]!!.jsonPrimitive.content
            val keys = case.jsonObject["keys"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(field, keys, Genres.keys(field))
        }
    }

    @Test
    fun tagsOfAnEarlierVersionReadAsThisOnes() {
        for (case in cases["tags"]!!.jsonArray) {
            val tag = case.jsonObject["tag"]!!.jsonPrimitive.content
            val canonical = case.jsonObject["canonical"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(tag, canonical, Genres.canonicalTags(tag))
        }
        assertEquals(listOf("genre:adventure", "genre:drama"), Genres.canonicalTags(listOf("genre:aventure", "genre:adventure", "genre:drame")))
    }

    @Test
    fun htmlReferencesAreDecoded() {
        for (case in cases["entities"]!!.jsonArray) {
            val input = case.jsonObject["in"]!!.jsonPrimitive.content
            assertEquals(input, case.jsonObject["out"]!!.jsonPrimitive.content, HtmlEntities.decode(input))
        }
    }

    /** The bug: "Action &amp; Adventure" was split on its `&`, giving the genres "Action" and "amp; Adventure". */
    @Test
    fun aGenreFieldIsDecodedBeforeItIsSplit() {
        assertEquals(listOf("Action", "Adventure", "Sci-Fi", "Fantasy"), Genres.split("Action &amp; Adventure, Sci-Fi &amp; Fantasy"))
        assertEquals(listOf("genre:war", "genre:politics"), Selections.genres("War &amp; Politics"))
    }

    /** The bug: the same genre in each language was its own tag ("Aventure", "Adventure", "Abenteuer"). */
    @Test
    fun aSelectionMatchesTitlesWhateverTheLanguageOfTheirGenres() {
        val french = Selections.Facts(genres = Genres.keys("Aventure, Drame").toSet())
        val german = Selections.Facts(genres = Genres.keys("Abenteuer").toSet())
        val english = Selections.Facts(genres = Genres.keys("Action & Adventure").toSet())
        val tag = Selections.genre("Adventure")!!
        for (facts in listOf(french, german, english)) assertTrue(Selections.matches(listOf(tag), all = false, facts = facts))
        // A tag an earlier version saved from a French title still matches, in both modes.
        assertTrue(Selections.matches(listOf("genre:aventure"), all = true, facts = german))
        assertTrue(Selections.matches(listOf("genre:drame", "genre:aventure"), all = true, facts = french))
        // A tag of two genres (an earlier version's, from TMDb's "Action & Adventure") needs both.
        assertTrue(Selections.matches(listOf("genre:action adventure"), all = false, facts = english))
        assertFalse(Selections.matches(listOf("genre:action adventure"), all = false, facts = german))
    }

    /**
     * A device of the earlier version keys its titles' genres by their names normalised: a tag this version saves
     * (`genre:adventure`) matches its English-named titles there, as before.
     */
    @Test
    fun aCanonicalTagMatchesEnglishNamesOnAnEarlierVersion() {
        fun earlierFacts(field: String) = field.split(',', '/', '|', '&').map { TextNormalizer.normalize(it) }.filter { it.isNotEmpty() }.toSet()
        for (key in Genres.canonical) {
            val english = Genres.TABLE.trim().lines().first { TextNormalizer.normalize(it.substringBefore('|')) == key }.substringBefore('|')
            assertTrue(key, key in earlierFacts(english))
        }
        assertTrue("adventure" in earlierFacts("Action & Adventure"))
        assertFalse("adventure" in earlierFacts("Aventure"))
    }
}
