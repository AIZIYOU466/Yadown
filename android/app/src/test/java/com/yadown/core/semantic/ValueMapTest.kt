package com.yadown.core.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValueMapTest {

    @Test
    fun uniformCoversWholeFileWithSingleWeight() {
        val map = ValueMap.uniform(1_000)
        assertEquals(1, map.starts.size)
        assertEquals(0L, map.starts.first())
        assertEquals(1.0, map.weightAt(999), 0.0)
    }

    @Test
    fun fromSegmentsFillsGapsWithZeroWeight() {
        val map = ValueMap.fromSegments(
            1_000,
            listOf(Segment(0, 100, 10.0), Segment(500, 600, 50.0))
        )
        assertEquals(0.0, map.weightAt(300), 0.0)
        assertEquals(10.0, map.weightAt(50), 0.0)
        assertEquals(50.0, map.weightAt(550), 0.0)
    }

    @Test
    fun priorityOrderPutsHighWeightFirst() {
        val map = ValueMap.fromSegments(
            1_000,
            listOf(Segment(0, 100, 10.0), Segment(500, 600, 90.0))
        )
        // 权重最高的区间起点应排在最前。
        assertEquals(500L, map.priorityOrder().first())
    }

    @Test
    fun fromSegmentsAlwaysCoversToEndOfFile() {
        val map = ValueMap.fromSegments(1_000, listOf(Segment(0, 100, 5.0)))
        // 尾部未声明区间必须补 0，否则调度器会漏掉这部分字节。
        assertEquals(0.0, map.weightAt(999), 0.0)
        assertTrue(map.starts.last() < 1_000)
    }

    @Test
    fun emptySegmentsFallBackToUniform() {
        val map = ValueMap.fromSegments(1_000, emptyList())
        assertEquals(1, map.starts.size)
        assertEquals(1.0, map.weightAt(0), 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveTotalSize() {
        ValueMap.uniform(0)
    }
}
