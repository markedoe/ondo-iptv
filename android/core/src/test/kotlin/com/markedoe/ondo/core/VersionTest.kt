package com.markedoe.ondo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule

class VersionTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @Test
    fun version() = assertEquals("1.0", OndoCore.VERSION)
}
