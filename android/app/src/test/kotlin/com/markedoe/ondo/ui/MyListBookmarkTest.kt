package com.markedoe.ondo.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.ui.components.DrawnIcon
import com.markedoe.ondo.ui.components.HeroCarousel
import com.markedoe.ondo.ui.components.HeroPage
import com.markedoe.ondo.ui.components.LocalPosterMyList
import com.markedoe.ondo.ui.components.LocalWatchedTitles
import com.markedoe.ondo.ui.components.MY_LIST_BADGE_TAG
import com.markedoe.ondo.ui.components.PosterCard
import com.markedoe.ondo.ui.components.PosterMyList
import com.markedoe.ondo.ui.components.WATCHED_BADGE_TAG
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.DetailActions
import com.markedoe.ondo.ui.screens.HomeActions
import com.markedoe.ondo.ui.screens.HomeUiState
import com.markedoe.ondo.ui.screens.MovieDetailScreen
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.OndoTvTheme
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TvHomeScreen
import com.markedoe.ondo.ui.tv.TvMovieDetailScreen
import com.markedoe.ondo.ui.tv.TvPosterCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The icons My List and Watched draw, for both test classes. */
internal object MyListIcons {
    val bookmark = Icons.Filled.Bookmark.name
    val outline = Icons.Outlined.BookmarkBorder.name
    val check = Icons.Rounded.Check.name

    fun drawn(node: SemanticsNode): String? = node.config.getOrNull(DrawnIcon)

    /** The icon drawn inside the node [owner] matches, in the unmerged tree. */
    fun drawnIn(compose: ComposeContentTestRule, owner: SemanticsMatcher): String =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(DrawnIcon) and hasAnyAncestor(owner), useUnmergedTree = true)
            .fetchSemanticsNodes().map { drawn(it)!! }.single()

    fun badge(compose: ComposeContentTestRule, tag: String): SemanticsNode =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
}

