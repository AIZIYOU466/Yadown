package com.yadown.core.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ConnectionMetricsTest {

    @Before
    fun setUp() = ConnectionMetrics.reset()

    @After
    fun tearDown() = ConnectionMetrics.reset()

    @Test
    fun reuseRateIsDerivedFromCallAndConnectCounts() {
        repeat(10) { ConnectionMetrics.recordCallStart() }
        repeat(2) { ConnectionMetrics.recordConnectStart() }
        repeat(8) { ConnectionMetrics.recordConnectionAcquired() }

        val snapshot = ConnectionMetrics.snapshot()
        assertEquals(10L, snapshot.callStarts)
        assertEquals(2L, snapshot.connectStarts)
        assertEquals(8L, snapshot.connectionsAcquired)
        assertEquals(0.8, snapshot.reuseRate, 0.001)
    }

    @Test
    fun noCallsYieldsZeroReuseRate() {
        assertEquals(0.0, ConnectionMetrics.snapshot().reuseRate, 0.001)
    }
}
