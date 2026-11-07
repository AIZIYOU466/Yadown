package com.yadown.core.transfer

/**
 * 健康感知并发闸门，规则见 docs/ARCHITECTURE.md §5.3。
 *
 * 单链路健康时 6，双链路时 12（6/链路）；存储 IOPS 作为全局硬保护，
 * 避免把写盘压力推到存储扛不住。网络与存储上限取较小者。
 */
object DispatcherPolicy {

    fun maxRequests(health: Float, storageIops: Int, linkCount: Int = 1): Int {
        val dual = linkCount >= 2
        val networkLimit = when {
            health > 0.7f -> if (dual) 24 else 16
            health > 0.3f -> if (dual) 12 else 8
            health > 0.1f -> if (dual) 2 else 2
            else -> 0
        }
        val storageLimit = when {
            storageIops > 5000 -> 32
            storageIops > 2000 -> 16
            else -> 2
        }
        return minOf(networkLimit, storageLimit)
    }
}
