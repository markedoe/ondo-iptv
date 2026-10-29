package com.markedoe.ondo

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.res.Resources
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.pm.PackageInfoCompat
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
import com.markedoe.ondo.core.library.PlaceholderNames
import com.markedoe.ondo.core.net.OkHttpClientAdapter
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.AwardsDownload
import com.markedoe.ondo.data.CrashLog
import com.markedoe.ondo.data.LocalSync
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.data.PlayTipBilling
import com.markedoe.ondo.data.SqliteMetadataStore
import com.markedoe.ondo.data.TipJar
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
    override fun onCreate() {
        super.onCreate()
        // The next start shows what stopped the app in Settings → About.
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).let { "${it.versionName} (${PackageInfoCompat.getLongVersionCode(it)})" }
        }.getOrDefault("")
        // What the device gives the app: a memory error reads against these figures.
        val activities = getSystemService(ActivityManager::class.java)
        val memory = "memory class ${activities?.memoryClass} MB, large ${activities?.largeMemoryClass} MB, " +
            "max heap ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB, low RAM ${activities?.isLowRamDevice}"
        val about = "Ondo $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n$memory"
        val crashFile = filesDir.resolve("ondo").resolve(CrashLog.FILE_NAME)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) recordLastExit(crashFile, about)
        CrashLog.install(crashFile, about)
    }

    /**
     * Why Android ended the last run, when it was on screen and no Kotlin error was recorded: an app stopped for lack of
     * memory or for not responding leaves no stack trace of its own.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun recordLastExit(file: java.io.File, about: String) {
        val exit = runCatching {
            getSystemService(ActivityManager::class.java)?.getHistoricalProcessExitReasons(packageName, 0, 1)?.firstOrNull()
        }.getOrNull() ?: return
        // In the background, Android ends apps to make room: not a crash.
        if (exit.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) return
        val reason = when (exit.reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> "out of memory (the device's)"
            ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash in native code"
            ApplicationExitInfo.REASON_SIGNALED -> "signal ${exit.status}"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
            else -> return
        }
        val trace = if (exit.reason == ApplicationExitInfo.REASON_ANR) {
            runCatching { exit.traceInputStream?.bufferedReader()?.use { CrashLog.mainThread(it.readText()) } }.getOrNull()
        } else {
            null
        }
        CrashLog.recordExit(file, about, CrashLog.SystemExit(exit.timestamp, reason, exit.description, exit.pss, exit.rss, trace))
    }

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

    /** The optional tips of Settings, through Google Play (they unlock nothing). */
    val tips: TipJar by lazy { TipJar(PlayTipBilling(this), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) }

    val repository: OndoRepository by lazy {
        OndoRepository(
            store = AppStore(filesDir.resolve("ondo")),
            http = OkHttpClientAdapter(okHttp),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            awardsSource = { assets.open("awards.json").bufferedReader().use { it.readText() } },
            reminderScheduler = WorkManagerReminderScheduler(this),
            awardsDownload = AwardsDownload(AwardsDownload.URL, cacheDir.resolve("awards.json")),
            downloadFolder = cacheDir.resolve("downloads"),
            // A quarter of what the device gives the app: the catalogue, the screens and the images need the rest.
            guideBudgetBytes = Runtime.getRuntime().maxMemory() / 4,
            placeholderNames = resources.placeholderNames(),
            metadata = SqliteMetadataStore(this),
        )
    }

    /** Sync on the local network, while Ondo is in front (the player included) and the setting is on. */
    val localSync: LocalSync by lazy {
        LocalSync(this, repository, ProcessLifecycleOwner.get().lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) })
    }

    /**
     * Posters, thumbnails and logos stay on disk whatever the image server's cache headers say (Coil's default
     * strategy): one seen once comes back from the disk at the next launch, without a download. In memory, a fifth
     * of the app's share, a tenth on a low-memory device (an entry-level TV stick: Google's limits, TV-ME).
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { imageHttp })) }
        .memoryCache {
            val lowMemory = getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
            MemoryCache.Builder().maxSizePercent(context, if (lowMemory) 0.1 else 0.2).build()
        }
        .diskCache { artworkCache() }
        .crossfade(true)
        .build()

}

/**
 * The images seen, kept until the app is uninstalled: in the app's own storage, not its cache folder, which Android (a
 * TV with little storage above all) empties to free space. Left out of backups. Up to a tenth of the free space,
 * between 256 MB and 2 GB; past that, the images seen longest ago go first. Images kept by an earlier version (in the
 * cache folder) are moved there once.
 */
internal fun Context.artworkCache(): DiskCache {
    val folder = noBackupFilesDir.resolve("artwork")
    val earlier = cacheDir.resolve("artwork")
    if (!folder.exists() && earlier.isDirectory) earlier.renameTo(folder)
    return DiskCache.Builder()
        .directory(folder)
        .maxSizePercent(0.1)
        .minimumMaxSizeBytes(256L * 1024 * 1024)
        .maximumMaxSizeBytes(2L * 1024 * 1024 * 1024)
        .build()
}

/** The app's words for the names the core gives in English where the provider gives none ("Uncategorized", "Channel 12"…). */
fun Resources.placeholderNames(): PlaceholderNames = PlaceholderNames(
    uncategorized = getString(R.string.placeholder_uncategorized),
    other = getString(R.string.placeholder_other),
    otherNumbered = getString(R.string.placeholder_other_number),
    channel = getString(R.string.placeholder_channel),
    movie = getString(R.string.placeholder_movie),
    series = getString(R.string.placeholder_series),
    episode = getString(R.string.placeholder_episode),
    locale = configuration.locales[0],
)
