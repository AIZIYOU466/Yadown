package com.yadown.core.semantic

import kotlinx.serialization.Serializable

/**
 * 价值密度图：把 [0, totalSize) 划成若干区间，每段给出下载优先级权重。
 * 权重越大越先下发，用于把「能力解锁」所需字节提前送到本地。
 *
 * 区间必须覆盖整个文件且互不重叠（[ValueMap.fromSegments] 负责规整），
 * 这样引擎可以只依赖「起点 + 权重」顺序下发，而不需要区间查表。
 */
@Serializable
data class ValueMap(
    val totalSize: Long,
    val starts: List<Long>,
    val weights: List<Double>
) {
    init {
        require(totalSize > 0) { "totalSize 必须为正: $totalSize" }
        require(starts.size == weights.size) { "starts 与 weights 长度必须一致" }
        require(starts.isNotEmpty()) { "ValueMap 不能为空" }
        require(starts.first() == 0L) { "首个区间必须从 0 开始" }
        require(starts.zipWithNext().all { (a, b) -> b > a }) { "区间起点必须严格递增" }
        require(starts.last() < totalSize) { "区间起点必须落在文件内" }
    }

    /** 返回覆盖 [start, endInclusive] 的权重（取区间起点所在段的权重）。 */
    fun weightAt(start: Long): Double {
        var lo = 0
        var hi = starts.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= start) lo = mid else hi = mid - 1
        }
        return weights[lo]
    }

    /** 权重降序、起点升序的稳定排序键。 */
    fun priorityOrder(): List<Long> =
        starts.indices.sortedWith(compareByDescending<Int> { weights[it] }.thenBy { starts[it] })
            .map { starts[it] }

    companion object {
        /** 顺序传输（无格式语义）时的均匀权重。 */
        fun uniform(totalSize: Long, segmentCount: Int = 1): ValueMap {
            require(totalSize > 0)
            val count = segmentCount.coerceAtLeast(1)
            val starts = (0 until count).map { totalSize * it / count }
            return ValueMap(totalSize, starts, List(count) { 1.0 })
        }

        /**
         * 从「区间 + 权重」构造。允许区间留空或重叠：留空段补 0.0，重叠段取后写入者，
         * 从而保证结果是覆盖 [0, totalSize) 的完整划分。
         */
        fun fromSegments(totalSize: Long, segments: List<Segment>): ValueMap {
            require(totalSize > 0) { "totalSize 必须为正: $totalSize" }
            if (segments.isEmpty()) return uniform(totalSize)

            val bounds = sortedSetOf(0L, totalSize)
            segments.forEach { segment ->
                if (segment.start in 0 until totalSize) bounds += segment.start
                if (segment.endExclusive in 1..totalSize) bounds += segment.endExclusive
            }
            val points = bounds.toList()
            val starts = points.dropLast(1)
            val weights = starts.map { start ->
                val end = points[points.indexOf(start) + 1]
                segments.lastOrNull { it.start <= start && it.endExclusive >= end }?.weight ?: 0.0
            }
            return ValueMap(totalSize, starts, weights)
        }
    }
}

/** 半开区间 [start, endExclusive) 及其下载权重。 */
@Serializable
data class Segment(val start: Long, val endExclusive: Long, val weight: Double) {
    init {
        require(start >= 0) { "start 不能为负: $start" }
        require(endExclusive > start) { "endExclusive 必须大于 start" }
    }
}

/**
 * 可用性契约：把「已下载字节集合」翻译成用户可感知的能力状态。
 * 阈值是字节级判定，只依赖关键区域是否已就绪，不依赖下载百分比。
 */
@Serializable
data class AvailabilityContract(
    val capability: Capability,
    val readySegments: List<Segment>
) {
    enum class Capability { PLAYABLE, INSTALLABLE, PREVIEWABLE, SEQUENTIAL }

    /** 所有关键区域都已下载完成才解锁能力。 */
    fun isAvailable(downloaded: (Long, Long) -> Boolean): Boolean =
        readySegments.all { downloaded(it.start, it.endExclusive - 1) }
}

/** 落盘方式描述，供 StreamPipe 选择写入策略。 */
@Serializable
data class SinkSpec(
    val fileName: String,
    val sequentialOnly: Boolean = false
)

/**
 * L1 → L2 的交付契约（docs/ARCHITECTURE.md §5.1）。
 * [valueMap] 驱动 L3 下发顺序，[availabilityContract] 驱动 UI 能力解锁。
 */
@Serializable
data class ContentDescriptor(
    val contentId: String,
    val totalSize: Long,
    val mimeType: String,
    val valueMap: ValueMap?,
    val availabilityContract: AvailabilityContract,
    val sinkSpec: SinkSpec
) {
    val hasSemantics: Boolean get() = valueMap != null
}
