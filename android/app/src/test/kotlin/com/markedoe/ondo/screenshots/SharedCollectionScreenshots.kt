package com.markedoe.ondo.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.model.Profile
import com.markedoe.ondo.data.Favorite
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.data.UserCollection
import com.markedoe.ondo.ui.UiMappers
import com.markedoe.ondo.ui.screens.CollectionActions
import com.markedoe.ondo.ui.screens.CollectionScreen
import com.markedoe.ondo.ui.screens.MyListActions
import com.markedoe.ondo.ui.screens.MyListKind
import com.markedoe.ondo.ui.screens.MyListScreen
import com.markedoe.ondo.ui.theme.OndoTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Reference screenshots of a collection shared with every profile (docs/PROFILES.md, "Shared lists"), on a phone, from the
 * demo catalogue: its page with the "Shared with all profiles" switch and the shared mark by its name, and My List's
 * collections with the mark on the shared one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w360dp-h640dp-xxhdpi")
class SharedCollectionScreenshots {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun pictures() = ShowcaseImages.install(ApplicationProvider.getApplicationContext())

    @After
    fun noPictures() = ShowcaseImages.reset()

    /** The demo catalogue with two profiles, a collection of the open profile and one shared with every profile. */
    private fun state(): OndoState {
        val catalogue = ShowcaseCatalogue.load(french = false)
        val base = catalogue.state()
        fun items(count: Int, from: Int) = catalogue.movies.drop(from).take(count).mapIndexed { index, movie ->
            Favorite(ShowcaseCatalogue.PLAYLIST_ID, ItemKind.MOVIE, movie.id, movie.name, movie.posterUrl, addedAt = 100L + index)
        }
        val own = UserCollection("c-own", "Rainy Sundays", items(2, 4), updatedAt = 200)
        val shared = UserCollection("c-shared", "Family movie night", items(5, 0), updatedAt = 300, placedAt = 300)
        return base.copy(
            library = base.library.copy(collections = listOf(own)),
            household = LibraryState(collections = listOf(shared)),
            profiles = listOf(Profile.main("Me"), Profile(id = "11111111-1111-1111-1111-111111111111", name = "Sam", colorIndex = 3, symbol = "star.fill")),
        )
    }

    @Test
    fun sharedCollectionPage() {
        val page = requireNotNull(UiMappers.collection(state(), "c-shared", ShowcaseCatalogue.NOW))
        compose.setContent { OndoTheme { CollectionScreen(page, CollectionActions()) } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$BASELINES/phone/en-US/collection-shared.png", BaselineOptions)
    }

    @Test
    fun sharedMarkInMyList() {
        val myList = UiMappers.myList(state(), ShowcaseCatalogue.NOW)
        compose.setContent { OndoTheme { MyListScreen(myList, MyListKind.COLLECTIONS, MyListActions()) } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$BASELINES/phone/en-US/my-list-shared-collection.png", BaselineOptions)
    }
}
