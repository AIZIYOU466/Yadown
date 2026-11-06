package com.yadown.core.transfer

import com.yadown.core.semantic.Segment
import com.yadown.core.semantic.ValueMap
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadPlanPriorityTest {

    @Test
    fun withoutValueMapOrderIsUnchanged() {
        val plan = DownloadPlan.create(totalSize = 100, chunkSize = 10)
        assertEquals((0 until 10).toList(), plan.priorityOrder(null))
    }

    @Test
    fun highWeightChunksComeFirst() {
        val plan = DownloadPlan.create(totalSize = 100, chunkSize = 10)
        // 第 7 个分块（70..79）权重大幅高于其余。
        val map = ValueMap.fromSegments(100, listOf(Segment(0, 100, 1.0), Segment(70, 80, 99.0)))
        val order = plan.priorityOrder(map)
        assertEquals("高权重分块应最先下发", 7, order.first())
    }

    @Test
    fun equalWeightsKeepNaturalIndexOrder() {
        val plan = DownloadPlan.create(totalSize = 100, chunkSize = 10)
        val map = ValueMap.uniform(100)
        assertEquals((0 until 10).toList(), plan.priorityOrder(map))
    }

    @Test
    fun priorityOrderKeepsEveryChunkExactlyOnce() {
        val plan = DownloadPlan.create(totalSize = 1_000, chunkSize = 64)
        val map = ValueMap.fromSegments(
            1_000,
            listOf(Segment(0, 128, 50.0), Segment(900, 1_000, 90.0))
        )
        val order = plan.priorityOrder(map)
        // 重排只改次序，不能改变集合——否则会漏下或重复下载分块。
        assertEquals(plan.chunks.size, order.size)
        assertEquals(plan.chunks.indices.toSet(), order.toSet())
    }

    @Test
    fun chunkBoundariesAreNotAffectedByValueMap() {
        val plan = DownloadPlan.create(totalSize = 100, chunkSize = 10)
        plan.priorityOrder(ValueMap.fromSegments(100, listOf(Segment(50, 60, 99.0))))
        // 位图按 index 索引，边界一旦随权重变化就会让位图与文件内容错位。
        assertEquals(0L, plan.chunks.first().start)
        assertEquals(9L, plan.chunks.first().endInclusive)
        assertEquals(99L, plan.chunks.last().endInclusive)
    }

    @Test
    fun chunkSplitterStillProducesContiguousHalves() {
        val split = ChunkSplitter.split(0, 9)!!
        assertEquals(0L to 4L, split.first.first to split.first.last)
        assertEquals(5L to 9L, split.second.first to split.second.last)
    }
}
