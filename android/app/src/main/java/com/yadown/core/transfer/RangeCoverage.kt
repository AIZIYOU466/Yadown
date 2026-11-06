package com.yadown.core.transfer

/**
 * 记录已下载的字节区间并判断任意区间是否被完整覆盖。
 * 区间保持有序且合并，查询为二分查找，供播放器的每次 read 调用。
 */
class RangeCoverage {
    private val ranges = mutableListOf<LongRange>()

    @Synchronized
    fun add(range: LongRange) {
        if (range.first > range.last) return

        var index = 0
        while (index < ranges.size && ranges[index].last + 1 < range.first) {
            index++
        }

        var mergedStart = range.first
        var mergedEnd = range.last
        while (index < ranges.size && ranges[index].first <= mergedEnd + 1) {
            mergedStart = minOf(mergedStart, ranges[index].first)
            mergedEnd = maxOf(mergedEnd, ranges[index].last)
            ranges.removeAt(index)
        }
        ranges.add(index, mergedStart..mergedEnd)
    }

    @Synchronized
    fun covers(start: Long, endInclusive: Long): Boolean {
        if (endInclusive < start) return true
        var low = 0
        var high = ranges.size - 1
        var candidate = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (ranges[mid].first <= start) {
                candidate = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return candidate >= 0 && ranges[candidate].last >= endInclusive
    }
}
