package com.markedoe.ondo.data

import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.core.model.Profile
import com.markedoe.ondo.ui.Labels
import com.markedoe.ondo.ui.UiMappers
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Viewer profiles (docs/PROFILES.md): the upgrade of an existing store, isolation, PIN, Kids, deletion, launch. */
class ProfilesTest {
    @get:Rule val folder = TemporaryFolder()

    private val clock = Fixtures.NOW

    /** A store as a version before profiles left it: playlists, a catalogue, settings and the one library. */
    private fun store(settings: AppSettings = AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID), library: LibraryState? = null): AppStore {
        val store = AppStore(folder.root)
        store.savePlaylists(listOf(Fixtures.playlist))
        store.saveContent(Fixtures.PLAYLIST_ID, Fixtures.content)
        store.saveSettings(settings)
        library?.let { store.saveLibrary(it) }
        return store
    }

    private fun TestScope.repository(store: AppStore = AppStore(folder.root)) =
        OndoRepository(store, FakeHttp(emptyMap()), backgroundScope, now = { clock }, io = StandardTestDispatcher(testScheduler), mainProfileName = "Me")

    private suspend fun OndoRepository.awaitState(condition: (OndoState) -> Boolean): OndoState = state.first(condition)

    private suspend fun TestScope.started(store: AppStore = store()): OndoRepository {
        val repository = repository(store)
        repository.bootstrap()
        repository.awaitState { it.isReady && it.content.movies.isNotEmpty() }
        return repository
    }

    /** Runs the pending saves and switches (the test's dispatcher), then reads the disk afresh. */
    private fun TestScope.disk(): AppStore {
        testScheduler.advanceUntilIdle()
        return AppStore(folder.root)
    }

    /** Switches to [profile]: the switch runs in the repository's scope, so the test waits for it. */
    private suspend fun switch(repository: OndoRepository, profile: Profile) {
        repository.switchProfile(profile.id)
        repository.awaitState { it.activeProfile.id == profile.id }
    }

    private fun key(movieId: String) = "${Fixtures.PLAYLIST_ID}|MOVIE|$movieId|"

    /** Plays [movieId] and leaves it at [position] of [duration] seconds. */
    private fun watch(repository: OndoRepository, movieId: String, position: Double, duration: Double = 6000.0) {
        val movie = repository.state.value.content.movies.first { it.id == movieId }
        assertNotNull(repository.playRequest(movie))
        repository.recordProgress(key(movieId), position, duration)
    }

    @Test
    fun anExistingStoreUpgradesToTheMainProfileWithNothingLost() = runTest {
        val before = LibraryState(
            favorites = listOf(Favorite(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, "1", "Northern Lights", addedAt = clock - 10)),
            history = listOf(WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, "2", title = "Glass City", position = 600.0, duration = 6000.0, lastWatchedAt = clock - 5)),
            hiddenCategories = setOf("${Fixtures.PLAYLIST_ID}|m-thriller"),
            hiddenTags = setOf("PT"),
            collections = listOf(UserCollection("c1", "Weekend", listOf(Favorite(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, "3", "Silent Harbor", addedAt = clock - 3)), updatedAt = clock - 3)),
        )
        val settings = AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID, hideWatchedMovies = true, liveBuffer = 4)
        val repository = started(store(settings, before))

        val state = repository.state.value
        assertEquals(listOf(Profile.DEFAULT_ID), state.profiles.map { it.id })
        assertEquals("Me", state.activeProfile.name)
        assertTrue(state.activeProfile.isMain)
        assertFalse(state.needsProfileSelection)
        assertEquals(before, state.library)
        assertTrue(state.settings.hideWatchedMovies)
        assertEquals(4, state.settings.liveBuffer)
        // On disk: the library keeps its document, untouched; the profiles document is added.
        val disk = disk()
        assertEquals(before, disk.loadLibrary())
        assertTrue("no profiles document until a profile is added", disk.loadProfiles().isEmpty())
        assertTrue(File(folder.root, "library.json").exists())
        assertTrue(folder.root.listFiles().orEmpty().none { it.name.startsWith("library-") })
        // And the next launch reads it all back the same.
        val again = started(AppStore(folder.root))
        assertEquals(before, again.state.value.library)
    }

    @Test
    fun watchingOnOneProfileLeavesTheOtherUntouchedOnEverySurface() = runTest {
        val repository = started(store(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID, hideWatchedMovies = true)))
        val main = repository.state.value.activeProfile
        // The main profile: a film half watched, another finished, one in My List, a collection.
        watch(repository, "2", 600.0)
        watch(repository, "7", 5990.0)
        repository.toggleFavorite(ItemKind.MOVIE, "1", "Northern Lights", null)
        repository.createCollection("Weekend")
        val mainLibrary = repository.state.value.library
        val other = requireNotNull(repository.addProfile("Sam", 3, "star.fill", isKids = false, pin = null))
        switch(repository, other)

        val labels = Labels()
        fun surfaces(state: OndoState) = listOf(
            UiMappers.home(state, labels, clock).continueWatching.map { it.key },
            UiMappers.home(state, labels, clock).myList.map { it.key },
            UiMappers.hiddenMovieIds(state).toList(),
            listOfNotNull(UiMappers.progress(state.library.progress(key("2"))), UiMappers.progress(state.library.progress(key("7")))),
        )
        val sam = repository.state.value
        assertEquals(LibraryState(), sam.library)
        // Continue Watching, My List, Hide watched (the setting is shared, the watched films are not), the badges.
        assertEquals(listOf(emptyList<Any>(), emptyList(), emptyList(), emptyList()), surfaces(sam))
        assertTrue(sam.settings.hideWatchedMovies)

        // Sam watches something else; the main profile does not get it.
        watch(repository, "3", 900.0)
        repository.toggleFavorite(ItemKind.MOVIE, "4", "The Last Signal", null)
        val samLibrary = repository.state.value.library
        switch(repository, main)
        val back = repository.state.value
        assertEquals(mainLibrary, back.library)
        assertNull(back.library.progress(key("3")))
        assertFalse(back.library.isFavorite(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, "4"))
        assertEquals(setOf("7"), UiMappers.hiddenMovieIds(back))
        assertTrue(UiMappers.home(back, labels, clock).continueWatching.isNotEmpty())

        // Each library is in its own document.
        val disk = disk()
        assertEquals(mainLibrary, disk.loadLibrary())
        assertEquals(samLibrary, disk.loadLibrary(other.libraryDocumentName))
    }

    @Test
    fun aChangeMadeJustBeforeASwitchIsSavedInTheProfileItWasMadeIn() = runTest {
        val repository = started()
        val other = requireNotNull(repository.addProfile("Sam", 1, Profile.INITIAL, isKids = false, pin = null))
        testScheduler.advanceUntilIdle()
        // No save has run when the switch is asked: the favourite is still the main profile's.
        repository.toggleFavorite(ItemKind.MOVIE, "1", "Northern Lights", null)
        switch(repository, other)
        val disk = disk()
        assertTrue(disk.loadLibrary().isFavorite(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, "1"))
        assertTrue(disk.loadLibrary(other.libraryDocumentName).favorites.isEmpty())
        assertTrue(repository.state.value.library.favorites.isEmpty())
    }

    @Test
    fun deletingAProfileRemovesItsLibraryAndNeverTheMainOne() = runTest {
        val repository = started()
        repository.toggleFavorite(ItemKind.MOVIE, "1", "Northern Lights", null)
        val other = requireNotNull(repository.addProfile("Sam", 1, Profile.INITIAL, isKids = false, pin = null))
        switch(repository, other)
        repository.toggleFavorite(ItemKind.MOVIE, "2", "Glass City", null)
        repository.setLaunchProfile(other.id)
        testScheduler.advanceUntilIdle()
        assertTrue(File(folder.root, "${other.libraryDocumentName}.json").exists())

        // The profile in use: the main one opens, with its library.
        repository.deleteProfile(other.id)
        repository.awaitState { state -> state.profiles.none { it.id == other.id } }
        val disk = disk()
        val state = repository.state.value
        assertEquals(listOf(Profile.DEFAULT_ID), state.profiles.map { it.id })
        assertTrue(state.activeProfile.isMain)
        assertNull(state.settings.launchProfileId)
        assertTrue(state.library.isFavorite(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, "1"))
        assertFalse(state.library.isFavorite(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, "2"))
        assertFalse(File(folder.root, "${other.libraryDocumentName}.json").exists())
        assertEquals(listOf(Profile.DEFAULT_ID), disk.loadProfiles().map { it.id })
        assertTrue(disk.loadLibrary().isFavorite(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, "1"))

        // The main profile stays, even with another profile left.
        repository.addProfile("Ana", 2, Profile.INITIAL, isKids = false, pin = null)
        repository.deleteProfile(Profile.DEFAULT_ID)
        testScheduler.advanceUntilIdle()
        assertTrue(repository.state.value.profiles.any { it.isMain })
        assertTrue(File(folder.root, "library.json").exists())
    }

    @Test
    fun deletingAPlaylistTakesItsEntriesOutOfEveryProfile() = runTest {
        val repository = started()
        repository.toggleFavorite(ItemKind.MOVIE, "1", "Northern Lights", null)
        val other = requireNotNull(repository.addProfile("Sam", 1, Profile.INITIAL, isKids = false, pin = null))
        switch(repository, other)
        repository.toggleFavorite(ItemKind.MOVIE, "2", "Glass City", null)
        testScheduler.advanceUntilIdle()

        repository.delete(Fixtures.PLAYLIST_ID)
        val disk = disk()
        assertTrue(disk.loadLibrary().favorites.isEmpty())
        assertTrue(disk.loadLibrary(other.libraryDocumentName).favorites.isEmpty())
    }

    @Test
    fun whoIsWatchingShowsAtLaunchWithSeveralProfilesUnlessOneIsAlwaysOpened() = runTest {
        val first = started()
        assertFalse(first.state.value.needsProfileSelection)
        val sam = requireNotNull(first.addProfile("Sam", 1, Profile.INITIAL, isKids = false, pin = null))
        testScheduler.advanceUntilIdle()

        val second = started(AppStore(folder.root))
        assertTrue(second.state.value.needsProfileSelection)
        switch(second, sam)
        assertFalse(second.state.value.needsProfileSelection)
        second.setLaunchProfile(sam.id)
        testScheduler.advanceUntilIdle()

        val third = started(AppStore(folder.root))
        assertFalse(third.state.value.needsProfileSelection)
        assertEquals(sam.id, third.state.value.activeProfile.id)
    }

    @Test
    fun aProfilePinIsCheckedAndWrongOnesMakeTheNextTryWait() = runTest {
        val repository = started()
        val sam = requireNotNull(repository.addProfile("Sam", 1, Profile.INITIAL, isKids = false, pin = "2468"))
        assertEquals(Profile.hashPin("2468"), sam.pinHash)
        // The PIN is kept as a hash only.
        assertFalse(File(folder.root, "profiles.json").also { disk() }.readText().contains("2468"))
        assertEquals(OndoRepository.PinCheck.OK, repository.checkPin(sam, "2468"))
        repeat(Profile.FREE_PIN_TRIES + 1) { assertEquals(OndoRepository.PinCheck.WRONG, repository.checkPin(sam, "0000")) }
        // Even the right one waits now.
        assertEquals(OndoRepository.PinCheck.WAIT, repository.checkPin(sam, "2468"))
        assertNotNull(repository.pinRetryAt())
    }

    @Test
    fun aKidsProfileNeverSeesAdultCategoriesAndItsSettingsAskAParent() = runTest {
        val repository = started(store(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID, showAdultCategories = true)))
        // The main profile shows the adult category: the setting is on.
        assertFalse(Fixtures.adultMovies.id in UiMappers.hiddenCategoryIds(repository.state.value))
        val kids = requireNotNull(repository.addProfile("Kids", 4, "pawprint.fill", isKids = true, pin = null))
        switch(repository, kids)
        val state = repository.state.value
        assertTrue(Fixtures.adultMovies.id in UiMappers.hiddenCategoryIds(state))
        assertTrue(UiMappers.vod(state, series = false, title = "Movies").categories.none { it.id == Fixtures.adultMovies.id })
        // Nobody can unlock (no PIN): the settings stay open, or nobody could leave the profile.
        assertFalse(state.needsParentForSettings)

        // Once the main profile has a PIN, the Kids profile's settings ask for it.
        repository.updateProfile(Profile.DEFAULT_ID, "Me", 0, Profile.INITIAL, isKids = false, pin = "1357", removePin = false)
        assertTrue(repository.state.value.needsParentForSettings)
        assertEquals(OndoRepository.PinCheck.WRONG, repository.unlockKidsSettings("0000"))
        assertTrue(repository.state.value.needsParentForSettings)
        assertEquals(OndoRepository.PinCheck.OK, repository.unlockKidsSettings("1357"))
        assertFalse(repository.state.value.needsParentForSettings)
        // Leaving the profile locks it again.
        val main = repository.state.value.profiles.first { it.isMain }
        switch(repository, main)
        switch(repository, kids)
        assertTrue(repository.state.value.needsParentForSettings)
    }

    @Test
    fun aKidsProfileSeesNoAdultLiveChannelNorZapsToOne() {
        val adultLive = MediaCategory("l-adult", "Adult XXX", ContentType.LIVE)
        val night = Channel(id = "c9", name = "Night Channel", number = 9, categoryId = adultLive.id, streamUrl = "http://example.com/live/c9.m3u8", epgId = "c9.test")
        val kids = Profile(name = "Kids", isKids = true)
        val content = Fixtures.content.copy(liveCategories = Fixtures.content.liveCategories + adultLive, channels = Fixtures.content.channels + night)
        val main = OndoState(
            isReady = true,
            settings = AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID, showAdultCategories = true),
            playlists = listOf(Fixtures.playlist),
            content = content,
            profiles = listOf(Profile.main("Me"), kids),
        )
        assertTrue(UiMappers.zappingChannels(main).any { it.id == night.id })
        val kidsState = main.copy(settings = main.settings.copy(activeProfileId = kids.id))
        assertTrue(UiMappers.visibleChannels(kidsState).none { it.id == night.id })
        assertTrue(UiMappers.zappingChannels(kidsState).none { it.id == night.id })
    }

    @Test
    fun onlyTheMainProfileSyncsForNow() = runTest {
        val repository = started()
        assertNotNull(repository.syncSnapshot("TV"))
        val sam = requireNotNull(repository.addProfile("Sam", 1, Profile.INITIAL, isKids = false, pin = null))
        switch(repository, sam)
        assertNull(repository.syncSnapshot("TV"))
        assertFalse(repository.state.value.syncsThisProfile)
    }
}
