package com.yadown.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadPlanTest {

    @Test
    fun exactMultipleCreatesEqualChunks() {
        val plan = DownloadPlan.create(3L * 1024 * 1024, 1024 * 1024)
        assertEquals(3, plan.chunks.size)
        assertEquals(ChunkRange(0, 0, 1048575), plan.chunks[0])
        assertEquals(ChunkRange(1, 1048576, 2097151), plan.chunks[1])
        assertEquals(ChunkRange(2, 2097152, 3145727), plan.chunks[2])
    }

    @Test
    fun remainderCreatesTailChunk() {
        val plan = DownloadPlan.create(10, 4)
        assertEquals(
            listOf(
                ChunkRange(0, 0, 3),
                ChunkRange(1, 4, 7),
                ChunkRange(2, 8, 9)
            ),
            plan.chunks
        )
    }

    @Test
    fun smallerThanChunkSizeYieldsSingleChunk() {
        val plan = DownloadPlan.create(3, 4)
        assertEquals(listOf(ChunkRange(0, 0, 2)), plan.chunks)
    }

    @Test
    fun invalidTotalThrows() {
        assertThrows(IllegalArgumentException::class.java) { DownloadPlan.create(0) }
    }

    @Test
    fun invalidChunkSizeThrows() {
        assertThrows(IllegalArgumentException::class.java) { DownloadPlan.create(10, 0) }
    }
}
