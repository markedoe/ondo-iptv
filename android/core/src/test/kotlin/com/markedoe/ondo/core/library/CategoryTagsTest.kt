package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.Fixtures
import com.markedoe.ondo.core.HangGuard
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.MediaCategory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule

class CategoryTagsTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    /** Same cases as the Swift core (`category_tags.json` in both test suites). */
    @Test
    fun tagsOfCategoryNames() {
        val cases = Json.parseToJsonElement(Fixtures.bytes("category_tags.json").decodeToString()).jsonObject
        assertTrue(cases.size > 20)
        for ((name, expected) in cases) {
            val tag = if (expected is JsonNull) null else expected.jsonPrimitive.content
            assertEquals(tag, CategoryTags.tagOf(name), name)
        }
    }

    @Test
    fun aTagNeedsTwoCategories() {
        val categories = listOf(
            MediaCategory("1", "|PT| Filmes", ContentType.MOVIE),
            MediaCategory("2", "|PT| Séries", ContentType.SERIES),
            MediaCategory("3", "FR: Films", ContentType.MOVIE),
            MediaCategory("4", "Drama", ContentType.MOVIE),
        )
        val found = CategoryTags.detect(categories)
        assertEquals(setOf("PT"), found.keys)
        assertEquals(listOf("1", "2"), found.getValue("PT").map { it.id })
    }

    /**
     * A title's own tag: only a delimited code at the start of its name ("|PT| …", "[PT] …", "PT - …", "PT| …", a flag),
     * never a word that only starts with the letters ("Portugal: The Man", "PTSD"). Same cases as the Swift core.
     */
    @Test
    fun aTitlesOwnTag() {
        for (name in listOf("|PT| Tony Carreira – Ao Vivo", "[PT] Tony Carreira", "PT - Tony Carreira", "PT| Tony Carreira", "PT: Tony Carreira", "\uD83C\uDDF5\uD83C\uDDF9 Tony Carreira", "---|PT| Tony Carreira")) {
            assertEquals("PT", CategoryTags.titleTag(name), name)
            assertTrue(CategoryTags.isHiddenByName(name, setOf("PT")), name)
        }
        for (name in listOf("Portugal: The Man – Live", "PTSD Stories", "PTSD: A Life", "Pt - lower case", "Tony Carreira", "Partners - In Crime")) {
            assertTrue(!CategoryTags.isHiddenByName(name, setOf("PT")), name)
        }
        assertTrue(!CategoryTags.isHiddenByName("|PT| Tony Carreira", emptySet()), "no language hidden")
        assertTrue(!CategoryTags.isHiddenByName("|FR| Le Transport", setOf("PT")), "another language")
    }
}
