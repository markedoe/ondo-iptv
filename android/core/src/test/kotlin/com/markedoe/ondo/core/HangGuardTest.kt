package com.markedoe.ondo.core

import java.util.Collections
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** A test that hangs is stopped and named, with every thread's stack; one within its time is left alone. */
class HangGuardTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val reports: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val guard = HangGuard(limitSeconds = 1, out = { reports += it })
    private val description = Description.createTestDescription(HangGuardTest::class.java, "waitsForEver")

    private fun statement(body: () -> Unit) = object : Statement() {
        override fun evaluate() = body()
    }

    @Test
    fun aTestOverItsTimeIsInterruptedAndFailsWithEveryThreadsStack() {
        val error = assertFailsWith<AssertionError> {
            guard.apply(statement { runBlocking { CompletableDeferred<Unit>().await() } }, description).evaluate()
        }
        assertTrue(error.message, error.message.orEmpty().contains("waitsForEver") && error.message.orEmpty().contains("ran over 1 s"))
        assertEquals("one report, before the interrupt", 1, reports.size)
        val report = reports.single()
        assertTrue(report, report.contains("HangGuard: ${description.displayName} still running after 1 s"))
        // The test's own thread, where it waited.
        assertTrue(report, report.contains("\"${Thread.currentThread().name}\""))
        assertTrue(report, report.contains("BlockingCoroutine"))
        assertFalse("the interrupt does not reach the next test", Thread.currentThread().isInterrupted)
    }

    @Test
    fun aTestWithinItsTimeKeepsItsOwnResult() {
        guard.apply(statement { }, description).evaluate()
        val failure = assertFailsWith<IllegalStateException> { guard.apply(statement { error("its own failure") }, description).evaluate() }
        assertEquals("its own failure", failure.message)
        Thread.sleep(1_500)
        assertTrue("nothing reported", reports.isEmpty())
    }
}
