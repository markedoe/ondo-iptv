package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.CoreJson
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.ChannelEdit
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.CustomList
import com.markedoe.ondo.core.model.HistoryEntry
import com.markedoe.ondo.core.model.MediaReference
import com.markedoe.ondo.core.model.Playlist
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.PlaylistSource
import com.markedoe.ondo.core.model.Profile
import com.markedoe.ondo.core.model.ProgramReminder
import com.markedoe.ondo.core.utc
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryMergeTest {
    @Test
    fun mergeConverges() {
        val playlist = UUID.randomUUID().toString()
        fun ref(id: String) = MediaReference(playlist, ContentType.MOVIE, id)
        val t0 = 1_000_000L

        val phone = UserLibrary()
            .toggleFavorite(ref("a"), "A", null, now = t0)
            .recordPlayback(HistoryEntry(ref("m"), "M", lastWatchedAt = t0, position = 100.0, duration = 1000.0))
            .setCategoryHidden(true, playlist, "c1")

        val mac = UserLibrary()
            .toggleFavorite(ref("b"), "B", null, now = t0 + 5_000)
            .recordPlayback(HistoryEntry(ref("m"), "M", lastWatchedAt = t0 + 60_000, position = 500.0, duration = 1000.0))
            .copy(customLists = listOf(CustomList(name = "Kids")))

        val onPhone = phone.merged(mac)
        val onMac = mac.merged(phone)
        assertEquals(setOf("A", "B"), onPhone.favorites.map { it.title }.toSet())
        assertEquals(1, onPhone.history.size)
        assertEquals(500.0, onPhone.history.first().position, "most recent progress wins")
        assertEquals(1, onPhone.hiddenCategoryKeys.size)
        assertEquals(listOf("Kids"), onPhone.customLists.map { it.name })
        assertEquals(onMac.history, onPhone.history)
        assertEquals(onMac.favorites.map { it.id }.toSet(), onPhone.favorites.map { it.id }.toSet())
        assertEquals(onPhone.history, onPhone.merged(onMac).history, "idempotent")
        assertEquals(onPhone, onPhone.merged(onPhone), "merging with itself changes nothing")
    }

    @Test
    fun deletionsPropagate() {
        val playlist = UUID.randomUUID().toString()
        fun ref(id: String) = MediaReference(playlist, ContentType.LIVE, id)
        val t0 = 1_000_000L
        var phone = UserLibrary()
            .toggleFavorite(ref("a"), "A", null, now = t0)
            .recordPlayback(HistoryEntry(ref("h"), "H", lastWatchedAt = t0))
        var mac = phone

        // Removed on the phone later.
        phone = phone.toggleFavorite(ref("a"), "A", null, now = t0 + 10_000).clearHistory(now = t0 + 10_000)

        val merged = mac.merged(phone)
        assertTrue(merged.favorites.isEmpty(), "removal wins over an older copy")
        assertTrue(merged.history.isEmpty())

        // Re-added afterwards on the Mac: the newer addition wins.
        mac = merged.toggleFavorite(ref("a"), "A", null, now = t0 + 20_000)
        assertEquals(1, mac.merged(phone).favorites.size)
    }

    @Test
    fun historyTombstonesAndOrdering() {
        val playlist = "p"
        fun ref(id: String) = MediaReference(playlist, ContentType.MOVIE, id)
        val base = UserLibrary()
            .recordPlayback(HistoryEntry(ref("a"), "A", lastWatchedAt = 10))
            .recordPlayback(HistoryEntry(ref("b"), "B", lastWatchedAt = 20))
        val removed = base.removeHistory(base.history.first { it.title == "A" }, now = 15)
        assertEquals(listOf("B"), base.merged(removed).history.map { it.title }, "removed after it was watched")
        val watchedAgain = base.recordPlayback(HistoryEntry(ref("a"), "A", lastWatchedAt = 30))
        assertEquals(listOf("A", "B"), removed.merged(watchedAgain).history.map { it.title }, "watched again after the removal")
    }

    @Test
    fun oldLibraryJsonDecodes() {
        val json = """{"favorites":[],"history":[],"hiddenCategoryKeys":[],"lockedCategoryKeys":[]}"""
        val library = CoreJson.decodeFromString(UserLibrary.serializer(), json)
        assertTrue(library.customLists.isEmpty())
        assertNull(library.historyClearedAt)
    }

    @Test
    fun libraryRoundTrip() {
        val playlist = "p"
        val library = UserLibrary()
            .toggleFavorite(MediaReference(playlist, ContentType.SERIES, "s", "e"), "S", "http://img", now = 1)
            .recordPlayback(HistoryEntry(MediaReference(playlist, ContentType.MOVIE, "m"), "M", "2010", null, 2, 30.5, 100.0))
            .createList("Kids", id = "list-1", now = 3)
            .toggleReminder(ProgramReminder(playlist, "c", "C", "Show", 10_000, 20_000), now = 4)
            .setChannelHidden(true, playlist, "c", now = 5)
            .setChannelHidden(false, playlist, "c", now = 6)
        val decoded = CoreJson.decodeFromString(UserLibrary.serializer(), CoreJson.encodeToString(UserLibrary.serializer(), library))
        assertEquals(library, decoded)
    }
}

