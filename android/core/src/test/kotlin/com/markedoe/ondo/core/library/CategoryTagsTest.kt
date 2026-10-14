package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.Fixtures
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.MediaCategory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CategoryTagsTest {
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
}
