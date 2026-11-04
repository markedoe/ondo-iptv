package com.markedoe.ondo.core

import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * A test that runs longer than [limitSeconds] is stopped with every thread's stack in the log, instead of the CI job
 * waiting in silence until its own timeout. Unlike JUnit's Timeout rule, the test stays on its own thread (Robolectric's
 * main looper and the Compose rules need that); a watchdog thread waits beside it. When time is up it prints all
 * threads, interrupts the test, which ends a runBlocking or a wait, and fails it; a test that still does not end
 * within [GRACE_SECONDS] stops its test JVM, and Gradle fails the build with it.
 *
 * Each test class takes it as its first rule: `@get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()`
 * (.github/scripts/check_hang_guard.py checks it).
 */
class HangGuard(
    private val limitSeconds: Long = DEFAULT_LIMIT_SECONDS,
    /** Where a report goes: the log and a file (a test of HangGuard itself keeps it). */
    private val out: (String) -> Unit = { write(it) },
) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val test = Thread.currentThread()
            val ended = CountDownLatch(1)
            val timedOut = AtomicBoolean(false)
            val watchdog = Thread({
                try {
                    if (ended.await(limitSeconds, TimeUnit.SECONDS)) return@Thread
                    timedOut.set(true)
                    out(report("${description.displayName} still running after $limitSeconds s: every thread, then the test is interrupted"))
                    test.interrupt()
                    if (ended.await(GRACE_SECONDS, TimeUnit.SECONDS)) return@Thread
                    out(report("${description.displayName} did not end $GRACE_SECONDS s after being interrupted: its test JVM stops"))
                    Runtime.getRuntime().halt(HALT_STATUS)
                } catch (_: InterruptedException) {
                }
            }, "HangGuard ${description.methodName}")
            watchdog.isDaemon = true
            watchdog.start()
            var failure: Throwable? = null
            try {
                base.evaluate()
            } catch (error: Throwable) {
                failure = error
            } finally {
                ended.countDown()
            }
            if (timedOut.get()) {
                Thread.interrupted() // the interrupt, if the test did not consume it
                throw AssertionError("${description.displayName} ran over $limitSeconds s (HangGuard; every thread's stack is above in the log)", failure)
            }
            failure?.let { throw it }
        }
    }

    companion object {
        const val DEFAULT_LIMIT_SECONDS = 120L
        const val GRACE_SECONDS = 15L
        private const val HALT_STATUS = 86

        /** Where the dumps are also written: build/reports/tests/hang-guard, uploaded with the reports in CI. */
        private val folder: File? get() = System.getProperty("ondo.hangGuard.dir")?.let(::File)

        fun report(title: String): String = "\n=== HangGuard: $title ===\n" + threads() + "=== HangGuard: end ===\n"

        @Synchronized
        private fun write(text: String) {
            // The test JVM's own error stream reaches the Gradle log even when the test's output is not shown;
            // the test's stream (Gradle's STANDARD_ERROR for the test) and a file keep it too.
            runCatching { PrintStream(FileOutputStream(FileDescriptor.err), true).apply { print(text); flush() } }
            runCatching { System.err.print(text); System.err.flush() }
            runCatching { folder?.let { it.mkdirs(); File(it, "hang-${System.currentTimeMillis()}.txt").appendText(text) } }
        }

        /** Every thread with its state and its whole stack (android.jar, which app tests compile against, has no ThreadMXBean). */
        fun threads(): String = buildString {
            Thread.getAllStackTraces().entries.sortedBy { it.key.name }.forEach { (thread, stack) ->
                append("\"${thread.name}\" ${if (thread.isDaemon) "daemon " else ""}${thread.state}\n")
                stack.forEach { append("    at $it\n") }
                append('\n')
            }
        }
    }
}
