package com.markedoe.ondo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

/** What a page last computed, kept while the page is in the back stack (a view model of its navigation entry). */
internal class PageValues : ViewModel() {
    class Kept(val keys: List<Any?>, val value: Any?)

    val values = HashMap<String, Kept>()
}

/**
 * [produceState] for a page whose content is computed in the background, starting from what the page last showed:
 * back on the page, it is there at once with all its items, so its list comes back scrolled where it was and the
 * remote on the card it left (an empty list first would scroll it back to the top). Computed again as before, unless
 * [again] is false and the keys are the same (a request to a service, made once).
 */
@Composable
internal fun <T> produceKept(name: String, initial: T, vararg keys: Any?, again: Boolean = true, compute: suspend () -> T): State<T> {
    val page: PageValues = viewModel()
    val kept = page.values[name]

    @Suppress("UNCHECKED_CAST")
    val start = if (kept != null) kept.value as T else initial
    return produceState(start, *keys) {
        val current = keys.toList()
        if (!again && page.values[name]?.keys == current) return@produceState
        val result = compute()
        page.values[name] = PageValues.Kept(current, result)
        value = result
    }
}
