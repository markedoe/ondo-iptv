package com.markedoe.ondo.data

import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.model.Profile
import com.markedoe.ondo.core.sync.SyncList
import com.markedoe.ondo.ui.UiMappers
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

/**
 * Collections shared with every profile (docs/PROFILES.md, "Shared lists"): sharing and taking back, what each profile
 * sees and may change, a Kids profile, and sync between two devices.
 */
class SharedCollectionsTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()
    @get:Rule val folder = TemporaryFolder()

    private val clock = Fixtures.NOW

    /** A device of its own: its store in [name], the same playlist and catalogue. */
    private suspend fun TestScope.device(name: String): OndoRepository {
        val directory = java.io.File(folder.root, name).apply { mkdirs() }
        val store = AppStore(directory)
        if (store.loadPlaylists().isEmpty()) {
            store.savePlaylists(listOf(Fixtures.playlist))
            store.saveContent(Fixtures.PLAYLIST_ID, Fixtures.content)
            store.saveSettings(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID, syncDeviceId = name))
        }
        val repository = OndoRepository(store, FakeHttp(emptyMap()), backgroundScope, now = { clock }, io = StandardTestDispatcher(testScheduler), mainProfileName = "Me")
        repository.bootstrap()
        repository.state.first { it.isReady && it.content.movies.isNotEmpty() }
        return repository
    }

    private suspend fun switch(repository: OndoRepository, profile: Profile) {
        repository.switchProfile(profile.id)
        repository.state.first { it.activeProfile.id == profile.id }
    }

    /** A session both ways, as two devices on the network have. */
    private fun TestScope.session(a: OndoRepository, b: OndoRepository) {
        requireNotNull(b.mergeSync(requireNotNull(a.syncSnapshot("A")), "B"))
        requireNotNull(a.mergeSync(requireNotNull(b.syncSnapshot("B")), "A"))
        testScheduler.advanceUntilIdle()
    }

    private fun OndoRepository.collection(name: String) = state.value.collections.firstOrNull { it.name == name }

    private fun OndoRepository.add(id: String, movie: com.markedoe.ondo.core.model.Movie) =
        toggleInCollection(id, ItemKind.MOVIE, movie.id, movie.name, null)

    @Test
    fun sharingMovesTheCollectionWholeAndEveryProfileSeesAndEditsIt() = runTest {
        val repository = device("tv")
        val id = repository.createCollection("Weekend")
        repository.add(id, Fixtures.glassCity)
        val before = requireNotNull(repository.collection("Weekend"))
        val sam = requireNotNull(repository.addProfile("Sam", 3, "star.fill", isKids = false, pin = null))

        repository.setCollectionShared(id, true)
        val state = repository.state.value
        assertTrue(state.isShared(id))
        assertTrue(state.library.collections.none { it.id == id })
        assertEquals(listOf(SyncKeys.list(id)), state.library.syncDeleted.keys.filter { it.startsWith("list:") })
        val shared = state.household.collections.single()
        assertEquals(before.items, shared.items)
        assertEquals(before.name, shared.name)
        assertTrue(shared.placedAt > state.library.syncDeleted.getValue(SyncKeys.list(id)))

        // Sam sees it, marked shared, and adds to it; the main profile sees Sam's addition.
        switch(repository, sam)
        assertEquals(listOf("Weekend"), repository.state.value.collections.map { it.name })
        assertEquals(listOf(true), UiMappers.myList(repository.state.value, clock).collections.map { it.shared })
        repository.add(id, Fixtures.lastSignal)
        repository.renameCollection(id, "Friday")
        switch(repository, repository.state.value.profiles.first { it.isMain })
        assertEquals(listOf(Fixtures.glassCity.id, Fixtures.lastSignal.id), repository.collection("Friday")?.items?.map { it.itemId })

        // Kept across launches, apart from every profile's library.
        testScheduler.advanceUntilIdle()
        val again = device("tv")
        assertEquals(listOf("Friday"), again.state.value.household.collections.map { it.name })
    }

    @Test
    fun takingBackMovesItIntoTheProfileAndTheOthersLoseIt() = runTest {
        val repository = device("tv")
        val id = repository.createCollection("Weekend")
        repository.add(id, Fixtures.glassCity)
        val sam = requireNotNull(repository.addProfile("Sam", 3, "star.fill", isKids = false, pin = null))
        repository.setCollectionShared(id, true)
        switch(repository, sam)
        repository.setCollectionShared(id, false)
        val state = repository.state.value
        assertFalse(state.isShared(id))
        assertEquals(listOf(Fixtures.glassCity.id), state.library.collections.single { it.id == id }.items.map { it.itemId })
        assertTrue(state.household.syncDeleted.containsKey(SyncKeys.list(id)))
        switch(repository, state.profiles.first { it.isMain })
        assertNull(repository.collection("Weekend"))
    }

    @Test
    fun aKidsProfileSeesSharedCollectionsAndChangesNone() = runTest {
        val repository = device("tv")
        val id = repository.createCollection("Weekend")
        repository.add(id, Fixtures.glassCity)
        val kids = requireNotNull(repository.addProfile("Kids", 4, "pawprint.fill", isKids = true, pin = null))
        repository.setCollectionShared(id, true)
        switch(repository, kids)
        val household = repository.state.value.household
        repository.add(id, Fixtures.lastSignal)
        repository.renameCollection(id, "Mine")
        repository.deleteCollection(id)
        repository.setCollectionShared(id, false)
        assertEquals(household, repository.state.value.household)
        val page = requireNotNull(UiMappers.collection(repository.state.value, id, clock))
        assertTrue(page.shared)
        assertFalse(page.canEdit)
        assertFalse(page.canShare)
        // Not offered on a title's page either.
        assertTrue(UiMappers.collectionChoices(repository.state.value, ItemKind.MOVIE, Fixtures.glassCity.id).isEmpty())
    }

    @Test
    fun deletingASharedCollectionDeletesItForEveryProfile() = runTest {
        val repository = device("tv")
        val id = repository.createCollection("Weekend")
        val sam = requireNotNull(repository.addProfile("Sam", 3, "star.fill", isKids = false, pin = null))
        repository.setCollectionShared(id, true)
        switch(repository, sam)
        repository.deleteCollection(id)
        assertTrue(repository.state.value.household.collections.isEmpty())
        assertTrue(repository.state.value.household.syncDeleted.containsKey(SyncKeys.list(id)))
        switch(repository, repository.state.value.profiles.first { it.isMain })
        assertNull(repository.collection("Weekend"))
    }

    @Test
    fun deletingTheProfileThatSharedItLeavesTheSharedCollection() = runTest {
        val repository = device("tv")
        val sam = requireNotNull(repository.addProfile("Sam", 3, "star.fill", isKids = false, pin = null))
        switch(repository, sam)
        val id = repository.createCollection("Weekend")
        repository.setCollectionShared(id, true)
        switch(repository, repository.state.value.profiles.first { it.isMain })
        repository.deleteProfile(sam.id)
        assertNotNull(repository.collection("Weekend"))
        assertTrue(repository.state.value.isShared(id))
    }

    @Test
    fun aSharedCollectionSyncsBetweenDevicesWithoutADuplicate() = runTest {
        val tv = device("tv")
        val phone = device("phone")
        val id = tv.createCollection("Weekend")
        tv.add(id, Fixtures.glassCity)
        requireNotNull(tv.addProfile("Sam", 3, "star.fill", isKids = false, pin = null))
        session(tv, phone)
        assertEquals(listOf(id), phone.state.value.library.collections.map { it.id })

        // The TV shares it while the phone, not told yet, adds to it in the main profile (its second change is dated after
        // the share: both clocks stand at the same time after their session).
        tv.setCollectionShared(id, true)
        phone.add(id, Fixtures.northernLights)
        phone.add(id, Fixtures.lastSignal)
        session(phone, tv)
        session(tv, phone)
        for (device in listOf(tv, phone)) {
            val state = device.state.value
            assertTrue(state.library.collections.none { it.id == id })
            assertEquals(listOf(Fixtures.glassCity.id, Fixtures.northernLights.id, Fixtures.lastSignal.id), state.household.collections.single().items.map { it.itemId })
        }
        val snapshot = requireNotNull(phone.syncSnapshot("phone"))
        assertEquals(listOf(id), snapshot.household?.lists?.map(SyncList::id))
        assertTrue(snapshot.lists.none { it.id == id })

        // Deleted for every profile on the phone: gone on the TV too.
        phone.deleteCollection(id)
        session(phone, tv)
        assertTrue(tv.state.value.collections.isEmpty())
    }

    @Test
    fun deletingAPlaylistTakesItsItemsOutOfTheSharedCollections() = runTest {
        val repository = device("tv")
        val id = repository.createCollection("Weekend")
        repository.add(id, Fixtures.glassCity)
        requireNotNull(repository.addProfile("Sam", 3, "star.fill", isKids = false, pin = null))
        repository.setCollectionShared(id, true)
        repository.delete(Fixtures.PLAYLIST_ID)
        val household = repository.state.value.household
        assertTrue(household.collections.single().items.isEmpty())
        assertTrue(household.syncDeleted.keys.none { it.startsWith("playlist:") })
    }
}
