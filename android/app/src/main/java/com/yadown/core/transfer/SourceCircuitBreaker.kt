package com.yadown.core.transfer

import java.util.concurrent.ConcurrentHashMap

/**
 * 快速熔断：10s 窗口失败率 >50% 或连续 3 次失败即暂时剔除该源/链路，
 * 429 按服务端 Retry-After 冷却，DNS 失败快速冷却后由下一次请求重新解析。
 * 冷却结束后允许一次半开探测；成功清零，失败重新冷却。
 */
class SourceCircuitBreaker(
    private val clock: () -> Long = System::currentTimeMillis,
    private val windowMs: Long = 10_000L,
    private val failureThreshold: Int = 3,
    private val failureRateThreshold: Double = 0.5,
    private val defaultCooldownMs: Long = 10_000L,
    private val dnsCooldownMs: Long = 5_000L,
    private val rateLimitedCooldownMs: Long = 30_000L
) {

    private class State {
        val failures = ArrayDeque<Long>()
        val successes = ArrayDeque<Long>()
        var consecutiveFailures = 0
        var cooldownUntil = 0L
    }

    private val lock = Any()
    private val states = ConcurrentHashMap<String, State>()

    fun onSuccess(key: String) {
        val now = clock()
        synchronized(lock) {
            val state = states.getOrPut(key) { State() }
            prune(state, now)
            state.successes.addLast(now)
            state.consecutiveFailures = 0
            state.cooldownUntil = 0L
        }
    }

    fun onFailure(key: String, cause: FailureCause, retryAfterMs: Long? = null) {
        val now = clock()
        synchronized(lock) {
            val state = states.getOrPut(key) { State() }
            prune(state, now)
            state.failures.addLast(now)
            state.consecutiveFailures++

            if (cause == FailureCause.RATE_LIMITED) {
                state.cooldownUntil = now + (retryAfterMs ?: rateLimitedCooldownMs)
                return
            }

            val total = state.failures.size + state.successes.size
            val failureRate = if (total == 0) 0.0 else state.failures.size.toDouble() / total
            val trip = state.consecutiveFailures >= failureThreshold ||
                (total >= MIN_RATE_SAMPLES && failureRate > failureRateThreshold)
            if (trip) {
                state.cooldownUntil = now + cooldownFor(cause)
            }
        }
    }

    fun isOpen(key: String, now: Long = clock()): Boolean = synchronized(lock) {
        val state = states[key] ?: return false
        state.cooldownUntil > now
    }

    fun cooldownRemaining(key: String, now: Long = clock()): Long = synchronized(lock) {
        ((states[key]?.cooldownUntil ?: 0L) - now).coerceAtLeast(0L)
    }

    private fun cooldownFor(cause: FailureCause): Long = when (cause) {
        FailureCause.DNS_RESOLVE_FAILED -> dnsCooldownMs
        else -> defaultCooldownMs
    }

    private fun prune(state: State, now: Long) {
        while (state.failures.isNotEmpty() && now - state.failures.first() > windowMs) {
            state.failures.removeFirst()
        }
        while (state.successes.isNotEmpty() && now - state.successes.first() > windowMs) {
            state.successes.removeFirst()
        }
    }

    private companion object {
        const val MIN_RATE_SAMPLES = 4
    }
}
