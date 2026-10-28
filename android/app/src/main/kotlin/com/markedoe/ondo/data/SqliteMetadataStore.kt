package com.markedoe.ondo.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.markedoe.ondo.core.library.CreditRecord
import com.markedoe.ondo.core.library.IndexedPerson
import com.markedoe.ondo.core.library.MetadataStore
import com.markedoe.ondo.core.library.TitleMetadata

/**
 * The [MetadataStore] of the app: a SQLite file (`metadata.sqlite`, as on Apple devices). It is a
 * cache: a file that cannot be read is started again. [name] null keeps it in memory (tests).
 */
class SqliteMetadataStore(context: Context, name: String? = FILE_NAME) : MetadataStore {
    private val helper = object : SQLiteOpenHelper(context, name, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE title (key TEXT PRIMARY KEY, kind TEXT NOT NULL, tmdb_id INTEGER, backdrop TEXT, trailer TEXT, " +
                    "vote_average REAL, vote_count INTEGER, fetched_at INTEGER NOT NULL, found INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE credit (title_key TEXT NOT NULL, person_key TEXT NOT NULL, name TEXT NOT NULL, role TEXT NOT NULL, " +
                    "position INTEGER NOT NULL, photo TEXT, PRIMARY KEY (title_key, role, person_key))",
            )
            db.execSQL("CREATE INDEX credit_person ON credit (person_key)")
            db.execSQL("CREATE TABLE dead_image (url TEXT PRIMARY KEY, at INTEGER NOT NULL)")
        }

        /** Entries older than [KEPT_FOR_MS] leave the file: a title nobody opens for months is not kept for ever. */
        override fun onOpen(db: SQLiteDatabase) {
            if (db.isReadOnly) return
            val before = (System.currentTimeMillis() - KEPT_FOR_MS).toString()
            runCatching {
                db.delete("credit", "title_key IN (SELECT key FROM title WHERE fetched_at < ?)", arrayOf(before))
                db.delete("title", "fetched_at < ?", arrayOf(before))
                db.delete("dead_image", "at < ?", arrayOf(before))
            }
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = restart(db)

        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = restart(db)

        /** A cache: another layout starts empty. */
        private fun restart(db: SQLiteDatabase) {
            for (table in listOf("title", "credit", "dead_image")) db.execSQL("DROP TABLE IF EXISTS $table")
            onCreate(db)
        }
    }

    private val db: SQLiteDatabase? by lazy { runCatching { helper.writableDatabase }.getOrNull() }

    private inline fun <T> read(empty: T, block: (SQLiteDatabase) -> T): T {
        val db = db ?: return empty
        return runCatching { block(db) }.getOrDefault(empty)
    }

    private inline fun write(block: (SQLiteDatabase) -> Unit) {
        val db = db ?: return
        runCatching {
            db.beginTransaction()
            try {
                block(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    override fun titles(keys: List<String>): Map<String, TitleMetadata> = read(emptyMap()) { db ->
        val result = HashMap<String, TitleMetadata>()
        for (chunk in keys.distinct().chunked(MAX_ARGUMENTS)) {
            val marks = chunk.joinToString(",") { "?" }
            db.rawQuery(
                "SELECT key, kind, tmdb_id, backdrop, trailer, vote_average, vote_count, fetched_at, found FROM title WHERE key IN ($marks)",
                chunk.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    val key = c.getString(0)
                    result[key] = TitleMetadata(
                        key = key,
                        kind = c.getString(1),
                        tmdbId = if (c.isNull(2)) null else c.getInt(2),
                        backdropUrl = c.getString(3),
                        trailerKey = c.getString(4),
                        voteAverage = if (c.isNull(5)) null else c.getDouble(5),
                        voteCount = if (c.isNull(6)) null else c.getInt(6),
                        fetchedAt = c.getLong(7),
                        found = c.getInt(8) != 0,
                    )
                }
            }
        }
        result
    }

    override fun save(titles: List<TitleMetadata>) = write { db ->
        for (title in titles) {
            val values = ContentValues().apply {
                put("key", title.key)
                put("kind", title.kind)
                put("tmdb_id", title.tmdbId)
                put("backdrop", title.backdropUrl)
                put("trailer", title.trailerKey)
                put("vote_average", title.voteAverage)
                put("vote_count", title.voteCount)
                put("fetched_at", title.fetchedAt)
                put("found", if (title.found) 1 else 0)
            }
            db.insertWithOnConflict("title", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    override fun credits(titleKey: String): List<CreditRecord> = read(emptyList()) { db ->
        db.rawQuery(
            "SELECT person_key, name, role, position, photo FROM credit WHERE title_key = ? ORDER BY role = 'cast', position",
            arrayOf(titleKey),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val role = if (c.getString(2) == "director") CreditRecord.Role.DIRECTOR else CreditRecord.Role.CAST
                    add(CreditRecord(c.getString(0), c.getString(1), role, c.getInt(3), c.getString(4)))
                }
            }
        }
    }

    override fun saveCredits(credits: List<CreditRecord>, titleKeys: List<String>) = write { db ->
        for (key in titleKeys.distinct()) {
            db.delete("credit", "title_key = ?", arrayOf(key))
            for (credit in credits) {
                val values = ContentValues().apply {
                    put("title_key", key)
                    put("person_key", credit.personKey)
                    put("name", credit.name)
                    put("role", if (credit.role == CreditRecord.Role.DIRECTOR) "director" else "cast")
                    put("position", credit.position)
                    put("photo", credit.photoUrl)
                }
                db.insertWithOnConflict("credit", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
    }

    override fun titleKeys(personKey: String): List<String> = read(emptyList()) { db ->
        db.rawQuery("SELECT DISTINCT title_key FROM credit WHERE person_key = ? ORDER BY title_key", arrayOf(personKey)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    override fun people(query: String, limit: Int): List<IndexedPerson> = read(emptyList()) { db ->
        val tokens = MetadataStore.tokens(query) ?: return@read emptyList()
        // The longest word narrows the search in SQLite; every word is then checked here.
        val longest = tokens.maxBy { it.length }.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        db.rawQuery(
            "SELECT person_key, MIN(name), MAX(photo), COUNT(DISTINCT title_key) AS titles FROM credit " +
                "WHERE person_key LIKE ? ESCAPE '\\' GROUP BY person_key ORDER BY titles DESC, person_key LIMIT ?",
            arrayOf("%$longest%", (limit * 10).toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext() && size < limit) {
                    if (MetadataStore.matches(c.getString(0), tokens)) add(IndexedPerson(c.getString(1), c.getString(2), c.getInt(3)))
                }
            }
        }
    }

    override fun deadImages(since: Long): Set<String> = read(emptySet()) { db ->
        db.rawQuery("SELECT url FROM dead_image WHERE at >= ?", arrayOf(since.toString())).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    override fun markDeadImage(url: String, at: Long) = write { db ->
        db.insertWithOnConflict("dead_image", null, ContentValues().apply {
            put("url", url)
            put("at", at)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun removeAll() = write { db ->
        for (table in listOf("title", "credit", "dead_image")) db.delete(table, null, null)
    }

    fun close() = helper.close()

    companion object {
        const val FILE_NAME = "metadata.sqlite"
        private const val VERSION = 1
        private const val KEPT_FOR_MS = 180L * 24 * 60 * 60 * 1000

        /** SQLite's limit on the `?` of one statement is 999 on older Android versions. */
        private const val MAX_ARGUMENTS = 500
    }
}
