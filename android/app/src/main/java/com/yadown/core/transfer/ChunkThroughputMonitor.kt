package com.yadown.core.transfer

import java.util.concurrent.ConcurrentHashMap

/**
 * 分块瞬时吞吐监控：判断「慢但未失败」的块，给事件驱动裂解提供信号。
 *
 * 判定不是绝对阈值，而是跨活动块的中位数（对比同一网络、同一服务器下的其他连接）；
 * 样本不足 3 个时退回绝对下限，避免冷启动把唯一活动块误判为慢。
 * 连续 [slowWindows] 个窗口低于阈值才判定，抵消单次抖动。
 */
class ChunkThroughputMonitor(
    private val clock: () -> Long = System::currentTimeMillis,
    private val windowMs: Long = 1_000L,
    private val slowWindows: Int = 2,
    private val minRateBytesPerSec: Double = 64.0 * 1024,
    private val slowRatio: Double = 0.5
) {

    private class State {
        @Volatile
        var rate: Double = 0.0

        var lastBytes: Long = 0L
        var lastAt: Long = 0L
        var initialized: Boolean = false
        var slowCount: Int = 0
    }

    private val states = ConcurrentHashMap<Int, State>()

    /** [totalBytes] 是本次请求已收到的累计字节（从块起点算）。 */
    fun onProgress(chunkIndex: Int, totalBytes: Long) {
        val now = clock()
        val state = states.computeIfAbsent(chunkIndex) { State() }
        synchronized(state) {
            if (!state.initialized) {
                state.initialized = true
                state.lastAt = now
                return
            }
            val elapsed = now - state.lastAt
            if (elapsed < windowMs) return
            val observed = (totalBytes - state.lastBytes) * 1000.0 / elapsed
            state.rate = if (state.rate == 0.0) observed else {
                EWMA_ALPHA * observed + (1 - EWMA_ALPHA) * state.rate
            }
            state.lastBytes = totalBytes
            state.lastAt = now
            if (observed < threshold()) state.slowCount++ else state.slowCount = 0
        }
    }

    fun onChunkFinished(chunkIndex: Int) {
        states.remove(chunkIndex)
    }

    fun rateOf(chunkIndex: Int): Double = states[chunkIndex]?.rate ?: 0.0

    /** 剩余字节仍多于已传字节、且已连续多个窗口低于阈值时才值得拆分。 */
    fun shouldSplit(chunkIndex: Int, remainingBytes: Long, transferredBytes: Long): Boolean {
        if (transferredBytes <= 0 || remainingBytes <= transferredBytes) return false
        val state = states[chunkIndex] ?: return false
        return state.slowCount >= slowWindows
    }

    /** 活动块最近窗口速率的中位数；样本少于 3 个返回 null，调用方改用绝对阈值。 */
    fun medianRate(): Double? {
        val samples = states.values.mapNotNull { state ->
            state.rate.takeIf { it > 0.0 }
        }.sorted()
        if (samples.size < MIN_MEDIAN_SAMPLES) return null
        return samples[samples.size / 2]
    }

    private fun threshold(): Double {
        val median = medianRate() ?: return minRateBytesPerSec
        return maxOf(minRateBytesPerSec, median * slowRatio)
    }

    private companion object {
        const val EWMA_ALPHA = 0.3
        const val MIN_MEDIAN_SAMPLES = 3
    }
}
