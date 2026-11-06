package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.HangGuard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The language of a provider's description and the tags at the end of a title's name, for hidden languages
 * (`fixtures/plot-language`, the same files and cases as the Swift `PlotLanguageTests`).
 */
class PlotLanguageTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val cases: JsonObject = Json.parseToJsonElement(File("../../fixtures/plot-language/cases.json").readText()).jsonObject

    @Test
    fun theTableIsTheSharedOne() {
        assertEquals(File("../../fixtures/plot-language/table.txt").readText().trim(), PlotLanguage.TABLE.trim())
        // Short lines (the linters' limits): a language with many words goes on over several lines.
        assertTrue(PlotLanguage.TABLE.lines().all { it.length <= 150 })
    }

    /** Each word of the table is one the scan reads (folded, short enough), and belongs to one language only. */
    @Test
    fun everyWordOfTheTableIsReadByTheScan() {
        val owners = HashMap<Long, String>()
        for (line in PlotLanguage.TABLE.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            val parts = line.split('|')
            if (parts[1] != "words") continue
            for (word in parts.drop(2)) {
                val key = PlotLanguage.key(word)
                assertTrue("the scan cannot read $word", key > 0)
                assertEquals("$word is lower case", word, word.lowercase())
                val other = owners.put(key, parts[0])
                assertTrue("$word belongs to $other and ${parts[0]}", other == null)
            }
        }
        assertEquals(listOf("es", "pt", "fr", "en", "it", "de", "nl", "tr", "pl", "ar", "hi", "zh"), PlotLanguage.languages)
    }

    @Test
    fun theLanguageOfEachDescriptionOfTheCases() {
        val plots = cases.getValue("plots").jsonArray
        assertTrue(plots.size > 20)
        for (case in plots) {
            val text = case.jsonObject.getValue("text").jsonPrimitive.content
            val expected = case.jsonObject.getValue("language").let { if (it is JsonNull) null else it.jsonPrimitive.content }
            assertEquals(text, expected, PlotLanguage.detect(text))
        }
    }

    /** Spanish and Portuguese share many words: each is told by its own ones, in either case. */
    @Test
    fun spanishAndPortugueseAreToldApart() {
        assertEquals("es", PlotLanguage.detect("Cuando su hermano desaparece, Marta deja la ciudad y vuelve al pueblo donde nadie quiere hablar de lo que pasó hace años."))
        assertEquals("pt", PlotLanguage.detect("Quando o irmão desaparece, Marta deixa a cidade e volta para a vila onde ninguém quer falar sobre o que aconteceu há anos. Mas ela não desiste."))
        assertEquals("es", PlotLanguage.detect("CUANDO SU HERMANO DESAPARECE, MARTA DEJA LA CIUDAD Y VUELVE AL PUEBLO DONDE NADIE QUIERE HABLAR DE LO QUE PASÓ HACE AÑOS."))
    }

    @Test
    fun aShortOrUnclearDescriptionHasNoLanguage() {
        assertNull(PlotLanguage.detect(null))
        assertNull(PlotLanguage.detect(""))
        assertNull("too short", PlotLanguage.detect("El amor y su sombra."))
        // As many words of two languages: neither.
        assertNull("mixed", PlotLanguage.detect("El hombre and the mujer, los amigos with the casa del pueblo, ella and his hijo."))
    }

    /** Only the start of a description is read: what follows the first [PlotLanguage.SCANNED] characters counts for nothing. */
    @Test
    fun onlyTheStartOfADescriptionIsRead() {
        val filler = "x".repeat(PlotLanguage.SCANNED)
        assertNull(PlotLanguage.detect("$filler Un joven abogado descubre que su padre esconde un secreto cuando regresa a su pueblo."))
        assertEquals("es", PlotLanguage.detect("Un joven abogado descubre que su padre esconde un secreto cuando regresa a su pueblo. $filler"))
    }

    @Test
    fun theLanguagesOfHiddenTags() {
        for (case in cases.getValue("languages").jsonArray) {
            val hidden = case.jsonObject.getValue("hidden").jsonArray.map { it.jsonPrimitive.content }.toSet()
            val expected = case.jsonObject.getValue("languages").jsonArray.map { it.jsonPrimitive.content }.toSet()
            assertEquals(hidden.toString(), expected, PlotLanguage.hiddenLanguages(hidden))
        }
        assertTrue(PlotLanguage.isLanguageTag("LAT"))
        assertFalse("French subtitles are no French audio", PlotLanguage.isLanguageTag("VOSTFR"))
        assertFalse(PlotLanguage.isLanguageTag("HBO"))
    }

    /** Same cases as the Swift core: a tag at the very end of a title's name, years and quality words after it left out. */
    @Test
    fun theTagAtTheEndOfATitlesName() {
        val trailing = cases.getValue("trailing").jsonArray
        assertTrue(trailing.size > 20)
        for (case in trailing) {
            val name = case.jsonObject.getValue("name").jsonPrimitive.content
            val expected = case.jsonObject.getValue("tag").let { if (it is JsonNull) null else it.jsonPrimitive.content }
            assertEquals(name, expected, CategoryTags.trailingTag(name))
        }
        assertTrue(CategoryTags.isHiddenByName("La Casa (ES)", setOf("ES")))
        assertTrue("its start still counts", CategoryTags.isHiddenByName("|ES| La Casa", setOf("ES")))
        assertFalse("only the hidden tag", CategoryTags.isHiddenByName("The Office (UK)", setOf("ES")))
        assertFalse("nothing hidden", CategoryTags.isHiddenByName("La Casa (ES)", emptySet()))
    }

    /**
     * Regression guard for a hidden language's titles filed in categories of no language: a series of "Netflix"
     * described in Spanish, or named "… (ES)", is hidden with Spanish; a category tagged with a language the viewer kept
     * keeps its titles whatever their description; nothing is hidden while no hidden tag stands for a language.
     */
    @Test
    fun aTitleIsHiddenByItsNameOrByItsDescription() {
        val spanish = "Un joven abogado descubre que su padre esconde un secreto que pondrá en peligro a toda la familia cuando regresa a su pueblo."
        val english = "A young lawyer discovers that his father is hiding a secret that will put the whole family in danger when he returns home."
        val es = setOf("ES")
        // Described in Spanish, in a category of no tag ("Netflix") or of a tag of no language ("HBO | Series").
        assertTrue(CategoryTags.isHiddenTitle("La Casa", spanish, null, es))
        assertTrue(CategoryTags.isHiddenTitle("La Casa", spanish, CategoryTags.tagOf("HBO | Series"), es))
        // Another tag of the language hidden ("LAT"): the same language.
        assertTrue(CategoryTags.isHiddenTitle("La Casa", spanish, null, setOf("LAT")))
        // Named with it at the end.
        assertTrue(CategoryTags.isHiddenTitle("La Casa (ES)", english, null, es))
        // A category of a language the viewer kept wins.
        assertFalse(CategoryTags.isHiddenTitle("La Casa", spanish, CategoryTags.tagOf("|FR| Séries"), es))
        // Nothing hidden, or no tag of a language: nothing new disappears.
        assertFalse(CategoryTags.isHiddenTitle("La Casa", spanish, null, emptySet()))
        assertFalse(CategoryTags.isHiddenTitle("La Casa", spanish, null, setOf("VIP")))
        assertFalse(CategoryTags.isHiddenTitle("La Casa", english, null, es))
        assertFalse("no description", CategoryTags.isHiddenTitle("La Casa", null, null, es))
    }
}
