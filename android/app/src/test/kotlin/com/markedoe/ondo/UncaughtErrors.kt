package com.markedoe.ondo

import java.util.Collections
import org.junit.rules.ExternalResource

/**
 * Every error that no code caught, on any thread (a coroutine of the repository, of sync, of a screen): on a device,
 * each one stops the app. A test that leaves one behind fails, with the first one as its cause; [check] fails at once.
 * Under a Compose test rule, kotlinx-coroutines-test takes the coroutines' ones first and fails the test with them.
 */
class UncaughtErrors : ExternalResource() {
    private val errors: MutableList<Pair<String, Throwable>> = Collections.synchronizedList(mutableListOf())
    private var previous: Thread.UncaughtExceptionHandler? = null

    override fun before() {
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error -> errors += thread.name to error }
    }

    override fun after() {
        Thread.setDefaultUncaughtExceptionHandler(previous)
        check()
    }

    fun check() {
        val first = synchronized(errors) { errors.firstOrNull() } ?: return
        throw AssertionError("uncaught on thread ${first.first} (${errors.size} in all): ${first.second}", first.second)
    }
}
