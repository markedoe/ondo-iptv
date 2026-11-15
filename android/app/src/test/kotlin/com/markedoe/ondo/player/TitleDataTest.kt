package com.markedoe.ondo.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.markedoe.ondo.FiledTwice
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.library.DefaultReason
import com.markedoe.ondo.core.library.PictureSupport
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.data.SavedPlaylist
import com.markedoe.ondo.ui.UiMappers
import com.markedoe.ondo.ui.theme.OndoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The title data of More, Stream Statistics: a film's raw names, categories, ids and tags, each version's chip, the one
 * playing and the one played first; an episode's raw title and its series' name. Never the login, the password, the
 * server or an address. Invented names and example.com only.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class TitleDataTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createComposeRule()

    private val playlist = SavedPlaylist(
        id = Fixtures.PLAYLIST_ID, name = "Panel", url = "http://panel.example.com:8080", username = "demouser", password = "s3cret-pass",
    )

    private fun version(id: String, name: String, extension: String) = Movie(
        id = id, name = name, categoryId = Fixtures.drama.id, year = 2026, containerExtension = extension, xtreamStreamId = id.drop(1).toInt(),
        streamUrl = "http://panel.example.com:8080/movie/demouser/s3cret-pass/${id.drop(1)}.$extension",
    )

    // The case of the TV: a plain version, a 4K Dolby Vision one and another plain one, only their containers differ.
    private val plain = version("v101", "Copper Valley", "mp4")
    private val dv = version("v102", "Copper Valley 4K DV", "mkv")
    private val other = version("v103", "Copper Valley", "mkv")

    private fun state(): OndoState = run {
        // A screen without 4K or Dolby Vision, whatever another test left.
        UiMappers.pictureSupport = PictureSupport()
        Fixtures.state(
            content = Fixtures.content.copy(movies = Fixtures.content.movies + listOf(plain, dv, other)),
            settings = AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID, groupVersions = true),
        ).copy(playlists = listOf(playlist))
    }

    private fun everyText(data: TitleData): List<String> =
        data.lines.map { it.value } + data.versions.flatMap { listOfNotNull(it.chip, it.name, it.category, it.shownId, it.container, it.tags, it.sameAs) }

    @Test
    fun aFilmShowsItsVersionsAndTheirChips() {
        val data = TitleData.of(state(), "${Fixtures.PLAYLIST_ID}|MOVIE|${dv.id}|", shownTitle = "Copper Valley")!!
        val lines = data.lines.associate { (if (it.label != 0) "#${it.label}" else it.key) to it.value }
        assertEquals("Copper Valley 4K DV", lines["name"])
        assertEquals(dv.id, lines["id"])
        assertEquals("102", lines["stream_id"])
        assertEquals("Drama (m-drama)", lines["category"])
        assertEquals("2026", lines["year"])
        assertEquals("mkv → MKV", lines["container_extension"])
        assertEquals("UHD, DOLBY_VISION", data.lines.single { it.label == com.markedoe.ondo.R.string.title_data_tags }.value)
        assertEquals(listOf(plain.id, dv.id, other.id), data.versions.map { it.id })
        assertEquals(listOf("Standard · MP4", "4K Dolby Vision", "Standard · MKV"), data.versions.map { it.chip })
        assertEquals(listOf(false, true, false), data.versions.map { it.playing })
        assertEquals("–", data.versions[0].tags)
        // No Dolby Vision on the test's screen: a plain version first, for the screen's sake.
        assertEquals(listOf(true, false, false), data.versions.map { it.first })
        assertEquals(DefaultReason.BEST_FOR_SCREEN, data.reason)
    }

    @Test
    fun theLoginPasswordAndServerNeverShow() {
        val named = state().let { s ->
            s.copy(content = s.content.copy(movies = s.content.movies.map { if (it.id == plain.id) it.copy(name = "Copper Valley demouser:s3cret-pass@panel.example.com") else it }))
        }
        for (id in listOf(plain.id, dv.id, other.id)) {
            val data = TitleData.of(named, "${Fixtures.PLAYLIST_ID}|MOVIE|$id|", shownTitle = "Copper Valley http://panel.example.com:8080/x?token=abc")!!
            for (text in everyText(data)) {
                for (secret in listOf("demouser", "s3cret-pass", "panel.example.com", "http", "token=abc")) {
                    assertFalse("$secret in $text", secret in text.lowercase())
                }
            }
        }
    }

    @Test
    fun anEpisodeShowsItsRawTitleAndItsSeriesName() {
        val series = Fixtures.harborLights
        val episode = series.episodes.first()
        val data = TitleData.of(state(), "${Fixtures.PLAYLIST_ID}|SERIES|${series.id}|${episode.id}", shownTitle = "S01E01 · Episode 1")!!
        val lines = data.lines.associate { it.key to it.value }
        assertEquals("Harbor Lights", lines["series_name"])
        assertEquals(episode.title, lines["episode_title"])
        assertNull("not a provider's number: never shown", lines["episode_id"])
        assertEquals("S01E01", lines["episode"])
        assertEquals("MP4", lines["container_extension"])
        assertTrue(data.versions.isEmpty())
    }

    /**
     * An M3U playlist's ids are a hash of each stream's address, its login and password included: no text of the panel
     * holds one, nor any part of one longer than 4 characters.
     */
    @Test
    fun anM3uPlaylistsIdsNeverShow() {
        val m3u = SavedPlaylist(id = Fixtures.PLAYLIST_ID, name = "List", url = "http://list.example.com/get.php?username=demouser&password=s3cret-pass")
        val hashed = listOf(
            plain.copy(id = "m3fa9c01b2e44d7a1", xtreamStreamId = null),
            dv.copy(id = "m0b1c2d3e4f5a6b7c", xtreamStreamId = null),
            other.copy(id = "m3fa9c01b2e44d7a1-2", xtreamStreamId = null),
        )
        val state = state().let { it.copy(playlists = listOf(m3u), content = it.content.copy(movies = Fixtures.content.movies + hashed)) }
        for (version in hashed) {
            val data = TitleData.of(state, "${Fixtures.PLAYLIST_ID}|MOVIE|${version.id}|", shownTitle = "Copper Valley")!!
            assertEquals(3, data.versions.size)
            for (text in everyText(data)) {
                for (id in hashed.map { it.id }) {
                    for (start in 0..id.length - 5) assertFalse("${id.substring(start, start + 5)} in $text", id.substring(start, start + 5) in text)
                }
            }
            assertTrue(data.lines.none { it.key == "id" })
        }
    }

    @Test
    fun aChannelHasNoTitleData() {
        assertNull(TitleData.of(state(), "${Fixtures.PLAYLIST_ID}|CHANNEL|c1|", shownTitle = "News"))
        assertNull(TitleData.of(state(), null, shownTitle = "News"))
        assertNull(TitleData.of(state(), "${Fixtures.PLAYLIST_ID}|MOVIE|missing|", shownTitle = "Gone"))
    }

    /**
     * One film a panel files under two categories is one chip on its page; the title data still lists every stream, the
     * other one marked "same as" the one its chip plays. Its displayed title is the app's, without the provider's prefix
     * (it showed "|FR| …"), from the player and from a TV page's chip alike.
     */
    @Test
    fun aFilmFiledTwiceListsEveryStreamAndWhichOneItIsTheSameAs() {
        UiMappers.pictureSupport = PictureSupport()
        val shape = FiledTwice
        try {
            val held = TitleData.film(shape.state(), shape.again, shownTitle = shape.again.name)
            val played = TitleData.of(shape.state(), "${Fixtures.PLAYLIST_ID}|MOVIE|${shape.first.id}|", shownTitle = shape.first.name)!!
            assertEquals("Copper Vallee", held.lines.single { it.label == com.markedoe.ondo.R.string.title_data_shown }.value)
            assertEquals("COPPER VALLÉE", played.lines.single { it.label == com.markedoe.ondo.R.string.title_data_shown }.value)
            for (data in listOf(held, played)) {
                assertEquals(listOf(shape.first.id, shape.uhd.id, shape.again.id), data.versions.map { it.id })
                assertEquals(listOf("Standard", "4K Dolby Vision", "Standard"), data.versions.map { it.chip })
                assertEquals(listOf(null, null, shape.first.id), data.versions.map { it.sameAs })
            }
            compose.setContent { OndoTheme { TitleDataPanel(played, television = true) } }
            compose.onNodeWithText("Standard  ·  ▶ Playing  ·  ★ Plays first", useUnmergedTree = true).assertExists()
            compose.onNodeWithText("Standard  ·  = Same as ${shape.first.id}", useUnmergedTree = true).assertExists()
        } finally {
            UiMappers.forgetCatalogue()
        }
    }

    /** Every stream of a film one chip holds (no chip on its page): each still named, never "hidden", one "same as". */
    @Test
    fun aFilmOfOneChipNamesEachStream() {
        UiMappers.pictureSupport = PictureSupport()
        val shape = FiledTwice
        try {
            val alone = Fixtures.state(content = shape.content.copy(movies = shape.content.movies - shape.uhd))
            val data = TitleData.film(alone, shape.again, shownTitle = shape.again.name)
            assertEquals(listOf(shape.first.id, shape.again.id), data.versions.map { it.id })
            assertEquals(listOf("Standard", "Standard"), data.versions.map { it.chip })
            assertEquals(listOf(null, shape.first.id), data.versions.map { it.sameAs })
        } finally {
            UiMappers.forgetCatalogue()
        }
    }

    @Test
    fun thePanelReadsTheVersionsAndWhichPlays() {
        val data = TitleData.of(state(), "${Fixtures.PLAYLIST_ID}|MOVIE|${dv.id}|", shownTitle = "Copper Valley")!!
        compose.setContent { OndoTheme { TitleDataPanel(data, television = true) } }
        for (text in listOf("Title data", "Displayed title", "Versions", "mkv → MKV")) {
            compose.onNodeWithText(text, useUnmergedTree = true).assertExists()
        }
        compose.onNodeWithTag("titleDataVersion-${dv.id}", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("4K Dolby Vision  ·  ▶ Playing", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Standard · MP4  ·  ★ Plays first", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("titleDataReason", useUnmergedTree = true).assert(hasText("suits this screen best", substring = true))
    }

    /**
     * A long title data on a TV (five versions, the screen after them) in less room than it needs: it scrolls with the
     * remote. Down brings the last version listed, then the screen, on screen; Up goes back to the top. It was cut at
     * the bottom of the screen, its versions out of reach.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aLongTitleDataScrollsWithTheRemote() {
        val more = listOf(version("v104", "Copper Valley VOSTFR", "mkv"), version("v105", "Copper Valley MULTI", "mp4"))
        val long = state().let { it.copy(content = it.content.copy(movies = it.content.movies + more)) }
        val data = TitleData.of(long, "${Fixtures.PLAYLIST_ID}|MOVIE|${dv.id}|", shownTitle = "Copper Valley")!!
        assertTrue("more versions than the panel lists", data.versions.size > TitleData.PANEL_VERSIONS)
        compose.setContent { OndoTheme { Box(Modifier.height(320.dp)) { TitleDataPanel(data, television = true) } } }
        val tag = SemanticsProperties.TestTag
        val tags = compose.onAllNodes(SemanticsMatcher("a version") { it.config.getOrNull(tag)?.startsWith("titleDataVersion-") == true }, useUnmergedTree = true)
            .fetchSemanticsNodes().mapNotNull { node -> node.config.getOrNull(tag) }
        assertEquals(TitleData.PANEL_VERSIONS, tags.size)
        val last = compose.onNodeWithTag(tags.last(), useUnmergedTree = true)
        last.assertIsNotDisplayed()
        val panel = compose.onNodeWithTag("titleData")
        panel.requestFocus()
        fun shown(tag: String) = runCatching { compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed() }.isSuccess
        repeat(20) { if (!shown(tags.last())) panel.performKeyInput { pressKey(Key.DirectionDown) }.also { compose.waitForIdle() } }
        last.assertIsDisplayed()
        repeat(20) { if (!shown("titleDataChosen")) panel.performKeyInput { pressKey(Key.DirectionDown) }.also { compose.waitForIdle() } }
        compose.onNodeWithTag("titleDataChosen", useUnmergedTree = true).assertIsDisplayed()
        repeat(40) { panel.performKeyInput { pressKey(Key.DirectionUp) }; compose.waitForIdle() }
        compose.onNodeWithText("Title data", useUnmergedTree = true).assertIsDisplayed()
        last.assertIsNotDisplayed()
    }

    /** On the panel, the versions come before what the app read of the screen. */
    @Test
    fun thePanelListsTheVersionsBeforeTheScreen() {
        val data = TitleData.of(state(), "${Fixtures.PLAYLIST_ID}|MOVIE|${dv.id}|", shownTitle = "Copper Valley")!!
        compose.setContent { OndoTheme { TitleDataPanel(data, television = true) } }
        val versions = compose.onNodeWithText("Versions", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        val screen = compose.onNodeWithTag("titleDataScreen", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        assertTrue("versions at $versions, screen at $screen", versions < screen)
    }

    /** The screen block says where 4K came from and what the sound output takes as it is. */
    @Test
    fun theScreenSaysWhere4kComesFromAndWhatTheSoundOutputTakes() {
        val box = DisplayFacts.of(current = 1920 to 1080, modes = listOf(1920 to 1080), hdrTypes = listOf(android.view.Display.HdrCapabilities.HDR_TYPE_HDR10))
            .with(DisplayFacts.Screen(3840 to 2160, true, AudioOutput(listOf("AC-3", "E-AC-3"), 8)))
            .with(DisplayFacts.Decoders(dolbyVisionDecoder = false, hevcMain10Decoder = true, uhdDecoder = false))
        val data = TitleData.of(state(), "${Fixtures.PLAYLIST_ID}|MOVIE|${dv.id}|", shownTitle = "Copper Valley", display = box)!!
        assertTrue(box.pictureSupport().uhd)
        compose.setContent { OndoTheme { TitleDataPanel(data, television = true) } }
        compose.onNodeWithTag("titleDataUhd", useUnmergedTree = true).assert(hasText("4K: yes (display size) · display size 3840×2160"))
        compose.onNodeWithTag("titleDataAudioOutput", useUnmergedTree = true).assert(hasText("Sound output: up to 7.1 · as is: AC-3, E-AC-3"))
    }
}
