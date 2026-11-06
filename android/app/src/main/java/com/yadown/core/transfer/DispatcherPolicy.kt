package com.yadown.core.transfer

/**
 * 健康感知并发闸门，规则见 docs/ARCHITECTURE.md §5.3。
 * 网络健康度与存储 IOPS 各自给出上限，取较小者作为实际并发数。
 */
object DispatcherPolicy {

    fun maxRequests(health: Float, storageIops: Int): Int {
        val networkLimit = when {
            health > 0.7f -> 6
            health > 0.3f -> 3
            health > 0.1f -> 1
            else -> 0
        }
        val storageLimit = when {
            storageIops > 5000 -> 8
            storageIops > 2000 -> 4
            else -> 2
        }
        return minOf(networkLimit, storageLimit)
    }
}
