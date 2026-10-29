package com.markedoe.ondo.screenshots

import android.content.Context
import android.graphics.BitmapFactory
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import java.io.File
import kotlinx.coroutines.Dispatchers

/**
 * The demo catalogue's pictures in screenshots: an address ending with a file name of `fixtures/showcase/art` is
 * answered with that file, at once and on the calling thread, so that a screenshot shows the picture rather than
 * the cover drawn while it loads. Any other address fails (the app's fallback shows, as it would on a device).
 */
object ShowcaseImages {
    /** The pictures, in the repository (tests run from `android/app`). */
    val folder: File = File("../../fixtures/showcase/art").canonicalFile

    fun install(context: Context) {
        val loader = ImageLoader.Builder(context)
            .components { add(LocalPictures(folder)) }
            .coroutineContext(Dispatchers.Unconfined)
            .mainCoroutineContext(Dispatchers.Unconfined)
            .build()
        SingletonImageLoader.setUnsafe(loader)
    }

    fun reset() = SingletonImageLoader.reset()

    private class LocalPictures(private val folder: File) : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val request = chain.request
            val name = (request.data as? String)?.substringAfterLast('/')?.substringBefore('?')
            val file = name?.let { File(folder, it) }?.takeIf { it.isFile }
            val bitmap = file?.let { BitmapFactory.decodeFile(it.path) }
                ?: return ErrorResult(null, request, IllegalArgumentException("Not a demo picture: ${request.data}"))
            return SuccessResult(bitmap.asImage(), request, DataSource.DISK)
        }
    }
}
