package com.markedoe.ondo

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

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

    /**
     * A poster or a cast photo seen once comes back from the disk, without the network, after the app was closed:
     * even when the image server says not to keep it (no-store), and with the network gone.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun anImageSeenOnceComesBackFromTheDiskWithoutTheNetwork() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        val png = ByteArrayOutputStream().also { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        var downloads = 0
        var online = true
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            if (!online) throw IOException("offline")
            downloads++
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Cache-Control", "no-store")
                .body(png.toResponseBody("image/png".toMediaType()))
                .build()
        }.build()
        val url = "https://image.example.com/t/p/w185/ruth.jpg"

        // The test machine cannot decode images (no ImageDecoder): what counts is the file kept, read again offline.
        val first = app.artworkLoader(http)
        first.execute(ImageRequest.Builder(app).data(url).build())
        assertEquals(1, downloads)
        assertNotNull("kept on the disk", first.diskCache?.openSnapshot(url)?.also { it.close() })
        first.shutdown()

        // The next launch, offline: a new loader on the same files.
        online = false
        val next = app.artworkLoader(http)
        assertNotNull("still on the disk", next.diskCache?.openSnapshot(url)?.also { it.close() })
        val result = next.execute(ImageRequest.Builder(app).data(url).memoryCachePolicy(CachePolicy.DISABLED).build())
        val failure = (result as? ErrorResult)?.throwable
        assertFalse("read from the disk, not the network: $failure", failure?.message == "offline")
        assertEquals("not downloaded again", 1, downloads)
        next.shutdown()
    }
}
