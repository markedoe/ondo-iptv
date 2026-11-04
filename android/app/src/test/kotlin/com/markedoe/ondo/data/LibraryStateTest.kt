package com.markedoe.ondo.data

import com.markedoe.ondo.HangGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibraryStateTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private fun entry(itemId: String, kind: ItemKind = ItemKind.MOVIE, episodeId: String? = null, at: Long = 0, playlistId: String = "p1") =
        WatchEntry(playlistId, kind, itemId, episodeId, title = itemId, lastWatchedAt = at)

    /** Hide all / Show all: every category of the playlist at once, the other playlists untouched. */
    @Test
    fun categoriesHideAndShowTogether() {
        val hidden = LibraryState().settingCategoryHidden("p2", "a", hidden = true).settingCategoriesHidden("p1", listOf("a", "b", "c"), hidden = true)
        assertTrue(listOf("a", "b", "c").all { hidden.isCategoryHidden("p1", it) })
        val shown = hidden.settingCategoriesHidden("p1", listOf("a", "b"), hidden = false)
        assertFalse(shown.isCategoryHidden("p1", "a"))
        assertTrue(shown.isCategoryHidden("p1", "c"))
        assertTrue(shown.isCategoryHidden("p2", "a"))
    }

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

    /**
     * An episode watched to the end stays in the history, watched; the series' card goes on from it (its next episode,
     * see Showcase), never back to an older episode left halfway, and a movie watched to the end leaves.
     */
    @Test
    fun aFinishedEpisodeStaysWatchedAndKeepsItsSeriesInContinueWatching() {
        val second = entry("s1", ItemKind.SERIES, "e2")
        val third = entry("s1", ItemKind.SERIES, "e3")
        val library = LibraryState()
            .recordStart(second).updateProgress(second.key, 900.0, 2_700.0, now = 1)
            .recordStart(third).updateProgress(third.key, 2_700.0, 2_700.0, now = 2)
        assertTrue(library.progress(third.key)!!.isFinished)
        assertEquals("the one left halfway keeps its progress", 900.0, library.progress(second.key)!!.position, 0.0)
        assertEquals(listOf(third.key), library.continueWatching("p1").map { it.key })
    }

    /**
     * An episode watched to the end and watched again stays watched: starting it and the positions saved while it
     * plays change nothing (the same time, so the series goes on from where it was); a movie watched again is resumed.
     * Taken out of the history, it is unwatched.
     */
    @Test
    fun anEpisodeWatchedAgainStaysWatched() {
        val episode = entry("s1", ItemKind.SERIES, "e1")
        val movie = entry("m1")
        val watched = LibraryState()
            .recordStart(episode).updateProgress(episode.key, 2_700.0, 2_700.0, now = 1)
            .recordStart(movie).updateProgress(movie.key, 6_000.0, 6_000.0, now = 2)
        val again = watched.recordStart(episode.copy(lastWatchedAt = 3)).updateProgress(episode.key, 600.0, 2_700.0, now = 4)
            .recordStart(movie.copy(lastWatchedAt = 5)).updateProgress(movie.key, 600.0, 6_000.0, now = 6)
        val rewatched = again.progress(episode.key)!!
        assertTrue("still watched", rewatched.isFinished)
        assertEquals(watched.progress(episode.key), rewatched)
        assertEquals("the series' card still goes on after it", listOf(episode.key), again.continueWatching("p1").filter { it.kind == ItemKind.SERIES }.map { it.key })
        assertTrue("a movie watched again is resumed", again.progress(movie.key)!!.isResumable)
        assertNull(again.remove(rewatched, now = 7).progress(episode.key))
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

    /**
     * A card taken out of Continue watching leaves it: a series' other episodes in progress go with it (else the one
     * before would take the card's place), and its next episode does not take it either; the episodes watched to the
     * end and the other titles stay.
     */
    @Test
    fun aCardLeavesContinueWatchingWithItsSeries() {
        var library = LibraryState()
        val items = listOf(entry("m1"), entry("s1", ItemKind.SERIES, "e1"), entry("s1", ItemKind.SERIES, "e3"), entry("s1", ItemKind.SERIES, "e2"), entry("s2", ItemKind.SERIES, "e1"))
        for ((index, item) in items.withIndex()) {
            library = library.recordStart(item).updateProgress(item.key, 120.0, 3_000.0, now = index.toLong())
        }
        // e3 was finished before e2 was started: s1 shows e2, the latest.
        library = library.updateProgress(entry("s1", ItemKind.SERIES, "e3").key, 2_990.0, 3_000.0, now = 2)
        library = library.updateProgress(entry("s1", ItemKind.SERIES, "e2").key, 300.0, 3_000.0, now = 10)
        val card = library.continueWatching("p1").first { it.itemId == "s1" }
        assertEquals("e2", card.episodeId)
        val removed = library.removingFromContinueWatching(card, now = 20)
        assertEquals(listOf("s2", "m1"), removed.continueWatching("p1").map { it.itemId })
        assertTrue("the episode watched to the end stays watched", removed.progress(entry("s1", ItemKind.SERIES, "e3").key)!!.isFinished)
        assertNull(removed.progress(entry("s1", ItemKind.SERIES, "e1").key))
        // The other devices forget them too.
        assertTrue(removed.syncDeleted.keys.containsAll(listOf(SyncKeys.progress(entry("s1", ItemKind.SERIES, "e1")), SyncKeys.progress(card))))
        // A movie: only it.
        assertEquals(listOf("s1", "s2"), library.removingFromContinueWatching(library.progress(entry("m1").key)!!, now = 21).continueWatching("p1").map { it.itemId })
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
    fun collectionsAreMadeFilledRenamedAndDeleted() {
        val movie = Favorite("p1", ItemKind.MOVIE, "m1", "Movie", addedAt = 1)
        val series = Favorite("p2", ItemKind.SERIES, "s1", "Series", addedAt = 1)
        var library = LibraryState().creatingCollection("c1", " Weekend ", now = 1).creatingCollection("c2", "kids", now = 2)
        assertEquals("sorted by name, trimmed", listOf("kids", "Weekend"), library.collections.map { it.name })
        library = library.togglingInCollection("c1", movie, now = 3).togglingInCollection("c1", series, now = 4)
        val weekend = library.collections.single { it.id == "c1" }
        assertTrue(weekend.contains("p1", ItemKind.MOVIE, "m1"))
        assertFalse(weekend.contains("p2", ItemKind.MOVIE, "m1"))
        assertEquals(4, weekend.updatedAt)
        assertFalse("a second tap takes it out", library.togglingInCollection("c1", movie, now = 5).collections.single { it.id == "c1" }.contains("p1", ItemKind.MOVIE, "m1"))
        library = library.renamingCollection("c2", "Animation", now = 6)
        assertEquals(listOf("Animation", "Weekend"), library.collections.map { it.name })
        assertEquals("deleting a playlist empties it from collections", listOf(series), library.removingPlaylist("p1").collections.single { it.id == "c1" }.items)
        assertEquals(listOf("c1"), library.deletingCollection("c2").collections.map { it.id })
    }

    @Test
    fun remindersToggleAndForgetEndedProgrammes() {
        val ended = Reminder("p1", "c1", "News", "Old", start = 10, end = 20)
        val next = Reminder("p1", "c1", "News", "Next", start = 200, end = 300)
        val soon = Reminder("p1", "c2", "Sport", "Soon", start = 100, end = 150)
        var library = LibraryState(reminders = listOf(ended)).togglingReminder(next, now = 50).togglingReminder(soon, now = 50)
        assertEquals("soonest first, ended ones forgotten", listOf("Soon", "Next"), library.reminders.map { it.title })
        library = library.togglingReminder(next, now = 60)
        assertEquals(listOf("Soon"), library.reminders.map { it.title })
        assertTrue(library.upcomingReminders("p2", now = 60).isEmpty())
        assertTrue(library.removingPlaylist("p1").reminders.isEmpty())
    }

    @Test
    fun historyIsCapped() {
        var library = LibraryState()
        repeat(LibraryState.MAX_HISTORY + 20) { library = library.recordStart(entry("m$it", at = it.toLong())) }
        assertEquals(LibraryState.MAX_HISTORY, library.history.size)
        assertEquals("m${LibraryState.MAX_HISTORY + 19}", library.history.first().itemId)
    }

    /** Live TV's Recent row: this playlist's channels only, the last watched first. */
    @Test
    fun recentChannelsAreThisPlaylistsNewestFirst() {
        val library = listOf(
            entry("c1", ItemKind.CHANNEL, at = 1),
            entry("m1", at = 5),
            entry("c2", ItemKind.CHANNEL, at = 3),
            entry("c3", ItemKind.CHANNEL, at = 9, playlistId = "p2"),
        ).fold(LibraryState()) { library, watched -> library.recordStart(watched) }
        assertEquals(listOf("c2", "c1"), library.recentChannels("p1").map { it.itemId })
        assertEquals(listOf("c2"), library.recentChannels("p1", limit = 1).map { it.itemId })
        assertEquals(listOf("c3"), library.recentChannels("p2").map { it.itemId })
    }

    /** Watched with 10 minutes or less left once three quarters are seen, or at 95 % (the rule of the Kotlin core). */
    @Test
    fun watchedWithTenMinutesLeft() {
        fun movie(minutes: Double, of: Double) = entry("m1").copy(position = minutes * 60, duration = of * 60)
        assertTrue("10 minutes left", movie(100.0, of = 110.0).isFinished)
        assertFalse("11 minutes left", movie(99.0, of = 110.0).isFinished)
        assertTrue("a short film, 2 minutes left", movie(18.0, of = 20.0).isFinished)
        assertFalse("a short film needs more than its last 10 minutes", movie(10.0, of = 20.0).isFinished)
        assertFalse("a 12-minute film a quarter in", movie(3.0, of = 12.0).isFinished)
        assertFalse("unknown duration: 95 % only, so never", movie(100.0, of = 0.0).isFinished)

        val watching = LibraryState().recordStart(movie(99.0, of = 110.0))
        assertEquals(listOf("m1"), watching.continueWatching("p1").map { it.itemId })
        assertEquals(emptySet<String>(), watching.finishedMovies("p1"))
        val watched = watching.updateProgress(entry("m1").key, 100.0 * 60, 110.0 * 60, now = 1)
        assertEquals("watched: out of Continue watching", emptyList<WatchEntry>(), watched.continueWatching("p1"))
        assertEquals("watched: hidden with the watched movies", setOf("m1"), watched.finishedMovies("p1"))
        assertTrue(watched.progress(entry("m1").key)!!.isFinished)
    }

    /** Watched once the end credits start, when the file's chapters say where; kept by a progress or a start without it. */
    @Test
    fun watchedAtTheEndCredits() {
        val watching = LibraryState().recordStart(entry("m1").copy(position = 6_000.0, duration = 7_200.0))
            .updateProgress(entry("m1").key, 6_000.0, 7_200.0, now = 1, creditsStart = 6_300.0)
        assertEquals(listOf("m1"), watching.continueWatching("p1").map { it.itemId })
        val later = watching.updateProgress(entry("m1").key, 6_200.0, 7_200.0, now = 2).recordStart(entry("m1"))
        assertEquals(6_300.0, later.progress(entry("m1").key)!!.creditsStart!!, 0.0)
        val watched = later.updateProgress(entry("m1").key, 6_300.0, 7_200.0, now = 3)
        assertEquals("watched at the credits", emptyList<WatchEntry>(), watched.continueWatching("p1"))
        assertEquals(setOf("m1"), watched.finishedMovies("p1"))
        assertFalse("no chapter: 15 minutes left", entry("m2").copy(position = 6_300.0, duration = 7_200.0).isFinished)
    }

    /** "Hide watched movies": a movie of this playlist watched (see [WatchEntry.isFinished]), never an episode or a channel. */
    @Test
    fun finishedMoviesAreThisPlaylistsMoviesWatchedToTheEnd() {
        val library = listOf(
            entry("done").copy(position = 5_800.0, duration = 6_000.0),
            entry("credits").copy(position = 5_400.0, duration = 6_000.0),
            entry("almost").copy(position = 5_399.0, duration = 6_000.0),
            entry("episode", ItemKind.SERIES, episodeId = "e1").copy(position = 2_700.0, duration = 2_700.0),
            entry("elsewhere", playlistId = "p2").copy(position = 6_000.0, duration = 6_000.0),
            entry("unknown length").copy(position = 6_000.0),
        ).fold(LibraryState()) { library, watched -> library.recordStart(watched) }
        assertEquals(setOf("done", "credits"), library.finishedMovies("p1"))
        assertEquals(setOf("elsewhere"), library.finishedMovies("p2"))
    }

    /** Sync keeps the latest removal of a key: an older one arriving later does not move it back. */
    @Test
    fun aRemovalKeepsItsLatestTime() {
        val library = LibraryState().deleting("list:a", 20).deleting("list:a", 10)
        assertEquals(20L, library.syncDeleted["list:a"])
        assertEquals(30L, library.deleting("list:a", 30).syncDeleted["list:a"])
    }

    /** A movie or a person taken out of My List is remembered for sync at the time of the toggle. */
    @Test
    fun aFavouriteTakenAwayIsRememberedForSyncPeopleToo() {
        val movie = Favorite("p1", ItemKind.MOVIE, "m1", "Movie", addedAt = 1)
        val removed = LibraryState().toggleFavorite(movie).toggleFavorite(movie.copy(addedAt = 7))
        assertEquals(7L, removed.syncDeleted[SyncKeys.favorite(movie)])
        val person = Favorite.person("Alex Marsh", photoUrl = null, now = 1)
        val gone = LibraryState().toggleFavorite(person).toggleFavorite(person.copy(addedAt = 9))
        assertEquals(9L, gone.syncDeleted[SyncKeys.favorite(person)])
    }

    /** A photo found later fills a person kept without one, in My List and the collections; a known photo stays. */
    @Test
    fun aPersonsPhotoFillsOnlyWhatHadNone() {
        val bare = Favorite.person("Alex Marsh", photoUrl = null, now = 1)
        val pictured = Favorite.person("Robin Hale", photoUrl = "http://img.example.com/robin.jpg", now = 1)
        val library = LibraryState().toggleFavorite(bare).toggleFavorite(pictured)
            .creatingCollection("l1", "Actors", now = 1).togglingInCollection("l1", bare, now = 2)

        val filled = library.withPersonPhoto("Alex Marsh", "http://img.example.com/alex.jpg")
        assertEquals("http://img.example.com/alex.jpg", filled.favorites.single { it.itemId == "Alex Marsh" }.imageUrl)
        assertEquals("http://img.example.com/alex.jpg", filled.collections.single().items.single().imageUrl)

        val again = filled.withPersonPhoto("Robin Hale", "http://img.example.com/other.jpg")
        assertEquals("http://img.example.com/robin.jpg", again.favorites.single { it.itemId == "Robin Hale" }.imageUrl)
        assertTrue("nothing changed: the same state", again === filled)
    }
}
