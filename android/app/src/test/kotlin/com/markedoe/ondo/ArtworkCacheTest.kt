package com.markedoe.ondo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The images seen stay until the app is uninstalled: in the app's own storage, which Android does not empty to free
 * space as it does the cache folder, and at least 256 MB of them. The ones an earlier version kept in the cache folder
 * come along.
 */
@RunWith(AndroidJUnit4::class)
class ArtworkCacheTest {
    @Test
    fun imagesAreKeptOutsideTheCacheFolderAndTheEarlierOnesMoveThere() {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        val earlier = app.cacheDir.resolve("artwork").apply { mkdirs() }
        earlier.resolve("journal").writeText("kept by an earlier version")

        val cache = app.artworkCache()
        val folder = cache.directory.toFile()
        assertEquals(app.noBackupFilesDir.resolve("artwork").canonicalPath, folder.canonicalPath)
        assertFalse("not in the folder Android empties", folder.canonicalPath.startsWith(app.cacheDir.canonicalPath))
        assertEquals("kept by an earlier version", folder.resolve("journal").readText())
        assertFalse(earlier.exists())
        assertTrue("room for a large catalogue's posters", cache.maxSize >= 256L * 1024 * 1024)
    }
}
