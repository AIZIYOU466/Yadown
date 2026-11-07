package com.yadown.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkThroughputMonitorTest {

    private var now = 0L

    private fun monitor(
        windowMs: Long = 1_000,
        slowWindows: Int = 2,
        minRate: Double = 64.0 * 1024
    ) = ChunkThroughputMonitor(
        clock = { now },
        windowMs = windowMs,
        slowWindows = slowWindows,
        minRateBytesPerSec = minRate
    )

    @Test
    fun slowChunkNeedsConsecutiveWindows() {
        val monitor = monitor()
        monitor.onProgress(1, 0)
        now = 1_000
        monitor.onProgress(1, 1_000)
        assertFalse(monitor.shouldSplit(1, remainingBytes = 10_000, transferredBytes = 1_000))

        now = 2_000
        monitor.onProgress(1, 2_000)
        assertTrue(monitor.shouldSplit(1, remainingBytes = 10_000, transferredBytes = 2_000))
    }

    @Test
    fun fastChunkResetsSlowCounter() {
        val monitor = monitor()
        monitor.onProgress(1, 0)
        now = 1_000
        monitor.onProgress(1, 1_000)
        now = 2_000
        monitor.onProgress(1, 2_000)
        assertTrue(monitor.shouldSplit(1, 10_000, 2_000))

        now = 3_000
        monitor.onProgress(1, 2_000 + 200_000)
        assertFalse(monitor.shouldSplit(1, 10_000, 202_000))
    }

    @Test
    fun splitRequiresRemainingLargerThanTransferred() {
        val monitor = monitor()
        monitor.onProgress(1, 0)
        now = 1_000
        monitor.onProgress(1, 1_000)
        now = 2_000
        monitor.onProgress(1, 2_000)

        assertFalse(monitor.shouldSplit(1, remainingBytes = 1_000, transferredBytes = 2_000))
        assertTrue(monitor.shouldSplit(1, remainingBytes = 10_000, transferredBytes = 2_000))
    }

    @Test
    fun finishedChunkIsRemoved() {
        val monitor = monitor()
        monitor.onProgress(1, 0)
        monitor.onChunkFinished(1)

        assertFalse(monitor.shouldSplit(1, 10_000, 1_000))
        assertEquals(0.0, monitor.rateOf(1), 0.0001)
    }

    @Test
    fun medianRequiresAtLeastThreeSamples() {
        val monitor = monitor()
        monitor.onProgress(1, 0)
        now = 1_000
        monitor.onProgress(1, 1_000)
        assertNull(monitor.medianRate())

        monitor.onProgress(2, 0)
        monitor.onProgress(3, 0)
        now = 2_000
        monitor.onProgress(1, 2_000)
        monitor.onProgress(2, 10_000)
        monitor.onProgress(3, 30_000)

        assertEquals(10_000.0, monitor.medianRate()!!, 0.001)
    }
}
