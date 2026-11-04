package com.markedoe.ondo.core.library

/**
 * The rows of the Home, Movies or Series tab in the order the viewer chose, and those they hid (Settings → Row order).
 * A row is known by a key that survives a playlist refresh: a built-in row by its name ([ALL], [CONTINUE_WATCHING]…), a
 * provider category by its ID and its name ([categoryKey]), so that a category the provider numbers anew is still found
 * by its name. The same rules as the Swift `RowOrder` (IPTVCore); the order syncs (docs/SYNC.md, "Rows").
 */
object RowOrder {
    /** "All movies" / "All series": the latest additions, then See all the catalogue. */
    const val ALL = "all"
    const val CONTINUE_WATCHING = "continue"
    const val MY_LIST = "mylist"
    const val NEW_RELEASES = "new"
    const val TRENDING = "trending"
    /** Movies only. */
    const val AWARD_WINNERS = "awards"
    const val CATEGORY_PREFIX = "cat:"

    /** The tabs, as sync names them (`SyncRows.tab`). */
    const val MOVIES = "movie"
    const val SERIES = "series"
    const val HOME = "home"

    /** Home's rows (below its carousel, which stays at the top): movies and series in progress, … */
    const val HOME_CONTINUE_MOVIES = "continue-movies"
    const val HOME_CONTINUE_SERIES = "continue-series"
    const val HOME_NEW_MOVIES = "new-movies"
    const val HOME_NEW_SERIES = "new-series"
    /** Live TV: the channels in My List, or those watched lately. */
    const val HOME_CHANNELS = "channels"
    /** The viewer's own lists (Apple devices only). */
    const val HOME_COLLECTIONS = "collections"

    /** The built-in rows of a tab, in their default order, above the categories. */
    fun builtIns(series: Boolean): List<String> =
        if (series) listOf(ALL, CONTINUE_WATCHING, MY_LIST, NEW_RELEASES, TRENDING)
        else listOf(ALL, CONTINUE_WATCHING, MY_LIST, NEW_RELEASES, TRENDING, AWARD_WINNERS)

    /**
     * Home's rows in their default order: movies and series in progress, My List, the new movies and series, Trending,
     * the collections ([collections]: the platforms that show them), the channels last; on a playlist without movies or
     * series ([hasVod] false), the channels right after My List.
     */
    fun homeBuiltIns(hasVod: Boolean, collections: Boolean): List<String> = buildList {
        add(HOME_CONTINUE_MOVIES)
        add(HOME_CONTINUE_SERIES)
        add(MY_LIST)
        if (!hasVod) add(HOME_CHANNELS)
        add(HOME_NEW_MOVIES)
        add(HOME_NEW_SERIES)
        add(TRENDING)
        if (collections) add(HOME_COLLECTIONS)
        if (hasVod) add(HOME_CHANNELS)
    }

    /** The key of a provider category: `cat:<id>|<name>`, its name as [normalizedName] gives it. */
    fun categoryKey(id: String, name: String): String = "$CATEGORY_PREFIX${clean(id)}|${normalizedName(name)}"

    /**
     * A category name as a key holds it: without control characters, its spaces trimmed and runs of them made one,
     * A to Z in lowercase (the same on every platform, whatever its case rules); other letters keep their case.
     */
    fun normalizedName(name: String): String = buildString(name.length) {
        var space = false
        for (char in name) {
            when {
                char == ' ' || char < ' ' || char == '\u007f' -> space = isNotEmpty()
                else -> {
                    if (space) append(' ')
                    space = false
                    append(if (char in 'A'..'Z') char + 32 else char)
                }
            }
        }
    }

    /** An ID without control characters (a key holds no line feed, so that the sync tie strings are unambiguous). */
    private fun clean(id: String): String = id.filter { it >= ' ' && it != '\u007f' && it != '|' }

    /** A key as sync accepts it: not blank, no control characters. */
    fun isValidKey(key: String): Boolean = key.isNotBlank() && key.none { it < ' ' || it == '\u007f' }

