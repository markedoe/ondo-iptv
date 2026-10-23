package com.markedoe.ondo.core.sync

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** The network side of sync (docs/SYNC.md): which addresses are local, and how a snapshot travels. */
object SyncNetwork {
    const val SERVICE_TYPE = "_ondo._tcp"
    const val MAX_FRAME = 16 * 1024 * 1024
    const val CONNECT_TIMEOUT_MS = 10_000
    const val SESSION_TIMEOUT_MS = 30_000
    const val MAX_SESSIONS = 4

    /**
     * Whether [address] is on the local network: IPv4 10/8, 172.16/12, 192.168/16, 169.254/16 and 127/8; IPv6
     * fc00::/7, fe80::/10 and ::1; an IPv4 address mapped in IPv6 is checked as IPv4. Sync talks to nothing else.
     */
    fun isLocal(address: InetAddress): Boolean {
        val bytes = address.address
        return when (address) {
            is Inet4Address -> isLocalV4(bytes)
            is Inet6Address -> isLocalV6(bytes)
            else -> false
        }
    }

    /** The same rule on raw address bytes (4 or 16). */
    fun isLocal(bytes: ByteArray): Boolean = when (bytes.size) {
        4 -> isLocalV4(bytes)
        16 -> isLocalV6(bytes)
        else -> false
    }

    private fun isLocalV4(b: ByteArray): Boolean {
        val first = b[0].toInt() and 0xFF
        val second = b[1].toInt() and 0xFF
        return first == 10 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) ||
            (first == 169 && second == 254) ||
            first == 127
    }

    private fun isLocalV6(b: ByteArray): Boolean {
        val mapped = (0 until 10).all { b[it].toInt() == 0 } && (b[10].toInt() and 0xFF) == 0xFF && (b[11].toInt() and 0xFF) == 0xFF
        if (mapped) return isLocalV4(b.copyOfRange(12, 16))
        val loopback = (0 until 15).all { b[it].toInt() == 0 } && b[15].toInt() == 1
        val first = b[0].toInt() and 0xFF
        val second = b[1].toInt() and 0xFF
        return loopback ||
            (first and 0xFE) == 0xFC ||
            (first == 0xFE && (second and 0xC0) == 0x80)
    }

    /** Writes one frame: the length on 4 bytes (big-endian), then [payload]. */
    fun writeFrame(output: OutputStream, payload: ByteArray) {
        require(payload.size <= MAX_FRAME) { "frame too large" }
        val size = payload.size
        output.write(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()))
        output.write(payload)
        output.flush()
    }

    /** Reads one frame; a frame over [MAX_FRAME] or cut short is an error. */
    fun readFrame(input: InputStream): ByteArray {
        val header = readFully(input, 4)
        val size = ((header[0].toInt() and 0xFF) shl 24) or ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        if (size < 0 || size > MAX_FRAME) throw IOException("frame too large")
        return readFully(input, size)
    }

    private fun readFully(input: InputStream, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = input.read(bytes, read, size - read)
            if (count < 0) throw EOFException("frame cut short")
            read += count
        }
        return bytes
    }
}
