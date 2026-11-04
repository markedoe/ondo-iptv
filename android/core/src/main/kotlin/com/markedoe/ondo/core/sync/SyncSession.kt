package com.markedoe.ondo.core.sync

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

/**
 * One sync session each way (docs/SYNC.md, "Transport"): a client sends its snapshot, the server merges it and
 * answers with the merged snapshot. Both sides talk only to local addresses.
 */
object SyncSession {
    /**
     * Answers sessions on [server] until cancelled (then closes it). [merge] gets each snapshot received and the
     * address it came from, and gives the snapshot to answer with; null answers nothing. Connections from an address
     * [isAllowed] refuses, and those over [SyncNetwork.MAX_SESSIONS] at a time, are closed before anything is read.
     * [delivered] gets each snapshot whose answer reached its client: the client closed the connection after it.
     */
    suspend fun serve(
        server: ServerSocket,
        isAllowed: (InetAddress) -> Boolean = SyncNetwork::isLocal,
        delivered: suspend (SyncSnapshot) -> Unit = {},
        merge: suspend (SyncSnapshot, InetAddress) -> SyncSnapshot?,
    ) = coroutineScope {
        val sessions = Semaphore(SyncNetwork.MAX_SESSIONS)
        // Started at once, not dispatched: a coroutine cancelled before it runs never reaches its finally, and the
        // blocking accept() would then wait for ever on a server nobody closes.
        val closer = launch(start = CoroutineStart.UNDISPATCHED) { closing(server) }
        try {
            accept(server, sessions, isAllowed, delivered, merge)
        } finally {
            closer.cancel()
        }
    }

    private suspend fun accept(
        server: ServerSocket,
        sessions: Semaphore,
        isAllowed: (InetAddress) -> Boolean,
        delivered: suspend (SyncSnapshot) -> Unit,
        merge: suspend (SyncSnapshot, InetAddress) -> SyncSnapshot?,
    ) {
        withContext(Dispatchers.IO) {
            while (isActive) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                val address = client.inetAddress
                if (address == null || !isAllowed(address) || !sessions.tryAcquire()) {
                    client.closeQuietly()
                    continue
                }
                launch {
                    try {
                        answer(client, address, delivered, merge)
                    } catch (_: IOException) {
                        // The other side left, sent something else, or took too long: the session ends here.
                    } finally {
                        sessions.release()
                    }
                }
            }
        }
    }

    private suspend fun answer(
        client: Socket,
        address: InetAddress,
        delivered: suspend (SyncSnapshot) -> Unit,
        merge: suspend (SyncSnapshot, InetAddress) -> SyncSnapshot?,
    ) {
        withDeadline(client) {
            client.soTimeout = SyncNetwork.SESSION_TIMEOUT_MS
            val incoming = SyncSnapshot.decode(SyncNetwork.readFrame(client.getInputStream())) ?: return@withDeadline
            val answer = merge(incoming, address) ?: return@withDeadline
            // Written, then read by the client once it closes (end of stream): only then does the session count.
            val closed = withContext(Dispatchers.IO) {
                SyncNetwork.writeFrame(client.getOutputStream(), answer.encoded())
                client.getInputStream().read() == -1
            }
            if (closed) delivered(incoming)
        }
    }

    /**
     * Sends [snapshot] to the server at [address] and returns its answer: null when the address is not local, the
     * server could not be reached, or it answered nothing usable.
     */
    suspend fun exchange(
        address: InetAddress,
        port: Int,
        snapshot: SyncSnapshot,
        isAllowed: (InetAddress) -> Boolean = SyncNetwork::isLocal,
    ): SyncSnapshot? {
        if (!isAllowed(address) || port !in 1..65535) return null
        val socket = Socket()
        return try {
            withDeadline(socket) {
                withContext(Dispatchers.IO) {
                    socket.connect(InetSocketAddress(address, port), SyncNetwork.CONNECT_TIMEOUT_MS)
                    socket.soTimeout = SyncNetwork.SESSION_TIMEOUT_MS
                    SyncNetwork.writeFrame(socket.getOutputStream(), snapshot.encoded())
                    SyncSnapshot.decode(SyncNetwork.readFrame(socket.getInputStream()))
                }
            }
        } catch (_: IOException) {
            null
        }
    }

    /**
     * Runs [block] and closes [socket] when it ends, when the session time is up, or when the caller is cancelled:
     * closing the socket is what ends a blocking read or write.
     */
    private suspend fun <T> withDeadline(socket: Closeable, block: suspend () -> T): T = coroutineScope {
        // Started at once, like the closer of serve(): its finally closes the socket even if cancelled straight away.
        val deadline = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                delay(SyncNetwork.SESSION_TIMEOUT_MS.toLong())
            } finally {
                socket.closeQuietly()
            }
        }
        try {
            block()
        } finally {
            deadline.cancel()
            socket.closeQuietly()
        }
    }

    private suspend fun closing(socket: Closeable): Nothing = try {
        awaitCancellation()
    } finally {
        socket.closeQuietly()
    }

    private fun Closeable.closeQuietly() = try {
        close()
    } catch (_: IOException) {
    }
}
