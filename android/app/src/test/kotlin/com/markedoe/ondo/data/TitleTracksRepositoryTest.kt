package com.markedoe.ondo.data

import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.model.Profile
import com.markedoe.ondo.core.net.HttpClient
import com.markedoe.ondo.core.net.HttpException
import com.markedoe.ondo.core.playback.TrackDefaults
import com.markedoe.ondo.core.playback.TrackMemory
import com.markedoe.ondo.core.playback.TrackPick
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The sound and subtitles chosen for a film or a series, and each profile's languages (docs/TRACKS.md): kept in the
 * library of the profile playback belongs to, found again by the title's keys.
 */
@RunWith(RobolectricTestRunner::class)
class TitleTracksRepositoryTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val folder = TemporaryFolder()

    private val list = "http://provider.example.com/list.m3u"
    private val catalogue = """
        #EXTM3U
        #EXTINF:5400 group-title="Movies | Drama",Quiet Water (2021)
        http://provider.example.com/quiet.mp4
    """.trimIndent()

    private class Http(private val list: String, private val catalogue: String) : HttpClient {
        override suspend fun get(url: String, headers: Map<String, String>): ByteArray =
            if (url == list) catalogue.toByteArray() else throw HttpException.BadStatus(404)

        override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray, contentType: String): ByteArray =
            throw HttpException.BadStatus(404)
    }

    private suspend fun TestScope.repository(settings: AppSettings? = null): OndoRepository {
        val store = AppStore(folder.root)
        settings?.let(store::saveSettings)
        val repository = OndoRepository(store, Http(list, catalogue), backgroundScope, io = StandardTestDispatcher(testScheduler))
        repository.bootstrap()
        repository.state.first { it.isReady }
        assertTrue(repository.addPlaylist("Home", list).isSuccess)
        repository.state.first { it.content.movies.isNotEmpty() }
        return repository
    }

    private fun OndoRepository.seriesKeys(episode: String) =
        listOfNotNull(TrackMemory.titleKey("${state.value.activePlaylistId}|${ItemKind.SERIES.name}|s1|$episode"))

    @Test
    fun theTracksChosenOnEpisodeOneAreThoseOfEpisodeTwo() = runTest {
        val repository = repository()
        repository.rememberTracks(repository.seriesKeys("e1"), audio = TrackPick(language = "fr"))
        repository.rememberTracks(repository.seriesKeys("e1"), subtitles = TrackPick(language = "en"))
        val (memory, _) = repository.titleTracks(repository.seriesKeys("e2"))
        assertEquals("fr", memory?.audio?.language)
        assertEquals("en", memory?.subtitles?.language)
    }

    @Test
    fun aFilmStartedAgainKeepsItsTracksAndItsRequestCarriesItsVersions() = runTest {
        val repository = repository()
        val movie = repository.state.value.content.movies.first()
        val first = requireNotNull(repository.playRequest(movie))
        assertEquals(listOf("${repository.state.value.activePlaylistId}|MOVIE|${movie.id}"), first.trackKeys)
        repository.rememberTracks(first.trackKeys, audio = TrackPick(language = "fr"), subtitlesOff = true)
        repository.playbackEnded()
        val again = requireNotNull(repository.playRequest(movie, fromStart = true))
        val (memory, _) = repository.titleTracks(again.trackKeys)
        assertEquals("fr", memory?.audio?.language)
        assertTrue(memory!!.subtitlesOff)
        // Another version of the film: its request lists this one's key after its own, so the choice is found.
        val other = movie.copy(id = "${movie.id}-4k")
        val version = requireNotNull(repository.playRequest(other, versions = listOf(other, movie)))
        assertEquals(listOf("${repository.state.value.activePlaylistId}|MOVIE|${other.id}", first.trackKeys.single()), version.trackKeys)
        assertEquals("fr", repository.titleTracks(version.trackKeys).first?.audio?.language)
    }

    @Test
    fun withNothingChosenTheProfilesLanguagesApply() = runTest {
        val repository = repository()
        assertEquals(null to null, repository.titleTracks(repository.seriesKeys("e1")))
        repository.setTrackDefaults(TrackDefaults(audio = "es", subtitles = "fr"))
        assertEquals(null to TrackDefaults(audio = "es", subtitles = "fr"), repository.titleTracks(repository.seriesKeys("e1")))
        assertEquals(TrackDefaults(audio = "es", subtitles = "fr"), repository.trackDefaults())
    }

    @Test
    fun anotherProfileDoesNotInheritTheChoicesNorTheLanguages() = runTest {
        val repository = repository()
        repository.setTrackDefaults(TrackDefaults(audio = "fr"))
        repository.rememberTracks(repository.seriesKeys("e1"), audio = TrackPick(language = "fr"), subtitles = TrackPick(language = "en"))
        val guest = requireNotNull(repository.addProfile("Guest", 1, "star", isKids = false, pin = null)).id
        repository.switchProfile(guest)
        repository.state.first { it.activeProfile.id == guest }
        assertEquals(null to null, repository.titleTracks(repository.seriesKeys("e2")))
        assertEquals(TrackDefaults(), repository.trackDefaults())
        repository.switchProfile(Profile.DEFAULT_ID)
        repository.state.first { it.activeProfile.id == Profile.DEFAULT_ID }
        assertEquals("fr", repository.titleTracks(repository.seriesKeys("e2")).first?.audio?.language)
    }

    /** The languages the player learned on the device before each profile had its own are the main profile's, until it sets some. */
    @Test
    fun theLanguagesLearnedBeforeAreTheMainProfilesOnly() = runTest {
        val repository = repository(AppSettings(preferredAudioLanguage = "fr", preferredSubtitleLanguage = "en", subtitlesOn = true))
        assertEquals(TrackDefaults(audio = "fr", subtitles = "en"), repository.trackDefaults())
        repository.setTrackDefaults(TrackDefaults(audio = "de"))
        assertEquals(TrackDefaults(audio = "de"), repository.trackDefaults())
        val guest = requireNotNull(repository.addProfile("Guest", 1, "star", isKids = false, pin = null)).id
        repository.switchProfile(guest)
        repository.state.first { it.activeProfile.id == guest }
        assertNull(repository.titleTracks(repository.seriesKeys("e1")).second)
    }
}
