package com.markedoe.ondo.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The last error that stopped the app: kept on the device for Settings → About, without addresses or logins. */
class CrashLogTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun anUncaughtErrorIsRecordedThenHandedToTheSystem() {
        val file = File(folder.root, "ondo/${CrashLog.FILE_NAME}")
        val system = Thread.getDefaultUncaughtExceptionHandler()
        var handed: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, error -> handed = error }
        try {
            CrashLog.install(file, "Ondo 1.1.0 (1007) · Android 12 (API 31) · Test TV", now = { 1_760_000_000_000L })
            val error = OutOfMemoryError("Failed to allocate a 268435472 byte allocation")
            val thread = Thread { throw error }.apply { name = "guide" }
            thread.start()
            thread.join()
            assertSame(error, handed)
            val crash = requireNotNull(CrashLog.read(file))
            assertEquals(1_760_000_000_000L, crash.at)
            assertTrue(crash.report.startsWith("Ondo 1.1.0 (1007) · Android 12 (API 31) · Test TV\nThread: guide\njava.lang.OutOfMemoryError: Failed to allocate"))
            assertTrue(crash.report.contains("CrashLogTest"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(system)
        }
    }

    @Test
    fun addressesAndLoginsAreLeftOut() {
        val text = CrashLog.redact(
            "java.io.IOException: http://panel.example.com:8080/xmltv.php?username=demo&password=secret failed\n" +
                "at https://demo:secret@cdn.example.com/list.m3u\n" + // leak-guard: allow (made-up login, to check it is left out)
                "stream panel.example.com/live/demo/secret/12.ts ended; token=abc123&x=1\n" +
                "java.net.UnknownHostException: Unable to resolve host \"panel.example.com\": No address associated with hostname\n" +
                "java.net.SocketTimeoutException: failed to connect to panel.example.com/203.0.113.7 (port 8080) from /198.51.100.20 (port 41234)\n" +
                "\tat com.markedoe.ondo.data.OndoRepository.load(OndoRepository.kt:391)",
        )
        for (private in listOf("secret", "demo", "panel.example.com", "cdn.example.com", "abc123", "203.0.113.7", "198.51.100.20")) {
            assertFalse("$private in:\n$text", text.contains(private))
        }
        assertTrue(text, text.startsWith("java.io.IOException: http://… failed\nat https://…\nstream …/live/…/…/12.ts ended; token=…&x=1\n"))
        assertTrue(text, text.contains("Unable to resolve host …: No address associated with hostname"))
        assertTrue(text, text.contains("failed to connect to …/… (port 8080) from /… (port 41234)"))
        // The trace itself stays readable.
        assertTrue(text, text.endsWith("\tat com.markedoe.ondo.data.OndoRepository.load(OndoRepository.kt:391)"))
    }

    /** The system's network errors name IPv6 addresses too, the device's own among them ("from /…"). */
    @Test
    fun ipv6AddressesAreLeftOut() {
        val text = CrashLog.redact(
            "java.net.ConnectException: failed to connect to /2001:db8::7 (port 8080) from /2001:db8:1:2:3:4:5:25 (port 41234) after 10000ms\n" +
                "java.net.ConnectException: Failed to connect to panel.example.com/[2001:db8::8]:8080\n" +
                "java.net.SocketException: connect to /fe80::1c2b:3cff:fe4d:5e6f%wlan0 failed\n" +
                "Thread: main 12:34:56\n" +
                "\tat com.markedoe.ondo.data.OndoRepository.load(OndoRepository.kt:391)",
        )
        for (private in listOf("2001:db8", "fe80", "3cff:fe4d")) {
            assertFalse("$private in:\n$text", text.contains(private))
        }
        assertTrue(text, text.contains("failed to connect to /… (port 8080) from /… (port 41234)"))
        // Times and the trace stay readable.
        assertTrue(text, text.contains("Thread: main 12:34:56\n\tat com.markedoe.ondo.data.OndoRepository.load(OndoRepository.kt:391)"))
    }

    /** Android ended the run (out of memory, not responding): its reason and the memory in use, unless already on file. */
    @Test
    fun anExitByAndroidIsRecordedOnce() {
        val file = File(folder.root, CrashLog.FILE_NAME)
        val about = "Ondo 1.1.0 (1010) · Android 12 (API 31) · Test TV\nmemory class 192 MB, large 384 MB, max heap 384 MB, low RAM false"
        val exit = CrashLog.SystemExit(at = 1_760_000_100_000L, reason = "out of memory (the device's)", description = "lmk", pssKb = 412_000, rssKb = 530_000)
        CrashLog.recordExit(file, about, exit)
        val crash = requireNotNull(CrashLog.read(file))
        assertEquals(1_760_000_100_000L, crash.at)
        assertEquals("$about\nStopped by Android: out of memory (the device's) (lmk)\nMemory at that moment: PSS 402 MB, RSS 517 MB", crash.report)

        // A Kotlin crash recorded after it is kept; the same exit read again at the next start changes nothing.
        CrashLog.record(file, about, "main", IllegalStateException("broken"), at = 1_760_000_200_000L)
        CrashLog.recordExit(file, about, exit)
        assertTrue(requireNotNull(CrashLog.read(file)).report.contains("IllegalStateException: broken"))
    }

    @Test
    fun anAnrKeepsTheMainThreadsStack() {
        val trace = """
            ----- pid 4242 at 2026-10-23 16:20:00 -----
            Cmd line: com.markedoe.ondo

            "Signal Catcher" daemon prio=10 tid=2 Runnable
              at java.lang.Object.wait(Native method)

            "main" prio=5 tid=1 Runnable
              at com.markedoe.ondo.ui.UiMappers.home(UiMappers.kt:120)
              at com.markedoe.ondo.ui.OndoApp.Home(OndoApp.kt:300)

            "OkHttp Dispatcher" prio=5 tid=20 Waiting
        """.trimIndent()
        assertEquals(
            "\"main\" prio=5 tid=1 Runnable\n  at com.markedoe.ondo.ui.UiMappers.home(UiMappers.kt:120)\n  at com.markedoe.ondo.ui.OndoApp.Home(OndoApp.kt:300)",
            CrashLog.mainThread(trace),
        )
        assertNull(CrashLog.mainThread("no thread here"))
    }

    @Test
    fun noRecordOrAnUnreadableOneIsNoCrash() {
        val file = File(folder.root, CrashLog.FILE_NAME)
        assertNull(CrashLog.read(file))
        file.writeText("not a date\nsomething")
        assertNull(CrashLog.read(file))
    }
}
