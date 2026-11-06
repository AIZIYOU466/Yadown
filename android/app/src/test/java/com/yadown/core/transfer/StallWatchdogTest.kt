package com.yadown.core.transfer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StallWatchdogTest {

    @Test
    fun notStalledBeforeTimeout() {
        val watchdog = StallWatchdog(timeoutMs = 20_000, startAtMs = 0)
        assertFalse(watchdog.isStalled(19_999))
    }

    @Test
    fun stalledAtTimeoutBoundary() {
        val watchdog = StallWatchdog(timeoutMs = 20_000, startAtMs = 0)
        assertTrue(watchdog.isStalled(20_000))
    }

    @Test
    fun progressResetsTimer() {
        val watchdog = StallWatchdog(timeoutMs = 20_000, startAtMs = 0)
        watchdog.onProgress(10_000)
        assertFalse(watchdog.isStalled(29_999))
        assertTrue(watchdog.isStalled(30_000))
    }

    @Test
    fun customTimeout() {
        val watchdog = StallWatchdog(timeoutMs = 1_000, startAtMs = 5_000)
        assertFalse(watchdog.isStalled(5_999))
        assertTrue(watchdog.isStalled(6_000))
    }
}
