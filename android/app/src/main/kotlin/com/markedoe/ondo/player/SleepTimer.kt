package com.markedoe.ondo.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The player's sleep timer: [onEnd] (the player pauses) once the chosen minutes are up. Another choice starts it
 * again from now, Off stops it. It runs on [scope] (the player screen's, on the main thread) while the screen is open.
 */
@Stable
class SleepTimer(
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val onEnd: () -> Unit,
) {
    /** The minutes chosen, null when off. */
    var minutes by mutableStateOf<Int?>(null)
        private set

    /** When the player will pause (a time of day, for the screen), null when off. */
    var endsAt by mutableStateOf<Long?>(null)
        private set

    private var job: Job? = null

    /** Starts the timer for [chosen] minutes from now, or stops it (null). */
    fun set(chosen: Int?) {
        job?.cancel()
        if (chosen == null || chosen <= 0) {
            minutes = null
            endsAt = null
            return
        }
        minutes = chosen
        endsAt = now() + chosen * MINUTE_MS
        job = scope.launch {
            delay(chosen * MINUTE_MS)
            minutes = null
            endsAt = null
            onEnd()
        }
    }

    companion object {
        /** The choices, as on the Apple apps. */
        val CHOICES = listOf(15, 30, 45, 60, 90, 120)
        private const val MINUTE_MS = 60_000L
    }
}
