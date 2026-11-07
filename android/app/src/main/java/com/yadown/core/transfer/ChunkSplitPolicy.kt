package com.yadown.core.transfer

/**
 * 慢块裂解准入：只在「单块明显慢于其他块」时拆分，且不能丢弃过多已传字节。
 *
 * - 整条链路都慢（中位数低于绝对下限）时禁用裂解：拆完还是同一带宽，只会丢进度；
 * - 单连接（per-key=1）时禁用：拆出的子块也走同一连接，请求数增加但吞吐不变；
 * - 已传超过 25% 不取消：接近完成的块保留进度，避免反复从头开始。
 */
object ChunkSplitPolicy {

    const val MIN_MEDIAN_RATE = 64.0 * 1024

    fun shouldSplit(
        medianRate: Double?,
        blockRate: Double,
        transferredBytes: Long,
        chunkLength: Long,
        connectionLimit: Int
    ): Boolean {
        if (connectionLimit < 2) return false
        if (transferredBytes <= 0 || transferredBytes * 4 >= chunkLength) return false
        if (medianRate == null) {
            // 样本不足时只在「单块极慢」时拆一次；后续有中位数后按相对判定。
            return blockRate < MIN_MEDIAN_RATE
        }
        if (medianRate < MIN_MEDIAN_RATE) return false
        return blockRate < medianRate * 0.5
    }
}
