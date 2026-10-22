package com.markedoe.ondo.player

import android.view.KeyEvent

/** What a key of the remote (or a keyboard) does in the player. */
sealed interface RemoteAction {
    data object TogglePlay : RemoteAction
    data object Play : RemoteAction
    data object Pause : RemoteAction
    data object SeekBack : RemoteAction
    data object SeekForward : RemoteAction
    data object NextChannel : RemoteAction
    data object PreviousChannel : RemoteAction

    /** Back to the channel watched before (the remote's "last channel" key). */
    data object LastChannel : RemoteAction
    data object ShowControls : RemoteAction
    data object HideControls : RemoteAction

    /** A figure of a channel number being typed. */
    data class Digit(val value: Int) : RemoteAction
}

/**
 * The player's remote keys (Google's TV app quality: TV-PC, TV-PP). With the controls hidden, OK pauses and resumes,
 * Left and Right go back and forward, Up and Down change channel on a live one; with the controls shown, the arrows
 * and OK move through them and Back hides them. Channel keys, figures and "last channel" work on a live channel at
 * any time, the media keys on everything. Null: the key does what it does by default.
 */
object RemoteKeys {
    fun action(keyCode: Int, controlsShown: Boolean, zapping: Boolean, seekable: Boolean): RemoteAction? {
        digit(keyCode)?.let { return if (zapping) RemoteAction.Digit(it) else null }
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> return RemoteAction.TogglePlay
            KeyEvent.KEYCODE_MEDIA_PLAY -> return RemoteAction.Play
            KeyEvent.KEYCODE_MEDIA_PAUSE -> return RemoteAction.Pause
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> return RemoteAction.SeekForward.takeIf { seekable }
            KeyEvent.KEYCODE_MEDIA_REWIND -> return RemoteAction.SeekBack.takeIf { seekable }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> return RemoteAction.NextChannel.takeIf { zapping }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> return RemoteAction.PreviousChannel.takeIf { zapping }
            KeyEvent.KEYCODE_LAST_CHANNEL -> return RemoteAction.LastChannel.takeIf { zapping }
            KeyEvent.KEYCODE_INFO -> return if (controlsShown) RemoteAction.HideControls else RemoteAction.ShowControls
        }
        if (controlsShown) return RemoteAction.HideControls.takeIf { keyCode == KeyEvent.KEYCODE_BACK }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> RemoteAction.TogglePlay
            KeyEvent.KEYCODE_DPAD_LEFT -> RemoteAction.SeekBack.takeIf { seekable }
            KeyEvent.KEYCODE_DPAD_RIGHT -> RemoteAction.SeekForward.takeIf { seekable }
            KeyEvent.KEYCODE_DPAD_UP -> RemoteAction.NextChannel.takeIf { zapping }
            KeyEvent.KEYCODE_DPAD_DOWN -> RemoteAction.PreviousChannel.takeIf { zapping }
            else -> null
        }
    }

    private fun digit(keyCode: Int): Int? = when (keyCode) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
        else -> null
    }
}

/**
 * A channel number typed on the remote, figure by figure: it is complete after [MAX_DIGITS] figures, else when the
 * player stops waiting for the next one ([WAIT_MS] after the last).
 */
class ChannelNumberEntry {
    var typed: String = ""
        private set

    /** Adds a figure; the number when it is complete, else null (more may come). */
    fun add(digit: Int): Int? {
        typed = (typed + digit).takeLast(MAX_DIGITS)
        return if (typed.length == MAX_DIGITS) finish() else null
    }

    /** The number typed so far (null when none, or only zeros), and a fresh start. */
    fun finish(): Int? {
        val number = typed.toIntOrNull()?.takeIf { it > 0 }
        typed = ""
        return number
    }

    companion object {
        const val MAX_DIGITS = 4
        const val WAIT_MS = 1_500L
    }
}
