package com.markedoe.ondo

import android.app.ActivityManager
import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
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
import com.markedoe.ondo.data.LocalSync
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.data.WorkManagerReminderScheduler
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/** Owns the app-wide objects: one HTTP client, the repository, sync on the local network, the image loader. */
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

    /** Sync on the local network, while Ondo is in front (the player included) and the setting is on. */
    val localSync: LocalSync by lazy {
        LocalSync(this, repository, ProcessLifecycleOwner.get().lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) })
    }

    /**
     * Posters, thumbnails and logos stay on disk whatever the image server's cache headers say (Coil's default
     * strategy): one seen once comes back from the disk at the next launch, without a download. The folder is in the
     * app's cache, which Android empties only when the device runs out of space. In memory, a fifth of the app's
     * share, a tenth on a low-memory device (an entry-level TV stick: Google's limits, TV-ME).
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { imageHttp })) }
        .memoryCache {
            val lowMemory = getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
            MemoryCache.Builder().maxSizePercent(context, if (lowMemory) 0.1 else 0.2).build()
        }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("artwork")).maxSizeBytes(512L * 1024 * 1024).build() }
        .crossfade(true)
        .build()
}
