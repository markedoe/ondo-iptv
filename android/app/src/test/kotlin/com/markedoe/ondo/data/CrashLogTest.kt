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

    @Test
    fun noRecordOrAnUnreadableOneIsNoCrash() {
        val file = File(folder.root, CrashLog.FILE_NAME)
        assertNull(CrashLog.read(file))
        file.writeText("not a date\nsomething")
        assertNull(CrashLog.read(file))
    }
}
