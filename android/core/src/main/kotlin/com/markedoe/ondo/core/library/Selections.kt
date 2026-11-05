package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.StableHash
import com.markedoe.ondo.core.TextNormalizer

/**
 * A selection: a row the viewer makes of tags (docs/ROWS.md, "Selections"): genres, awards, decades and styles, a title
 * matching any of them (or all of them), shown in the Home, Movies or Series tab as a new pick every day. The same tags,
 * rules and tests as the Swift `Selections` (IPTVCore); the selections sync (`SyncSelection`, docs/SYNC.md, "Selections").
 */
object Selections {
    /** A genre, by its name as [TextNormalizer.normalize] gives it: `genre:drama`. */
    const val GENRE = "genre:"

    /** A festival or ceremony of the awards file (`AwardsCatalogue.Award.group`): `award:cannes`. */
    const val AWARD = "award:"

    /** A decade, by its first year: `decade:1990`. */
    const val DECADE = "decade:"

    /** A style of [CategoryStyles] (the provider's category names): `style:manga`. */
    const val STYLE = "style:"

    /** The row key of a selection in a tab's order (`RowOrder`): `sel:<id>`. */
    const val ROW_PREFIX = "sel:"

    /** The match modes (`SyncSelection.match`): a title with any of the tags, or with all of them. */
    const val ANY = "any"
    const val ALL = "all"

    /**
     * How far the daily pick moves a title from its rating: its key is its weighted rating plus up to this much, drawn
     * anew each day, so a title rated a little lower often comes first, one rated far lower seldom does.
     */
    const val SPREAD = 2.0

    fun genre(name: String): String? = TextNormalizer.normalize(name).takeIf { it.isNotEmpty() }?.let { GENRE + it }

    fun award(group: String): String = AWARD + group

    fun decade(year: Int): String = DECADE + (year - Math.floorMod(year, 10))

    fun style(style: String): String = STYLE + style

    fun rowKey(id: String): String = ROW_PREFIX + id

    /** The selection ID of a row key; null for another row. */
    fun idOfRow(key: String): String? = if (key.startsWith(ROW_PREFIX)) key.substring(ROW_PREFIX.length).takeIf { it.isNotEmpty() } else null

    /** Whether this version knows the kind of [tag]; a tag of another kind (a newer version's) is left out of the match. */
    fun isKnown(tag: String): Boolean = kindOf(tag) != null && tag.length > kindOf(tag)!!.length

    private fun kindOf(tag: String): String? = listOf(GENRE, AWARD, DECADE, STYLE).firstOrNull { tag.startsWith(it) }

    /**
     * What a title is tagged with: its genres (normalised names), the festivals and ceremonies of its awards (won or
     * selected), its year, and the styles of its category's name ([stylesOf]).
     */
    data class Facts(
        val genres: Set<String> = emptySet(),
        val awardGroups: Set<String> = emptySet(),
        val year: Int? = null,
        val styles: Set<String> = emptySet(),
    )

    /** The styles of [CategoryStyles] a category name holds. */
    fun stylesOf(categoryName: String): Set<String> {
        val tokens = CategoryStyles.tokens(categoryName)
        return CategoryStyles.order.filterTo(LinkedHashSet()) { CategoryStyles.matches(tokens, it) }
    }

    /** Whether a title of [facts] has [tag]. */
    fun has(facts: Facts, tag: String): Boolean = when {
        tag.startsWith(GENRE) -> tag.substring(GENRE.length) in facts.genres
        tag.startsWith(AWARD) -> tag.substring(AWARD.length) in facts.awardGroups
        tag.startsWith(DECADE) -> {
            val start = tag.substring(DECADE.length).toIntOrNull()
            start != null && facts.year != null && facts.year >= start && facts.year < start + 10
        }
        tag.startsWith(STYLE) -> tag.substring(STYLE.length) in facts.styles
        else -> false
    }

    /**
     * Whether a title of [facts] belongs to a selection of [tags]: one of them ([all] false), or each of them. Tags of a
     * kind this version does not know are left out; with no tag left, no title matches.
     */
    fun matches(tags: Collection<String>, all: Boolean, facts: Facts): Boolean {
        val known = tags.filter(::isKnown)
        if (known.isEmpty()) return false
        return if (all) known.all { has(facts, it) } else known.any { has(facts, it) }
    }

    /**
     * The day's order of a selection's titles: each title's key is its TMDb weighted rating ([WeightedRating.weighted],
     * at least [RecentReleases.MINIMUM_VOTES] votes; else [WeightedRating.PRIOR_AVERAGE]) plus [SPREAD] times a number
     * drawn from the selection, the [day] ("2026-11-05", the device's own date) and the title ([key]), the highest
     * first; the titles [watched] last. The same titles give the same order all day, and another the next day; only
     * additions and multiplications of exact numbers, so every platform gives the same order.
     */
    fun <T> dailyOrder(
        items: List<T>,
        selectionId: String,
        day: String,
        key: (T) -> String,
        rating: (T) -> TmdbRating?,
        watched: (T) -> Boolean,
    ): List<T> {
        class Keyed<I>(val item: I, val key: String, val score: Double, val watched: Boolean)
        val keyed = items.map { item ->
            val id = key(item)
            Keyed(item, id, score(rating(item)) + SPREAD * draw("$selectionId|$day|$id"), watched(item))
        }
        val order = compareBy<Keyed<T>> { it.watched }.thenByDescending { it.score }.thenBy { it.key }
        return keyed.sortedWith(order).map { it.item }
    }

    /** A title's rating for the pick: its weighted rating with enough votes, else the prior average. */
    fun score(rating: TmdbRating?): Double =
        rating?.takeIf { it.votes >= RecentReleases.MINIMUM_VOTES }?.let { WeightedRating.weighted(it.average, it.votes) } ?: WeightedRating.PRIOR_AVERAGE

    /** A number in [0, 1) drawn from [seed]: [StableHash.fnv1a] mixed (SplitMix64's finaliser), its 53 top bits. */
    fun draw(seed: String): Double {
        var z = StableHash.fnv1a(seed)
        z = (z xor (z shr 30)) * 0xbf58476d1ce4e5b9uL
        z = (z xor (z shr 27)) * 0x94d049bb133111ebuL
        z = z xor (z shr 31)
        return (z shr 11).toLong().toDouble() * (1.0 / 9007199254740992.0)
    }

    /** The name a selection takes when the viewer gives none: its tags' names, joined ("Drama · Cannes · 1990s"). */
    fun defaultName(tags: List<String>, name: (String) -> String): String = tags.filter(::isKnown).take(3).joinToString(" · ", transform = name)

    /** The decades of [years], the latest first. */
    fun decades(years: Iterable<Int?>): List<Int> = years.filterNotNull().filter { it in 1870..2999 }.map { it - Math.floorMod(it, 10) }.distinct().sortedDescending()
}
