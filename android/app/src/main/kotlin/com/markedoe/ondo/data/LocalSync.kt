package com.markedoe.ondo.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.markedoe.ondo.core.sync.SyncNetwork
import com.markedoe.ondo.core.sync.SyncSession
import com.markedoe.ondo.core.sync.SyncSnapshot
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sync on the local network (docs/SYNC.md) while Ondo is in front, the setting is on and a Wi-Fi or wired network is
 * connected: this device listens for sessions and announces itself with Bonjour (`_ondo._tcp`), finds the other
 * Android devices that do the same and syncs with them. iPhone, iPad and Mac connect to it. Only other devices of the
 * local network are ever reached or answered ([isPeerAddress]).
 */
class LocalSync(
    private val context: Context,
    private val repository: OndoRepository,
    /** Whether Ondo is in front (the process has a started screen). */
    private val foreground: Flow<Boolean>,
    /** The wall clock, for the time of the last session shown in Settings. */
    private val now: () -> Long = System::currentTimeMillis,
    /** A clock that never goes back (not the wall clock, which a time server may move), for when sessions are due. */
    private val ticks: () -> Long = SystemClock::elapsedRealtime,
    /** Whether a Wi-Fi or wired network is connected: not on mobile data alone. */
    private val localNetwork: Flow<Boolean> = onLocalNetwork(context),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            runs(foreground, repository.state.map { it.isReady && it.settings.syncOnLocalNetwork }, localNetwork)
                .collectLatest { on ->
                    // Sync never takes the app down: after an error it starts again a minute later.
                    while (on) {
                        try {
                            run()
                            break
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            delay(RESTART_MS)
                        } catch (_: OutOfMemoryError) {
                            // A frame from another device too large for this one's memory: not a stop of the app.
                            delay(RESTART_MS)
                        }
                    }
                }
        }
    }

    /** The answer to a snapshot or a probe received; null (and nothing changed) when this device cannot use it. */
    private suspend fun answer(incoming: SyncSnapshot, name: String): SyncSnapshot? =
        try {
            repository.answerSync(incoming, name)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            null
        }

    /** Merges a snapshot received, off the main thread; null (and nothing changed) when this device cannot use it. */
    private suspend fun merge(incoming: SyncSnapshot, name: String): SyncSnapshot? =
        try {
            repository.mergeSyncInBackground(incoming, name)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            null
        }

    private sealed interface Event {
        data class Registered(val serviceName: String) : Event
        data class Found(val service: NsdServiceInfo) : Event
        data class Resolved(val serviceName: String, val service: NsdServiceInfo, val id: String, val name: String, val address: InetAddress, val port: Int) : Event
        data class Lost(val serviceName: String) : Event
        data class Heard(val id: String, val name: String?) : Event
        data class Done(val id: String, val worked: Boolean) : Event
        /** A regular session put off during playback: tried again at the next period. */
        data class Deferred(val id: String) : Event
        data object Changed : Event
        data object Tick : Event
    }

    /** Another device: found with Bonjour (an Android device, with an address), or only heard from (iPhone, iPad, Mac). */
    private class Peer(val id: String, var name: String) {
        var serviceName: String? = null
        var service: NsdServiceInfo? = null
        var address: InetAddress? = null
        var port = 0
        var syncedAt: Long? = null
        var failures = 0
        var dueAt = Long.MAX_VALUE
        var busy = false
        /** Its address is to be resolved again before the next session (the last one failed). */
        var stale = false
        /** The digest of the snapshot this device last sent it, and the one it answered with (docs/SYNC.md, "Transport"). */
        var sentDigest: String? = null
        var answerDigest: String? = null
        /** When this device last sent it a full snapshot: probes go only in between (docs/SYNC.md, "Transport"). */
        var fullAt = Long.MIN_VALUE / 2
    }

    /** Runs until cancelled (Ondo goes to the background, or sync is turned off): then everything stops. */
    @OptIn(FlowPreview::class)
    private suspend fun run() = coroutineScope {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return@coroutineScope
        val me = repository.syncDeviceId()
        val name = deviceName()
        val server = try {
            withContext(Dispatchers.IO) { ServerSocket(0) }
        } catch (_: IOException) {
            return@coroutineScope
        }
        val events = Channel<Event>(Channel.UNLIMITED)
        launch {
            SyncSession.serve(
                server,
                isAllowed = ::isPeerAddress,
                // A full session counts once its answer has reached the client (it closed after reading it).
                delivered = { incoming -> if (!incoming.isProbe) withContext(Dispatchers.Main) { repository.noteSyncSession(incoming.device) } },
            ) { incoming, _ ->
                withContext(Dispatchers.Main) {
                    if (incoming.device == me || incoming.device.isBlank()) return@withContext null
                    answer(incoming, name)?.also { events.trySend(Event.Heard(incoming.device, incoming.deviceName)) }
                }
            }
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { events.trySend(Event.Registered(info.serviceName)) }
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { events.trySend(Event.Found(info)) }
            override fun onServiceLost(info: NsdServiceInfo) { events.trySend(Event.Lost(info.serviceName)) }
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) = Unit
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
        }
        val service = NsdServiceInfo().apply {
            serviceName = "Ondo-${me.take(8)}"
            serviceType = SyncNetwork.SERVICE_TYPE
            port = server.localPort
            setAttribute("d", me)
            setAttribute("v", SyncSnapshot.VERSION.toString())
            setAttribute("n", name)
        }
        val multicast = multicastLock()
        val peers = HashMap<String, Peer>()
        val resolving = Mutex()
        var ownServiceName = service.serviceName

        fun publish() = repository.reportSyncDevices(
            peers.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Peer::name).thenBy(Peer::id))
                .map { SyncDevice(it.id, it.name, it.syncedAt, unreachable = it.failures > 0) },
        )

        // A session with each peer that is due: the client sends this device's snapshot and keeps the merged answer, or
        // only a probe when nothing changed here since the last session, with a full snapshot at least every hour
        // (docs/SYNC.md, "Transport"). Everything is worked out off the main thread. During playback, a regular session
        // that would send a large snapshot waits for the next period.
        fun startDue(regular: Boolean = false) {
            val time = ticks()
            peers.values.filter { !it.busy && it.address != null && it.dueAt <= time }.forEach { peer ->
                peer.busy = true
                launch {
                    // During playback, no full snapshot is built only to be put off: a probe goes (docs/SYNC.md).
                    val full = time - peer.fullAt >= FULL_EVERY_MS && !repository.isPlaying
                    val prepared = repository.syncOffer(name, peer.sentDigest, peer.answerDigest, full)
                    val offer = prepared?.first
                    val size = prepared?.second ?: 0
                    if (regular && offer != null && !offer.isProbe && repository.isPlaying && size > LIGHT_SNAPSHOT_BYTES) {
                        events.send(Event.Deferred(peer.id))
                        return@launch
                    }
                    var address = peer.address
                    var port = peer.port
                    if (peer.stale) {
                        val found = peer.service?.let { resolving.withLock { resolve(nsd, it) } }?.let(::endpoint)
                        address = found?.first
                        port = found?.second ?: 0
                    }
                    val answer = if (address == null || offer == null) null else SyncSession.exchange(address, port, offer, isAllowed = ::isPeerAddress)
                    val worked = answer != null && answer.device != me && (answer.isUnchanged || merge(answer, name) != null)
                    if (worked && answer != null && !answer.isUnchanged) {
                        // The server has what was sent (a probe sent nothing new); its digest says what it has now.
                        if (offer != null && !offer.isProbe) {
                            peer.sentDigest = offer.digest
                            peer.fullAt = time
                            repository.noteSyncSession(answer.device)
                        }
                        peer.answerDigest = answer.digest
                    }
                    if (worked && peer.stale) {
                        peer.address = address
                        peer.port = port
                    }
                    events.send(Event.Done(peer.id, worked))
                }
            }
        }

        try {
            nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, registration)
            nsd.discoverServices(SyncNetwork.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
            launch {
                // Changes made here only: what a merge brought travels on with the next regular session (no session loop).
                repository.localSyncChanges.debounce(CHANGE_DELAY_MS).collect { events.send(Event.Changed) }
            }
            launch {
                while (true) {
                    delay(TICK_MS)
                    events.send(Event.Tick)
                }
            }
            for (event in events) {
                when (event) {
                    is Event.Registered -> ownServiceName = event.serviceName
                    is Event.Found -> if (event.service.serviceName != ownServiceName) launch {
                        val resolved = resolving.withLock { resolve(nsd, event.service) } ?: return@launch
                        val attributes = resolved.attributes
                        val id = attributes["d"]?.decodeToString()?.trim()?.lowercase()
                        val version = attributes["v"]?.decodeToString()
                        val endpoint = endpoint(resolved)
                        if (id.isNullOrEmpty() || id.length > MAX_ID || id == me || version != SyncSnapshot.VERSION.toString() || endpoint == null) return@launch
                        val peerName = attributes["n"]?.decodeToString()?.take(MAX_NAME)?.ifBlank { null } ?: resolved.serviceName
                        events.send(Event.Resolved(event.service.serviceName, event.service, id, peerName, endpoint.first, endpoint.second))
                    }
                    is Event.Resolved -> {
                        val peer = peers.getOrPut(event.id) { Peer(event.id, event.name) }
                        peer.name = event.name
                        peer.serviceName = event.serviceName
                        peer.service = event.service
                        peer.address = event.address
                        peer.port = event.port
                        peer.stale = false
                        peer.failures = 0
                        // Between two Android devices, the smaller ID connects (docs/SYNC.md, "Transport").
                        if (me < peer.id) peer.dueAt = ticks()
                        publish()
                        startDue()
                    }
                    is Event.Lost -> {
                        peers.values.filter { it.serviceName == event.serviceName }.forEach { peers.remove(it.id) }
                        publish()
                    }
                    is Event.Heard -> {
                        val peer = peers.getOrPut(event.id) { Peer(event.id, event.name ?: event.id.take(8)) }
                        event.name?.take(MAX_NAME)?.ifBlank { null }?.let { peer.name = it }
                        peer.syncedAt = now()
                        peer.failures = 0
                        publish()
                    }
                    is Event.Done -> {
                        val peer = peers[event.id] ?: continue
                        peer.busy = false
                        if (event.worked) {
                            peer.syncedAt = now()
                            peer.failures = 0
                            peer.stale = false
                            peer.dueAt = if (me < peer.id) ticks() + PERIOD_MS else Long.MAX_VALUE
                        } else {
                            peer.failures++
                            peer.stale = true
                            peer.dueAt = ticks() + RETRIES_MS[(peer.failures - 1).coerceAtMost(RETRIES_MS.lastIndex)]
                        }
                        publish()
                    }
                    is Event.Deferred -> {
                        val peer = peers[event.id] ?: continue
                        peer.busy = false
                        peer.dueAt = ticks() + PERIOD_MS
                    }
                    // Either side connects soon after a change of its own.
                    Event.Changed -> {
                        peers.values.filter { it.address != null }.forEach { it.dueAt = minOf(it.dueAt, ticks()) }
                        startDue()
                    }
                    Event.Tick -> startDue(regular = true)
                }
            }
        } finally {
            runCatching { nsd.stopServiceDiscovery(discovery) }
            runCatching { nsd.unregisterService(registration) }
            runCatching { multicast?.release() }
            repository.reportSyncDevices(emptyList())
        }
    }

    /** The service's address and port, when it is another device's (IPv4 first: an IPv6 link-local one needs its interface). */
    private fun endpoint(service: NsdServiceInfo): Pair<InetAddress, Int>? {
        @Suppress("DEPRECATION")
        val addresses = if (Build.VERSION.SDK_INT >= 34) service.hostAddresses else listOfNotNull(service.host)
        val address = addresses.filter(::isPeerAddress).sortedBy { it !is Inet4Address }.firstOrNull() ?: return null
        return (address to service.port).takeIf { service.port in 1..65535 }
    }

    /** One resolution at a time (older Android versions refuse a second one while one runs); null when it fails. */
    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, service: NsdServiceInfo): NsdServiceInfo? = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val answered = AtomicBoolean(false)
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                    if (answered.compareAndSet(false, true)) continuation.resume(null)
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    if (answered.compareAndSet(false, true)) continuation.resume(info)
                }
            }
            continuation.invokeOnCancellation {
                if (Build.VERSION.SDK_INT >= 34) runCatching { nsd.stopServiceResolution(listener) }
            }
            try {
                nsd.resolveService(service, listener)
            } catch (_: IllegalArgumentException) {
                if (answered.compareAndSet(false, true)) continuation.resume(null)
            }
        }
    }

    /** Lets the other devices' announcements through Wi-Fi's power saving while sync runs (nothing without Wi-Fi). */
    private fun multicastLock(): WifiManager.MulticastLock? = runCatching {
        context.applicationContext.getSystemService(WifiManager::class.java)?.createMulticastLock("ondo-sync")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()

    /** The name the device has in the system's settings, or its model. */
    private fun deviceName(): String =
        (Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.trim()?.ifBlank { null } ?: Build.MODEL).take(MAX_NAME)

    companion object {
        private const val TICK_MS = 5_000L
        private const val PERIOD_MS = 60_000L
        /** A full snapshot goes to each server at least this often, probes in between. */
        private const val FULL_EVERY_MS = 60 * 60_000L
        /** A regular session during playback goes only with a snapshot this small (or a probe). */
        private const val LIGHT_SNAPSHOT_BYTES = 64 * 1024
        private const val CHANGE_DELAY_MS = 3_000L
        private const val RESOLVE_TIMEOUT_MS = 10_000L
        private const val RESTART_MS = 60_000L
        private const val MAX_ID = 64
        private const val MAX_NAME = 40
        private val RETRIES_MS = longArrayOf(15_000L, 60_000L, 300_000L)

        /**
         * Another device of the local network ([SyncNetwork.isLocal]), never this one: another app of this device could
         * listen or ask on a loopback address, and the snapshots hold the playlists' logins.
         */
        fun isPeerAddress(address: InetAddress): Boolean = !address.isLoopbackAddress && SyncNetwork.isLocal(address)

        /** Wi-Fi or wired networks, with or without internet (a home network may have none). */
        internal fun localNetworkRequest(): NetworkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        /**
         * Whether a Wi-Fi or wired network is connected. On mobile data alone, private addresses belong to the
         * carrier's network, not to the viewer's home: sync waits.
         */
        fun onLocalNetwork(context: Context): Flow<Boolean> = callbackFlow {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val networks = HashSet<Network>()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    synchronized(networks) {
                        networks += network
                        trySend(true)
                    }
                }

                override fun onLost(network: Network) {
                    synchronized(networks) {
                        networks -= network
                        trySend(networks.isNotEmpty())
                    }
                }
            }
            val watching = connectivity != null && try {
                connectivity.registerNetworkCallback(localNetworkRequest(), callback)
                true
            } catch (_: RuntimeException) {
                false
            }
            // Until the system answers, no network; without its answers at all, sync is not held back.
            if (watching) trySend(synchronized(networks) { networks.isNotEmpty() }) else trySend(true)
            awaitClose { if (watching) runCatching { connectivity.unregisterNetworkCallback(callback) } }
        }.distinctUntilChanged()

        /** Sync runs while Ondo is in [front], the setting is [on] and a local network is connected ([lan]). */
        internal fun runs(front: Flow<Boolean>, on: Flow<Boolean>, lan: Flow<Boolean>): Flow<Boolean> =
            combine(front, on, lan) { inFront, settingOn, onLan -> inFront && settingOn && onLan }.distinctUntilChanged()
    }
}
