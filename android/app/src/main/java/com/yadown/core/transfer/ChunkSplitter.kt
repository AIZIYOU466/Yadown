package com.yadown.core.transfer

/**
 * 动态裂解：把一个大分块一分为二，用于慢连接回填。
 * 只负责区间计算，index 分配与持久化原子性由调用方负责。
 */
object ChunkSplitter {

    fun split(start: Long, endInclusive: Long): Pair<LongRange, LongRange>? {
        val length = endInclusive - start + 1
        if (length < 2) return null
        val mid = start + length / 2 - 1
        return (start..mid) to (mid + 1..endInclusive)
    }
}
