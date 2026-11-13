package com.markedoe.ondo

// TEMPORARY (branch claude/tvfocus-debug only, never merged): traces of the TV Home focus race.
object FocusTrace {
    @JvmStatic
    fun log(message: String) {
        println("FOCUSTRACE ${System.nanoTime() / 1_000_000} [${Thread.currentThread().name}] $message")
    }
}
