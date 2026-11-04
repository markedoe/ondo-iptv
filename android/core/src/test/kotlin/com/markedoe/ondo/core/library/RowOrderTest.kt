package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.HangGuard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The order of the rows of the Movies and Series tabs (the same cases as the Swift `RowOrderTests`). */
class RowOrderTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val action = RowOrder.categoryKey("movie-1", "Action")
    private val drama = RowOrder.categoryKey("movie-2", "  Drama   FR ")
    private val kids = RowOrder.categoryKey("movie-3", "Kids")

    private fun keys(rows: List<RowOrder.Row>) = rows.map { it.key }

    @Test
    fun aCategoryKeyHoldsItsIdAndItsNameFolded() {
        assertEquals("cat:movie-1|action", action)
        assertEquals("cat:movie-2|drama fr", drama)
        // Only A to Z fold; control characters go, and a bar in the ID cannot cut the key.
        assertEquals("cat:ab|Élan ÉtÉ", RowOrder.categoryKey("a|\nb", "Élan\tÉTÉ"))
    }

    @Test
    fun withoutASavedOrderTheRowsKeepTheirDefaultOrder() {
        val current = RowOrder.builtIns(series = false) + listOf(action, drama)
        assertEquals(current, keys(RowOrder.arrange(current, emptyList(), emptyList())))
        // Series have their award winners too, under the same key as movies.
        assertEquals(listOf("all", "continue", "mylist", "new", "trending", "awards"), RowOrder.builtIns(series = true))
    }

    @Test
    fun theSavedOrderAppliesAndNewCategoriesGoToTheEnd() {
        val current = listOf("all", "continue", action, drama, kids)
        val rows = RowOrder.arrange(current, listOf(drama, "all", action), listOf("continue"))
        assertEquals(listOf(drama, "all", action, "continue", kids), keys(rows))
        assertEquals(listOf("continue"), rows.filter { it.hidden }.map { it.key })
    }

    @Test
    fun aRemovedCategoryIsRememberedAndComesBackAtItsPlace() {
        val saved = listOf(drama, kids, "all", action)
        // Kids is gone from the playlist: not shown, but kept after Drama when the viewer moves the others.
        val without = listOf("all", action, drama)
        assertEquals(listOf(drama, "all", action), keys(RowOrder.arrange(without, saved, listOf(kids))))
        val moved = listOf("all", drama, action)
        val next = RowOrder.saving(moved, saved)
        assertEquals(listOf("all", drama, kids, action), next)
        // Hidden while gone, and hidden again when it comes back.
        val hidden = RowOrder.savingHidden(without, emptyList(), listOf(kids))
        assertEquals(listOf(kids), hidden)
        val back = RowOrder.arrange(without + kids, next, hidden)
        assertEquals(listOf("all", drama, kids, action), keys(back))
        assertEquals(listOf(kids), back.filter { it.hidden }.map { it.key })
    }

    @Test
    fun aCategoryRenumberedOrRenamedIsFoundByItsNameOrItsId() {
        val saved = listOf(drama, action)
        // The provider numbered Drama anew: found by its name.
        val renumbered = RowOrder.categoryKey("movie-20", "Drama FR")
        // Action renamed: found by its ID.
        val renamed = RowOrder.categoryKey("movie-1", "Action & Adventure")
        val rows = RowOrder.arrange(listOf("all", renamed, renumbered), saved, listOf(action))
        assertEquals(listOf(renumbered, renamed, "all"), keys(rows))
        assertEquals(listOf(renamed), rows.filter { it.hidden }.map { it.key })
        // Saved again, they take their new keys.
        assertEquals(listOf(renumbered, renamed, "all"), RowOrder.saving(keys(rows), saved))
    }

    @Test
    fun eachRowOnceWhateverTheSavedKeys() {
        val current = listOf("all", action)
        val rows = RowOrder.arrange(current, listOf(action, action, "all", "all", "cat:movie-1|other"), emptyList())
        assertEquals(listOf(action, "all"), keys(rows))
        assertEquals(listOf("all", action), RowOrder.saving(listOf("all", action), listOf("", "all", action)))
    }
}
