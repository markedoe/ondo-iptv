package com.markedoe.ondo.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.markedoe.ondo.core.library.CreditRecord
import com.markedoe.ondo.core.library.IndexedPerson
import com.markedoe.ondo.core.library.MetadataFreshness
import com.markedoe.ondo.core.library.RelatedTitle
import com.markedoe.ondo.core.library.TitleMetadata
import com.markedoe.ondo.core.library.TmdbGenre
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What TMDb said stays in a SQLite file between launches: titles, their credits, the people in them, dead images. */
@RunWith(AndroidJUnit4::class)
class SqliteMetadataStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Genres and the titles TMDb recommends are kept with a title; a genre's titles are found by its name. */
    @Test
    fun genresAndRecommendationsAreKept() {
        val now = System.currentTimeMillis()
        val related = listOf(RelatedTitle(false, 6, "Dock Street", 2015), RelatedTitle(true, 7, "Port\tWatch", null))
        val harbor = TitleMetadata("movie:5", "movie", 5, fetchedAt = now, genres = listOf(TmdbGenre(80, "Comédie"), TmdbGenre(18, "Drama")), related = related)
        val store = SqliteMetadataStore(context, "genres.sqlite")
        store.save(listOf(harbor, TitleMetadata("movie:6", "movie", 6, fetchedAt = now, genres = emptyList())))
        val read = store.titles(listOf("movie:5")).getValue("movie:5")
        assertEquals(harbor.genres, read.genres)
        assertEquals(listOf(RelatedTitle(false, 6, "Dock Street", 2015), RelatedTitle(true, 7, "Port Watch", null)), read.related)
        assertEquals(listOf("movie:5"), store.titlesOfGenre("comedie", series = false).map { it.key })
        assertTrue(store.titlesOfGenre("comedie", series = true).isEmpty())
    }

    /** A poster's facts are read for the titles kept under their name: found ones only, without what TMDb recommends. */
    @Test
    fun namedTitlesAreTheFoundTitlesKeptUnderTheirName() {
        val now = System.currentTimeMillis()
        val store = SqliteMetadataStore(context, "named.sqlite")
        val named = TitleMetadata("movie:quiet water:2021", "movie", 5, voteAverage = 7.2, voteCount = 40, fetchedAt = now, genres = listOf(TmdbGenre(18, "Drama")), related = listOf(RelatedTitle(false, 6, "Dock Street", 2015)))
        store.save(
            listOf(
                named,
                named.copy(key = "movie:5"),
                TitleMetadata("movie:nowhere:", "movie", fetchedAt = now, found = false),
            ),
        )
        val read = store.namedTitles()
        assertEquals(listOf("movie:quiet water:2021"), read.map { it.key })
        assertEquals(named.genres, read.single().genres)
        assertEquals(com.markedoe.ondo.core.library.TmdbRating(7.2, 40), read.single().rating)
        assertEquals(null, read.single().related)
    }

    /** A person's movies and series on TMDb stay on the device: their page shows at once the next time. */
    @Test
    fun aPersonsWorksAreKept() {
        val works = listOf(RelatedTitle(false, 5, "Quiet Water", 2021), RelatedTitle(true, 7, "Port Watch", null))
        // Asked now: entries months old leave the file when it opens.
        val askedAt = System.currentTimeMillis()
        SqliteMetadataStore(context, "works.sqlite").apply {
            savePersonWorks("ana lima", askedAt, works)
            close()
        }
        assertEquals(askedAt to works, SqliteMetadataStore(context, "works.sqlite").personWorks("ana lima"))
        assertEquals(null, SqliteMetadataStore(context, "works.sqlite").personWorks("nobody"))
    }

    /** A file of the first layout keeps its titles; they lack genres, so they are asked again as they show. */
    @Test
    fun aFileOfTheFirstLayoutIsKept() {
        val file = context.getDatabasePath("old.sqlite").apply { parentFile?.mkdirs() }
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE title (key TEXT PRIMARY KEY, kind TEXT NOT NULL, tmdb_id INTEGER, backdrop TEXT, trailer TEXT, " +
                    "vote_average REAL, vote_count INTEGER, fetched_at INTEGER NOT NULL, found INTEGER NOT NULL)",
            )
            db.execSQL("CREATE TABLE credit (title_key TEXT NOT NULL, person_key TEXT NOT NULL, name TEXT NOT NULL, role TEXT NOT NULL, position INTEGER NOT NULL, photo TEXT, PRIMARY KEY (title_key, role, person_key))")
            db.execSQL("CREATE TABLE dead_image (url TEXT PRIMARY KEY, at INTEGER NOT NULL)")
            val now = System.currentTimeMillis()
            db.execSQL("INSERT INTO title VALUES ('movie:9', 'movie', 9, 'https://image.tmdb.org/t/p/w1280/road.jpg', NULL, NULL, NULL, $now, 1)")
            db.version = 1
        }
        val kept = SqliteMetadataStore(context, "old.sqlite").titles(listOf("movie:9")).getValue("movie:9")
        assertEquals("https://image.tmdb.org/t/p/w1280/road.jpg", kept.backdropUrl)
        assertEquals(null, kept.genres)
        assertTrue("asked again", !MetadataFreshness.isFresh(kept, System.currentTimeMillis()))
    }

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
