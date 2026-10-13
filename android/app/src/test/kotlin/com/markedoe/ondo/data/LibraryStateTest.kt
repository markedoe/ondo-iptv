package com.markedoe.ondo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryStateTest {
    private fun entry(itemId: String, kind: ItemKind = ItemKind.MOVIE, episodeId: String? = null, at: Long = 0, playlistId: String = "p1") =
        WatchEntry(playlistId, kind, itemId, episodeId, title = itemId, lastWatchedAt = at)

    @Test
    fun favouritesToggleAndStayPerPlaylist() {
        val favorite = Favorite("p1", ItemKind.MOVIE, "m1", "Movie", addedAt = 1)
        val library = LibraryState().toggleFavorite(favorite)
        assertTrue(library.isFavorite("p1", ItemKind.MOVIE, "m1"))
        assertFalse(library.isFavorite("p2", ItemKind.MOVIE, "m1"))
        assertFalse(library.isFavorite("p1", ItemKind.SERIES, "m1"))
        assertFalse(library.toggleFavorite(favorite).isFavorite("p1", ItemKind.MOVIE, "m1"))
    }

    @Test
    fun restartingKeepsTheKnownPosition() {
        var library = LibraryState().recordStart(entry("m1", at = 1))
        library = library.updateProgress(entry("m1").key, position = 600.0, duration = 6_000.0, now = 2)
        library = library.recordStart(entry("m1", at = 3))
        val progress = library.progress(entry("m1").key)!!
        assertEquals(600.0, progress.position, 0.0)
        assertEquals(6_000.0, progress.duration, 0.0)
        assertEquals(1, library.history.size)
    }

    @Test
    fun episodesKeepTheirOwnProgress() {
        val first = entry("s1", ItemKind.SERIES, "e1")
        val second = entry("s1", ItemKind.SERIES, "e2")
        val library = LibraryState().recordStart(first).recordStart(second)
            .updateProgress(second.key, 900.0, 2_700.0, now = 5)
        assertNull(library.progress(first.key)?.takeIf { it.position > 0 })
        assertEquals(900.0, library.progress(second.key)!!.position, 0.0)
    }

    @Test
    fun continueWatchingShowsResumableItemsOncePerSeries() {
        var library = LibraryState()
        for ((index, item) in listOf(entry("m1"), entry("s1", ItemKind.SERIES, "e1"), entry("s1", ItemKind.SERIES, "e2"), entry("m2"), entry("c1", ItemKind.CHANNEL)).withIndex()) {
            library = library.recordStart(item).updateProgress(item.key, 120.0, 3_000.0, now = index.toLong())
        }
        // m2 is finished: not in Continue watching.
        library = library.updateProgress(entry("m2").key, 2_990.0, 3_000.0, now = 10)
        val keys = library.continueWatching("p1").map { it.itemId + (it.episodeId?.let { id -> "/$id" } ?: "") }
        assertEquals(listOf("s1/e2", "m1"), keys)
    }

    @Test
    fun watchEntryThresholds() {
        val base = entry("m1")
        assertFalse("under 30 s nothing to resume", base.copy(position = 20.0, duration = 6_000.0).isResumable)
        assertTrue(base.copy(position = 600.0, duration = 6_000.0).isResumable)
        assertTrue("95 % is finished", base.copy(position = 5_700.0, duration = 6_000.0).isFinished)
        assertEquals(90, base.copy(position = 600.0, duration = 6_000.0).minutesLeft)
        assertFalse("channels are never finished", entry("c1", ItemKind.CHANNEL).copy(position = 100.0, duration = 100.0).isFinished)
    }

    @Test
    fun deletingAPlaylistForgetsItsLibrary() {
        val library = LibraryState()
            .toggleFavorite(Favorite("p1", ItemKind.MOVIE, "m1", "A", addedAt = 1))
            .toggleFavorite(Favorite("p2", ItemKind.MOVIE, "m1", "A", addedAt = 1))
            .recordStart(entry("m1", playlistId = "p1"))
            .removingPlaylist("p1")
        assertEquals(listOf("p2"), library.favorites.map { it.playlistId })
        assertTrue(library.history.isEmpty())
    }

    @Test
    fun historyIsCapped() {
        var library = LibraryState()
        repeat(LibraryState.MAX_HISTORY + 20) { library = library.recordStart(entry("m$it", at = it.toLong())) }
        assertEquals(LibraryState.MAX_HISTORY, library.history.size)
        assertEquals("m${LibraryState.MAX_HISTORY + 19}", library.history.first().itemId)
    }
}
