package com.yadown.core.source

import com.yadown.core.transfer.FailureCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceHealthModelTest {

    private var now = 1_000L
    private val model = SourceHealthModel(clock = { now }, ttlMs = 60_000)

    private fun success(prev: SourceHealth?, throughput: Double = 1_000.0): SourceHealth =
        model.onSuccess("cdn.example.com/a/b", prev, throughput, latencyMs = 100.0)

    @Test
    fun firstSuccessInitializesAndExpandsCautiously() {
        val health = success(prev = null)

        assertEquals(1_000.0, health.throughput, 1e-9)
        assertEquals(0.0, health.failureRate, 1e-9)
        assertEquals(100.0, health.latencyMs, 1e-9)
        assertEquals(9, health.connectionLimit)
        assertEquals(now + 60_000, health.expiresAt)
    }

    @Test
    fun connectionLimitExpandsUpToSixteen() {
        var health: SourceHealth? = null
        repeat(20) {
            health = success(prev = health)
        }

        assertEquals(16, health!!.connectionLimit)
    }

    @Test
    fun ewmaSmoothsThroughputAndLatency() {
        val first = success(prev = null, throughput = 1_000.0)
        val second = model.onSuccess("cdn.example.com/a/b", first, 2_000.0, 200.0)

        assertEquals(1_300.0, second.throughput, 1e-9)
        assertEquals(130.0, second.latencyMs, 1e-9)
    }

    @Test
    fun singleFailureDoesNotRollbackButRepeatedOnesDo() {
        val healthy = success(prev = null)
        assertEquals(9, healthy.connectionLimit)

        val firstFailure = model.onFailure("cdn.example.com/a/b", healthy, FailureCause.NETWORK_TIMEOUT)!!
        assertEquals("单次超时不降并发", 9, firstFailure.connectionLimit)
        assertEquals(0.3, firstFailure.failureRate, 1e-9)

        val secondFailure = model.onFailure("cdn.example.com/a/b", firstFailure, FailureCause.NETWORK_TIMEOUT)!!
        assertEquals("第二次失败触发回滚", 4, secondFailure.connectionLimit)

        val thirdFailure = model.onFailure("cdn.example.com/a/b", secondFailure, FailureCause.NETWORK_TIMEOUT)!!
        assertEquals(2, thirdFailure.connectionLimit)
    }

    @Test
    fun repeatedFailuresMarkSourceUnusableAndLimitNeverBelowTwo() {
        var health: SourceHealth? = null
        repeat(6) {
            health = model.onFailure("cdn.example.com/a/b", health, FailureCause.RATE_LIMITED)
        }

        assertEquals(2, health!!.connectionLimit)
        assertTrue(health!!.failureRate > 0.8)
        assertFalse(model.isUsable(health))
    }

    @Test
    fun nonSourceFailureDoesNotPolluteHealth() {
        val healthy = success(prev = null)

        assertNull(model.onFailure("cdn.example.com/a/b", healthy, FailureCause.STORAGE_FULL))
        assertNull(model.onFailure("cdn.example.com/a/b", healthy, FailureCause.CONTENT_CHANGED))
        assertNull(model.onFailure("cdn.example.com/a/b", healthy, FailureCause.CHECKSUM_MISMATCH))
        assertNull(model.onFailure("cdn.example.com/a/b", healthy, FailureCause.LINK_EXPIRED))
    }

    @Test
    fun successAfterOneFailureCanReexpand() {
        val healthy = success(prev = null)
        val failed = model.onFailure("cdn.example.com/a/b", healthy, FailureCause.NETWORK_TIMEOUT)!!
        val recovered = success(prev = failed)

        assertEquals(0.15, recovered.failureRate, 1e-9)
        assertEquals(10, recovered.connectionLimit)
    }

    @Test
    fun expiredSampleIsDetected() {
        val health = success(prev = null)
        now += 60_000

        assertTrue(model.isExpired(health))
    }
}
