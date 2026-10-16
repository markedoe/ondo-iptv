package com.markedoe.ondo

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.markedoe.ondo.core.net.OkHttpClientAdapter
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.AwardsDownload
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.data.WorkManagerReminderScheduler
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/** Owns the app-wide objects: one HTTP client, the repository, the image loader. */
class OndoApplication : Application(), SingletonImageLoader.Factory {
    /** Playlists, guides and artwork share connections and the cache. */
    val okHttp: OkHttpClient by lazy { OkHttpClientAdapter.defaultClient() }

    /**
     * Artwork has its own queue (the shared one allows 5 requests per host) and shorter timeouts: posters do not
     * wait behind the provider's catalogue and guide downloads, and a dead image server gives up sooner.
     */
    private val imageHttp: OkHttpClient by lazy {
        okHttp.newBuilder()
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 10 })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    val repository: OndoRepository by lazy {
        OndoRepository(
            store = AppStore(filesDir.resolve("ondo")),
            http = OkHttpClientAdapter(okHttp),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            awardsSource = { assets.open("awards.json").bufferedReader().use { it.readText() } },
            reminderScheduler = WorkManagerReminderScheduler(this),
            awardsDownload = AwardsDownload(AwardsDownload.URL, cacheDir.resolve("awards.json")),
        )
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { imageHttp })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("artwork")).maxSizeBytes(256L * 1024 * 1024).build() }
        .crossfade(true)
        .build()
}
