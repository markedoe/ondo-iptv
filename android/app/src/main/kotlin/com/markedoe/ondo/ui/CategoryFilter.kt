package com.markedoe.ondo.ui

import com.markedoe.ondo.core.TextNormalizer
import com.markedoe.ondo.core.library.CategoryStyles
import com.markedoe.ondo.core.library.CategoryTags
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.data.OndoState
import java.util.Locale

/** A language or country tag of the catalogue's categories ("|PT| Filmes") in the categories screen. */
data class TagRow(val tag: String, val label: String, val count: Int, val hidden: Boolean)

/**
 * One category in the categories screen; [hiddenWith] is the tag ("PT"), style ("manga") or word hiding it, when one
 * of them is hidden.
 */
data class CategoryToggle(val id: String, val name: String, val count: Int, val hidden: Boolean, val hiddenWith: String?)

/** A style found in the catalogue's category names ("manga", see `CategoryStyles`), or hidden before. */
data class StyleRow(val style: String, val count: Int, val hidden: Boolean)

/** A word the viewer hid, and the categories of the catalogue holding it. */
data class WordRow(val word: String, val count: Int)

data class CategoriesUiState(
    val tags: List<TagRow> = emptyList(),
    val categories: List<CategoryToggle> = emptyList(),
    val styles: List<StyleRow> = emptyList(),
    val words: List<WordRow> = emptyList(),
    /** False while filtering: the tags, styles and words are not listed then. */
    val showsRules: Boolean = true,
)

/** "Show only your languages?": the viewer's language and country tags are kept, the others hidden. */
data class TagSuggestion(val kept: List<String>, val keptNames: List<String>, val hidden: List<String>)

/**
 * Hiding categories by language or country tag, a rule that also covers the categories the provider adds later.
 * Pure functions, unit tested; same rules as the Apple app.
 */
object CategoryFilter {
    /** Codes providers use for a language rather than for a country ("AR" is Arabic, not Argentina). */
    private val languageCodes = setOf(
        "EN", "FR", "ES", "PT", "DE", "IT", "NL", "AR", "TR", "PL", "RU", "HI", "ZH", "JA", "KO", "EL", "RO",
        "HU", "CS", "FA", "UR", "HE", "TH", "VI", "BN", "TA", "TE", "PA", "SQ", "SR", "HR", "BG",
    )

    /** Codes providers use for a country that is not its ISO code. */
    private val countryAliases = mapOf("UK" to "GB")

    /** Tags shared by several categories and the tag of each such category; styles found; words of each name. */
    class TagIndex(content: PlaylistContent) {
        private val all = content.liveCategories + content.movieCategories + content.seriesCategories
        val categories: Map<String, List<MediaCategory>> = CategoryTags.detect(all)
        val tagOf: Map<String, String> = categories.flatMap { (tag, list) -> list.map { it.id to tag } }.toMap()
        val styles: Map<String, List<MediaCategory>> = CategoryStyles.detect(all)
        val words: Map<String, List<String>> = all.associate { it.id to CategoryStyles.tokens(it.name) }

        /** Categories of the catalogue whose name holds this style or word. */
        fun count(rule: String): Int = words.values.count { CategoryStyles.matches(it, rule) }
    }

    @Volatile private var cached: Pair<PlaylistContent, TagIndex>? = null

    /** Built once per catalogue. */
    fun tags(content: PlaylistContent): TagIndex {
        cached?.let { (known, index) -> if (known === content) return index }
        return TagIndex(content).also { cached = content to it }
    }

    /** The language or country a tag stands for, in [locale]; null for other tags ("EX-YU", "VIP"). */
    fun name(tag: String, locale: Locale): String? {
        val name = when {
            tag in languageCodes -> Locale.forLanguageTag(tag.lowercase()).getDisplayLanguage(locale)
            tag.length == 2 -> Locale.forLanguageTag("und-" + (countryAliases[tag] ?: tag)).getDisplayCountry(locale)
            else -> null
        }
        // Java answers with the code itself when it does not know it.
        if (name.isNullOrEmpty() || name.equals(tag, ignoreCase = true) || name.equals(countryAliases[tag], ignoreCase = true)) return null
        return name.replaceFirstChar { it.titlecase(locale) }
    }

