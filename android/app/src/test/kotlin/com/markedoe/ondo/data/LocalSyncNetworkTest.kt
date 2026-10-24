package com.markedoe.ondo.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.markedoe.ondo.core.sync.SyncNetwork
import com.markedoe.ondo.core.sync.SyncSession
import com.markedoe.ondo.core.sync.SyncSnapshot
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork

/**
 * Who this device syncs with (docs/SYNC.md): devices of the local network only, never another app on this device
 * (a loopback address), and only while it is on a Wi-Fi or wired network, not on mobile data alone.
 */
@RunWith(RobolectricTestRunner::class)
class LocalSyncNetworkTest {
    private val snapshot = SyncSnapshot(device = "11111111-2222-3333-4444-555555555555", deviceName = "Phone")
    private val answer = SyncSnapshot(device = "99999999-2222-3333-4444-555555555555", deviceName = "TV")

    private fun address(text: String) = InetAddress.getByName(text)

    @Test
    fun devicesOfTheLocalNetworkAreReachedAndNothingElse() {
        assertTrue(LocalSync.isPeerAddress(address("192.168.1.20"))) // leak-guard: allow
        assertTrue(LocalSync.isPeerAddress(address("10.0.0.7"))) // leak-guard: allow
        assertTrue(LocalSync.isPeerAddress(address("fe80::1")))
        // Another app on this device.
        assertFalse(LocalSync.isPeerAddress(address("127.0.0.1")))
        assertFalse(LocalSync.isPeerAddress(address("127.12.0.1")))
        assertFalse(LocalSync.isPeerAddress(address("::1")))
        assertFalse(LocalSync.isPeerAddress(address("::ffff:127.0.0.1")))
        // The internet.
        assertFalse(LocalSync.isPeerAddress(address("8.8.8.8")))
    }

    /** An app on this device that connects to the sync port gets nothing: no playlist, no login. */
    @Test
    fun anotherAppOnThisDeviceGetsNoAnswer() = runBlocking {
        val loopback = InetAddress.getLoopbackAddress()
        // The shared rule alone would answer it.
        ServerSocket(0, 8, loopback).let { server ->
            val serving = launch(Dispatchers.IO) { SyncSession.serve(server, isAllowed = SyncNetwork::isLocal) { _, _ -> answer } }
            assertNotNull(SyncSession.exchange(loopback, server.localPort, snapshot, isAllowed = { true }))
            serving.cancelAndJoin()
        }
        ServerSocket(0, 8, loopback).let { server ->
            var merged = false
            val serving = launch(Dispatchers.IO) { SyncSession.serve(server, isAllowed = LocalSync::isPeerAddress) { _, _ -> merged = true; answer } }
            assertNull(SyncSession.exchange(loopback, server.localPort, snapshot, isAllowed = { true }))
            assertFalse(merged)
            serving.cancelAndJoin()
        }
    }

    @Test
    fun syncRunsOnlyWhileAWifiOrWiredNetworkIsConnected() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val main = shadowOf(android.os.Looper.getMainLooper())
        val seen = MutableStateFlow<Boolean?>(null)
        val watching = CoroutineScope(Dispatchers.Main.immediate).launch { LocalSync.onLocalNetwork(context).collect { seen.value = it } }
        main.idle()
        assertEquals("nothing connected yet", false, seen.value)
        val callbacks = shadowOf(connectivity).networkCallbacks
        assertEquals(1, callbacks.size)
        val wifi = ShadowNetwork.newInstance(1)
        val ethernet = ShadowNetwork.newInstance(2)
        callbacks.single().onAvailable(wifi)
        main.idle()
        assertEquals(true, seen.value)
        callbacks.single().onAvailable(ethernet)
        callbacks.single().onLost(wifi)
        main.idle()
        assertEquals("the wired network is still there", true, seen.value)
        callbacks.single().onLost(ethernet)
        main.idle()
        assertEquals(false, seen.value)
        watching.cancel()
        main.idle()
        assertTrue("the callback goes with the last watcher", shadowOf(connectivity).networkCallbacks.isEmpty())
    }

    @Test
    fun theRequestAsksForWifiOrEthernetEvenWithoutInternet() {
        val request = LocalSync.localNetworkRequest()
        assertTrue(request.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        assertTrue(request.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        assertFalse(request.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
        // A home network without internet is still the home network.
        assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
    }

    @Test
    fun syncRunsInFrontWithTheSettingOnAndOnTheLocalNetwork() = runBlocking {
        val front = MutableStateFlow(true)
        val on = MutableStateFlow(true)
        val lan = MutableStateFlow(false)
        val runs = LocalSync.runs(front, on, lan)
        assertFalse(withTimeout(1_000) { runs.first() })
        lan.value = true
        assertTrue(withTimeout(1_000) { runs.first() })
        front.value = false
        assertFalse(withTimeout(1_000) { runs.first() })
    }
}