/**
 * My List is a bookmark everywhere, Watched a check mark: on a poster, the bookmark ribbon in the end corner and the
 * check mark on its disc in the start one, never two checks; on the pages, an outlined bookmark filled once in My List.
 * Apple has the same test (`MyListBookmarkTests`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class MyListBookmarkTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createComposeRule()

    private val movie = ShowcaseItem.MovieItem(Fixtures.glassCity)

    private fun poster(direction: LayoutDirection) {
        compose.setContent {
            OndoTheme {
                CompositionLocalProvider(
                    LocalWatchedTitles provides setOf(movie.key),
                    LocalPosterMyList provides PosterMyList(setOf(movie.key)) {},
                    LocalLayoutDirection provides direction,
                ) {
                    PosterCard(movie, 120.dp, onClick = {})
                }
            }
        }
    }

    private fun assertBadges(start: Rect, end: Rect, poster: Rect, rtl: Boolean = false) {
        assertTrue("the watched check mark at the start: $start in $poster", if (rtl) start.left > poster.center.x else start.right < poster.center.x)
        assertTrue("the bookmark at the end: $end in $poster", if (rtl) end.right < poster.center.x else end.left > poster.center.x)
        assertTrue("the bookmark hangs from the top edge: $end in $poster", end.top <= poster.top + 1f)
    }

    @Test
    fun watchedAndMyListBadgesDifferInIconAndCorner() {
        poster(LayoutDirection.Ltr)
        val watched = MyListIcons.badge(compose, WATCHED_BADGE_TAG)
        val kept = MyListIcons.badge(compose, MY_LIST_BADGE_TAG)
        assertEquals(MyListIcons.check, MyListIcons.drawn(watched))
        assertEquals(MyListIcons.bookmark, MyListIcons.drawn(kept))
        assertNotEquals(MyListIcons.drawn(watched), MyListIcons.drawn(kept))
        val poster = compose.onNode(hasStateDescription("Watched, In My List")).fetchSemanticsNode().boundsInRoot
        assertBadges(watched.boundsInRoot, kept.boundsInRoot, poster)
    }

    @Test
    fun rightToLeftTheCornersAreMirrored() {
        poster(LayoutDirection.Rtl)
        val poster = compose.onNode(hasStateDescription("Watched, In My List")).fetchSemanticsNode().boundsInRoot
        assertBadges(MyListIcons.badge(compose, WATCHED_BADGE_TAG).boundsInRoot, MyListIcons.badge(compose, MY_LIST_BADGE_TAG).boundsInRoot, poster, rtl = true)
    }

    @Test
    fun aPagesMyListButtonIsAnOutlinedBookmarkFilledOnceInMyList() {
        var state by mutableStateOf(UiMappers.movieDetail(Fixtures.state(), Fixtures.glassCity).copy(inMyList = false))
        compose.setContent { OndoTheme { MovieDetailScreen(state, DetailActions()) } }
        compose.onNode(hasStateDescription("Not in My List")).performScrollTo()
        assertEquals(MyListIcons.outline, MyListIcons.drawnIn(compose, hasStateDescription("Not in My List")))
        state = state.copy(inMyList = true)
        assertEquals(MyListIcons.bookmark, MyListIcons.drawnIn(compose, hasStateDescription("In My List")))
    }

    @Test
    fun theHerosMyListButtonIsABookmarkToo() {
        var page by mutableStateOf(HeroPage(item = movie, meta = "Movie · 2024", inMyList = false, resumable = false, plot = "A plot."))
        compose.setContent { OndoTheme { HeroCarousel(listOf(page), onOpen = {}, onPlay = {}, onToggleMyList = {}, autoAdvance = false) } }
        assertEquals(MyListIcons.outline, MyListIcons.drawnIn(compose, hasStateDescription("Not in My List")))
        page = page.copy(inMyList = true)
        assertEquals(MyListIcons.bookmark, MyListIcons.drawnIn(compose, hasStateDescription("In My List")))
    }
}

/** On a TV: the poster's bookmark (24 dp at least), the page's and the hero's My List buttons. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvMyListBookmarkTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createComposeRule()

    private val movie = ShowcaseItem.MovieItem(Fixtures.glassCity)

    @Test
    fun aTvPostersBadgesDifferAndTheBookmarkIsLargeEnough() {
        compose.setContent {
            OndoTheme {
                TelevisionFrame {
                    CompositionLocalProvider(LocalWatchedTitles provides setOf(movie.key), LocalPosterMyList provides PosterMyList(setOf(movie.key)) {}) {
                        Row { TvPosterCard(movie, onClick = {}) }
                    }
                }
            }
        }
        val watched = MyListIcons.badge(compose, WATCHED_BADGE_TAG)
        val kept = MyListIcons.badge(compose, MY_LIST_BADGE_TAG)
        assertEquals(MyListIcons.check, MyListIcons.drawn(watched))
        assertEquals(MyListIcons.bookmark, MyListIcons.drawn(kept))
        assertTrue("the start and the end corners", watched.boundsInRoot.right < kept.boundsInRoot.left)
        assertTrue("24 dp at least: ${kept.boundsInRoot.height / compose.density.density} dp", kept.boundsInRoot.height / compose.density.density >= 24f)
    }

    @Test
    fun aTvPagesMyListButtonIsAnOutlinedBookmarkFilledOnceInMyList() {
        var state by mutableStateOf(UiMappers.movieDetail(Fixtures.state(), Fixtures.glassCity).copy(inMyList = false))
        compose.setContent { OndoTheme { TelevisionFrame { TvMovieDetailScreen(state, DetailActions()) } } }
        compose.waitForIdle()
        assertEquals(MyListIcons.outline, MyListIcons.drawnIn(compose, hasTestTag("detailMyList")))
        state = state.copy(inMyList = true)
        compose.waitForIdle()
        assertEquals(MyListIcons.bookmark, MyListIcons.drawnIn(compose, hasTestTag("detailMyList")))
    }

    @Test
    fun aTvHerosMyListButtonIsABookmarkToo() {
        val page = HeroPage(item = movie, meta = "Movie · 2024", inMyList = false, resumable = false, plot = "A plot.")
        var state by mutableStateOf(HomeUiState(playlistName = "Example", updatedAt = 1_000L, now = 600_000L, hero = listOf(page), hasVod = true))
        compose.setContent { OndoTvTheme { TvHomeScreen(state, HomeActions(), autoAdvance = false) } }
        compose.waitForIdle()
        assertEquals(MyListIcons.outline, MyListIcons.drawnIn(compose, hasTestTag("heroMyList")))
        state = state.copy(hero = listOf(page.copy(inMyList = true)))
        compose.waitForIdle()
        assertEquals(MyListIcons.bookmark, MyListIcons.drawnIn(compose, hasTestTag("heroMyList")))
    }
}
