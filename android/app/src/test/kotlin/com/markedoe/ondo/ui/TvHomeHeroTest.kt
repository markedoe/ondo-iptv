package com.markedoe.ondo.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.ui.components.HeroPage
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.HomeActions
import com.markedoe.ondo.ui.screens.HomeUiState
import com.markedoe.ondo.ui.tv.OndoTvTheme
import com.markedoe.ondo.ui.tv.TvHomeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home's featured titles on a TV, in Portuguese (long button words): a long title and a long plot give up their
 * lines, never the buttons, which keep one height and one line each.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "pt-rBR-w960dp-h540dp-land-television-xhdpi")
class TvHomeHeroTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun page(title: String, plot: String, resumable: Boolean) = HeroPage(
        item = ShowcaseItem.MovieItem(Movie(id = title, name = title, categoryId = "1", streamUrl = "https://example.com/$title.mp4")),
        meta = "Filme · 2024 · Drama · ★ 7,1",
        inMyList = false,
        resumable = resumable,
        plot = plot,
    )

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test
    fun aLongTitleAndPlotLeaveTheButtonsWhole() {
        val long = "Uma história muito longa sobre um farol esquecido no fim do mundo e os seus guardiões"
        val plot = List(12) { "Uma frase comprida que ocupa espaço no resumo do filme." }.joinToString(" ")
        val state = HomeUiState(
            playlistName = "Example",
            updatedAt = 1_000L,
            now = 600_000L,
            hero = listOf(page(long, plot, resumable = true), page("Curto", "Breve.", resumable = false)),
            hasVod = true,
        )
        compose.setContent { OndoTvTheme { TvHomeScreen(state, HomeActions(), autoAdvance = false) } }
        compose.waitForIdle()

        val frame = bounds("heroFrame")
        val buttons = listOf("heroPlay", "heroMyList", "heroInfo").map(::bounds)
        val density = compose.density.density
        buttons.forEach { button ->
            assertTrue("a button inside the frame: $button in $frame", button.top >= frame.top && button.bottom <= frame.bottom)
            assertTrue("a button of its full height: ${button.height / density} dp", button.height / density >= 40f)
        }
        assertEquals(1, buttons.map { it.height }.distinct().size)
        assertEquals(1, buttons.map { it.center.y }.distinct().size)
        assertTrue("the title above the buttons", bounds("heroTitle").bottom <= buttons.first().top)
        // Each word on one line.
        for (label in listOf("Retomar", "Minha lista", "Mais informações")) {
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            assertEquals("$label on one line", 1, results.single().lineCount)
            assertTrue("$label not cut", !results.single().hasVisualOverflow)
        }
    }
}
