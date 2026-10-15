package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.TextNormalizer
import com.markedoe.ondo.core.model.MediaCategory

/**
 * Styles that providers name categories after ("Manga", "Kids", "Documentaries"), in several languages, so that a
 * viewer can hide a whole style at once, in every playlist, those the provider adds later included; and any other
 * word the viewer chooses ("Bollywood"). Only category names are looked at: a movie's own genre ("Animation") would
 * hide far more than meant. Same rules as the Swift `CategoryStyles`.
 */
object CategoryStyles {
    /** Style -> the words that name it, normalised ([TextNormalizer]): lower case, without accents. */
    val words: Map<String, List<String>> = mapOf(
        "manga" to listOf("manga", "mangas", "anime", "animes"),
        "kids" to listOf(
            "kids", "kid", "children", "enfants", "enfant", "jeunesse", "infantil", "infantiles", "ninos", "criancas",
            "kinder", "bambini", "cartoon", "cartoons", "dessins animes", "dessin anime",
        ),
        "animation" to listOf("animation", "animations", "animacion", "animacao", "animazione", "animatie"),
        "documentary" to listOf(
            "documentary", "documentaries", "documentaire", "documentaires", "documental", "documentales",
            "documentario", "documentarios", "documentari", "doku", "dokus", "dokumentation",
        ),
        "sport" to listOf("sport", "sports", "deportes", "deporte", "esporte", "esportes", "sportif", "sportifs"),
        "horror" to listOf("horror", "horreur", "terror", "terreur", "epouvante"),
        "music" to listOf("music", "musique", "musica", "musicas", "musik", "concert", "concerts"),
    )

    /** The order the styles are offered in. */
    val order = listOf("manga", "kids", "animation", "documentary", "sport", "horror", "music")

    /** Phrases holding a style's word without being that style: French "dessins animés" are cartoons, not anime. */
    private val exceptions = mapOf("manga" to listOf("dessins animes", "dessin anime"))

    /** Whether a category name is hidden by this rule: a style ("manga") or a word chosen by the viewer. */
    fun matches(name: String, rule: String): Boolean = matches(tokens(name), rule)

    /** The first of these rules (in alphabetical order) that hides a category of these words, if any. */
    fun ruleHiding(words: List<String>, rules: Collection<String>): String? = rules.sorted().firstOrNull { matches(words, it) }

    /** The styles found in these category names, and the categories of each. */
    fun detect(categories: List<MediaCategory>): Map<String, List<MediaCategory>> {
        val found = linkedMapOf<String, MutableList<MediaCategory>>()
        for (category in categories) {
            val words = tokens(category.name)
            for (style in order) if (matches(words, style)) found.getOrPut(style) { mutableListOf() } += category
        }
        return found
    }

    /** A word typed by the viewer as a rule ("  Bollywood " -> "bollywood"), or null when it holds no letter or digit. */
    fun ruleForWord(word: String): String? = TextNormalizer.normalize(word).ifEmpty { null }

    /** The normalised words of a category name. */
    fun tokens(name: String): List<String> = TextNormalizer.normalize(name).split(' ').filter { it.isNotEmpty() }

    fun matches(tokens: List<String>, rule: String): Boolean {
        var remaining = tokens
        for (exception in exceptions[rule].orEmpty()) remaining = removing(exception.split(' '), remaining)
        // A style's words, or the viewer's word and its plural.
        val phrases = words[rule] ?: TextNormalizer.normalize(rule).let { if (it.isEmpty()) emptyList() else listOf(it, it + "s") }
        return phrases.any { contains(remaining, it.split(' ')) }
    }

    private fun contains(words: List<String>, phrase: List<String>): Boolean {
        if (phrase.isEmpty() || words.size < phrase.size) return false
        return (0..words.size - phrase.size).any { start -> phrase.indices.all { words[start + it] == phrase[it] } }
    }

    private fun removing(phrase: List<String>, words: List<String>): List<String> {
        val result = mutableListOf<String>()
        var index = 0
        while (index < words.size) {
            if (index + phrase.size <= words.size && phrase.indices.all { words[index + it] == phrase[it] }) {
                index += phrase.size
            } else {
                result += words[index]
                index++
            }
        }
        return result
    }
}