    /** A row of the tab: its key, and whether the viewer hid it. */
    data class Row(val key: String, val hidden: Boolean)

    /**
     * The rows of [current] (their keys, in their default order) in the [order] saved, then those it does not name in
     * their default order (a category new to the playlist goes to the end), each hidden when [hidden] names it. A
     * saved key no row has (a category the provider removed) is skipped and kept for when it comes back.
     */
    fun arrange(current: List<String>, order: List<String>, hidden: Collection<String>): List<Row> {
        val placed = matches(current, order)
        val hiddenRows = matches(current, hidden.toList()).values.toHashSet()
        val seen = HashSet<String>()
        val result = ArrayList<Row>(current.size)
        for (index in order.indices) {
            val key = placed[index] ?: continue
            if (seen.add(key)) result.add(Row(key, key in hiddenRows))
        }
        for (key in current) if (seen.add(key)) result.add(Row(key, key in hiddenRows))
        return result
    }

    /**
     * The order to save once the viewer put the rows of the tab in [arranged] (current keys): those keys, with each key
     * of [previous] that no current row has (a category gone for now) kept after the row it followed, so that it comes
     * back at its place.
     */
    fun saving(arranged: List<String>, previous: List<String>): List<String> {
        val placed = matches(arranged, previous)
        val after = HashMap<String?, MutableList<String>>()
        var anchor: String? = null
        previous.forEachIndexed { index, key ->
            val row = placed[index]
            if (row != null) anchor = row else if (isValidKey(key)) after.getOrPut(anchor) { ArrayList() }.add(key)
        }
        val result = LinkedHashSet<String>()
        after[null]?.let(result::addAll)
        for (key in arranged) {
            result.add(key)
            after[key]?.let(result::addAll)
        }
        return result.toList()
    }

    /**
     * The keys to save as hidden: the current rows hidden ([hiddenNow]), and the saved ones ([previous]) that no current
     * row has (a category gone for now, hidden again when it comes back), sorted and each once.
     */
    fun savingHidden(current: List<String>, hiddenNow: Collection<String>, previous: Collection<String>): List<String> {
        val list = previous.toList()
        val placed = matches(current, list)
        val gone = list.filterIndexed { index, key -> placed[index] == null && isValidKey(key) }
        return (hiddenNow + gone).toSortedSet().toList()
    }

    /**
     * Which current row each saved key names, by its index in [saved]: the same key first, then a category of the same
     * ID, then one of the same name; each row named once at most.
     */
    private fun matches(current: List<String>, saved: List<String>): Map<Int, String> {
        val result = HashMap<Int, String>()
        val used = HashSet<String>()
        val exact = current.toHashSet()
        saved.forEachIndexed { index, key -> if (key in exact && used.add(key)) result[index] = key }
        val byId = HashMap<String, String>()
        val byName = HashMap<String, String>()
        for (key in current) {
            val (id, name) = category(key) ?: continue
            byId.putIfAbsent(id, key)
            byName.putIfAbsent(name, key)
        }
        for (pass in 0..1) {
            saved.forEachIndexed { index, key ->
                if (index in result) return@forEachIndexed
                val (id, name) = category(key) ?: return@forEachIndexed
                val row = (if (pass == 0) byId[id] else byName[name].takeIf { name.isNotEmpty() }) ?: return@forEachIndexed
                if (used.add(row)) result[index] = row
            }
        }
        return result
    }

    /** The ID and the name of a category key; null for a built-in row. */
    private fun category(key: String): Pair<String, String>? {
        if (!key.startsWith(CATEGORY_PREFIX)) return null
        val bar = key.indexOf('|', CATEGORY_PREFIX.length)
        return if (bar < 0) key.substring(CATEGORY_PREFIX.length) to "" else key.substring(CATEGORY_PREFIX.length, bar) to key.substring(bar + 1)
    }
}
