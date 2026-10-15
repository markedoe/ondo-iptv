package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.Fixtures
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.MediaCategory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CategoryStylesTest {
    /** Same cases as the Swift core (`category_styles.json` in both test suites). */
    @Test
    fun stylesAndWordsOfCategoryNames() {
        val fixture = Json.parseToJsonElement(Fixtures.bytes("category_styles.json").decodeToString()).jsonObject
        val styles = fixture.getValue("styles").jsonObject
        assertTrue(styles.size > 15)
        for ((name, expected) in styles) {
            assertEquals(expected.jsonArray.map { it.jsonPrimitive.content }, CategoryStyles.order.filter { CategoryStyles.matches(name, it) }, name)
        }
        val words = fixture.getValue("words").jsonArray
        assertTrue(words.size > 5)
        for (item in words.map { it.jsonObject }) {
            val name = item.getValue("name").jsonPrimitive.content
            val rule = CategoryStyles.ruleForWord(item.getValue("word").jsonPrimitive.content)!!
            assertEquals(item.getValue("matches").jsonPrimitive.boolean, CategoryStyles.matches(name, rule), "$name / $rule")
        }
        assertNull(CategoryStyles.ruleForWord(" - "))
    }

    @Test
    fun stylesFoundInAPlaylist() {
        val categories = listOf(
            MediaCategory("1", "|FR| Manga", ContentType.SERIES),
            MediaCategory("2", "Anime VOSTFR", ContentType.MOVIE),
            MediaCategory("3", "Documentaires", ContentType.MOVIE),
            MediaCategory("4", "Drama", ContentType.MOVIE),
        )
        val found = CategoryStyles.detect(categories)
        assertEquals(setOf("manga", "documentary"), found.keys)
        assertEquals(listOf("1", "2"), found.getValue("manga").map { it.id })
        assertEquals("manga", CategoryStyles.ruleHiding(CategoryStyles.tokens("|FR| Manga"), listOf("sport", "manga")))
        assertNull(CategoryStyles.ruleHiding(CategoryStyles.tokens("Drama"), listOf("sport", "manga")))
    }
}