    /** Language and country codes of the viewer ("fr-FR", "en-US" -> FR, EN, US), as tags. */
    fun viewerCodes(locales: List<Locale>): Set<String> {
        val codes = mutableSetOf<String>()
        for (locale in locales) {
            locale.language.takeIf { it.isNotEmpty() }?.let { codes += it.uppercase() }
            locale.country.takeIf { it.isNotEmpty() }?.let { codes += it.uppercase() }
        }
        if ("GB" in codes) codes += "UK"
        return codes
    }

    fun isHidden(state: OndoState, category: MediaCategory): Boolean =
        state.library.isCategoryHidden(state.activePlaylistId.orEmpty(), category.id) || hiddenWith(state, category) != null

    /** The tag, style or word hiding this category, if one of them is hidden. */
    fun hiddenWith(state: OndoState, category: MediaCategory): String? {
        val index = tags(state.content)
        index.tagOf[category.id]?.takeIf { it in state.library.hiddenTags }?.let { return it }
        if (state.library.hiddenWords.isEmpty()) return null
        return CategoryStyles.ruleHiding(index.words[category.id] ?: CategoryStyles.tokens(category.name), state.library.hiddenWords)
    }

    /** Languages and countries first (by name), other tags after. */
    fun sortedTags(index: TagIndex, locale: Locale): List<String> =
        index.categories.keys.sortedWith(compareBy<String>({ name(it, locale) == null }, { name(it, locale) ?: it }))

    fun categories(state: OndoState, type: ContentType, filter: String, locale: Locale): CategoriesUiState {
        val index = tags(state.content)
        val counts = when (type) {
            ContentType.LIVE -> state.content.channels.groupingBy { it.categoryId }.eachCount()
            ContentType.MOVIE -> state.content.movies.groupingBy { it.categoryId }.eachCount()
            ContentType.SERIES -> state.content.series.groupingBy { it.categoryId }.eachCount()
        }
        val list = when (type) {
            ContentType.LIVE -> state.content.liveCategories
            ContentType.MOVIE -> state.content.movieCategories
            ContentType.SERIES -> state.content.seriesCategories
        }
        val tokens = TextNormalizer.normalize(filter).split(' ').filter { it.isNotEmpty() }
        val shown = if (tokens.isEmpty()) list else list.filter { category ->
            val name = TextNormalizer.normalize(category.name)
            tokens.all { it in name }
        }
        val hiddenWords = state.library.hiddenWords
        return CategoriesUiState(
            tags = if (tokens.isNotEmpty()) emptyList() else sortedTags(index, locale).map { tag ->
                TagRow(tag, name(tag, locale)?.let { "$tag · $it" } ?: tag, index.categories[tag]?.size ?: 0, tag in state.library.hiddenTags)
            },
            styles = CategoryStyles.order.filter { it in index.styles || it in hiddenWords }.map { style ->
                StyleRow(style, index.styles[style]?.size ?: 0, style in hiddenWords)
            },
            words = hiddenWords.filter { it !in CategoryStyles.words }.sorted().map { WordRow(it, index.count(it)) },
            showsRules = tokens.isEmpty(),
            categories = shown.map { category ->
                CategoryToggle(category.id, category.name, counts[category.id] ?: 0, isHidden(state, category), hiddenWith(state, category))
            },
        )
    }

    /** The suggestion, when it makes sense and was neither answered nor dismissed. */
    fun suggestion(state: OndoState, viewerCodes: Set<String>, locale: Locale): TagSuggestion? {
        val playlistId = state.activePlaylistId ?: return null
        if (playlistId in state.settings.dismissedTagSuggestions) return null
        val tags = sortedTags(tags(state.content), locale)
        // A choice was already made about one of these tags.
        if (tags.any { it in state.library.hiddenTags }) return null
        val known = tags.filter { name(it, locale) != null }
        val kept = known.filter { it in viewerCodes }
        val hidden = known.filter { it !in viewerCodes }
        if (known.size < 3 || kept.isEmpty() || hidden.isEmpty()) return null
        return TagSuggestion(kept, kept.map { name(it, locale) ?: it }, hidden)
    }
}
