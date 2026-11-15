package com.markedoe.ondo.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.R
import com.markedoe.ondo.core.library.MeasuredSpecs
import com.markedoe.ondo.core.library.PictureSupport
import com.markedoe.ondo.core.model.MediaDetails
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.PictureRange
import com.markedoe.ondo.core.model.SpecSource
import com.markedoe.ondo.core.model.StreamSpecs
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.player.TitleData
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a film's version is made of, on its page: the facts line's badges after its format, its chip, and the title
 * data's line with where it comes from; measured by the player first, then the provider's details, then the name's
 * tags. Read from what is kept: never a request. Invented names and example.com only.
 */
@RunWith(AndroidJUnit4::class)
class StreamSpecsAppTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private fun version(id: String, name: String) = Movie(
        id = id, name = name, categoryId = Fixtures.drama.id, year = 2026, containerExtension = "mkv", xtreamStreamId = id.drop(1).toInt(),
        streamUrl = "http://stream.example.com/movie/${id.drop(1)}.mkv",
    )

    // Two versions the names call the same, and a 4K Dolby Vision one.
    private val plain = version("v201", "Sand Harbor")
    private val again = version("v202", "Sand Harbor")
    private val dv = version("v203", "Sand Harbor 4K DV")

    private val provider1080 = StreamSpecs(width = 1920, height = 802, range = PictureRange.SDR, videoCodec = "H.264", audioCodec = "AAC", channels = 6)
    private val measured4k = StreamSpecs(width = 3840, height = 2160, range = PictureRange.DOLBY_VISION, videoCodec = "HEVC", audioCodec = "E-AC-3", channels = 6, atmos = true)

    private fun state(details: Map<String, MediaDetails> = emptyMap(), measured: Map<String, StreamSpecs> = emptyMap()): OndoState {
        UiMappers.pictureSupport = PictureSupport()
        return Fixtures.state(
            content = Fixtures.content.copy(movies = Fixtures.content.movies + listOf(plain, again, dv)),
            settings = AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID, groupVersions = true),
        ).copy(movieDetails = details, measuredSpecs = measured.mapKeys { (id, _) -> MeasuredSpecs.key(Fixtures.PLAYLIST_ID, id) })
    }

    @After
    fun forget() {
        UiMappers.forgetCatalogue()
        UiMappers.pictureSupport = PictureSupport()
    }

    @Test
    fun theFactsLineShowsWhatTheVersionThatPlaysIsMadeOf() {
        // Nothing known: the name's tags only (and nothing for a plain name).
        val bare = UiMappers.movieDetail(state(), plain, Locale.US, selected = dv.id)
        assertEquals(listOf("4K", "Dolby Vision"), bare.specBadges)
        assertEquals("4K, Dolby Vision", bare.specDescription)
        assertEquals(emptyList<String>(), UiMappers.movieDetail(state(), plain, Locale.US, selected = plain.id).specBadges)

        // The provider's details: a film in scope is 1080p.
        val known = state(details = mapOf(plain.id to MediaDetails(title = "Sand Harbor", streamSpecs = provider1080)))
        val page = UiMappers.movieDetail(known, plain, Locale.US, selected = plain.id)
        assertEquals(listOf("1080p", "H.264", "5.1 AAC"), page.specBadges)
        assertEquals("1080p, H.264, 5.1 surround sound, AAC", page.specDescription)

        // Measured by the player: first, field by field over the provider's.
        val played = state(
            details = mapOf(dv.id to MediaDetails(title = "Sand Harbor", streamSpecs = StreamSpecs(bitrate = 18000, audioCodec = "AC-3", channels = 6))),
            measured = mapOf(dv.id to StreamSpecs(width = 3840, height = 1608, range = PictureRange.HDR10, videoCodec = "HEVC", source = SpecSource.MEASURED)),
        )
        val measured = UiMappers.movieDetail(played, dv, Locale.US, selected = dv.id)
        assertEquals("measured HDR10 over the name's Dolby Vision", listOf("4K", "HDR10", "HEVC", "5.1 AC-3"), measured.specBadges)
        assertEquals(SpecSource.MEASURED, UiMappers.specSource(played, dv.id))
        assertEquals(SpecSource.PROVIDER, UiMappers.specSource(known, plain.id))
        assertNull(UiMappers.specSource(known, again.id))
    }

    /** Another playlist's stream of the same id is another stream: its measured specs are not this one's. */
    @Test
    fun measuredSpecsBelongToTheirPlaylist() {
        val other = state().copy(measuredSpecs = mapOf(MeasuredSpecs.key("another", dv.id) to measured4k))
        assertNull(UiMappers.streamSpecs(other, dv.id))
    }

    @Test
    fun theChipsSayTheSpecsAndKeepVersionsWhoseSpecsDifferApart() {
        val words = com.markedoe.ondo.core.library.VersionWords()
        // Nothing known: the two plain versions are one chip.
        assertEquals(listOf("Standard", "4K Dolby Vision"), UiMappers.movieDetail(state(), plain, Locale.US).versions.map { it.label })
        // Measured 4K Dolby Vision on one of them, the provider's 1080p on the other: three chips.
        val known = state(
            details = mapOf(plain.id to MediaDetails(title = "Sand Harbor", streamSpecs = provider1080)),
            measured = mapOf(again.id to measured4k),
        )
        val chips = UiMappers.versionChips(known, UiMappers.visibleVersions(known, plain), words)
        assertEquals(listOf("1080p · 5.1", "4K Dolby Vision · Atmos", "4K Dolby Vision"), chips.map { it.label })
        assertEquals(listOf(listOf(plain.id), listOf(again.id), listOf(dv.id)), chips.map { it.members })
    }

    @Test
    fun theTitleDataSaysEachVersionsSpecsAndWhereTheyComeFrom() {
        val known = state(
            details = mapOf(plain.id to MediaDetails(title = "Sand Harbor", streamSpecs = provider1080)),
            measured = mapOf(again.id to measured4k),
        )
        val data = TitleData.film(known, plain, shownTitle = "Sand Harbor")
        assertEquals(listOf(plain.id, again.id, dv.id), data.versions.map { it.id })
        assertEquals(
            listOf("1920×802 · 1080p · SDR · H.264 · 5.1 AAC", "3840×2160 · 4K · Dolby Vision · HEVC · 5.1 E-AC-3 · Atmos", "4K · Dolby Vision"),
            data.versions.map { it.specs },
        )
        assertEquals(
            listOf(R.string.title_data_specs_provider, R.string.title_data_specs_measured, R.string.title_data_specs_name),
            data.versions.map { it.specSource },
        )
        assertEquals("1920×802 · 1080p · SDR · H.264 · 5.1 AAC", data.lines.single { it.label == R.string.title_data_specs_provider }.value)
    }
}
