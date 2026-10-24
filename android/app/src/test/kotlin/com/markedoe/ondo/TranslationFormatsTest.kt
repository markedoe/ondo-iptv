package com.markedoe.ondo

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A translation whose placeholders differ from the English text (another type, a second argument, a stray %, `% s`,
 * an Arabic or Hindi digit typed inside `%1$s`) makes getString(id, args) throw, in that language only. Every text and
 * plural with placeholders is formatted in each language of the app with arguments of the types the English text
 * asks for, and must keep the English placeholders.
 */
@RunWith(AndroidJUnit4::class)
class TranslationFormatsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val languages = listOf("en", "fr", "es", "pt", "zh-CN", "ar", "hi")

    /** One count in each plural category of these languages: zero, one, two, few (ar 3), many (ar 11, fr/es/pt 1e6). */
    private val quantities = listOf(0, 1, 2, 3, 11, 100, 1_000_000)

    private val placeholder = Regex("""%(\d+\$)?[-#+ 0,(<]*\d*(\.\d+)?([tT])?([a-zA-Z%])""")

    private data class Placeholder(val position: Int, val conversion: Char)

    private fun placeholders(text: String): Set<Placeholder> {
        var next = 0
        return placeholder.findAll(text).mapNotNull { match ->
            val conversion = match.groupValues[4].single()
            if (conversion == '%' || conversion == 'n') return@mapNotNull null
            val position = match.groupValues[1].removeSuffix("$").toIntOrNull() ?: ++next
            Placeholder(position, conversion.lowercaseChar())
        }.toSet()
    }

    private fun arguments(english: Set<Placeholder>): Array<Any> {
        val count = english.maxOfOrNull { it.position } ?: 0
        return Array(count) { index ->
            when (english.firstOrNull { it.position == index + 1 }?.conversion) {
                'd', 'o', 'x' -> 3
                'f', 'e', 'g', 'a' -> 1.5
                'c' -> 'x'
                else -> "x"
            }
        }
    }

    private fun localized(tag: String): Context =
        context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })

    private fun ids(type: Class<*>): Map<String, Int> = type.fields.associate { it.name to it.getInt(null) }

    @Test
    fun everyTranslationFormatsWithTheEnglishArguments() {
        val english = localized("en").resources
        val problems = mutableListOf<String>()
        for (tag in languages) {
            val resources = localized(tag).resources
            for ((name, id) in ids(R.string::class.java)) {
                val expected = placeholders(english.getString(id))
                val text = resources.getString(id)
                if (placeholders(text) != expected) problems += "$tag $name: placeholders ${placeholders(text)}, English $expected: $text"
                // A text without placeholders is read with getString(id), which does not format it.
                if (expected.isEmpty()) continue
                runCatching { resources.getString(id, *arguments(expected)) }
                    .onFailure { problems += "$tag $name: ${it.javaClass.simpleName} ${it.message}: $text" }
            }
            for ((name, id) in ids(R.plurals::class.java)) {
                val expected = quantities.flatMap { placeholders(english.getQuantityText(id, it).toString()) }.toSet()
                for (quantity in quantities) {
                    val text = resources.getQuantityText(id, quantity).toString()
                    // A form may leave the number out (Arabic "one minute", "two minutes"), never add another one.
                    if (!expected.containsAll(placeholders(text))) problems += "$tag $name ($quantity): placeholders ${placeholders(text)}, English $expected: $text"
                    runCatching { resources.getQuantityString(id, quantity, *arguments(expected)) }
                        .onFailure { problems += "$tag $name ($quantity): ${it.javaClass.simpleName} ${it.message}: $text" }
                }
            }
        }
        assertTrue(problems.distinct().joinToString("\n", "Translations that cannot be formatted:\n"), problems.isEmpty())
    }

    @Test
    fun eachLanguageHasItsOwnTexts() {
        // The check above is only worth something if each language really loads its own file.
        val settings = languages.map { localized(it).resources.getString(R.string.settings) }
        assertTrue("$settings", settings.distinct().size == languages.size)
    }
}
