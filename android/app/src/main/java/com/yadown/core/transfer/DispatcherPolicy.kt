package com.yadown.core.transfer

/**
 * 健康感知并发闸门，规则见 docs/ARCHITECTURE.md §5.3。
 *
 * 单链路健康时 6，双链路时 12（6/链路）；存储 IOPS 作为全局硬保护，
 * 避免把写盘压力推到存储扛不住。网络与存储上限取较小者。
 */
object DispatcherPolicy {

    fun maxRequests(health: Float, storageIops: Int, linkCount: Int = 1): Int {
        val links = linkCount.coerceIn(1, 2)
        val networkLimit = when {
            health > 0.7f -> 8 * links
            health > 0.3f -> 4 * links
            health > 0.1f -> 1 * links
            else -> 0
        }
        val storageLimit = when {
            storageIops > 5000 -> 12
            storageIops > 2000 -> 8
            else -> 2
        }
        return minOf(networkLimit, storageLimit)
    }
}
