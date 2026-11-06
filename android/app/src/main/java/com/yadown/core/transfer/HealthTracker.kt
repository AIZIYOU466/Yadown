package com.yadown.core.transfer

import java.util.concurrent.atomic.AtomicLong

/**
 * 下载健康度：成功/失败率 → [DispatcherPolicy] 的输入。
 * 分数 = 1 - 失败数/总数；0 次样本时为 1（默认健康）。
 */
class HealthTracker {
    private val total = AtomicLong(0)
    private val failures = AtomicLong(0)

    fun recordSuccess() {
        total.incrementAndGet()
    }

    fun recordFailure() {
        total.incrementAndGet()
        failures.incrementAndGet()
    }

    fun score(): Float {
        val t = total.get()
        return if (t == 0L) 1f else 1f - failures.get().toFloat() / t
    }
}