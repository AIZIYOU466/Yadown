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

    /**
     * 慢块自适应裂解：速率越低、块越小越容易继续拆；高速链路不拆大块，
     * 避免把快速完成的块拆碎增加请求数。末尾 64–128KB 允许微拆，专治「99% 卡死」。
     */
    fun splitAdaptive(
        start: Long,
        endInclusive: Long,
        rateBytesPerSec: Double
    ): Pair<LongRange, LongRange>? {
        val length = endInclusive - start + 1
        if (length < 2 || length <= TAIL_MIN_CHUNK) return null
        if (length <= TAIL_BYTES) return split(start, endInclusive)

        val minChunk = when {
            rateBytesPerSec < LOW_RATE -> LOW_MIN_CHUNK
            rateBytesPerSec < HIGH_RATE -> MID_MIN_CHUNK
            else -> HIGH_MIN_CHUNK
        }
        if (length < minChunk * 2) return null
        return split(start, endInclusive)
    }

    private const val LOW_RATE = 256.0 * 1024
    private const val HIGH_RATE = 2.0 * 1024 * 1024
    private const val LOW_MIN_CHUNK = 512L * 1024
    private const val MID_MIN_CHUNK = 1L * 1024 * 1024
    private const val HIGH_MIN_CHUNK = 2L * 1024 * 1024
    private const val TAIL_BYTES = 128L * 1024
    private const val TAIL_MIN_CHUNK = 64L * 1024
}