class CustomListTest {
    @Test
    fun listLifecycleAndSync() {
        val playlist = UUID.randomUUID().toString()
        val movie = MediaReference(playlist, ContentType.MOVIE, "m")
        val channel = MediaReference(playlist, ContentType.LIVE, "c")
        val t0 = 1_000_000L
        val kids = "kids-list"

        var phone = UserLibrary().createList("Kids", id = kids, now = t0)
        phone = phone.toggleInList(kids, movie, "M", null, now = t0)
        phone = phone.toggleInList(kids, channel, "C", null, now = t0)
        phone = phone.moveItems(kids, fromIndices = setOf(1), toIndex = 0, now = t0)
        assertEquals(listOf("C", "M"), phone.customLists[0].items.map { it.title })
        assertTrue(phone.customLists[0].contains(movie))

        var mac = phone
        // Phone removes an item later; Mac renames earlier: the latest edit wins for the whole list.
        mac = mac.renameList(kids, "Children", now = t0 + 5_000)
        phone = phone.toggleInList(kids, movie, "M", null, now = t0 + 10_000)
        assertFalse(phone.customLists[0].contains(movie))
        val merged = mac.merged(phone)
        assertEquals(listOf("C"), merged.customLists.first().items.map { it.title })
        assertEquals("Kids", merged.customLists.first().name)

        // Deletion propagates.
        val deleted = merged.deleteList(kids)
        assertTrue(merged.merged(deleted).customLists.isEmpty())
        assertTrue(deleted.merged(merged).customLists.isEmpty())
    }

    @Test
    fun unknownListsAreIgnoredAndEpisodesAreStoredAtSeriesLevel() {
        val library = UserLibrary().createList("L", id = "l", now = 1)
        assertEquals(library, library.toggleInList("missing", MediaReference("p", ContentType.MOVIE, "m"), "M", null))
        val withEpisode = library.toggleInList("l", MediaReference("p", ContentType.SERIES, "s", "e1"), "S", null, now = 2)
        assertNull(withEpisode.customLists.single().items.single().reference.episodeId)
        assertTrue(withEpisode.customLists.single().contains(MediaReference("p", ContentType.SERIES, "s", "e9")))
        assertEquals(2L, withEpisode.customLists.single().updatedAt)
    }
}

class ProfileTest {
    @Test
    fun defaultProfileKeepsHistoricalLibraryName() {
        assertEquals("library", Profile(id = Profile.DEFAULT_ID, name = "Me").libraryDocumentName)
        val kid = Profile(name = "Kid", isKids = true)
        assertEquals("library-${kid.id}", kid.libraryDocumentName)
        val decoded = CoreJson.decodeFromString(Profile.serializer(), """{"id":"00000000-0000-0000-0000-000000000001"}""")
        assertFalse(decoded.isKids)
        assertFalse(decoded.isProtected)
        assertEquals("person.fill", decoded.symbol)
    }
}

class ReminderTest {
    private val playlistId = UUID.randomUUID().toString()
    private val start = utc(2025, 10, 9, 20, 0)

    private fun reminder(channel: String, startOffset: Long = 0) = ProgramReminder(
        playlistId = playlistId,
        channelId = channel,
        channelName = channel.replaceFirstChar { it.uppercase() },
        title = "Show on $channel",
        start = start + startOffset,
        end = start + startOffset + 3_600_000,
    )

    @Test
    fun toggleAndOrdering() {
        var library = UserLibrary()
        library = library.toggleReminder(reminder("b", startOffset = 600_000))
        library = library.toggleReminder(reminder("a"))
        assertEquals(listOf("a", "b"), library.reminders.map { it.channelId }, "soonest first")
        assertTrue(library.hasReminder(reminder("a").id))
        assertEquals("$playlistId|a|${start / 1000}", reminder("a").id)

        library = library.toggleReminder(reminder("a"))
        assertFalse(library.hasReminder(reminder("a").id))
        assertNotNull(library.reminderTombstones[reminder("a").id])
    }

