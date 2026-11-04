package com.markedoe.ondo.data

import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.core.model.Profile
import com.markedoe.ondo.core.sync.SyncFavorite
import com.markedoe.ondo.core.sync.SyncProfile
import com.markedoe.ondo.core.sync.SyncProfileData
import com.markedoe.ondo.core.sync.SyncProfiles
import com.markedoe.ondo.core.sync.SyncProgress
import com.markedoe.ondo.core.sync.SyncRows
import com.markedoe.ondo.core.sync.SyncSnapshot
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
import com.markedoe.ondo.HangGuard

/** Viewer profiles (docs/PROFILES.md): the upgrade of an existing store, isolation, PIN, Kids, deletion, launch. */
class ProfilesTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()
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

    private fun TestScope.repository(store: AppStore = AppStore(folder.root), reminders: ReminderScheduler = NoReminderScheduler) =
        OndoRepository(store, FakeHttp(emptyMap()), backgroundScope, now = { clock }, io = StandardTestDispatcher(testScheduler), mainProfileName = "Me", reminderScheduler = reminders)

    private suspend fun OndoRepository.awaitState(condition: (OndoState) -> Boolean): OndoState = state.first(condition)

    private suspend fun TestScope.started(store: AppStore = store(), reminders: ReminderScheduler = NoReminderScheduler): OndoRepository {
        val repository = repository(store, reminders)
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
        // Everything kept; the hidden category and language only gain the time sync needs (docs/PROFILES.md, "Migration").
        assertEquals(before, state.library.copy(hiddenAt = emptyMap()))
        assertEquals(setOf(SyncKeys.hiddenCategory(Fixtures.PLAYLIST_ID, "m-thriller"), SyncKeys.hiddenTag("PT")), state.library.hiddenAt.keys)
        assertTrue(state.settings.hideWatchedMovies)
        assertEquals(4, state.settings.liveBuffer)
        // On disk: the library keeps its document, with those times; the profiles document is added.
        val disk = disk()
        assertEquals(state.library, disk.loadLibrary())
        assertTrue("no profiles document until a profile is added", disk.loadProfiles().isEmpty())
        assertTrue(File(folder.root, "library.json").exists())
        assertTrue(folder.root.listFiles().orEmpty().none { it.name.startsWith("library-") })
        // And the next launch reads it all back the same, dating nothing again.
        val again = started(AppStore(folder.root))
        assertEquals(state.library, again.state.value.library)
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

    // MARK: Sync (docs/SYNC.md, "Profiles")

    /**
     * A device of its own: its store in [name], the same playlist and catalogue; [libraries] written before it starts, by
     * profile (as an earlier version left them), with [profiles] besides the main one.
     */
    private suspend fun TestScope.device(
        name: String,
        showAdult: Boolean = false,
        profiles: List<Profile> = emptyList(),
        libraries: Map<Profile, LibraryState> = emptyMap(),
    ): OndoRepository {
        val directory = folder.newFolder(name)
        val store = AppStore(directory)
        store.savePlaylists(listOf(Fixtures.playlist))
        store.saveContent(Fixtures.PLAYLIST_ID, Fixtures.content)
        store.saveSettings(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID, syncDeviceId = name, showAdultCategories = showAdult))
        if (profiles.isNotEmpty()) store.saveProfiles(listOf(Profile.main("Me")) + profiles)
        libraries.forEach { (profile, library) -> store.saveLibrary(library, profile.libraryDocumentName) }
        val repository = OndoRepository(store, FakeHttp(emptyMap()), backgroundScope, now = { clock }, io = StandardTestDispatcher(testScheduler), mainProfileName = "Me")
        repository.bootstrap()
        repository.awaitState { it.isReady && it.content.movies.isNotEmpty() }
        return repository
    }

    /** A session both ways, as two devices on the network have. */
    private fun TestScope.session(a: OndoRepository, b: OndoRepository) {
        requireNotNull(b.mergeSync(requireNotNull(a.syncSnapshot("A")), "B"))
        requireNotNull(a.mergeSync(requireNotNull(b.syncSnapshot("B")), "A"))
        testScheduler.advanceUntilIdle()
    }

    private fun OndoRepository.profile(name: String) = state.value.profiles.firstOrNull { it.name == name }

    @Test
    fun everyProfileSyncsWhicheverIsOpen() = runTest {
        val repository = started()
        val sam = requireNotNull(repository.addProfile("Sam", 1, Profile.INITIAL, isKids = false, pin = null))
        switch(repository, sam)
        watch(repository, "3", 900.0)
        val snapshot = requireNotNull(repository.syncSnapshot("TV"))
        assertTrue(snapshot.profiles.any { it.id == sam.id && it.name == "Sam" })
        assertEquals(listOf("3"), snapshot.profileData.single { it.profile == sam.id }.progress.map { it.item })
        assertTrue("the main profile's records are the snapshot's own", snapshot.progress.isEmpty())
    }

    @Test
    fun twoDevicesAddAndEditDifferentProfilesAndConverge() = runTest {
        val tv = device("tv")
        val phone = device("phone")
        val sam = requireNotNull(tv.addProfile("Sam", 1, "star.fill", isKids = false, pin = "2468"))
        val ana = requireNotNull(phone.addProfile("Ana", 2, "🦊", isKids = true, pin = null))
        switch(tv, sam)
        watch(tv, "3", 900.0)
        session(tv, phone)
        for (device in listOf(tv, phone)) assertEquals(setOf("Me", "Sam", "Ana"), device.state.value.profiles.map { it.name }.toSet())
        // The PIN stays on the device where it was set.
        assertTrue(tv.profile("Sam")!!.isProtected)
        assertFalse(phone.profile("Sam")!!.isProtected)
        assertTrue(phone.profile("Ana")!!.isKids)
        // Sam's watch reaches Sam on the phone, never the phone's main profile nor Ana.
        assertTrue(phone.state.value.library.history.isEmpty())
        switch(phone, phone.profile("Sam")!!)
        assertNotNull(phone.state.value.library.progress(key("3")))
        switch(phone, phone.profile("Ana")!!)
        assertTrue(phone.state.value.library.history.isEmpty())
        // A rename on the phone reaches the TV.
        phone.updateProfile(sam.id, "Samuel", 1, "star.fill", isKids = false, pin = null, removePin = false)
        session(tv, phone)
        assertEquals("Samuel", tv.profile("Samuel")?.name)
        assertTrue("its PIN kept on the TV", tv.profile("Samuel")!!.isProtected)
        assertEquals(ana.id, tv.profile("Ana")?.id)
    }

    @Test
    fun aProfileDeletedOnOneDeviceDisappearsOnTheOtherWithItsData() = runTest {
        val tv = device("tv")
        val phone = device("phone")
        val sam = requireNotNull(tv.addProfile("Sam", 1, Profile.INITIAL, isKids = false, pin = null))
        val ana = requireNotNull(tv.addProfile("Ana", 2, Profile.INITIAL, isKids = false, pin = null))
        switch(tv, sam)
        watch(tv, "3", 900.0)
        switch(tv, ana)
        watch(tv, "4", 600.0)
        session(tv, phone)
        val before = requireNotNull(phone.syncSnapshot("B"))
        // Deleted on the phone while it is open there: the main profile opens.
        switch(phone, phone.profile("Sam")!!)
        phone.deleteProfile(sam.id)
        assertTrue(phone.state.value.activeProfile.isMain)
        session(tv, phone)
        for (device in listOf(tv, phone)) assertEquals(setOf("Me", "Ana"), device.state.value.profiles.map { it.name }.toSet())
        assertFalse(File(folder.root, "tv/library-${sam.id}.json").exists())
        // An older snapshot of the phone, with Sam and his data, brings nothing back.
        tv.mergeSync(before, "B")
        testScheduler.advanceUntilIdle()
        assertNull(tv.profile("Sam"))
        // The other profiles are untouched: Ana keeps her watch.
        switch(tv, tv.profile("Ana")!!)
        assertNotNull(tv.state.value.library.progress(key("4")))
    }

    @Test
    fun anOlderDeviceWithoutProfilesMapsToTheMainProfile() = runTest {
        val tv = device("tv")
        val sam = requireNotNull(tv.addProfile("Sam", 1, Profile.INITIAL, isKids = false, pin = null))
        switch(tv, sam)
        watch(tv, "3", 900.0)
        val playlist = requireNotNull(tv.syncSnapshot("A")).playlists.single()
        val older = SyncSnapshot(
            device = "old",
            playlists = listOf(playlist),
            favorites = listOf(SyncFavorite(playlist.id, "movie", "1", "Northern Lights", addedAt = clock - 1)),
        )
        tv.mergeSync(older, "Old")
        testScheduler.advanceUntilIdle()
        assertEquals(setOf("Me", "Sam"), tv.state.value.profiles.map { it.name }.toSet())
        // Sam (open) keeps his own data; the older device's favourite is the main profile's.
        assertFalse(tv.state.value.library.isFavorite(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, "1"))
        assertNotNull(tv.state.value.library.progress(key("3")))
        switch(tv, tv.profile("Me")!!)
        assertTrue(tv.state.value.library.isFavorite(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, "1"))
        assertNull(tv.state.value.library.progress(key("3")))
    }

    @Test
    fun eachProfileHasItsOwnRowOrderAndAKidsProfileCannotChangeIt() = runTest {
        val moved = SyncRows(SyncRows.MOVIES, listOf("all", "continue"), updatedAt = 1234)
        // The order saved before profiles (the device's) becomes the main profile's, with its time.
        val repository = started(store(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID, rowOrders = listOf(moved))))
        assertEquals(listOf(moved), repository.state.value.library.rowOrders)
        assertTrue(disk().loadSettings().rowOrders.isEmpty())
        assertEquals(listOf(moved), AppStore(folder.root).loadLibrary().rowOrders)
        val sam = requireNotNull(repository.addProfile("Sam", 1, Profile.INITIAL, isKids = false, pin = null))
        val kids = requireNotNull(repository.addProfile("Kids", 4, Profile.INITIAL, isKids = true, pin = null))
        switch(repository, sam)
        assertNull(repository.state.value.library.rowOrder(series = false))
        repository.setRowOrder(series = false, arranged = listOf("continue", "all"), hidden = emptyList())
        assertEquals(listOf("continue", "all"), repository.state.value.library.rowOrder(series = false)?.order)
        switch(repository, kids)
        repository.setRowOrder(series = false, arranged = listOf("all", "continue"), hidden = listOf("all"))
        assertNull(repository.state.value.library.rowOrder(series = false))
        switch(repository, repository.profile("Me")!!)
        assertEquals(moved, repository.state.value.library.rowOrder(series = false))
        // Sam's order travels in Sam's data; the main one's as the snapshot's own.
        val snapshot = requireNotNull(repository.syncSnapshot("TV"))
        assertEquals(listOf(moved), snapshot.rows)
        assertEquals(listOf("continue", "all"), snapshot.profileData.single { it.profile == sam.id }.rows.single().order)
    }

    // MARK: Review fixes

    /** S5: a film keeps saving into the profile it started in, after a switch; once that profile is gone, nowhere. */
    @Test
    fun aPlaybackSavesIntoTheProfileItStartedInWhicheverIsOpen() = runTest {
        val repository = started()
        val main = repository.state.value.activeProfile
        watch(repository, "3", 600.0)
        val sam = requireNotNull(repository.addProfile("Sam", 1, Profile.INITIAL, isKids = false, pin = null))
        switch(repository, sam)
        watch(repository, "3", 900.0)
        repository.playbackStarted(key("3"))
        // Another profile opens while the film plays (picture in picture): the next saves are still Sam's.
        switch(repository, main)
        repository.recordProgress(key("3"), 3_000.0, 6_000.0)
        assertEquals(600.0, repository.state.value.library.progress(key("3"))!!.position, 0.0)
        switch(repository, sam)
        assertEquals(3_000.0, repository.state.value.library.progress(key("3"))!!.position, 0.0)
        switch(repository, main)
        // Sam deleted while the film still plays: its saves go nowhere, never into the main profile.
        repository.deleteProfile(sam.id)
        repository.recordProgress(key("3"), 4_000.0, 6_000.0)
        repository.playbackEnded()
        assertEquals(600.0, repository.state.value.library.progress(key("3"))!!.position, 0.0)
        assertEquals(600.0, disk().loadLibrary().progress(key("3"))!!.position, 0.0)
    }

    /** S2: a profile deleted on one device while another kept watching in it later stays, with that watch. */
    @Test
    fun aProfileDeletedWhileAnotherDeviceKeptWatchingInItStays() = runTest {
        val phone = device("phone")
        val sam = requireNotNull(phone.addProfile("Sam", 1, Profile.INITIAL, isKids = false, pin = null))
        val before = requireNotNull(phone.syncSnapshot("B"))
        phone.deleteProfile(sam.id)
        val deletedAt = requireNotNull(requireNotNull(phone.syncSnapshot("B")).deleted[SyncProfile.KEY_PREFIX + sam.id])
        // The TV, not yet told, watched in Sam after that.
        val playlist = before.playlists.single()
        val watch = SyncProgress(playlist.id, "movie", "3", title = "Silent Harbor", position = 900.0, duration = 6_000.0, watchedAt = deletedAt + 1_000)
        val tv = before.copy(device = "tv", profileData = listOf(SyncProfileData(sam.id, progress = listOf(watch))))
        phone.mergeSync(tv, "TV")
        testScheduler.advanceUntilIdle()
        assertNotNull(phone.profile("Sam"))
        switch(phone, phone.profile("Sam")!!)
        assertNotNull(phone.state.value.library.progress(key("3")))
    }

    /** S3: Kids lifted on a device without a parent PIN stays on where a parent set one, and sessions settle. */
    @Test
    fun kidsLiftedOnAnUnprotectedDeviceStaysOnWhereAParentSetAPin() = runTest {
        val tv = device("tv")
        val phone = device("phone")
        tv.updateProfile(Profile.DEFAULT_ID, "Me", 0, Profile.INITIAL, isKids = false, pin = "1357", removePin = false)
        val kid = requireNotNull(tv.addProfile("Kid", 4, Profile.INITIAL, isKids = true, pin = null))
        session(tv, phone)
        assertTrue(phone.profile("Kid")!!.isKids)
        phone.updateProfile(kid.id, "Kid", 4, Profile.INITIAL, isKids = false, pin = null, removePin = false)
        session(tv, phone)
        assertFalse(phone.profile("Kid")!!.isKids)
        assertTrue("a parent set a PIN on the TV", tv.profile("Kid")!!.isKids)
        // Nothing changes from one session to the next.
        val tvProfiles = tv.state.value.profiles
        val tvDigest = requireNotNull(tv.syncSnapshot("A")).contentDigest()
        session(tv, phone)
        assertEquals(tvProfiles, tv.state.value.profiles)
        assertEquals(tvDigest, requireNotNull(tv.syncSnapshot("A")).contentDigest())
        // Made Kids again elsewhere: it applies everywhere.
        phone.updateProfile(kid.id, "Kid", 4, Profile.INITIAL, isKids = true, pin = null, removePin = false)
        session(tv, phone)
        assertTrue(phone.profile("Kid")!!.isKids)
        assertTrue(tv.profile("Kid")!!.isKids)
    }

    /** S1: "Sam" made apart on two devices becomes one profile, the smaller ID, with the data of both. */
    @Test
    fun profilesOfTheSameNameMadeApartBecomeOne() = runTest {
        val tv = device("tv")
        val phone = device("phone")
        val onTv = requireNotNull(tv.addProfile("Sam", 1, Profile.INITIAL, isKids = false, pin = null))
        val onPhone = requireNotNull(phone.addProfile(" sam", 2, "star.fill", isKids = false, pin = "2468"))
        switch(tv, onTv)
        watch(tv, "3", 900.0)
        switch(phone, onPhone)
        phone.toggleFavorite(ItemKind.MOVIE, "4", "The Last Signal", null)
        session(tv, phone)
        session(tv, phone)
        val kept = minOf(onTv.id.lowercase(), onPhone.id.lowercase())
        for (device in listOf(tv, phone)) {
            val sams = device.state.value.profiles.filter { SyncProfiles.normalizedName(it.name) == "sam" }
            assertEquals(listOf(kept), sams.map { it.id.lowercase() })
            switch(device, sams.single())
            assertNotNull(device.state.value.library.progress(key("3")))
            assertTrue(device.state.value.library.isFavorite(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, "4"))
        }
        // The phone's PIN stays on the phone.
        assertTrue(phone.state.value.profiles.single { it.id.lowercase() == kept }.isProtected)
    }

    /** S6: a deletion in a profile learned from another device is dated when it was learned, as the main profile's. */
    @Test
    fun aDeletionInAProfileLearnedFromAnotherDeviceIsDatedWhenLearned() = runTest {
        val tv = device("tv")
        val sam = requireNotNull(tv.addProfile("Sam", 1, Profile.INITIAL, isKids = false, pin = null))
        val snapshot = requireNotNull(tv.syncSnapshot("A"))
        val removed = "favorite:${Fixtures.PLAYLIST_ID}|movie|9"
        tv.mergeSync(snapshot.copy(device = "phone", profileData = listOf(SyncProfileData(sam.id, deleted = mapOf(removed to clock - 300L * 86_400_000)))), "Phone")
        testScheduler.advanceUntilIdle()
        switch(tv, sam)
        assertTrue(removed in tv.state.value.library.syncDeletedLearned)
        // Old as it is, it still travels: it was learned now.
        assertTrue(removed in requireNotNull(tv.syncSnapshot("A")).profileData.single { it.profile == sam.id }.deleted)
    }

    /** Reminders of every profile go with their playlist, and those of a deleted profile with it. */
    @Test
    fun everyProfilesRemindersGoWithTheirPlaylistOrTheirProfile() = runTest {
        val sam = Profile(id = "11111111-1111-4111-8111-111111111111", name = "Sam", updatedAt = 1)
        val ana = Profile(id = "33333333-3333-4333-8333-333333333333", name = "Ana", updatedAt = 1)
        val store = store()
        store.saveProfiles(listOf(Profile.main("Me"), sam, ana))
        fun reminder(title: String) = Reminder(Fixtures.PLAYLIST_ID, "c1", "Ondo News", title, clock + 3_600_000, clock + 7_200_000)
        store.saveLibrary(LibraryState(reminders = listOf(reminder("Sam's"))), sam.libraryDocumentName)
        store.saveLibrary(LibraryState(reminders = listOf(reminder("Ana's"))), ana.libraryDocumentName)
        val cancelled = mutableListOf<String>()
        val scheduler = object : ReminderScheduler {
            override fun schedule(reminder: Reminder, now: Long) = Unit
            override fun cancel(reminder: Reminder) { cancelled += reminder.title }
        }
        val repository = started(store, scheduler)
        repository.deleteProfile(ana.id)
        assertEquals(listOf("Ana's"), cancelled)
        repository.delete(Fixtures.PLAYLIST_ID)
        assertEquals(listOf("Ana's", "Sam's"), cancelled)
    }

    // MARK: Hidden categories in sync (docs/SYNC.md, "Hidden categories")

    private fun OndoState.hides() = Triple(library.hiddenCategories, library.hiddenTags, library.hiddenWords)

    private val nothingHidden = Triple(emptySet<String>(), emptySet<String>(), emptySet<String>())

    /**
     * Categories, a language and a style hidden in a profile on the TV are hidden in that profile on the phone, and only
     * there; shown again on the phone, they are shown again on the TV; hidden again later, hidden again.
     */
    @Test
    fun hiddenCategoriesReachTheSameProfileOnTheOtherDeviceAndShowingAgainFollows() = runTest {
        val tv = device("tv")
        val phone = device("phone")
        val sam = requireNotNull(tv.addProfile("Sam", 1, Profile.INITIAL, isKids = false, pin = null))
        session(tv, phone)
        val drama = Fixtures.drama.id
        switch(tv, sam)
        tv.setCategoryHidden(drama, hidden = true)
        tv.setTagsHidden(listOf("PT"), hidden = true)
        tv.setWordHidden("manga", hidden = true)
        session(tv, phone)
        // The phone's main profile (open) hides nothing; its Sam hides them all.
        assertEquals(nothingHidden, phone.state.value.hides())
        switch(phone, phone.profile("Sam")!!)
        assertEquals(Triple(setOf("${Fixtures.PLAYLIST_ID}|$drama"), setOf("PT"), setOf("manga")), phone.state.value.hides())
        assertTrue(drama in UiMappers.hiddenCategoryIds(phone.state.value))
        // Shown again on the phone: shown again on the TV; the style stays hidden.
        phone.setCategoryHidden(drama, hidden = false)
        phone.setTagsHidden(listOf("PT"), hidden = false)
        session(tv, phone)
        assertEquals(Triple(emptySet<String>(), emptySet<String>(), setOf("manga")), tv.state.value.hides())
        assertFalse(drama in UiMappers.hiddenCategoryIds(tv.state.value))
        // Hidden again on the TV, after that: hidden again on the phone.
        tv.setTagsHidden(listOf("PT"), hidden = true)
        session(tv, phone)
        assertEquals(setOf("PT"), phone.state.value.library.hiddenTags)
        // The main profile never hid anything, on either device.
        switch(tv, tv.profile("Me")!!)
        switch(phone, phone.profile("Me")!!)
        assertEquals(nothingHidden, tv.state.value.hides())
        assertEquals(nothingHidden, phone.state.value.hides())
    }

    /**
     * Hidden sets kept before they synced have no time: the first launch after the update dates them, after everything the
     * device holds, and saves them. At the first session, two devices' sets add up in each profile: nothing is shown again.
     */
    @Test
    fun hiddenSetsKeptBeforeTheySyncedAddUpAtTheFirstSession() = runTest {
        val sam = Profile(id = "c3000000-0000-4000-8000-00000000000c", name = "Sam", symbol = Profile.INITIAL, updatedAt = clock - 10)
        val main = Profile.main("Me")
        val p = Fixtures.PLAYLIST_ID
        val tv = device(
            "tv", profiles = listOf(sam),
            libraries = mapOf(
                main to LibraryState(hiddenCategories = setOf("$p|${Fixtures.drama.id}"), hiddenTags = setOf("PT")),
                sam to LibraryState(hiddenWords = setOf("manga")),
            ),
        )
        val phone = device(
            "phone", profiles = listOf(sam),
            libraries = mapOf(
                main to LibraryState(hiddenCategories = setOf("$p|${Fixtures.sciFi.id}"), hiddenTags = setOf("ES")),
                sam to LibraryState(hiddenWords = setOf("anime"), hiddenTags = setOf("PT")),
            ),
        )
        // Dated at launch, and saved so.
        testScheduler.advanceUntilIdle()
        for (name in listOf("tv", "phone")) {
            val store = AppStore(File(folder.root, name))
            for (library in listOf(store.loadLibrary(), store.loadLibrary(sam.libraryDocumentName))) {
                assertFalse("$name: ${library.hiddenAt}", library.hasUndatedHidden())
                assertTrue(library.hiddenAt.values.all { it >= clock })
            }
        }
        session(tv, phone)
        session(tv, phone)
        for (device in listOf(tv, phone)) {
            switch(device, device.profile("Me")!!)
            assertEquals(Triple(setOf("$p|${Fixtures.drama.id}", "$p|${Fixtures.sciFi.id}"), setOf("PT", "ES"), emptySet<String>()), device.state.value.hides())
            switch(device, device.profile("Sam")!!)
            assertEquals(Triple(emptySet<String>(), setOf("PT"), setOf("manga", "anime")), device.state.value.hides())
        }
    }

    /** An adult-looking category shown again by sync stays out of a Kids profile, whatever sync says (docs/PROFILES.md). */
    @Test
    fun aCategoryShownAgainBySyncStaysOutOfAKidsProfileWhenItLooksAdult() = runTest {
        val tv = device("tv", showAdult = true)
        val phone = device("phone", showAdult = true)
        val kids = requireNotNull(tv.addProfile("Kids", 4, Profile.INITIAL, isKids = true, pin = null))
        switch(tv, kids)
        tv.setCategoryHidden(Fixtures.adultMovies.id, hidden = true)
        session(tv, phone)
        switch(phone, phone.profile("Kids")!!)
        phone.setCategoryHidden(Fixtures.adultMovies.id, hidden = false)
        session(tv, phone)
        val state = tv.state.value
        assertFalse("shown again, as sync says", state.library.isCategoryHidden(Fixtures.PLAYLIST_ID, Fixtures.adultMovies.id))
        assertTrue("still out: it looks adult", Fixtures.adultMovies.id in UiMappers.hiddenCategoryIds(state))
        assertTrue(UiMappers.vod(state, series = false, title = "Movies").categories.none { it.id == Fixtures.adultMovies.id })
    }

    /** Sync carries 16 profiles at most: a 17th cannot be made. */
    @Test
    fun noMoreThanSixteenProfiles() = runTest {
        val repository = started()
        repeat(Profile.MAX_PROFILES - 1) { assertNotNull(repository.addProfile("P$it", 0, Profile.INITIAL, isKids = false, pin = null)) }
        assertNull(repository.addProfile("One too many", 0, Profile.INITIAL, isKids = false, pin = null))
        assertEquals(Profile.MAX_PROFILES, repository.state.value.profiles.size)
    }
}
