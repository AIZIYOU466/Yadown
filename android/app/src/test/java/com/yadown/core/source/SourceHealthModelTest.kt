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
        assertEquals(3, health.connectionLimit)
        assertEquals(now + 60_000, health.expiresAt)
    }

    @Test
    fun ewmaSmoothsThroughputAndLatency() {
        val first = success(prev = null, throughput = 1_000.0)
        val second = model.onSuccess("cdn.example.com/a/b", first, 2_000.0, 200.0)

        assertEquals(1_300.0, second.throughput, 1e-9)
        assertEquals(130.0, second.latencyMs, 1e-9)
    }

    @Test
    fun sourceFailureRollsBackLimitImmediately() {
        val healthy = success(prev = null)
        assertEquals(3, healthy.connectionLimit)

        val failed = model.onFailure("cdn.example.com/a/b", healthy, FailureCause.NETWORK_TIMEOUT)

        assertEquals(0.3, failed!!.failureRate, 1e-9)
        assertEquals(1, failed.connectionLimit)
    }

    @Test
    fun repeatedFailuresMarkSourceUnusableAndLimitNeverZero() {
        var health: SourceHealth? = null
        repeat(5) {
            health = model.onFailure("cdn.example.com/a/b", health, FailureCause.RATE_LIMITED)
        }

        assertEquals(1, health!!.connectionLimit)
        assertTrue(health!!.failureRate > 0.8)
        assertFalse(model.isUsable(health))
    }

    @Test
    fun nonSourceFailureDoesNotPolluteHealth() {
        val healthy = success(prev = null)

        assertNull(model.onFailure("cdn.example.com/a/b", healthy, FailureCause.STORAGE_FULL))
        assertNull(model.onFailure("cdn.example.com/a/b", healthy, FailureCause.CONTENT_CHANGED))
        assertNull(model.onFailure("cdn.example.com/a/b", healthy, FailureCause.CHECKSUM_MISMATCH))
    }

    @Test
    fun successAfterFailureDoesNotImmediatelyReexpandLimit() {
        val healthy = success(prev = null)
        val failed = model.onFailure("cdn.example.com/a/b", healthy, FailureCause.NETWORK_TIMEOUT)!!
        val recovered = success(prev = failed)

        assertEquals(0.21, recovered.failureRate, 1e-9)
        assertEquals(1, recovered.connectionLimit)
    }

    @Test
    fun expiredSampleIsDetected() {
        val health = success(prev = null)
        now += 60_000

        assertTrue(model.isExpired(health))
    }
}
