package com.yadown.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

class DispatcherPolicyTest {

    @Test
    fun highHealthHighIops_usesNetworkLimit() {
        assertEquals(8, DispatcherPolicy.maxRequests(1.0f, 6000))
    }

    @Test
    fun midHealth_usesFour() {
        assertEquals(4, DispatcherPolicy.maxRequests(0.5f, 6000))
    }

    @Test
    fun lowHealth_usesOne() {
        assertEquals(1, DispatcherPolicy.maxRequests(0.2f, 6000))
    }

    @Test
    fun nearDeadHealth_usesZero() {
        assertEquals(0, DispatcherPolicy.maxRequests(0.05f, 6000))
    }

    @Test
    fun healthBoundary_070_fallsToFour() {
        assertEquals(4, DispatcherPolicy.maxRequests(0.7f, 6000))
    }

    @Test
    fun healthBoundary_030_fallsToOne() {
        assertEquals(1, DispatcherPolicy.maxRequests(0.3f, 6000))
    }

    @Test
    fun healthBoundary_010_fallsToZero() {
        assertEquals(0, DispatcherPolicy.maxRequests(0.1f, 6000))
    }

    @Test
    fun storageLimitCapsNetwork() {
        assertEquals(8, DispatcherPolicy.maxRequests(1.0f, 3000))
        assertEquals(2, DispatcherPolicy.maxRequests(1.0f, 1000))
    }

    @Test
    fun storageBoundaries() {
        assertEquals(8, DispatcherPolicy.maxRequests(1.0f, 5000))
        assertEquals(2, DispatcherPolicy.maxRequests(1.0f, 2000))
    }

    @Test
    fun dualLinkRaisesNetworkLimit() {
        assertEquals(12, DispatcherPolicy.maxRequests(1.0f, 6000, linkCount = 2))
        assertEquals(8, DispatcherPolicy.maxRequests(0.5f, 6000, linkCount = 2))
    }

    @Test
    fun zeroHealthZeroIops_usesZero() {
        assertEquals(0, DispatcherPolicy.maxRequests(0f, 0))
    }
}
