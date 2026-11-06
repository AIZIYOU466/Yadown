package com.yadown.core.transfer

/**
 * 记录已下载的字节区间并判断任意区间是否被完整覆盖。
 * 供边下边播的播放器 DataSource 判断「当前请求的字节是否已落盘」。
 */
class RangeCoverage {
    private val ranges = mutableListOf<LongRange>()

    @Synchronized
    fun add(range: LongRange) {
        if (range.first > range.last) return
        ranges.add(range)
    }

    @Synchronized
    fun covers(start: Long, endInclusive: Long): Boolean {
        if (endInclusive < start) return true
        var cursor = start
        ranges.sortedBy { it.first }.forEach { r ->
            if (r.last < cursor) return@forEach
            if (r.first > cursor) return false
            cursor = maxOf(cursor, r.last + 1)
            if (cursor > endInclusive) return true
        }
        return cursor > endInclusive
    }
}