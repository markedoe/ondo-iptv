package com.markedoe.ondo.player

/**
 * What the player does when a live stream stops ([ended]: its server closed it; [behindWindow]: the player fell
 * behind the stream's window): try again on the same address a few times in a row ([LiveReopening]), then the
 * channel's next address, then show the error.
 */
class LiveRecovery(max: Int = 3, freshAfterMs: Long = 60_000) {
    enum class Step { OPEN_AGAIN, BACK_TO_LIVE_EDGE, NEXT_ADDRESS, ERROR }

    private val reopening = LiveReopening(max, freshAfterMs)
    private val catchingUp = LiveReopening(max, freshAfterMs)

    /** A new address, or Try Again: the tries start again. */
    fun reset() {
        reopening.reset()
        catchingUp.reset()
    }

    fun ended(now: Long, nextAddress: Boolean): Step = when {
        reopening.reopens(now) -> Step.OPEN_AGAIN
        nextAddress -> Step.NEXT_ADDRESS
        else -> Step.ERROR
    }

    fun behindWindow(now: Long, nextAddress: Boolean): Step = when {
        catchingUp.reopens(now) -> Step.BACK_TO_LIVE_EDGE
        nextAddress -> Step.NEXT_ADDRESS
        else -> Step.ERROR
    }
}