    @Test
    fun upcomingAndPruning() {
        var library = UserLibrary().toggleReminder(reminder("a")).toggleReminder(reminder("b", startOffset = 7_200_000))
        assertEquals(listOf("b"), library.upcomingReminders(at = start + 3_700_000).map { it.channelId })
        library = library.pruneReminders(endedBefore = start + 3_700_000)
        assertEquals(listOf("b"), library.reminders.map { it.channelId })
    }

    @Test
    fun deletionPropagatesAndReAddingWins() {
        val t0 = utc(2025, 10, 1, 8)
        var phone = UserLibrary().toggleReminder(reminder("a"), now = t0)
        var mac = phone

        mac = mac.removeReminder(reminder("a").id, now = t0 + 60_000)
        assertTrue(phone.merged(mac).reminders.isEmpty(), "removal on the Mac reaches the phone")
        assertTrue(mac.merged(phone).reminders.isEmpty(), "and the phone's old copy does not bring it back")

        phone = phone.merged(mac)
        phone = phone.toggleReminder(reminder("a"), now = t0 + 120_000)
        assertEquals(1, mac.merged(phone).reminders.size, "set again later: the newer intent wins")
        assertEquals(phone.merged(mac), mac.merged(phone).merged(phone), "converges")
    }

    @Test
    fun removingAPlaylistDropsItsReminders() {
        val library = UserLibrary().toggleReminder(reminder("a")).removeData(playlistId)
        assertTrue(library.reminders.isEmpty())
    }

    @Test
    fun librariesWithoutRemindersDecode() {
        val library = CoreJson.decodeFromString(UserLibrary.serializer(), """{"favorites":[],"history":[]}""")
        assertTrue(library.reminders.isEmpty())
        assertTrue(library.reminderTombstones.isEmpty())
    }
}

class ChannelManagementTest {
    private val playlistId = UUID.randomUUID().toString()

    @Test
    fun editsRenameAndRenumberButKeepGuideMatching() {
        val content = PlaylistContent(
            channels = listOf(
                Channel(id = "1", name = "FR: TV1 FHD", number = 1, categoryId = "a", streamUrl = "http://h/1"),
                Channel(id = "2", name = "Nation 2", categoryId = "a", streamUrl = "http://h/2", epgName = "nation2"),
                Channel(id = "3", name = "Aria", categoryId = "a", streamUrl = "http://h/3"),
            ),
        )
        val edited = content.applying(
            mapOf(
                "1" to ChannelEdit(name = "TV1", number = 101),
                "2" to ChannelEdit(number = 2),
                "x" to ChannelEdit(name = "Ghost"),
            ),
        )
        assertEquals(listOf("TV1", "Nation 2", "Aria"), edited.channels.map { it.name })
        assertEquals(listOf(101, 2, null), edited.channels.map { it.number })
        assertEquals("FR: TV1 FHD", edited.channels[0].epgName, "the provider name still matches the guide")
        assertEquals("nation2", edited.channels[1].epgName)
        assertEquals(content, content.applying(emptyMap()))
        assertTrue(ChannelEdit().isEmpty)
    }

    @Test
    fun hiddenChannelsSyncBothWays() {
        val t0 = utc(2025, 10, 1, 8)
        var phone = UserLibrary().setChannelHidden(true, playlistId, "c1", now = t0)
        assertTrue(phone.isChannelHidden(playlistId, "c1"))
        assertEquals(setOf("c1"), phone.hiddenChannelIds(playlistId))

        var mac = UserLibrary().merged(phone)
        assertTrue(mac.isChannelHidden(playlistId, "c1"), "hiding reaches the other device")

        mac = mac.setChannelHidden(false, playlistId, "c1", now = t0 + 60_000)
        assertFalse(phone.merged(mac).isChannelHidden(playlistId, "c1"), "unhiding reaches it too")
        assertFalse(mac.merged(phone).isChannelHidden(playlistId, "c1"), "and an old copy does not hide it again")

        phone = phone.merged(mac)
        phone = phone.setChannelHidden(true, playlistId, "c1", now = t0 + 120_000)
        assertTrue(mac.merged(phone).isChannelHidden(playlistId, "c1"), "hidden again later wins")

        phone = phone.removeData(playlistId)
        assertTrue(phone.hiddenChannels.isEmpty())
    }

    @Test
    fun playlistChannelEditsDecode() {
        val playlist = Playlist(name = "P", source = PlaylistSource.Remote("http://h/p.m3u"))
            .copy(channelEdits = mapOf("1" to ChannelEdit(name = "TV1", number = 101)))
        val decoded = CoreJson.decodeFromString(Playlist.serializer(), CoreJson.encodeToString(Playlist.serializer(), playlist))
        assertEquals(ChannelEdit(name = "TV1", number = 101), decoded.channelEdits["1"])
    }
}
