package com.markedoe.ondo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The user's documents survive a damaged file: never read as empty, never written over. */
class AppStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun playlist(name: String) = SavedPlaylist(id = name, name = name, url = "http://example.com/$name.m3u")

    @Test
    fun anUnreadableDocumentIsKeptAsideAndThePreviousVersionIsUsed() {
        val store = AppStore(folder.root, now = { 42L })
        store.savePlaylists(listOf(playlist("first")))
        store.savePlaylists(listOf(playlist("first"), playlist("second")))
        folder.root.resolve("playlists.json").writeText("{ half-written")

        assertEquals(listOf(playlist("first")), store.loadPlaylists())
        val aside = folder.root.resolve("playlists.json.corrupt-42")
        assertEquals("{ half-written", aside.readText())
    }

    @Test
    fun withoutAnyReadableVersionTheDamagedFileIsStillKept() {
        val store = AppStore(folder.root, now = { 7L })
        folder.root.resolve("library.json").writeText("not json")

        assertEquals(LibraryState(), store.loadLibrary())
        assertTrue(folder.root.resolve("library.json.corrupt-7").exists())
        store.saveLibrary(LibraryState())
        assertEquals("not json", folder.root.resolve("library.json.corrupt-7").readText())
    }

    /** A deleted playlist leaves nothing behind: no previous version or damaged copy with its login, no imported file. */
    @Test
    fun aDeletedPlaylistLeavesNothingBehind() {
        val store = AppStore(folder.root.resolve("ondo"), now = { 9L })
        val imported = store.imports.apply { mkdirs() }.resolve("list.m3u").apply { writeText("#EXTM3U") }
        val elsewhere = folder.root.resolve("elsewhere.m3u").apply { writeText("#EXTM3U") }
        val local = SavedPlaylist(id = "local", name = "Local", url = imported.toURI().toString(), password = "made-up")
        val outside = SavedPlaylist(id = "outside", name = "Outside", url = elsewhere.toURI().toString())
        store.savePlaylists(listOf(local, outside, playlist("kept")))
        store.savePlaylists(listOf(local, outside, playlist("kept")))
        folder.root.resolve("ondo/playlists.json.corrupt-1").writeText("[{\"password\":\"made-up\"}]")

        store.savePlaylists(listOf(outside, playlist("kept")), removed = local)
        store.savePlaylists(listOf(playlist("kept")), removed = outside)

        assertEquals(listOf(playlist("kept")), store.loadPlaylists())
        assertFalse(folder.root.resolve("ondo/playlists.json.bak").exists())
        assertFalse(folder.root.resolve("ondo/playlists.json.corrupt-1").exists())
        assertFalse(imported.exists())
        assertTrue("only files Ondo imported are deleted", elsewhere.exists())
    }

    /** Settings written by an older version, before the buffer settings: they load, buffers on automatic. */
    @Test
    fun settingsFromAnOlderVersionLoadWithTheNewDefaults() {
        folder.root.resolve("settings.json").writeText("""{"appearance":"LIGHT","activePlaylistId":"p1","updateAwards":false}""")
        val settings = AppStore(folder.root).loadSettings()
        assertEquals(AppSettings(appearance = com.markedoe.ondo.ui.theme.Appearance.LIGHT, activePlaylistId = "p1", updateAwards = false), settings)
        assertEquals(0, settings.liveBuffer)
        assertEquals(0, settings.vodBuffer)

        AppStore(folder.root).saveSettings(settings.copy(liveBuffer = 3, vodBuffer = 10))
        assertEquals(3, AppStore(folder.root).loadSettings().liveBuffer)
        assertEquals(10, AppStore(folder.root).loadSettings().vodBuffer)
    }

    @Test
    fun catalogueSavesKeepNoPreviousVersion() {
        val store = AppStore(folder.root)
        store.saveContent("p", com.markedoe.ondo.core.model.PlaylistContent.EMPTY)
        store.saveContent("p", com.markedoe.ondo.core.model.PlaylistContent.EMPTY)
        assertTrue(!folder.root.resolve("content-p.json.bak").exists())
        assertTrue(folder.root.resolve("content-p.json").exists())
    }
}
