package com.markedoe.ondo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.R
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.CIRCLE_ACTION_LABEL_TAG
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TvCircleAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The round buttons of a title's page on a TV, in each language of the app: each line of their names (Trailer and
 * YouTube, My List, Collection) whole on a line of its own, never a word broken or cut short ("Bande-annonce" in
 * French).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvCircleActionLabelTest(private val language: String) {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun languages(): List<Array<Any>> = listOf("en", "fr", "es", "pt", "zh-rCN", "ar", "hi").map { arrayOf<Any>(it) }
    }

    @get:Rule val compose = createComposeRule()

    @Test
    fun eachLineOfAButtonsNameIsWhole() {
        RuntimeEnvironment.setQualifiers("+$language")
        compose.setContent {
            OndoTheme {
                TelevisionFrame {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TvCircleAction(Icons.Rounded.Add, stringResource(R.string.my_list), onClick = {})
                        TvCircleAction(Icons.Rounded.LibraryAdd, stringResource(R.string.collection), onClick = {})
                        TvCircleAction(Icons.Rounded.OndemandVideo, trailerLabel(), onClick = {})
                    }
                }
            }
        }
        val labels = compose.onAllNodesWithTag(CIRCLE_ACTION_LABEL_TAG, useUnmergedTree = true).fetchSemanticsNodes().map { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            layouts.single()
        }
        assertEquals("three buttons", 3, labels.size)
        for (label in labels) {
            val text = label.layoutInput.text.text
            assertEquals("\"$text\": one line each", text.count { it == '\n' } + 1, label.lineCount)
            assertFalse("\"$text\" is cut short", label.isLineEllipsized(label.lineCount - 1) || label.hasVisualOverflow)
        }
    }
}
