package com.markedoe.ondo.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.markedoe.ondo.core.library.CreditRecord
import com.markedoe.ondo.core.library.IndexedPerson
import com.markedoe.ondo.core.library.TitleMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What TMDb said stays in a SQLite file between launches: titles, their credits, the people in them, dead images. */
@RunWith(AndroidJUnit4::class)
class SqliteMetadataStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun whatIsSavedIsReadAgainAfterTheFileIsReopened() {
        val now = System.currentTimeMillis()
        val road = TitleMetadata("movie:longroad:2025", "movie", 9, "https://image.tmdb.org/t/p/w1280/road.jpg", "roadTrailer", 7.4, 321, now)
        val unknown = TitleMetadata("movie:nowhere:", "movie", fetchedAt = now, found = false)
        val kim = CreditRecord("kim park", "Kim Park", CreditRecord.Role.DIRECTOR, 0)
        val ruth = CreditRecord("ruth ames", "Ruth Ames", CreditRecord.Role.CAST, 0, "https://image.tmdb.org/t/p/w185/ruth.jpg")
        val ana = CreditRecord("ana lima", "Ana Lima", CreditRecord.Role.CAST, 1)

        SqliteMetadataStore(context, "kept.sqlite").apply {
            save(listOf(road, road.copy(key = "movie:9"), unknown))
            saveCredits(listOf(ruth, ana, kim), listOf("movie:longroad:2025", "movie:9"))
            saveCredits(listOf(ruth), listOf("series:harbor:"))
            markDeadImage("https://example.com/dead.jpg", now)
            markDeadImage("https://example.com/old.jpg", now - 10L * 24 * 60 * 60 * 1000)
            close()
        }

        val store = SqliteMetadataStore(context, "kept.sqlite")
        assertEquals(mapOf(road.key to road, unknown.key to unknown), store.titles(listOf(road.key, unknown.key, "movie:other:")))
        assertEquals("directors first, each in order", listOf(kim, ruth, ana), store.credits("movie:9"))
        assertEquals(listOf("movie:9", "movie:longroad:2025", "series:harbor:"), store.titleKeys("ruth ames"))
        assertEquals(
            listOf(IndexedPerson("Ruth Ames", "https://image.tmdb.org/t/p/w185/ruth.jpg", 3)),
            store.people("ames", 10),
        )
        assertEquals(listOf("Ana Lima"), store.people("lima an", 10).map { it.name })
        assertTrue("a % is a letter here, not a wildcard", store.people("%%%", 10).isEmpty())
        assertEquals(setOf("https://example.com/dead.jpg"), store.deadImages(now - 7L * 24 * 60 * 60 * 1000))

        // A title's credits are replaced, not added to.
        store.saveCredits(listOf(ana), listOf("movie:9"))
        assertEquals(listOf(ana), store.credits("movie:9"))

        store.removeAll()
        assertTrue(store.titles(listOf(road.key)).isEmpty())
        assertTrue(store.people("ruth", 10).isEmpty())
        assertTrue(store.deadImages(0).isEmpty())
        store.close()
    }

    /** A title nobody opened for months leaves the file when it opens. */
    @Test
    fun oldEntriesLeaveTheFile() {
        val old = System.currentTimeMillis() - 200L * 24 * 60 * 60 * 1000
        SqliteMetadataStore(context, "old.sqlite").apply {
            save(listOf(TitleMetadata("movie:a:", "movie", fetchedAt = old), TitleMetadata("movie:b:", "movie", fetchedAt = System.currentTimeMillis())))
            saveCredits(listOf(CreditRecord("kim park", "Kim Park", CreditRecord.Role.DIRECTOR, 0)), listOf("movie:a:"))
            close()
        }
        val store = SqliteMetadataStore(context, "old.sqlite")
        assertEquals(setOf("movie:b:"), store.titles(listOf("movie:a:", "movie:b:")).keys)
        assertTrue(store.titleKeys("kim park").isEmpty())
        store.close()
    }
}
