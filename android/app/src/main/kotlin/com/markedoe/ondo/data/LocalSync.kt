package com.markedoe.ondo.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sync on the local network (docs/SYNC.md) while Ondo is in front and the setting is on: this device listens for
 * sessions and announces itself with Bonjour (`_ondo._tcp`), finds the other Android devices that do the same and
 * syncs with them. iPhone, iPad and Mac connect to it. Only local addresses are ever reached or answered.
 */
class LocalSync(
    private val context: Context,
    private val repository: OndoRepository,
    /** Whether Ondo is in front (the process has a started screen). */
    private val foreground: Flow<Boolean>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(foreground, repository.state.map { it.isReady && it.settings.syncOnLocalNetwork }) { front, on -> front && on }
                .distinctUntilChanged()
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
                        }
                    }
                }
        }
    }

    /** Merges a snapshot received; null (and nothing changed) when this device cannot use it. */
    private fun merge(incoming: SyncSnapshot, name: String): SyncSnapshot? =
        try {
            repository.mergeSync(incoming, name)
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
            SyncSession.serve(server) { incoming, _ ->
                withContext(Dispatchers.Main) {
                    if (incoming.device == me || incoming.device.isBlank()) return@withContext null
                    merge(incoming, name)?.also { events.trySend(Event.Heard(incoming.device, incoming.deviceName)) }
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

        // A session with each peer that is due: the client sends this device's snapshot and keeps the merged answer.
        fun startDue() {
            val time = now()
            peers.values.filter { !it.busy && it.address != null && it.dueAt <= time }.forEach { peer ->
                peer.busy = true
                launch {
                    var address = peer.address
                    var port = peer.port
                    if (peer.stale) {
                        val found = peer.service?.let { resolving.withLock { resolve(nsd, it) } }?.let(::endpoint)
                        address = found?.first
                        port = found?.second ?: 0
                    }
                    val snapshot = repository.syncSnapshot(name)
                    val answer = if (address == null || snapshot == null) null else SyncSession.exchange(address, port, snapshot)
                    val worked = answer != null && answer.device != me && merge(answer, name) != null
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
                repository.state.map(SyncData::changes).distinctUntilChanged().drop(1).debounce(CHANGE_DELAY_MS).collect { events.send(Event.Changed) }
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
                        if (me < peer.id) peer.dueAt = now()
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
                            peer.dueAt = if (me < peer.id) now() + PERIOD_MS else Long.MAX_VALUE
                        } else {
                            peer.failures++
                            peer.stale = true
                            peer.dueAt = now() + RETRIES_MS[(peer.failures - 1).coerceAtMost(RETRIES_MS.lastIndex)]
                        }
                        publish()
                    }
                    // Either side connects soon after a change of its own.
                    Event.Changed -> {
                        peers.values.filter { it.address != null }.forEach { it.dueAt = minOf(it.dueAt, now()) }
                        startDue()
                    }
                    Event.Tick -> startDue()
                }
            }
        } finally {
            runCatching { nsd.stopServiceDiscovery(discovery) }
            runCatching { nsd.unregisterService(registration) }
            runCatching { multicast?.release() }
            repository.reportSyncDevices(emptyList())
        }
    }

    /** The service's address and port, when its address is local (IPv4 first: an IPv6 link-local one needs its interface). */
    private fun endpoint(service: NsdServiceInfo): Pair<InetAddress, Int>? {
        @Suppress("DEPRECATION")
        val addresses = if (Build.VERSION.SDK_INT >= 34) service.hostAddresses else listOfNotNull(service.host)
        val address = addresses.filter(SyncNetwork::isLocal).sortedBy { it !is Inet4Address }.firstOrNull() ?: return null
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

    private companion object {
        const val TICK_MS = 5_000L
        const val PERIOD_MS = 60_000L
        const val CHANGE_DELAY_MS = 3_000L
        const val RESOLVE_TIMEOUT_MS = 10_000L
        const val RESTART_MS = 60_000L
        const val MAX_ID = 64
        const val MAX_NAME = 40
        val RETRIES_MS = longArrayOf(15_000L, 60_000L, 300_000L)
    }
}
