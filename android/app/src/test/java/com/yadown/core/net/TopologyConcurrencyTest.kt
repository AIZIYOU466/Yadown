package com.yadown.core.net

import org.junit.Assert.assertEquals
import org.junit.Test

class TopologyConcurrencyTest {

    @Test
    fun singleLinkReuseUsesSixteen() {
        assertEquals(16, TopologyConcurrency.concurrencyFor(1, TransportTopology.REUSE, 8))
    }

    @Test
    fun singleLinkAutoWithoutSizeStaysReuseBudget() {
        assertEquals(16, TopologyConcurrency.concurrencyFor(1, TransportTopology.AUTO, 8))
    }

    @Test
    fun multiConnectionUsesConfiguredLimit() {
        assertEquals(4, TopologyConcurrency.concurrencyFor(1, TransportTopology.MULTI_CONNECTION, 4))
        assertEquals(8, TopologyConcurrency.concurrencyFor(1, TransportTopology.MULTI_CONNECTION, 8))
    }

    @Test
    fun multiLimitIsClampedToSafeRange() {
        assertEquals(2, TopologyConcurrency.concurrencyFor(1, TransportTopology.MULTI_CONNECTION, 0))
        assertEquals(32, TopologyConcurrency.concurrencyFor(1, TransportTopology.MULTI_CONNECTION, 999))
    }

    @Test
    fun dualLinkKeepsTwentyFour() {
        assertEquals(24, TopologyConcurrency.concurrencyFor(2, TransportTopology.REUSE, 4))
        assertEquals(24, TopologyConcurrency.concurrencyFor(2, TransportTopology.MULTI_CONNECTION, 4))
    }
}
