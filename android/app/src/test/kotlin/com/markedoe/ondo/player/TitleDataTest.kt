package com.markedoe.ondo.player

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        data.lines.map { it.value } + data.versions.flatMap { listOfNotNull(it.chip, it.name, it.category, it.id, it.container, it.tags) }

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
        assertEquals(episode.id, lines["episode_id"])
        assertEquals("S01E01", lines["episode"])
        assertEquals("MP4", lines["container_extension"])
        assertTrue(data.versions.isEmpty())
    }

    @Test
    fun aChannelHasNoTitleData() {
        assertNull(TitleData.of(state(), "${Fixtures.PLAYLIST_ID}|CHANNEL|c1|", shownTitle = "News"))
        assertNull(TitleData.of(state(), null, shownTitle = "News"))
        assertNull(TitleData.of(state(), "${Fixtures.PLAYLIST_ID}|MOVIE|missing|", shownTitle = "Gone"))
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
}
