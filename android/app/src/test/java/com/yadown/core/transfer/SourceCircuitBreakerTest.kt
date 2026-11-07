package com.yadown.core.transfer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceCircuitBreakerTest {

    private var now = 0L
    private val breaker = SourceCircuitBreaker(clock = { now })

    @Test
    fun threeConsecutiveFailuresTrip() {
        breaker.onFailure("k", FailureCause.NETWORK_TIMEOUT)
        now += 100
        breaker.onFailure("k", FailureCause.NETWORK_TIMEOUT)
        assertFalse(breaker.isOpen("k"))

        breaker.onFailure("k", FailureCause.NETWORK_TIMEOUT)
        assertTrue(breaker.isOpen("k"))
    }

    @Test
    fun successClearsCooldown() {
        repeat(3) { breaker.onFailure("k", FailureCause.NETWORK_TIMEOUT) }
        assertTrue(breaker.isOpen("k"))

        breaker.onSuccess("k")
        assertFalse(breaker.isOpen("k"))
    }

    @Test
    fun rateLimitedUsesRetryAfter() {
        breaker.onFailure("k", FailureCause.RATE_LIMITED, retryAfterMs = 5_000)

        assertTrue(breaker.isOpen("k"))
        now += 5_001
        assertFalse(breaker.isOpen("k"))
    }

    @Test
    fun dnsFailureCoolsDownFaster() {
        repeat(3) { breaker.onFailure("k", FailureCause.DNS_RESOLVE_FAILED) }

        now += 4_999
        assertTrue(breaker.isOpen("k"))
        now += 2
        assertFalse(breaker.isOpen("k"))
    }

    @Test
    fun windowFailureRateTripsBeforeThreeConsecutive() {
        breaker.onFailure("k", FailureCause.NETWORK_TIMEOUT)
        breaker.onSuccess("k")
        breaker.onFailure("k", FailureCause.NETWORK_TIMEOUT)
        breaker.onFailure("k", FailureCause.NETWORK_TIMEOUT)

        // 4 次请求中 3 次失败，失败率 75% > 50%，即使连续失败只有 2 次也应熔断。
        assertTrue(breaker.isOpen("k"))
    }

    @Test
    fun oldFailuresLeaveTheWindow() {
        breaker.onFailure("k", FailureCause.NETWORK_TIMEOUT)
        breaker.onFailure("k", FailureCause.NETWORK_TIMEOUT)
        breaker.onSuccess("k")
        now += 11_000
        breaker.onFailure("k", FailureCause.NETWORK_TIMEOUT)

        // 成功打断连续计数，旧失败也已离开 10s 窗口，不应熔断。
        assertFalse(breaker.isOpen("k"))
    }
}
