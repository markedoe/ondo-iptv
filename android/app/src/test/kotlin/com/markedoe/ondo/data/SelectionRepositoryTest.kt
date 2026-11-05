package com.markedoe.ondo.data

import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.library.RowOrder
import com.markedoe.ondo.core.library.Selections
import com.markedoe.ondo.core.sync.SyncSelection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A selection made, changed and deleted (Settings → Row order → New selection): its row goes to the top of each tab it
 * shows in, its deletion takes the row out and is remembered for sync, and a Kids profile cannot make one.
 */
@RunWith(RobolectricTestRunner::class)
class SelectionRepositoryTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val folder = TemporaryFolder()

    private var time = Fixtures.NOW

    private suspend fun TestScope.repository(): OndoRepository {
        val store = AppStore(folder.root)
        store.savePlaylists(listOf(Fixtures.playlist))
        store.saveSettings(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID))
        store.saveContent(Fixtures.PLAYLIST_ID, Fixtures.content)
        val repository = OndoRepository(store, FakeHttp(emptyMap()), backgroundScope, now = { time }, io = StandardTestDispatcher(testScheduler))
        repository.bootstrap()
        repository.state.first { it.isReady }
        return repository
    }

    @Test
    fun aSelectionGoesToTheTopOfItsTabsAndItsDeletionSyncs() = runTest {
        val repository = repository()
        repository.setRowOrder(RowOrder.MOVIES, listOf(RowOrder.CONTINUE_WATCHING, RowOrder.ALL), emptyList())
        time += 1_000
        val id = requireNotNull(repository.saveSelection(null, " Nineties ", listOf("decade:1990", "genre:drama"), all = true, tabs = listOf(RowOrder.MOVIES)))
        val library = repository.state.value.library
        val saved = library.selection(id)!!
        assertEquals(SyncSelection(id, "Nineties", listOf("decade:1990", "genre:drama"), SyncSelection.ALL, listOf(RowOrder.MOVIES), saved.updatedAt), saved)
        assertTrue(saved.updatedAt > 0)
        assertEquals(listOf(Selections.rowKey(id), RowOrder.CONTINUE_WATCHING, RowOrder.ALL), library.rowOrder(RowOrder.MOVIES)!!.order)
        // Shown on Home too, afterwards: its row goes to the top of Home; Movies keeps it where it is.
        time += 1_000
        repository.saveSelection(id, "Nineties", saved.tags, all = true, tabs = listOf(RowOrder.HOME, RowOrder.MOVIES))
        val changed = repository.state.value.library
        assertEquals(listOf(Selections.rowKey(id)), changed.rowOrder(RowOrder.HOME)!!.order)
        assertEquals(listOf(Selections.rowKey(id), RowOrder.CONTINUE_WATCHING, RowOrder.ALL), changed.rowOrder(RowOrder.MOVIES)!!.order)
        // Deleted: gone, its rows too, and remembered for sync.
        time += 1_000
        repository.deleteSelection(id)
        val after = repository.state.value.library
        assertNull(after.selection(id))
        assertTrue(after.rowOrders.none { Selections.rowKey(id) in it.order })
        assertNotNull(after.syncDeleted[SyncSelection.KEY_PREFIX + id])
        val snapshot = requireNotNull(repository.syncSnapshot("client"))
        assertTrue(snapshot.selections.isEmpty())
        assertNotNull(snapshot.deleted[SyncSelection.KEY_PREFIX + id])
    }

    @Test
    fun aKidsProfileCannotMakeOne() = runTest {
        val repository = repository()
        val kids = requireNotNull(repository.addProfile("Kids", 4, "pawprint.fill", isKids = true, pin = null))
        repository.switchProfile(kids.id)
        repository.state.first { it.activeProfile.id == kids.id }
        assertNull(repository.saveSelection(null, "Cartoons", listOf("style:kids"), all = false, tabs = listOf(RowOrder.HOME)))
        assertTrue(repository.state.value.library.selections.isEmpty())
    }
}
