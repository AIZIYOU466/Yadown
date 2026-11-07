package com.yadown.core.net

/**
 * 单链路下载的并发预算：决定 MULTI_CONNECTION 下多少条 TCP 同时取块。
 *
 * - 双链路保持 24（链路维度已有独立连接池，不在本次调节范围）；
 * - REUSE / AUTO 小文件走 16，与历史行为一致；
 * - MULTI_CONNECTION 用可配置的 [multiLimit]，用于找出「握手成本 vs 叠加吞吐」的最优点。
 */
object TopologyConcurrency {

    const val SINGLE_LINK_BUDGET = 16
    const val DUAL_LINK_BUDGET = 24
    const val MIN_MULTI_LIMIT = 2
    const val MAX_MULTI_LIMIT = 32

    fun concurrencyFor(
        linkCount: Int,
        topology: TransportTopology,
        multiLimit: Int
    ): Int = when {
        linkCount >= 2 -> DUAL_LINK_BUDGET
        topology == TransportTopology.MULTI_CONNECTION ->
            multiLimit.coerceIn(MIN_MULTI_LIMIT, MAX_MULTI_LIMIT)
        else -> SINGLE_LINK_BUDGET
    }
}
