package com.markedoe.ondo.core.sync

import com.markedoe.ondo.core.HangGuard
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A sync server stopped closes its socket and ends, however soon after it started. */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncSessionStopTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    /**
     * Stopped before its other coroutines had a chance to run: the socket was left open, accept() blocked for ever and
     * whoever waited for the server to end (LocalSyncNetworkTest in CI, the app when sync stops) waited with it.
     */
    @Test
    fun aServerStoppedRightAfterItStartedClosesItsSocketAndEnds() {
        val dispatcher = StandardTestDispatcher()
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        try {
            // serve() runs on this thread until accept() moves to an IO thread; nothing queued on the dispatcher has run.
            val serving = CoroutineScope(dispatcher).launch(start = CoroutineStart.UNDISPATCHED) { SyncSession.serve(server) { _, _ -> null } }
            serving.cancel()
            val deadline = System.currentTimeMillis() + 10_000
            while (!serving.isCompleted && System.currentTimeMillis() < deadline) {
                dispatcher.scheduler.runCurrent()
                Thread.sleep(20)
            }
            assertTrue("the server socket is closed", server.isClosed)
            assertTrue("the server ended", serving.isCompleted)
        } finally {
            // Frees the IO thread left in accept() when the test fails.
            server.close()
        }
    }
}
