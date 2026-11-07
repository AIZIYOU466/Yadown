package com.yadown.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkHealthStoreTest {

    private var now = 0L
    private val store = LinkHealthStore(clock = { now })

    @Test
    fun unknownLinkStartsUsableWithNeutralScore() {
        assertTrue(store.isUsable("wifi"))
        assertEquals(LinkHealthStore.NEUTRAL_SCORE, store.score("wifi"), 0.0001)
    }

    @Test
    fun successRecordsThroughputAndClearsFailures() {
        store.recordSuccess("wifi", bytes = 1_000, elapsedMs = 100)
        assertEquals(10_000.0, store.get("wifi")!!.throughput, 0.001)

        store.recordFailure("wifi")
        now += 1_000
        store.recordSuccess("wifi", bytes = 2_000, elapsedMs = 100)

        val health = store.get("wifi")!!
        assertEquals(0, health.consecutiveFailures)
        assertTrue(store.isUsable("wifi"))
        assertEquals(0L, health.cooldownUntil)
    }

    @Test
    fun twoConsecutiveFailuresTripCooldown() {
        store.recordFailure("cellular")
        assertTrue("单次失败不应熔断", store.isUsable("cellular"))

        now += 1_000
        store.recordFailure("cellular")
        assertFalse("连续两次失败应熔断", store.isUsable("cellular"))

        now += LinkHealthStore.COOLDOWN_MS + 1
        assertTrue("冷却后应允许探测", store.isUsable("cellular"))
    }

    @Test
    fun successAfterCooldownRestoresLink() {
        store.recordFailure("cellular")
        store.recordFailure("cellular")
        now += LinkHealthStore.COOLDOWN_MS + 1
        store.recordSuccess("cellular", bytes = 2_000, elapsedMs = 100)

        val health = store.get("cellular")!!
        assertEquals(0, health.consecutiveFailures)
        assertTrue("失败率应随成功衰减", health.failureRate < 0.5)
    }

    @Test
    fun throughputIsEwmaSmoothed() {
        store.recordSuccess("wifi", bytes = 1_000, elapsedMs = 100)
        store.recordSuccess("wifi", bytes = 3_000, elapsedMs = 100)

        val health = store.get("wifi")!!
        // 0.3 * 30000 + 0.7 * 10000
        assertEquals(16_000.0, health.throughput, 0.001)
    }
}
